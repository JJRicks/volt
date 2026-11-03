import contextlib
import io
import os
import sys
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council_fixture as cf  # noqa: E402
import gen_catalog  # noqa: E402
import merge  # noqa: E402
import translib  # noqa: E402

HAS_AAPT = merge.find_aapt()[0] is not None


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.paths = self.repo.paths
        self.sources, _ = translib.load_source(self.paths)

    def tearDown(self):
        self.repo.close()

    def decide(self, tag="es", area="common", texts=None, fallback=(), stale=()):
        texts = dict(cf.SPANISH if texts is None else texts)
        rows = []
        for s in self.sources[area]:
            if not s.translatable or s.key not in texts:
                continue
            sha = translib.source_sha1(s.kind, s.items)
            rows.append({"key": s.key, "kind": s.kind, "source_sha1": "1" * 40 if s.key in stale else sha,
                         "outcome": "fallback-english" if s.key in fallback else "agreed",
                         "text": None if s.key in fallback else texts[s.key]})
        translib.write_jsonl(self.paths.decisions(tag, area), rows)

    def run_merge(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = merge.main(["--root", self.repo.root] + list(args))
        return code, out.getvalue() + err.getvalue()

    def test_writes_sha_comments_and_tag(self):
        texts = dict(cf.SPANISH, common_dont="No borres l'almacenamiento  \"ahora\"")
        self.decide(texts=texts, fallback=("common_alarm_on",), stale=("common_capture",))
        code, out = self.run_merge("--tag", "es", "--area", "common", "--aapt", "off")
        self.assertEqual(code, 0, out)
        self.assertIn("common_alarm_on: falls back to English", out)
        self.assertIn("common_capture: the English changed since the decision", out)
        text = self.repo.read("res/values-es/strings_common.xml")
        self.assertIn('<string name="l10n_tag">es</string>', text)
        sha = translib.source_sha1("string", {"other": "Cancel"})
        self.assertIn("    <!-- src:%s -->\n    <string name=\"common_cancel\">Cancelar</string>" % sha, text)
        self.assertNotIn("common_alarm_on", text)
        self.assertNotIn("common_capture", text)
        self.assertNotIn("volt:", text)
        entries, problems = gen_catalog.read_strings_file(self.repo.path("res/values-es/strings_common.xml"))
        self.assertEqual(problems, [])
        by = dict((e.name, e) for e in entries)
        self.assertEqual(by["common_dont"].items["other"].text, "No borres l'almacenamiento  \"ahora\"")
        self.assertEqual(list(by["common_scooters_skipped"].items), ["one", "many", "other"])
        self.assertEqual(by["common_cancel"].note, "src:" + sha)

    def test_portuguese_folders(self):
        texts = {"ble_unlock": "Desbloquear patinete", "ble_searching": "Procurando 2 patinetes"}
        self.decide(tag="pt-BR", area="ble", texts=texts)
        self.decide(tag="pt-PT", area="ble", texts={"ble_unlock": "Desbloquear trotinete",
                                                    "ble_searching": "A procurar 2 trotinetes"})
        self.assertEqual(self.run_merge("--tag", "pt-BR", "--all", "--aapt", "off")[0], 0)
        self.assertEqual(self.run_merge("--tag", "pt-PT", "--all", "--aapt", "off")[0], 0)
        self.assertIn("Procurando", self.repo.read("res/values-pt-rBR/strings_ble.xml"))
        self.assertIn("trotinete", self.repo.read("res/values-pt/strings_ble.xml"))
        # The tag lives in the common area, which has not been merged yet: a note, not a failure.
        code, out = self.run_merge("--tag", "pt-BR", "--check", "--aapt", "off")
        self.assertEqual(code, 0, out)
        self.assertIn("no l10n_tag yet: merge the common area", out)

    def test_validation_catches_hand_edits(self):
        self.decide()
        self.assertEqual(self.run_merge("--tag", "es", "--area", "common", "--aapt", "off")[0], 0)
        path = "res/values-es/strings_common.xml"
        original = self.repo.read(path)
        cases = [
            (original.replace("<string name=\"l10n_tag\">es</string>", "<string name=\"l10n_tag\">en</string>"),
             "l10n_tag must be 'es'"),
            (original.replace("</resources>", "    <string name=\"common_extra\">Extra</string>\n</resources>"),
             "not in the default catalog"),
            (original.replace("</resources>", "    <string name=\"common_internal\">x</string>\n</resources>"),
             "translatable=\"false\""),
            (original.replace("a la posición %2$d", "a la posición"), "placeholders differ"),
            (original.replace("<item quantity=\"many\">%1$d de patinetes omitidos</item>", ""), "missing plural forms many"),
            (original.replace("Esperando</string>", "Esperando</string>".replace("Esperando", "Esp.")).replace(
                '    <string name="common_lock_waiting_short">Esp.</string>\n', ""), "partly translated"),
        ]
        for edited, expected in cases:
            self.repo.write(path, edited)
            code, out = self.run_merge("--tag", "es", "--check", "--aapt", "off")
            self.assertEqual(code, 1, expected)
            self.assertIn(expected, out)
        # Changing the English makes the translation stale.
        self.repo.write(path, original)
        self.repo.write("res/values/strings_common.xml", self.repo.read("res/values/strings_common.xml").replace(
            ">Cancel<", ">Cancel now<"))
        code, out = self.run_merge("--tag", "es", "--check", "--aapt", "off")
        self.assertEqual(code, 1)
        self.assertIn("common_cancel: stale", out)

    def test_folder_must_be_a_listed_language(self):
        self.repo.write("res/values-fr/strings_common.xml", "<resources><string name=\"common_cancel\">Annuler"
                                                            "</string></resources>")
        self.decide()
        self.run_merge("--tag", "es", "--area", "common", "--aapt", "off")
        code, out = self.run_merge("--tag", "es", "--check", "--aapt", "off")
        self.assertEqual(code, 1)
        self.assertIn("values-fr", out)

    def test_aapt_required_without_an_sdk(self):
        self.decide()
        empty = self.repo.path("no-sdk")
        os.makedirs(empty)
        with unittest.mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": empty}):
            code, out = self.run_merge("--tag", "es", "--area", "common", "--aapt", "required")
            self.assertEqual(code, 1)
            self.assertIn("is not installed", out)
            code, out = self.run_merge("--tag", "es", "--check")
            self.assertEqual(code, 0)
            self.assertIn("aapt not found", out)

    @unittest.skipUnless(HAS_AAPT, "aapt %s is not installed" % merge.AAPT_VERSION)
    def test_aapt_stores_every_text_exactly(self):
        texts = dict(cf.SPANISH, common_dont="No borres l'almacenamiento  \"ahora\" ",
                     common_cancel="@Cancelar ?\n\tya")
        self.decide(texts=texts)
        code, out = self.run_merge("--tag", "es", "--area", "common", "--aapt", "required")
        self.assertEqual(code, 0, out)
        self.assertIn("compiled", out)
        self.decide(tag="pt-BR", area="ble", texts={"ble_unlock": "Desbloquear patinete",
                                                   "ble_searching": "Procurando 2 patinetes"})
        code, out = self.run_merge("--tag", "pt-BR", "--area", "ble", "--aapt", "required")
        self.assertEqual(code, 0, out)
        self.assertIn("in pt-rBR exactly", out)
        # A hand-written spelling that aapt would rewrite (collapsed double space) is caught.
        path = "res/values-es/strings_common.xml"
        self.repo.write(path, self.repo.read(path).replace(">No informado<", ">No  informado<"))
        code, out = self.run_merge("--tag", "es", "--check", "--aapt", "required")
        self.assertEqual(code, 1, out)
        self.assertIn("repeated spaces", out)
        self.assertIn("aapt stored string/common_not_reported[other] as 'No informado'", out)

if __name__ == "__main__":
    unittest.main()
