import contextlib
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council_fixture  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import translib  # noqa: E402


class PackTest(unittest.TestCase):
    def setUp(self):
        self.repo = council_fixture.FixtureRepo()
        self.measurer = fontmetrics.Measurer(self.repo.paths.font(), use_java=False)

    def tearDown(self):
        self.repo.close()

    def build(self, tag="es", area="common"):
        return pack.build_pack(self.repo.paths, tag, area, self.measurer)

    def test_contents(self):
        p = self.build()
        self.assertEqual((p["format"], p["tag"], p["area"], p["folder"]), (pack.FORMAT, "es", "common", "values-es"))
        self.assertEqual(p["language"]["plurals"], ["one", "many", "other"])
        self.assertEqual(p["language"]["formality"], "tú")
        self.assertIn("Bloq.", p["style_guide"])
        self.assertEqual(p["width_method"], fontmetrics.METHOD_APPROX)
        by = dict((e["key"], e) for e in p["entries"])
        lock = by["common_lock_waiting"]
        self.assertEqual(lock["safety"], ["lock"])
        self.assertEqual(lock["ladder_group"], ["common_lock_waiting", "common_lock_waiting_short"])
        self.assertEqual((lock["budget_dp"], lock["budget_sp"]), (160.0, 14.0))
        self.assertGreater(lock["english_width_dp"], 100)
        self.assertLess(lock["english_width_dp"], 160)
        self.assertEqual(lock["note"], "Shown under the lock switch while Volt waits for the scooter's answer.")
        self.assertEqual([g["target"] for g in lock["glossary"] if g["id"] == "scooter"], [["patinete", "patinetes"]])
        self.assertEqual([n["key"] for n in lock["neighbours"]],
                         ["common_cancel", "common_lock_waiting_short", "common_move_widget"])
        self.assertEqual(by["common_move_widget"]["placeholders"], {"1": "s", "2": "d"})
        self.assertEqual(by["common_scooters_skipped"]["english"], {"one": "%1$d scooter skipped",
                                                                   "other": "%1$d scooters skipped"})
        self.assertEqual(by["common_disclaimer"]["dnt"], ["Volt", "NAVEE"])
        self.assertEqual(lock["source_sha1"], translib.source_sha1("string", {"other": "Waiting for the scooter"}))
        for entry in p["entries"]:
            self.assertEqual(tuple(entry), pack.PACK_ENTRY_FIELDS)
            self.assertEqual(entry["status"], "new")

    def test_only_ui_strings(self):
        keys = [e["key"] for e in self.build()["entries"]]
        self.assertNotIn("common_internal", keys)  # translatable="false"
        self.assertNotIn("l10n_tag", keys)
        self.assertNotIn("app_name", keys)  # strings.xml is not an area file
        # Reports and diagnostics never sit in strings_<area>.xml; anything else in res/ is not read.
        self.repo.write("res/values/report_templates.xml",
                        '<resources><string name="report_body">Diagnostics for the owner</string></resources>')
        text = json.dumps(self.build(), ensure_ascii=False)
        self.assertNotIn("Diagnostics for the owner", text)
        self.assertNotIn("volt-internal-id", text)

    def test_current_and_stale(self):
        sha = translib.source_sha1("string", {"other": "Cancel"})
        self.repo.write("res/values-es/strings_common.xml",
                        '<resources>\n    <!-- src:%s -->\n    <string name="common_cancel">Cancelar</string>\n'
                        '    <!-- src:%s -->\n    <string name="common_not_reported">No informado</string>\n'
                        '</resources>\n' % (sha, "0" * 40))
        by = dict((e["key"], e) for e in self.build()["entries"])
        self.assertEqual(by["common_cancel"]["status"], "current")
        self.assertEqual(by["common_cancel"]["current"]["text"], "Cancelar")
        self.assertEqual(by["common_not_reported"]["status"], "stale")
        self.assertTrue(by["common_not_reported"]["current"]["stale"])
        self.assertEqual(by["common_move_widget"]["status"], "new")

    def test_cli_writes_every_area(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "pt-BR", "--all", "--no-java"]), 0)
        self.assertIn("l10n/packs/pt-BR/common.json", out.getvalue())
        for area in ("ble", "common"):
            p = json.loads(self.repo.read("l10n/packs/pt-BR/%s.json" % area))
            self.assertEqual(p["folder"], "values-pt-rBR")
        with contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "fr", "--area", "common", "--no-java"]), 1)
        self.assertIn("l10n/lang/fr.tsv does not exist", err.getvalue())

    def test_sample_text(self):
        self.assertEqual(pack.sample_text("Move %1$s to %2$d at %3$.1f%%"), "Move 8888 to 888 at 88.8%")
        self.assertEqual(pack.sample_text("100%", formatted=False), "100%")


if __name__ == "__main__":
    unittest.main()
