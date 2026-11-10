#!/usr/bin/env python3
"""Homepage variants (site/README.md, "Variants"): the build contract, the variant markup check, the nginx
configuration and the live-variant switch. Temporary folders only; no network, server or browser."""
import hashlib, importlib.machinery, importlib.util, io, json, os, re, shutil, stat, subprocess, sys, tarfile, tempfile, threading, unittest
from pathlib import Path

sys.dont_write_bytecode = True  # loading the extensionless switch script must leave no __pycache__ in site/
APP = Path(__file__).resolve().parents[1]
SITE = APP / 'site'
KNOWN = ('original', 'refined', 'hybrid', 'app')
SHARED = ['favicon.svg', 'favicon-32.png', 'apple-touch-icon.png', 'icon-192.png', 'icon-512.png', 'og.png',
          'site.webmanifest', 'robots.txt', 'sitemap.xml']


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


checker = load('check_variants', SITE / 'tools' / 'check-variants.py')
switch = load('volt_homepage_switch', SITE / 'server' / 'volt-homepage-switch')


def make_tree(root, real_variants=True):
    """The files site/build.sh reads, as the deploy's git archive provides them. With
    real_variants=False the copy has no site/variants/, so a test's own variant can use any name."""
    root = Path(root)
    shutil.copytree(SITE, root / 'site', ignore=shutil.ignore_patterns('dist', 'dist.sha256', '.DS_Store'))
    if not real_variants:
        shutil.rmtree(root / 'site' / 'variants', ignore_errors=True)
    (root / 'res' / 'font').mkdir(parents=True)
    for weight in ('regular', 'medium', 'semibold', 'bold'):
        shutil.copy(APP / 'res' / 'font' / ('saira_%s.ttf' % weight), root / 'res' / 'font')
    (root / 'licenses').mkdir()
    shutil.copy(APP / 'licenses' / 'Saira-OFL.txt', root / 'licenses')
    shutil.copy(APP / 'AndroidManifest.xml', root)
    return root / 'site'


def build(site):
    return subprocess.run(['bash', str(site / 'build.sh')], capture_output=True, text=True)


def files(folder):
    folder = Path(folder)
    return {p.relative_to(folder).as_posix(): p.read_bytes() for p in folder.rglob('*') if p.is_file()}


def version():
    text = (APP / 'AndroidManifest.xml').read_text()
    return re.search(r'android:versionName="(\d+\.\d+\.\d+)', text).group(1)


def todays_page():
    """site/build.sh's output before variants existed, rebuilt independently of build.sh."""
    src = SITE / 'src'
    out = {name: (src / name).read_bytes() for name in ['styles.css', 'main.js'] + SHARED}
    out['index.html'] = (src / 'index.html').read_bytes().replace(b'@VERSION@', version().encode())
    for image in sorted((src / 'img').glob('*.webp')):
        out['img/' + image.name] = image.read_bytes()
    for weight in ('regular', 'medium', 'semibold', 'bold'):
        out['fonts/saira-%s.ttf' % weight] = (APP / 'res' / 'font' / ('saira_%s.ttf' % weight)).read_bytes()
    out['THIRD-PARTY-NOTICES.txt'] = (b'Volt homepage: third-party notices\n\n=== Saira typeface (regular, medium, semibold, bold) ===\n'
                                      + (APP / 'licenses' / 'Saira-OFL.txt').read_bytes())
    return out


class BuildContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.site = make_tree(cls.temp.name)
        test = cls.site / 'variants' / 'zz-test'
        (test / 'img').mkdir(parents=True)
        (test / 'deep' / 'er').mkdir(parents=True)
        (test / 'index.html').write_text('<!doctype html><p>Volt @VERSION@ for Android, and @VERSION@ again.</p>\n')
        (test / 'styles.css').write_text('body{color:red}\n')
        (test / 'og.png').write_bytes(b'variant og')
        (test / 'img' / 'extra.webp').write_bytes(b'extra')
        (test / 'deep' / 'er' / 'thing.js').write_text('"use strict";\n')
        (test / 'NOTES.md').write_text('builder notes\n')
        (test / 'deep' / 'README.MD').write_text('more notes\n')
        (test / '.DS_Store').write_bytes(b'junk')
        cls.result = build(cls.site)
        cls.dist = cls.site / 'dist'

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_build_succeeds(self):
        self.assertEqual(self.result.returncode, 0, self.result.stderr)

    def test_original_is_todays_page_byte_for_byte(self):
        expected = todays_page()
        self.assertEqual(files(self.dist / 'variants' / 'original'), expected)
        root = {k: v for k, v in files(self.dist).items() if not k.startswith('variants/') and k != 'variants.json'}
        self.assertEqual(root, expected, 'dist/ root keeps today\'s page for older tools')
        self.assertNotIn(b'/preview', expected['robots.txt'], 'crawlers must be able to fetch previews to see noindex')

    def test_variant_gets_shared_files_and_keeps_its_own(self):
        out = files(self.dist / 'variants' / 'zz-test')
        original = files(self.dist / 'variants' / 'original')
        for name in [n for n in SHARED if n != 'og.png'] + ['THIRD-PARTY-NOTICES.txt', 'fonts/saira-bold.ttf', 'img/phone-home.webp']:
            self.assertEqual(out[name], original[name], name)
        self.assertEqual(out['og.png'], b'variant og', 'a variant overrides a shared file at the same path')
        self.assertEqual(out['img/extra.webp'], b'extra')
        self.assertEqual(out['deep/er/thing.js'], b'"use strict";\n', 'subpaths are kept')
        self.assertNotIn('main.js', out, 'today\'s scripts are not shared')

    def test_markdown_and_dotfiles_are_left_out(self):
        out = files(self.dist / 'variants' / 'zz-test')
        self.assertFalse([n for n in out if n.lower().endswith('.md') or '/.' in '/' + n], sorted(out))

    def test_version_is_filled(self):
        page = (self.dist / 'variants' / 'zz-test' / 'index.html').read_text()
        self.assertEqual(page.count('Volt %s for Android' % version()), 1)
        self.assertNotIn('@VERSION@', page)
        self.assertNotIn('@VERSION@', (self.dist / 'variants' / 'original' / 'index.html').read_text())

    def test_variants_json(self):
        data = json.loads((self.dist / 'variants.json').read_text())
        self.assertEqual(data['format'], 'volt-homepage-variants-v1')
        names = [v['name'] for v in data['variants']]
        self.assertEqual(names[0], 'original')
        self.assertEqual(names[-1], 'zz-test', 'unknown names come after the known ones')
        self.assertEqual(sorted(names), sorted(['original', 'zz-test'] + [p.name for p in (self.site / 'variants').iterdir() if p.name != 'zz-test']))
        titles = {v['name']: v['title'] for v in data['variants']}
        self.assertEqual(titles['original'], 'Original')
        self.assertEqual(titles['zz-test'], 'Zz-test')
        for v in data['variants']:
            self.assertTrue(v['description'] and '\n' not in v['description'])
            self.assertTrue((self.dist / 'variants' / v['name'] / 'index.html').is_file())

    def test_known_titles(self):
        for name, title in [('original', 'Original'), ('refined', 'Refined'), ('hybrid', 'Hybrid'), ('app', 'App look')]:
            out = subprocess.run(['bash', '-c', 'eval "$(sed -n "/^variant_title()/,/^}/p" "$1")"; variant_title "$2"', '_',
                                  str(SITE / 'build.sh'), name], capture_output=True, text=True)
            self.assertEqual(out.stdout.strip(), title)

    def test_manifest_lists_every_file(self):
        listed = [line.split('  ', 1)[1] for line in (self.site / 'dist.sha256').read_text().splitlines()]
        self.assertEqual(sorted(listed), sorted(files(self.dist)))


