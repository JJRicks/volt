#!/usr/bin/env python3
"""Checks homepage pages against the variant contract (site/README.md, "Variants").

  python3 site/tools/check-variants.py [folder ...]

With no folders, checks site/src (original) and every site/variants/<name>/. A folder can also be a built
dist/variants/<name>/. Prints one line per problem and exits 1 when there are any.

Every page must work at / and at /preview/<name>/, and under the production CSP
(default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'none' ...):
  - URLs are relative, with no leading slash and no scheme. Links (<a href>) may also go to https:// pages
    elsewhere or to mailto:. The canonical link and og:/twitter: URLs may be absolute https://volt.jjricks.com/.
  - no inline <script> or <style>, no style="" attributes, no on* handlers, no javascript: or data: URLs;
  - no <base>, <iframe>, <frame>, <object>, <embed> or form actions;
  - in CSS, url() and @import are relative too (or a #fragment), never data: or external;
  - in JavaScript, no network calls (fetch, XMLHttpRequest, WebSocket, EventSource, sendBeacon), eval or
    new Function, and no http(s) URLs other than XML namespaces;
  - social preview images (og:image, twitter:image and their :url/:secure_url/:src forms) in a variant
    other than original must not depend on which variant is live, and must change URL when the image
    changes (link previews are cached by URL for a long time). So each is exactly
      https://volt.jjricks.com/preview/<name>/<path>?v=<first 12 hex digits of the SHA-256 of that file>
    where the file is the variant's own <path>, or the shared site/src/<path> when it ships none. nginx
    serves /preview/<name>/ from the current release whatever is live. The problem line gives the exact
    URL. <name> must be one nginx serves under /preview/. Original keeps https://volt.jjricks.com/og.png,
    which shows whichever variant is live (site/README.md, "Variants");
  - robots: a variant may not ship robots.txt (site/build.sh copies the shared one, which this checks
    against site/src/robots.txt), the shared robots.txt has no Disallow (a crawler that may not fetch a
    preview never sees its X-Robots-Tag: noindex), and no page has a robots or crawler <meta> (name
    "robots" or a crawler such as "googlebot") or <meta http-equiv="X-Robots-Tag">: robots rules for
    the homepage and its previews come from nginx and the shared robots.txt only.
site/build.sh runs this on every built variant and fails the build on any problem.
Standard library only.
"""
import hashlib, re, sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit

SITE = 'https://volt.jjricks.com/'
SOCIAL_IMAGE = re.compile(r'(og|twitter):image(:url|:secure_url|:src)?')
CRAWLER_META = re.compile(r'robots|bot\b|bot-|slurp|crawler|spider')
ORIGINAL_FOLDERS = ('original', 'src')
URL_ATTRS = {'src', 'href', 'poster', 'data', 'action', 'formaction', 'background', 'cite', 'longdesc',
             'manifest', 'icon', 'xlink:href', 'ping', 'archive', 'codebase', 'usemap'}
BANNED_TAGS = {'base', 'iframe', 'frame', 'frameset', 'object', 'embed', 'applet', 'portal'}
XML_NAMESPACES = ('http://www.w3.org/2000/svg', 'http://www.w3.org/1999/xlink', 'http://www.w3.org/1999/xhtml',
                  'http://www.w3.org/XML/1998/namespace', 'http://www.w3.org/2000/xmlns/')
JS_BANNED = [(re.compile(r'\bfetch\s*\('), 'fetch()'), (re.compile(r'\bXMLHttpRequest\b'), 'XMLHttpRequest'),
             (re.compile(r'\bWebSocket\b'), 'WebSocket'), (re.compile(r'\bEventSource\b'), 'EventSource'),
             (re.compile(r'\bsendBeacon\b'), 'sendBeacon'), (re.compile(r'\beval\s*\('), 'eval()'),
             (re.compile(r'\bnew\s+Function\b'), 'new Function'), (re.compile(r'\bimportScripts\b'), 'importScripts'),
             (re.compile(r'\bimport\s*\('), 'import()'), (re.compile(r'data:[a-z]', re.I), 'a data: URL')]


def relative_problem(url):
    """None when url is a plain relative URL (or a #fragment), else why not."""
    url = url.strip()
    if not url or url.startswith('#'):
        return None
    parts = urlsplit(url)
    if parts.scheme:
        return 'uses ' + parts.scheme + ': (pages need relative URLs)'
    if url.startswith('//'):
        return 'is protocol-relative (pages need relative URLs)'
    if url.startswith('/') or url.startswith('\\'):
        return 'starts with a slash, so it breaks at /preview/<name>/'
    return None


