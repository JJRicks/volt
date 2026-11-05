import hashlib
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council_fixture  # noqa: E402
import fontmetrics  # noqa: E402
import gen_catalog  # noqa: E402
import translib  # noqa: E402

B = chr(92)


class EncodingTest(unittest.TestCase):
    def roundtrip(self, text):
        raw = translib.encode_resource_text(text)
        decoded, problems, _ = gen_catalog.decode_resource_text(raw)
        self.assertEqual(problems, [], (text, raw))
        self.assertEqual(decoded, text, raw)
        return raw

    def test_cases(self):
        self.assertEqual(self.roundtrip("No borres"), "No borres")
        self.assertEqual(self.roundtrip("l'app"), "l" + B + "'app")
        self.assertEqual(self.roundtrip('Pulsa "Guardar"'), "Pulsa " + B + '"Guardar' + B + '"')
        self.assertEqual(self.roundtrip(" edge "), '" edge "')
        self.assertEqual(self.roundtrip("a  b"), '"a  b"')
        self.assertEqual(self.roundtrip("@home"), B + "@home")
        self.assertEqual(self.roundtrip("one\ntwo\tthree"), "one" + B + "ntwo" + B + "tthree")
        for text in ("?", "#1", "C:" + B + "x", "\u00a0lead", "100 %", "%1$s%%", "\ufffc", "🔋 ok", "\r", "x\x01",
                     '"', "'", "a\u2028b", "\n"):
            self.roundtrip(text)

    def test_random_texts_roundtrip(self):
        alphabet = "ab '\"\\@?#\n\t%$1éß\u00a0\u202f\ufffc🔋<>&\r\x07"
        rng = random.Random(7)
        for _ in range(3000):
            self.roundtrip("".join(rng.choice(alphabet) for _ in range(rng.randint(0, 9))))