class BuildFailures(unittest.TestCase):
    def failing(self, setup, message, name='broken'):
        with tempfile.TemporaryDirectory() as temp:
            site = make_tree(temp, real_variants=False)
            variant = site / 'variants' / name
            variant.mkdir(parents=True)
            setup(site, variant)
            result = build(site)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn(message, result.stderr)

    def test_variant_without_index_html(self):
        self.failing(lambda s, v: (v / 'styles.css').write_text('a{}'), 'has no index.html')

    def test_variant_without_version(self):
        self.failing(lambda s, v: (v / 'index.html').write_text('<p>no version</p>'), 'no @VERSION@ placeholder')

    def test_bad_variant_name(self):
        def setup(s, v):
            bad = s / 'variants' / 'Bad_Name'
            bad.mkdir()
            (bad / 'index.html').write_text('@VERSION@')
            (v / 'index.html').write_text('@VERSION@')
        self.failing(setup, 'use lowercase letters')

    def test_variant_named_original(self):
        def setup(s, v):
            other = s / 'variants' / 'original'
            other.mkdir()
            (other / 'index.html').write_text('@VERSION@')
            (v / 'index.html').write_text('@VERSION@')
        self.failing(setup, "today's page comes from src/")

    def test_symbolic_link_in_variant(self):
        def setup(s, v):
            (v / 'index.html').write_text('@VERSION@')
            os.symlink('index.html', v / 'link.html')
        self.failing(setup, 'not a regular file')

    def test_markup_problems_stop_the_build(self):
        self.failing(lambda s, v: (v / 'index.html').write_text('<p style="x">@VERSION@</p>'), 'style=""')

    def test_own_social_image_needs_a_preview_url(self):
        def setup(s, v):
            (v / 'og.png').write_bytes(b'a new card')
            (v / 'index.html').write_text('<meta property="og:image" content="https://volt.jjricks.com/og.png"><p>@VERSION@</p>')
        want = 'https://volt.jjricks.com/preview/refined/og.png?v=' + hashlib.sha256(b'a new card').hexdigest()[:12]
        self.failing(setup, want, 'refined')
        with tempfile.TemporaryDirectory() as temp:
            site = make_tree(temp, real_variants=False)
            v = site / 'variants' / 'refined'
            v.mkdir(parents=True)
            (v / 'og.png').write_bytes(b'a new card')
            (v / 'index.html').write_text('<meta property="og:image" content="%s"><p>@VERSION@</p>' % want)
            result = build(site)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_variant_robots_txt_stops_the_build(self):
        # Even a copy of the shared file: there is one robots.txt for the site.
        for text in ((SITE / 'src' / 'robots.txt').read_text(), 'User-agent: *\nDisallow: /preview/\n'):
            def setup(s, v, text=text):
                (v / 'index.html').write_text('<p>@VERSION@</p>')
                (v / 'robots.txt').write_text(text)
            self.failing(setup, 'variants may not ship robots.txt')

    def test_robots_meta_stops_the_build(self):
        self.failing(lambda s, v: (v / 'index.html').write_text('<meta name="robots" content="noindex"><p>@VERSION@</p>'),
                     '<meta name="robots"> is not allowed')