class PageCheck(HTMLParser):
    def __init__(self, name, problems, images=None):
        super().__init__(convert_charrefs=True)
        self.name, self.problems = name, problems
        self.in_script = False
        self.images = images if images is not None else []

    def problem(self, text):
        line, _ = self.getpos()
        self.problems.append('%s:%d: %s' % (self.name, line, text))

    def handle_starttag(self, tag, attrs):
        self.check(tag, attrs)
        if tag == 'script':
            self.in_script = True

    def handle_startendtag(self, tag, attrs):
        self.check(tag, attrs)

    def handle_endtag(self, tag):
        if tag == 'script':
            self.in_script = False

    def handle_data(self, data):
        if self.in_script and data.strip():
            self.problem('inline script (the CSP blocks it; use a .js file)')

    def check(self, tag, attrs):
        a = {}
        for key, value in attrs:
            key = key.lower()
            value = value or ''
            a[key] = value
            if key.startswith('on'):
                self.problem('<%s %s=> handler (the CSP blocks it)' % (tag, key))
            if key == 'style':
                self.problem('<%s style=""> (the CSP blocks inline styles; use a class)' % tag)
            low = value.strip().lower()
            if low.startswith('javascript:'):
                self.problem('<%s %s> uses javascript:' % (tag, key))
            if re.match(r'data:', low) or (key in ('srcset', 'imagesrcset') and 'data:' in low):
                self.problem('<%s %s> uses a data: URL (the CSP blocks it)' % (tag, key))
        if tag == 'style':
            self.problem('inline <style> (the CSP blocks it; use a .css file)')
        if tag in BANNED_TAGS:
            self.problem('<%s> is not allowed on the homepage' % tag)
        if tag == 'form' and a.get('action', '').strip():
            self.problem('<form action> (the CSP has form-action \'none\')')
        if tag == 'meta' and a.get('http-equiv', '').strip().lower() in ('refresh', 'content-security-policy', 'set-cookie',
                                                                         'x-robots-tag'):
            self.problem('<meta http-equiv="%s"> is not allowed' % a['http-equiv'])
        if tag == 'meta' and CRAWLER_META.search(a.get('name', '').strip().lower()):
            self.problem('<meta name="%s"> is not allowed: robots rules come from nginx and the shared robots.txt'
                         % a['name'])
        if tag == 'meta':
            key = (a.get('property') or a.get('name') or '').lower()
            content = a.get('content', '').strip()
            if re.fullmatch(r'(og|twitter):(url|image|video|audio|player)(:url|:secure_url|:src)?', key) and content:
                if not content.startswith(SITE) and relative_problem(content):
                    self.problem('<meta %s> URL must be relative or start with %s' % (key, SITE))
            if SOCIAL_IMAGE.fullmatch(key) and content:
                self.images.append((self.getpos()[0], key, content))
        rel = a.get('rel', '').lower().split()
        for key in ('srcset', 'imagesrcset'):
            if key in a:
                for candidate in a[key].split(','):
                    url = candidate.strip().split(' ')[0] if candidate.strip() else ''
                    why = relative_problem(url)
                    if why:
                        self.problem('<%s %s> %s %s' % (tag, key, url, why))
        for key in URL_ATTRS:
            if key not in a:
                continue
            url = a[key].strip()
            if tag == 'link' and key == 'href' and 'canonical' in rel:
                if not url.startswith(SITE):
                    self.problem('canonical link must start with ' + SITE)
                continue
            if tag in ('a', 'area') and key == 'href':
                parts = urlsplit(url)
                if parts.scheme == 'https' and parts.netloc or parts.scheme == 'mailto':
                    continue
            why = relative_problem(url)
            if why:
                self.problem('<%s %s="%s"> %s' % (tag, key, url, why))


def check_css(name, text, problems):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    for m in re.finditer(r'url\(\s*(["\']?)(.*?)\1\s*\)', text, flags=re.I):
        why = relative_problem(m.group(2))
        if m.group(2).strip().lower().startswith('data:'):
            why = 'is a data: URL (the CSP blocks it)'
        if why:
            problems.append('%s: url(%s) %s' % (name, m.group(2), why))
    for m in re.finditer(r'@import\s+(?:url\(\s*)?["\']?([^"\'\s);]+)', text, flags=re.I):
        why = relative_problem(m.group(1))
        if why:
            problems.append('%s: @import %s %s' % (name, m.group(1), why))


def check_js(name, text, problems):
    for pattern, what in JS_BANNED:
        if pattern.search(text):
            problems.append('%s: uses %s (the homepage makes no network requests and runs no dynamic code)' % (name, what))
    for m in re.finditer(r'https?://[^\s\'"`)]+', text):
        if not m.group(0).startswith(XML_NAMESPACES):
            problems.append('%s: contains %s (no external URLs in scripts)' % (name, m.group(0)))


def preview_names():
    """The variant names nginx serves under /preview/ (site/nginx/volt-homepage.conf)."""
    conf = (Path(__file__).resolve().parent.parent / 'nginx' / 'volt-homepage.conf').read_text()
    m = re.search(r'\^/preview/\(([a-z0-9|-]+)\)/\(\.\+\)\$', conf)
    return set(m.group(1).split('|')) if m else set()