class SourceTest(unittest.TestCase):
    def setUp(self):
        self.repo = council_fixture.FixtureRepo()

    def tearDown(self):
        self.repo.close()

    def test_source_sha1_definition(self):
        expected = hashlib.sha1("string\0other\0Cancel\0".encode("utf-8")).hexdigest()
        self.assertEqual(translib.source_sha1("string", {"other": "Cancel"}), expected)
        plural = translib.source_sha1("plural", {"other": "%1$d x", "one": "%1$d y"})
        self.assertEqual(plural, hashlib.sha1("plurals\0one\0%1$d y\0other\0%1$d x\0".encode("utf-8")).hexdigest())
        self.assertNotEqual(translib.source_sha1("string", {"other": "Cancel "}), expected)

    def test_load_source_by_area(self):
        sources, tag_area = translib.load_source(self.repo.paths)
        self.assertEqual(list(sources), ["ble", "common"])
        self.assertEqual(tag_area, "common")
        keys = [s.key for s in sources["common"]]
        self.assertEqual(keys[:3], ["common_cancel", "common_lock_waiting", "common_lock_waiting_short"])
        self.assertNotIn("l10n_tag", keys)
        self.assertNotIn("app_name", [s.key for rows in sources.values() for s in rows])
        groups = translib.ladder_groups(sources["common"])
        self.assertEqual(dict(groups), {"common_lock_waiting": ["common_lock_waiting", "common_lock_waiting_short"]})
        dont = [s for s in sources["common"] if s.key == "common_dont"][0]
        self.assertEqual(dont.items["other"], "Don't clear storage.")

    def test_language_and_glossary(self):
        lang = translib.load_language(self.repo.paths, "pt-BR")
        self.assertEqual(lang.folder, "values-pt-rBR")
        self.assertEqual(translib.load_language(self.repo.paths, "pt-PT").folder, "values-pt")
        with self.assertRaises(translib.L10nError):
            translib.load_language(self.repo.paths, "fr")
        with self.assertRaises(translib.L10nError):
            translib.load_language(self.repo.paths, "en")
        terms = dict((t.id, t) for t in translib.load_glossary(self.repo.paths, "es"))
        self.assertEqual(terms["scooter"].targets, ["patinete", "patinetes"])
        self.assertTrue(terms["navee"].dnt)
        self.assertEqual(terms["lock"].targets, ["bloquear", "bloqueado", "bloqueo"])
        # A term with no row yet has no target (glossary-check reports the missing row).
        glossary = self.repo.read("l10n/glossary/es.tsv")
        self.repo.write("l10n/glossary/es.tsv", "".join(
            line for line in glossary.splitlines(True) if not line.startswith("lock\t")))
        self.addCleanup(self.repo.write, "l10n/glossary/es.tsv", glossary)
        self.assertIsNone(dict((t.id, t) for t in translib.load_glossary(self.repo.paths, "es"))["lock"].targets)
        hits = [t.id for t in translib.glossary_hits(terms.values(), "Unlock the Scooter")]
        self.assertEqual(hits, ["scooter"])  # "lock" inside "Unlock" is not a whole word

    def test_draft_roundtrip(self):
        path = self.repo.path("l10n", "work", "es", "common", "drafts", "claude-opus.xml")
        translib.write_draft(path, "claude-opus", "claude-opus-5-5", [
            ("common_dont", "string", {"other": "No borres l'almacenamiento  ahora "}, 1, "a" * 40),
            ("common_scooters_skipped", "plural", {"one": "%1$d <x> & y", "other": "%1$d z"}, 2, None)],
            prompt="draft-review")
        draft = translib.read_draft(path)
        self.assertEqual((draft.provider, draft.model, draft.prompt), ("claude", "claude-opus-5-5", "draft-review"))
        self.assertEqual(draft.entries["common_dont"].items["other"], "No borres l'almacenamiento  ahora ")
        self.assertEqual(draft.entries["common_dont"].problems, [])
        self.assertEqual(draft.entries["common_scooters_skipped"].items["one"], "%1$d <x> & y")
        self.assertEqual(draft.entries["common_scooters_skipped"].attempt, 2)
        self.assertEqual(draft.entries["common_dont"].src, "a" * 40)
        self.assertIsNone(draft.entries["common_scooters_skipped"].src)
        with self.assertRaises(translib.L10nError):
            translib.write_draft(path, "mistral", "m", [])

    def test_hand_written_draft_problems_are_kept(self):
        self.repo.write("l10n/work/es/common/drafts/claude-x.xml",
                        '<resources xmlns:volt="%s" volt:model="claude-sonnet-5">\n'
                        '  <string name="common_dont">No borres l\'almacenamiento</string>\n</resources>\n'
                        % gen_catalog.VOLT_NS)
        draft = translib.read_draft(self.repo.path("l10n/work/es/common/drafts/claude-x.xml"))
        self.assertTrue(any("apostrophe" in p for p in draft.entries["common_dont"].problems))
        self.repo.write("l10n/work/es/common/drafts/claude-y.xml", "<resources><string name='a'>b</string></resources>")
        with self.assertRaises(translib.L10nError):
            translib.read_draft(self.repo.path("l10n/work/es/common/drafts/claude-y.xml"))

    def test_normalize(self):
        self.assertEqual(translib.normalize("«Hola»  mundo\u00a0"), '"Hola" mundo')
        self.assertEqual(translib.normalize("Cafe\u0301"), "Café")


class FontTest(unittest.TestCase):
    def setUp(self):
        self.font = fontmetrics.Font(council_fixture.REAL_FONT)

    def test_coverage(self):
        self.assertEqual(self.font.missing("Esperando ñ é ü ß €"), [])
        self.assertIn("П", self.font.missing("Привет"))
        self.assertEqual(self.font.missing("a\nb"), [])

    def test_approximation(self):
        measurer = fontmetrics.Measurer(council_fixture.REAL_FONT, use_java=False)
        widths = measurer.measure([(14, "Lock"), (28, "Lock"), (14, ""), (14, "Lock\nLock scooter")])
        self.assertEqual(measurer.method, fontmetrics.METHOD_APPROX)
        self.assertAlmostEqual(widths[1], 2 * widths[0], places=3)
        self.assertEqual(widths[2], 0)
        self.assertAlmostEqual(widths[3], measurer.measure([(14, "Lock scooter")])[0], places=3)

    @unittest.skipUnless(fontmetrics.java_command(), "no Java runtime")
    def test_java_agrees_with_approximation(self):
        measurer = fontmetrics.Measurer(council_fixture.REAL_FONT)
        texts = [(14, "Esperando al patinete"), (14, "Waiting for the scooter"), (12, "Bloq.")]
        widths = measurer.measure(texts)
        if measurer.method != fontmetrics.METHOD_JAVA:
            self.skipTest("java.awt unavailable: %s" % measurer.java_problem)
        for (size, text), width in zip(texts, widths):
            approx = measurer.font.approx_width(text, size)
            self.assertLess(abs(width - approx) / approx, 0.05, text)
            self.assertLessEqual(width, approx + 0.5, text)  # kerning only narrows Saira's Latin text


if __name__ == "__main__":
    unittest.main()