class VariantMarkup(unittest.TestCase):
    def test_todays_page_and_every_variant_pass(self):
        folders = [SITE / 'src'] + sorted(p for p in (SITE / 'variants').glob('*') if p.is_dir())
        for folder in folders:
            self.assertEqual(checker.check_folder(folder), [], folder)

    def page(self, body, extra=None, name='refined'):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / name
            folder.mkdir()
            (folder / 'index.html').write_text('<!doctype html><html><head><title>t</title>%s</head><body></body></html>' % body)
            for name, text in (extra or {}).items():
                if isinstance(text, bytes):
                    (folder / name).write_bytes(text)
                else:
                    (folder / name).write_text(text)
            return checker.check_folder(folder)

    def test_social_images_name_the_variant_and_its_content(self):
        today = (SITE / 'src' / 'og.png').read_bytes()
        mine = b'refined card'
        url = lambda path, data, name='refined': 'https://volt.jjricks.com/preview/%s/%s?v=%s' % (
            name, path, hashlib.sha256(data).hexdigest()[:12])
        meta = '<meta property="og:image" content="%s"><meta name="twitter:image" content="%s">'
        # Its own image, or the shared one when it ships none, at /preview/<name>/ with that file's hash.
        self.assertEqual(self.page(meta % ((url('og.png', mine),) * 2), {'og.png': mine}), [])
        self.assertEqual(self.page(meta % ((url('og.png', today),) * 2)), [])
        self.assertEqual(self.page(meta % ((url('og.png', today, 'hybrid'),) * 2), name='hybrid'), [])
        # Anything whose bytes depend on which variant is live, or that keeps its URL when the image changes,
        # is refused, with the URL to use, for both tags and any image path.
        for own in (None, mine):
            want = url('og.png', own or today)
            for bad in ('https://volt.jjricks.com/og.png', 'og.png', 'https://volt.jjricks.com/og.png?' + want.split('?')[1],
                        'https://volt.jjricks.com/preview/refined/og.png', 'https://volt.jjricks.com/preview/refined/og.png?v=000000000000',
                        'https://volt.jjricks.com/preview/hybrid/og.png?' + want.split('?')[1], 'preview/refined/og.png?' + want.split('?')[1]):
                problems = self.page(meta % (bad, bad), {'og.png': own} if own else None)
                self.assertEqual(len(problems), 2, (bad, problems))
                self.assertTrue(all(want in p for p in problems), (bad, problems))
        problems = self.page('<meta property="og:image:secure_url" content="https://volt.jjricks.com/card-2.png">', {'card-2.png': mine})
        self.assertTrue(problems and url('card-2.png', mine) in problems[0], problems)
        self.assertTrue(any('does not have' in p for p in self.page('<meta property="og:image" content="%s">' % url('missing.png', b''))))
        # A name nginx does not serve under /preview/ would give an image that never loads.
        problems = self.page(meta % ((url('og.png', today, 'other'),) * 2), name='other')
        self.assertTrue(any('nginx serves no /preview/other/' in p for p in problems), problems)
        # Original (src/) is today's page byte for byte and is left as it is: /og.png (README, "Variants").
        self.assertEqual(checker.check_folder(SITE / 'src'), [])

    def test_robots_come_from_the_shared_file_only(self):
        shared = (SITE / 'src' / 'robots.txt').read_text()
        self.assertEqual(self.page('', {'robots.txt': shared}), [], 'the copy the build makes')
        for text in ('User-agent: *\nDisallow: /preview/\n', 'User-agent: *\nDisallow: /*.png\n', 'User-agent: *\nAllow: /\n',
                     shared + 'Disallow: /private/\n', shared + '# a comment\n'):
            problems = self.page('', {'robots.txt': text})
            self.assertTrue(any('may not ship robots.txt' in p for p in problems), (text, problems))
        # The shared file itself: no Disallow of any kind (wildcards included), comments aside.
        for line in ('Disallow: /preview/', 'Disallow: /', 'Disallow: /*.png', 'Disallow: /p # comment'):
            self.assertTrue(checker.robots_problems('src/robots.txt', 'User-agent: *\n' + line + '\n'), line)
        for line in ('Allow: /', 'Disallow:', '# Disallow: /preview/'):
            self.assertEqual(checker.robots_problems('src/robots.txt', 'User-agent: *\n' + line + '\n'), [], line)
        self.assertEqual(checker.robots_problems('src/robots.txt', shared), [])
        # A source folder (site/variants/<name>/) may not have one at all.
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / 'refined'
            folder.mkdir()
            (folder / 'index.html').write_text('<title>t</title>')
            (folder / 'robots.txt').write_text(shared)
            self.assertTrue(any('may not ship robots.txt' in p for p in checker.check_folder(folder, source=True)))

    def test_no_page_sets_its_own_robots_rules(self):
        for snippet in ('<meta name="robots" content="noindex">', '<meta name="ROBOTS" content="index">',
                        '<meta name="googlebot" content="noindex">', '<meta name="bingbot" content="none">',
                        '<meta name="googlebot-news" content="noindex">', '<meta http-equiv="X-Robots-Tag" content="noindex">'):
            for name in ('refined', 'original'):
                self.assertTrue(self.page(snippet, name=name), (name, snippet))
        self.assertEqual(self.page('<meta name="description" content="bottom bot-free text">'), [])

    def test_allowed(self):
        card = 'https://volt.jjricks.com/preview/refined/og.png?v=' + hashlib.sha256((SITE / 'src' / 'og.png').read_bytes()).hexdigest()[:12]
        ok = ['<link rel="canonical" href="https://volt.jjricks.com/">',
              '<meta property="og:image" content="%s">' % card,
              '<meta name="twitter:image" content="%s">' % card,
              '<meta property="og:title" content="Volt: total control">',
              '<link rel="stylesheet" href="styles.css?v=abc"><script src="main.js?v=1" defer></script>',
              '<img src="img/a.webp" srcset="img/a.webp 1x, img/b.webp 2x" alt="">',
              '<a href="https://github.com/JJRicks/volt/releases/latest">Download</a><a href="#faq">FAQ</a><a href="THIRD-PARTY-NOTICES.txt">n</a>',
              '<a href="mailto:someone@example.test">Mail</a>',
              '<svg><use href="#bolt"></use><path fill="url(#grad)"/></svg>']
        for snippet in ok:
            self.assertEqual(self.page(snippet), [], snippet)
        self.assertEqual(self.page('', {'styles.css': '@font-face{src:url("fonts/saira-bold.ttf")} .a{fill:url(#g)} @import "more.css";',
                                        'main.js': 'const ns="http://www.w3.org/2000/svg"; el.style.transform="none";'}), [])

    def test_blocked(self):
        bad = {'<script>alert(1)</script>': 'inline script',
               '<script type="application/ld+json">{"a":1}</script>': 'inline script',
               '<style>p{}</style>': 'inline <style>',
               '<p style="color:red">x</p>': 'style=""',
               '<button onclick="go()">x</button>': 'onclick',
               '<img src="data:image/png;base64,AAAA" alt="">': 'data: URL',
               '<img srcset="img/a.webp 1x, data:image/png;base64,AA 2x" alt="">': 'data: URL',
               '<script src="https://cdn.example.test/x.js"></script>': 'uses https:',
               '<link rel="stylesheet" href="https://fonts.googleapis.com/css">': 'uses https:',
               '<link rel="stylesheet" href="/styles.css">': 'starts with a slash',
               '<img src="//cdn.example.test/a.png" alt="">': 'protocol-relative',
               '<a href="/">Home</a>': 'starts with a slash',
               '<a href="http://example.test/">x</a>': 'uses http:',
               '<a href="javascript:void(0)">x</a>': 'javascript:',
               '<link rel="canonical" href="https://example.test/">': 'canonical',
               '<meta property="og:image" content="https://cdn.example.test/og.png">': 'og:image',
               '<meta property="og:url" content="/">': 'og:url',
               '<iframe src="x.html"></iframe>': '<iframe>',
               '<base href="https://volt.jjricks.com/">': '<base>',
               '<form action="https://example.test/"></form>': 'form-action',
               '<meta http-equiv="refresh" content="0;url=x">': 'refresh'}
        for snippet, why in bad.items():
            problems = self.page(snippet)
            self.assertTrue(any(why in p for p in problems), '%s -> %s' % (snippet, problems))
        for css, why in [('a{background:url(data:image/png;base64,AA)}', 'data:'), ('a{background:url("https://x.test/a.png")}', 'https:'),
                         ('a{background:url(/img/a.webp)}', 'slash'), ('@import url("https://x.test/a.css");', '@import')]:
            problems = self.page('', {'styles.css': css})
            self.assertTrue(any(why in p for p in problems), '%s -> %s' % (css, problems))
        for js, why in [('fetch("x")', 'fetch()'), ('new XMLHttpRequest()', 'XMLHttpRequest'), ('eval("1")', 'eval()'),
                        ('img.src="https://tracker.test/p.gif"', 'https://tracker.test'), ('new Function("x")', 'new Function'),
                        ('el.src="data:image/png;base64,AA"', 'data:')]:
            problems = self.page('', {'main.js': js})
            self.assertTrue(any(why in p for p in problems), '%s -> %s' % (js, problems))