def social_image_problems(folder, page, images, reference):
    """A variant's link-preview images: /preview/<name>/<path>?v=<hash of that file>, whatever is live."""
    problems = []
    name = folder.name
    prefix = SITE + 'preview/' + name + '/'
    if images and name not in preview_names():
        problems.append('%s: nginx serves no /preview/%s/, so its link-preview image would not load; add %s to the '
                        '/preview/ locations in site/nginx/volt-homepage.conf' % (page, name, name))
    for line, key, url in images:
        base, _, query = url.partition('?')
        # The image path, wherever the URL points now, so the problem line can give the right URL.
        path = re.sub(r'^preview/[^/]*/', '', (base[len(SITE):] if base.startswith(SITE) else base).lstrip('/'))
        if not path or path.endswith('/') or '..' in path.split('/') or relative_problem(path):
            problems.append('%s:%d: <meta %s> must be %s<image path>?v=<hash>' % (page, line, key, prefix))
            continue
        mine, theirs = folder / path, reference / path
        data = mine.read_bytes() if mine.is_file() else theirs.read_bytes() if theirs.is_file() else None
        if data is None:
            problems.append('%s:%d: <meta %s> names %s, which this page does not have' % (page, line, key, path))
            continue
        want = '%s%s?v=%s' % (prefix, path, hashlib.sha256(data).hexdigest()[:12])
        if url != want:
            problems.append('%s:%d: <meta %s> must not depend on which variant is live and must change with the '
                            'image: use %s' % (page, line, key, want))
    return problems


def robots_problems(name, text, shared=None):
    """robots.txt: no Disallow at all, and in a variant, the shared file unchanged."""
    problems = []
    if shared is not None and text != shared:
        problems.append('%s: variants may not ship robots.txt; the build uses the shared site/src/robots.txt' % name)
    for raw in text.splitlines():
        line = raw.split('#', 1)[0].strip()
        if line.lower().startswith('disallow:') and line.split(':', 1)[1].strip():
            problems.append('%s: "%s": the shared robots.txt has no Disallow, so crawlers can always fetch a '
                            'preview and see its X-Robots-Tag: noindex' % (name, line))
    return problems


def check_folder(folder, reference=None, source=False):
    """Problems in one page folder. reference is today's page (default: site/src next to this tool), used
    for the social-image and robots rules, which apply to every folder except original (named original or
    src). source=True means folder is a site/variants/<name>/ source folder, which may not have robots.txt."""
    folder = Path(folder)
    reference = Path(reference) if reference else Path(__file__).resolve().parent.parent / 'src'
    variant = folder.name not in ORIGINAL_FOLDERS
    problems = []
    if source and variant and (folder / 'robots.txt').exists():
        problems.append('%s/robots.txt: variants may not ship robots.txt; the build uses the shared site/src/robots.txt'
                        % folder.name)
    if not (folder / 'index.html').is_file():
        problems.append('%s: no index.html' % folder)
    for path in sorted(p for p in folder.rglob('*') if p.is_file()):
        rel = path.relative_to(folder)
        if any(part.startswith('.') for part in rel.parts) or path.suffix.lower() == '.md':
            continue
        name = str(folder.name + '/' + rel.as_posix())
        suffix = path.suffix.lower()
        if suffix in ('.html', '.htm', '.svg'):
            text = path.read_text(encoding='utf-8')
            if suffix == '.svg':
                if re.search(r'<\s*script', text, re.I):
                    problems.append(name + ': <script> in an SVG')
                if re.search(r'(?i)(?:href|src)\s*=\s*["\']\s*(?:https?:|//|data:|javascript:)', text):
                    problems.append(name + ': external or data: reference in an SVG')
                continue
            images = []
            checker = PageCheck(name, problems, images)
            checker.feed(text)
            checker.close()
            if variant:
                problems += social_image_problems(folder, name, images, reference)
        elif suffix == '.css':
            check_css(name, path.read_text(encoding='utf-8'), problems)
        elif suffix in ('.js', '.mjs'):
            check_js(name, path.read_text(encoding='utf-8'), problems)
        elif rel.as_posix() == 'robots.txt' and not source:
            shared = (reference / 'robots.txt').read_text(encoding='utf-8') if variant else None
            problems += robots_problems(name, path.read_text(encoding='utf-8'), shared)
    return problems


def main(argv):
    site = Path(__file__).resolve().parent.parent
    sources = [] if argv else sorted(str(p) for p in (site / 'variants').glob('*') if p.is_dir())
    folders = argv or [str(site / 'src')] + sources
    problems = []
    for folder in folders:
        problems += check_folder(folder, source=folder in sources)
    for line in problems:
        print(line)
    print('check-variants: %d folder(s), %d problem(s)' % (len(folders), len(problems)), file=sys.stderr)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
