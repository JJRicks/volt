#!/usr/bin/env python3
"""Homepage variants (site/README.md, "Variants"): the build contract, the variant markup check, the nginx
configuration and the live-variant switch. Temporary folders only; no network, server or browser."""
import importlib.machinery, importlib.util, json, os, re, shutil, stat, subprocess, sys, tempfile, threading, unittest
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


def make_tree(root):
    """The files site/build.sh reads, as the deploy's git archive provides them."""
    root = Path(root)
    shutil.copytree(SITE, root / 'site', ignore=shutil.ignore_patterns('dist', 'dist.sha256', '.DS_Store'))
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
        self.assertIn(b'Disallow: /preview/', expected['robots.txt'])

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
    def failing(self, setup, message):
        with tempfile.TemporaryDirectory() as temp:
            site = make_tree(temp)
            variant = site / 'variants' / 'broken'
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


class VariantMarkup(unittest.TestCase):
    def test_todays_page_and_every_variant_pass(self):
        folders = [SITE / 'src'] + sorted(p for p in (SITE / 'variants').glob('*') if p.is_dir())
        for folder in folders:
            self.assertEqual(checker.check_folder(folder), [], folder)

    def page(self, body, extra=None):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / 'v'
            folder.mkdir()
            (folder / 'index.html').write_text('<!doctype html><html><head><title>t</title>%s</head><body></body></html>' % body)
            for name, text in (extra or {}).items():
                (folder / name).write_text(text)
            return checker.check_folder(folder)

    def test_allowed(self):
        ok = ['<link rel="canonical" href="https://volt.jjricks.com/">',
              '<meta property="og:image" content="https://volt.jjricks.com/og.png">',
              '<meta name="twitter:image" content="https://volt.jjricks.com/og.png">',
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

    def test_robots_disallows_previews(self):
        self.assertIn('Disallow: /preview/', (SITE / 'src' / 'robots.txt').read_text().splitlines())


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


if __name__ == '__main__':
    unittest.main(verbosity=1)