def parse_nginx(text):
    """A small block parser: [(words, children)] where children is None for a plain directive."""
    tokens = re.findall(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'|[{};]|[^\s{};"\']+', re.sub(r'(?m)#.*$', '', text))
    def block(i):
        items, words = [], []
        while i < len(tokens):
            t = tokens[i]
            if t == ';':
                items.append((words, None)); words = []
            elif t == '{':
                children, i = block(i + 1)
                items.append((words, children)); words = []
            elif t == '}':
                return items, i
            else:
                words.append(t)
            i += 1
        return items, i
    return block(0)[0]


class NginxConfig(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        tree = parse_nginx((SITE / 'nginx' / 'volt-homepage.conf').read_text())
        cls.https = [children for words, children in tree if words == ['server'] and any(w[:2] == ['listen', '443'] for w, c in children if c is None)][0]

    def headers(self, items):
        return {w[1].lower(): ' '.join(w[2:]) for w, c in items if c is None and w and w[0] == 'add_header'}

    def locations(self, items, inherited, path=()):
        for words, children in items:
            if children is not None and words and words[0] == 'location':
                own = self.headers(children)
                effective = own or inherited
                yield path + (' '.join(words[1:]),), effective
                yield from self.locations(children, effective, path + (' '.join(words[1:]),))

    def test_every_location_keeps_every_security_header(self):
        server = self.headers(self.https)
        for name in ('content-security-policy', 'strict-transport-security', 'x-content-type-options', 'x-frame-options',
                     'referrer-policy', 'permissions-policy', 'cross-origin-opener-policy'):
            self.assertIn(name, server)
        found = list(self.locations(self.https, server))
        self.assertTrue(found)
        for path, effective in found:
            for name, value in server.items():
                self.assertEqual(effective.get(name), value, '%s loses %s' % (' > '.join(path), name))

    def test_previews_are_noindex_and_live_is_the_root(self):
        server = self.headers(self.https)
        self.assertNotIn('x-robots-tag', server, 'the live page stays indexable')
        roots = [w for w, c in self.https if c is None and w[0] == 'root']
        self.assertEqual(roots, [['root', '/var/www/volt-home/live']])
        previews = [(p, e) for p, e in self.locations(self.https, server) if p[0] == '^~ /preview/']
        self.assertGreaterEqual(len(previews), 4)
        for path, effective in previews:
            self.assertIn('noindex', effective.get('x-robots-tag', ''), path)
        nested = ' '.join(p[-1] for p, e in previews)
        self.assertEqual(sorted(set(re.findall(r'\((\w+(?:\|\w+)+)\)', nested))), ['original|refined|hybrid|app'])
        self.assertEqual(tuple(switch.ALLOWED), KNOWN)

    def test_workbench_include_is_kept(self):
        self.assertIn(['include', '/etc/nginx/snippets/volt-dev.conf'], [w for w, c in self.https if c is None])

    def test_robots_lets_crawlers_see_the_noindex(self):
        lines = [l.split('#', 1)[0].strip() for l in (SITE / 'src' / 'robots.txt').read_text().splitlines()]
        self.assertFalse([l for l in lines if l.lower().startswith('disallow:') and l.split(':', 1)[1].strip()])

    def file_locations(self, items, path=()):
        for words, children in items:
            if children is not None and words and words[0] == 'location':
                here = path + (' '.join(words[1:]),)
                if any(w and w[0] == 'try_files' for w, c in children if c is None) and 'acme-challenge' not in here[0]:
                    yield here, children
                yield from self.file_locations(children, here)

    def test_every_file_is_revalidated(self):
        # A return visit after a switch must not reuse another variant's file at the same URL: every location
        # serving homepage files says no-cache ("expires epoch"), and nothing turns validators off.
        found = list(self.file_locations(self.https))
        self.assertGreaterEqual(len(found), 4)
        for path, children in found:
            self.assertIn(['expires', 'epoch'], [w for w, c in children if c is None], ' > '.join(path))
        text = re.sub(r'(?m)#.*$', '', (SITE / 'nginx' / 'volt-homepage.conf').read_text())
        self.assertEqual(sorted(set(re.findall(r'expires\s+([^;]+);', text))), ['epoch'])
        self.assertNotRegex(text, r'etag\s+off|if_modified_since\s+off|add_header\s+(Cache-Control|ETag|Last-Modified)')


class Switch(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(os.path.realpath(self.temp.name))
        self.home = base / 'volt-home'
        self.state = base / 'state'
        self.request_dir = base / 'volt-dev' / 'homepage'
        self.request_dir.mkdir(parents=True)
        self.request = self.request_dir / 'request'

    def tearDown(self):
        self.temp.cleanup()

    def release(self, name, variants, current=True):
        for variant in variants:
            folder = self.home / 'releases' / name / 'variants' / variant
            folder.mkdir(parents=True)
            (folder / 'index.html').write_text(variant)
        (self.home / 'releases' / name).mkdir(parents=True, exist_ok=True)
        if current:
            temp = self.home / 'current.new'
            if temp.is_symlink():
                temp.unlink()
            os.symlink(str(self.home / 'releases' / name), temp)
            os.replace(temp, self.home / 'current')

    def ask(self, content):
        if isinstance(content, str):
            content = content.encode()
        self.request.write_bytes(content)

    def run_switch(self):
        status, code = switch.apply(str(self.home), str(self.request), str(self.state))
        on_disk = json.loads((self.state / 'status.json').read_text())
        self.assertEqual(on_disk, status)
        return status, code

    def live(self):
        return os.readlink(self.home / 'live')

    def target(self, release, name):
        return str(self.home / 'releases' / release / 'variants' / name)

    def test_no_request_means_original(self):
        self.release('r1', ['original', 'refined'])
        status, code = self.run_switch()
        self.assertEqual((code, status['live'], status['result'], status['release']), (0, 'original', 'ok', 'r1'))
        self.assertEqual(self.live(), self.target('r1', 'original'))
        self.assertEqual(stat.S_IMODE(os.stat(self.state / 'status.json').st_mode), 0o644)
        self.assertRegex(status['at'], r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$')

    def test_allowlist_is_exact(self):
        self.release('r1', ['original', 'refined', 'hybrid', 'app', 'other'])
        for good in ('refined', 'refined\n', 'hybrid', 'app\n', 'original'):
            self.ask(good)
            status, code = self.run_switch()
            self.assertEqual((status['result'], status['live']), ('ok', good.strip()), good)
            self.assertEqual(self.live(), self.target('r1', good.strip()))
        self.ask('hybrid')
        self.run_switch()
        for bad in (b'Original', b' refined', b'refined ', b'refined\n\n', b'refined\r\n', b'../app', b'app/', b'', b'\n',
                    b'other', b'orig\x00inal', b'refined\nrefined', b'secret-token-1234567890', b'x' * 65, 'Ap\u0440'.encode()):
            self.ask(bad)
            status, code = self.run_switch()
            self.assertEqual((code, status['result'], status['live'], status['requested']), (0, 'error', 'hybrid', None), bad)
            self.assertEqual(self.live(), self.target('r1', 'hybrid'), 'an invalid request leaves live unchanged')
            self.assertNotIn('secret-token', json.dumps(status))
            self.assertIn('Hybrid stays live', status['message'])

    def test_missing_variant_leaves_live_unchanged(self):
        self.release('r1', ['original', 'refined'])
        self.ask('refined')
        self.run_switch()
        self.ask('app')
        status, code = self.run_switch()
        self.assertEqual((status['result'], status['live'], status['requested']), ('error', 'refined', 'app'))
        self.assertIn('not in release r1', status['message'])
        self.assertEqual(self.live(), self.target('r1', 'refined'))

    def test_variant_must_be_a_real_directory_with_a_page(self):
        self.release('r1', ['original', 'refined'])
        os.symlink(str(self.home / 'releases' / 'r1' / 'variants' / 'refined'), self.home / 'releases' / 'r1' / 'variants' / 'app')
        (self.home / 'releases' / 'r1' / 'variants' / 'hybrid').mkdir()
        for name in ('app', 'hybrid'):
            self.ask(name)
            status, _ = self.run_switch()
            self.assertEqual((status['result'], status['live']), ('error', 'original'), name)
        # A symbolic link in place of a variant's index.html, or of the variants folder, is refused too.
        os.symlink(str(self.home / 'releases' / 'r1' / 'variants' / 'refined' / 'index.html'),
                   self.home / 'releases' / 'r1' / 'variants' / 'hybrid' / 'index.html')
        self.ask('hybrid')
        self.assertEqual(self.run_switch()[0]['result'], 'error')
        self.release('r2', [])
        os.symlink(str(self.home / 'releases' / 'r1' / 'variants'), self.home / 'releases' / 'r2' / 'variants')
        status, code = self.run_switch()
        self.assertEqual((code, status['result']), (1, 'error'))
        self.assertEqual(self.live(), self.target('r1', 'original'))

    def test_request_must_be_a_plain_file(self):
        self.release('r1', ['original', 'refined'])
        elsewhere = Path(os.path.realpath(self.temp.name)) / 'elsewhere'
        elsewhere.mkdir()
        (elsewhere / 'request').write_text('refined')
        os.symlink(str(elsewhere / 'request'), self.request)
        status, _ = self.run_switch()
        self.assertEqual((status['result'], status['live']), ('error', 'original'))
        self.assertIn('symbolic link', status['message'])
        self.request.unlink()
        # A symbolic link for the folder above it.
        shutil.rmtree(self.request_dir)
        os.symlink(str(elsewhere), self.request_dir)
        status, _ = self.run_switch()
        self.assertEqual(status['result'], 'error')
        self.request_dir.unlink()
        self.request_dir.mkdir()
        # A FIFO must not hang the switch; a folder is not a request either.
        os.mkfifo(self.request)
        self.assertEqual(self.run_switch()[0]['result'], 'error')
        self.request.unlink()
        self.request.mkdir()
        self.assertEqual(self.run_switch()[0]['result'], 'error')
        self.request.rmdir()
        self.assertEqual(self.run_switch()[0]['result'], 'ok', 'no request file means original')

    def test_live_link_is_replaced_atomically(self):
        self.release('r1', ['original', 'refined'])
        self.run_switch()
        stop, failures, reads = threading.Event(), [], [0]
        def reader():
            while not stop.is_set():
                try:
                    target = os.readlink(self.home / 'live')
                    if target not in (self.target('r1', 'original'), self.target('r1', 'refined')):
                        failures.append(target)
                except OSError as e:
                    failures.append(repr(e))
                reads[0] += 1
        thread = threading.Thread(target=reader)
        thread.start()
        try:
            for i in range(150):
                self.ask('refined' if i % 2 == 0 else 'original')
                status, _ = self.run_switch()
                self.assertTrue(status['changed'])
        finally:
            stop.set()
            thread.join()
        self.assertEqual(failures, [], 'live was missing or wrong while it was being replaced')
        self.assertGreater(reads[0], 0)
        self.assertEqual(sorted(p.name for p in self.home.iterdir()), ['current', 'live', 'releases'], 'no temporary links left')
        self.assertFalse(self.run_switch()[0]['changed'], 'the same request changes nothing')

    def test_choice_follows_deploys_and_falls_back(self):
        self.release('r1', ['original', 'refined'])
        self.ask('refined')
        self.run_switch()
        # A new release with the variant: live moves into it.
        self.release('r2', ['original', 'refined'])
        self.assertEqual(self.live(), self.target('r1', 'refined'), 'until the switch runs, the previous release serves')
        status, _ = self.run_switch()
        self.assertEqual((status['result'], status['live'], status['release']), ('ok', 'refined', 'r2'))
        # A release without it: original, recorded as a fallback; the request still names refined.
        self.release('r3', ['original', 'hybrid'])
        status, code = self.run_switch()
        self.assertEqual((code, status['result'], status['live'], status['release'], status['previous']), (0, 'fallback', 'original', 'r3', 'refined'))
        self.assertIn('Original is live', status['message'])
        self.assertEqual(self.live(), self.target('r3', 'original'))
        self.assertEqual(self.request.read_text(), 'refined')
        # The next release that has it brings the choice back.
        self.release('r4', ['original', 'refined'])
        self.assertEqual(self.run_switch()[0]['live'], 'refined')

    def test_releases_without_variants_change_nothing(self):
        self.release('r1', ['original'])
        self.run_switch()
        self.release('legacy', [])
        status, code = self.run_switch()
        self.assertEqual((code, status['result'], status['live'], status['release']), (1, 'error', 'original', 'r1'))
        self.assertIn('no variants/original', status['message'])
        self.assertEqual(self.live(), self.target('r1', 'original'))

    def test_live_that_is_not_a_link_is_left_alone(self):
        self.release('r1', ['original'])
        (self.home / 'live').mkdir()
        status, code = self.run_switch()
        self.assertEqual((code, status['result']), (1, 'error'))
        self.assertTrue((self.home / 'live').is_dir() and not (self.home / 'live').is_symlink())

    def test_command_line(self):
        self.release('r1', ['original', 'app'])
        self.ask('app\n')
        out = subprocess.run([sys.executable, '-I', str(SITE / 'server' / 'volt-homepage-switch'), '--home', str(self.home),
                              '--request', str(self.request), '--state-dir', str(self.state)], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn('live=app release=r1 result=ok', out.stdout)
        self.assertEqual(self.live(), self.target('r1', 'app'))



class DeployRelease(unittest.TestCase):
    """site/server/deploy-release.sh in a temporary folder, with stand-ins for nginx and systemctl."""
    PREV, NEW = 'a' * 40, 'b' * 40

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(os.path.realpath(self.temp.name))
        self.home, self.nginx, self.uploads = base / 'volt-home', base / 'nginx', base / 'uploads'
        self.state, self.bin, self.mode = base / 'state', base / 'bin', base / 'mode'
        self.request = base / 'volt-dev' / 'homepage' / 'request'
        for d in (self.nginx / 'sites-available', self.nginx / 'sites-enabled', self.uploads, self.bin, self.request.parent):
            d.mkdir(parents=True)
        self.config = self.nginx / 'sites-available' / 'volt-homepage'
        self.config.write_text('server {\n    root /var/www/volt-home/live;\n}\n')
        self.switch_bin = SITE / 'server' / 'volt-homepage-switch'
        (self.bin / 'nginx').write_text('#!/bin/sh\nexit 0\n')
        # systemctl start runs the real switch ("run"), fails ("fail"), succeeds without doing anything ("noop"),
        # moves live and then fails ("movefail"), or moves live, locks the home folder so no link in it can be
        # replaced, and fails ("movelock"). Every start after the first fails, so a rollback cannot lean on it.
        self.starts = base / 'starts'
        run = '"%s" -I "%s" --home "%s" --request "%s" --state-dir "%s"' % (
            sys.executable, self.switch_bin, self.home, self.request, self.state)
        (self.bin / 'systemctl').write_text(
            '#!/bin/sh\ncase "$1" in\n  cat) exit 0 ;;\n  start)\n'
            '    [ -s "%s" ] && { echo again >> "%s"; exit 1; }\n    echo start >> "%s"\n'
            '    case "$(cat "%s")" in\n'
            '    run) exec %s ;;\n    fail) exit 1 ;;\n    movefail) %s; exit 1 ;;\n'
            '    movelock) %s; chmod 555 "%s"; exit 1 ;;\n  esac ;;\nesac\nexit 0\n'
            % (self.starts, self.starts, self.starts, self.mode, run, run, run, self.home))
        for stub in ('nginx', 'systemctl'):
            os.chmod(self.bin / stub, 0o755)
        self.mode.write_text('run')
        # The previous release, with refined live.
        for name, data in self.contents('old').items():
            path = self.home / 'releases' / self.PREV / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        os.symlink(str(self.home / 'releases' / self.PREV), self.home / 'current')
        self.request.write_text('refined\n')
        self.assertEqual(switch.apply(str(self.home), str(self.request), str(self.state))[0]['live'], 'refined')

    def tearDown(self):
        os.chmod(self.home, 0o755)
        self.temp.cleanup()

    def contents(self, tag):
        page = lambda name: ('<!doctype html><title>%s %s</title><link rel="stylesheet" href="styles.css">' % (name, tag)).encode()
        out = {'index.html': page('original'), 'variants.json': b'{}'}
        # Same size, different content: the case where an mtime+size validator could collide.
        for name, css in (('original', b'body{color:red}'), ('refined', b'body{color:tan}')):
            out['variants/%s/index.html' % name] = page(name)
            out['variants/%s/styles.css' % name] = css
            out['variants/%s/fonts/saira.ttf' % name] = b'the same font in both'
            out['variants/%s/og.png' % name] = ('%s card %s' % (name, tag)).encode()
        out['variants/refined/index.html'] += ('<meta property="og:image" content="%s">' % self.card_url(tag)).encode()
        return out

    @staticmethod
    def card_url(tag):
        return 'https://volt.jjricks.com/preview/refined/og.png?v=' + hashlib.sha256(('refined card %s' % tag).encode()).hexdigest()[:12]

    def fetch(self, url):
        """What nginx (site/nginx/volt-homepage.conf) answers: /preview/<name>/ from the current release's
        variants, everything else from the live variant."""
        path = url.split('https://volt.jjricks.com', 1)[1].split('?')[0]
        m = re.fullmatch(r'/preview/([a-z]+)/(.+)', path)
        folder = self.home / 'current' / 'variants' / m.group(1) if m else self.home / 'live'
        return (folder / (m.group(2) if m else path.lstrip('/'))).read_bytes()

    def deploy(self, switch_installed=True):
        contents = self.contents('new')
        tar_path = self.uploads / ('volt-home-%s.tar.gz' % self.NEW)
        with tarfile.open(tar_path, 'w:gz') as tar:
            for name, data in sorted(contents.items()):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        manifest = self.uploads / ('volt-home-%s.manifest' % self.NEW)
        manifest.write_text(''.join('%s  %s\n' % (hashlib.sha256(d).hexdigest(), n) for n, d in sorted(contents.items())))
        digest = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
        env = dict(os.environ, VOLT_HOME_DIR=str(self.home), VOLT_NGINX_DIR=str(self.nginx), VOLT_UPLOAD_DIR=str(self.uploads),
                   VOLT_SWITCH_BIN=str(self.switch_bin if switch_installed else self.bin / 'missing'),
                   VOLT_SWITCH_STATUS=str(self.state / 'status.json'), VOLT_RELEASE_OWNER='%d:%d' % (os.getuid(), os.getgid()),
                   PATH=str(self.bin) + os.pathsep + os.environ['PATH'])
        return subprocess.run(['bash', str(SITE / 'server' / 'deploy-release.sh'), self.NEW, digest(tar_path), digest(manifest),
                               self.PREV, digest(self.config)], capture_output=True, text=True, env=env)

    def current(self):
        return os.path.basename(os.path.realpath(self.home / 'current'))

    def live(self):
        return os.readlink(self.home / 'live')

    def variant(self, release, name):
        return str(self.home / 'releases' / release / 'variants' / name)

    def validator(self, url_path):
        """What nginx compares on a return visit: ETag "<mtime hex>-<size hex>" and Last-Modified (mtime)."""
        info = os.stat(os.path.join(self.live(), url_path))
        return '"%x-%x"' % (int(info.st_mtime), info.st_size), int(info.st_mtime)

    def test_deploy_moves_live_into_the_new_release(self):
        out = self.deploy()
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertRegex(out.stdout, r'UPDATE_OK .* live=refined result=ok in_new_release=yes')
        self.assertEqual((self.current(), self.live()), (self.NEW, self.variant(self.NEW, 'refined')))

    def test_return_visit_across_a_switch_never_reuses_the_old_file(self):
        self.assertEqual(self.deploy().returncode, 0)
        css, font = self.validator('styles.css'), self.validator('fonts/saira.ttf')
        self.request.write_text('original\n')
        self.assertEqual(switch.apply(str(self.home), str(self.request), str(self.state))[0]['live'], 'original')
        self.assertNotEqual(self.validator('styles.css')[0], css[0], 'same size, different CSS: the ETag must change')
        self.assertNotEqual(self.validator('styles.css')[1], css[1], 'and Last-Modified too')
        self.assertEqual(self.validator('fonts/saira.ttf'), font, 'an identical file keeps its validators (304)')
        # Derived from the content alone: the same as the manifest's hash says, in every release.
        digest = hashlib.sha256(b'body{color:red}').hexdigest()
        self.assertEqual(self.validator('styles.css')[1], 1_000_000_000 + int(digest[:15], 16) % 600_000_000)

    def test_link_preview_image_does_not_depend_on_what_is_live(self):
        url = self.card_url('old')
        refined = self.home / 'releases' / self.PREV / 'variants' / 'refined'
        self.assertEqual(checker.check_folder(refined), [], 'the page follows the og rule')
        for live in ('original', 'refined', 'original'):
            # Before refined goes live (an unfurl of its preview), while it is live, and after it stops being.
            self.request.write_text(live + '\n')
            self.assertEqual(switch.apply(str(self.home), str(self.request), str(self.state))[0]['live'], live)
            self.assertEqual(self.fetch(url), b'refined card old', live)
            self.assertEqual(hashlib.sha256(self.fetch(url)).hexdigest()[:12], url.rsplit('=', 1)[1])
        # Original keeps /og.png, which shows whichever variant is live: the documented limit.
        self.assertEqual(self.fetch('https://volt.jjricks.com/og.png'), b'original card old')
        # After a deploy, the new card has a new URL, served while either variant is live.
        self.request.write_text('refined\n')
        self.assertEqual(self.deploy().returncode, 0)
        self.assertEqual(self.fetch(self.card_url('new')), b'refined card new')
        self.assertNotEqual(self.card_url('new'), url)

    def test_failed_switch_with_a_stale_status_fails_and_restores(self):
        # A status claiming success for the new release, left by someone else, must not be believed.
        (self.state / 'status.json').write_text(json.dumps({'current_release': self.NEW, 'release': self.NEW, 'live': 'refined', 'result': 'ok'}))
        self.mode.write_text('fail')
        out = self.deploy()
        self.assertNotEqual(out.returncode, 0)
        self.assertNotIn('UPDATE_OK', out.stdout)
        self.assertIn('DEPLOY_FAILED', out.stderr)
        self.assertEqual((self.current(), self.live()), (self.PREV, self.variant(self.PREV, 'refined')))

    def test_switch_that_leaves_live_behind_fails_and_restores(self):
        self.mode.write_text('noop')
        out = self.deploy()
        self.assertNotEqual(out.returncode, 0)
        self.assertNotIn('UPDATE_OK', out.stdout)
        self.assertIn('was not left inside the new release', out.stderr)
        self.assertEqual((self.current(), self.live()), (self.PREV, self.variant(self.PREV, 'refined')))

    def test_switch_that_moves_live_then_fails_is_undone_without_the_switch(self):
        # The switch points live into the new release and then fails; the recovery start would fail too.
        self.mode.write_text('movefail')
        out = self.deploy()
        self.assertEqual(out.returncode, 1, out.stderr)
        self.assertNotIn('UPDATE_OK', out.stdout)
        self.assertIn('DEPLOY_FAILED', out.stderr)
        self.assertNotIn('DEPLOY_INCOMPLETE', out.stderr)
        self.assertEqual((self.current(), self.live()), (self.PREV, self.variant(self.PREV, 'refined')))
        self.assertEqual(self.starts.read_text(), 'start\n', 'the rollback must not start the switch again')

    @unittest.skipIf(os.geteuid() == 0, 'root ignores the locked folder')
    def test_rollback_that_cannot_finish_says_deploy_incomplete(self):
        self.mode.write_text('movelock')
        out = self.deploy()
        os.chmod(self.home, 0o755)
        self.assertNotEqual(out.returncode, 0)
        self.assertNotIn('UPDATE_OK', out.stdout)
        self.assertNotIn('DEPLOY_FAILED', out.stderr)
        self.assertRegex(out.stderr, r'DEPLOY_INCOMPLETE .*\(want [^)]*/releases/%s\)' % self.PREV)
        self.assertIn('DEPLOY_INCOMPLETE finish by hand', out.stderr)
        self.assertIn('ln -sfn %s/releases/%s/variants/refined %s/live.new' % (self.home, self.PREV, self.home), out.stderr)
        # Following the printed steps puts both links back.
        steps = re.search(r'finish by hand, as root: (.*)', out.stderr).group(1).replace('mv -Tf', 'python3 -I -c "import os,sys; os.replace(*sys.argv[1:])"')
        subprocess.run(['sh', '-c', steps], check=True, capture_output=True)
        self.assertEqual((self.current(), self.live()), (self.PREV, self.variant(self.PREV, 'refined')))

    def test_first_deploy_before_the_switch_exists(self):
        self.config.write_text('server {\n    root /var/www/volt-home/current;\n}\n')
        out = self.deploy(switch_installed=False)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn('live=not-installed', out.stdout)
        self.assertEqual(self.current(), self.NEW)

    def test_nginx_serving_live_without_the_switch_changes_nothing(self):
        out = self.deploy(switch_installed=False)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('the switch is not installed', out.stderr)
        self.assertEqual(self.current(), self.PREV)
        self.assertFalse((self.home / 'releases' / self.NEW).exists())


if __name__ == '__main__':
    unittest.main(verbosity=1)
