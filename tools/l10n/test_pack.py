import contextlib
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council  # noqa: E402
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


def shipped(rows):
    """A shipped res/values-*/strings_common.xml with a src comment per row: [(key, text, english or None)];
    None gives an outdated src."""
    lines = ["<resources>"]
    for key, text, english in rows:
        sha = translib.source_sha1("string", {"other": english}) if english else "0" * 40
        lines.append('    <!-- src:%s -->\n    <string name="%s">%s</string>' % (sha, key, text))
    return "\n".join(lines + ["</resources>", ""])


class WidthHintTest(unittest.TestCase):
    """pack.py --width-hints: shipped texts that fit a budget tightly or by abbreviation, for the drafters."""

    SHORT = "common_lock_waiting_short"  # 75dp@14sp, English "Waiting"

    def setUp(self):
        self.repo = council_fixture.FixtureRepo()
        self.measurer = fontmetrics.Measurer(self.repo.paths.font(), use_java=False)
        full = "Waiting for the scooter"
        # de fits tightly (73.6dp of 75 by the approximation); pt-PT and pt-BR by abbreviation.
        self.repo.write("res/values-de/strings_common.xml", shipped([
            (self.SHORT, "Warten auf", "Waiting"), ("common_lock_waiting", "Warten auf den Roller", full)]))
        self.repo.write("res/values-pt/strings_common.xml", shipped([
            (self.SHORT, "Aguard.", "Waiting"),
            ("common_lock_waiting", "Aguardando a resposta da trot.", full)]))  # over its budget: no hint
        self.repo.write("res/values-pt-rBR/strings_common.xml", shipped([
            (self.SHORT, "Esp. resp.", "Waiting"), ("common_lock_waiting", "Esp. o patinete", None)]))  # outdated
        self.repo.write("res/values-ru/strings_common.xml", shipped([(self.SHORT, "\u041e\u0436\u0438\u0434.", "Waiting")]))

    def tearDown(self):
        self.repo.close()

    def build(self, hints=None):
        return pack.build_pack(self.repo.paths, "es", "common", self.measurer, width_hint_tags=hints)

    def test_hints_for_a_tight_key(self):
        p = self.build(["de", "pt-PT", "pt-BR", "ru", "es"])
        self.assertEqual(p["width_hints"]["languages"], ["de", "pt-PT", "pt-BR", "ru", "es"])
        hints = p["width_hints"]["keys"]
        # Only the tight key; at most WIDTH_HINT_LANGUAGES, in the order given. ru (system font) is never
        # measured, es is the pack's own language, and over-budget or outdated texts are no hints.
        self.assertEqual(list(hints), [self.SHORT])
        self.assertEqual([(h["tag"], h["text"], h["budget_dp"]) for h in hints[self.SHORT]],
                         [("de", "Warten auf", 75.0), ("pt-PT", "Aguard.", 75.0)])
        de, pt = (h["width_dp"] for h in hints[self.SHORT])
        self.assertTrue(pack.TIGHT_SHARE * 75 <= de <= 75, de)
        self.assertLess(pt, pack.TIGHT_SHARE * 75)  # in only by its abbreviation
        by = dict((e["key"], e) for e in p["entries"])
        prompt = council.user_prompt(p, [by["common_cancel"], by["common_lock_waiting"], by[self.SHORT]])
        self.assertIn(
            "\n\nWidth hints: how shipped languages fit some tight keys below. A hint is about fitting the width "
            "only; translate from the English, not from these texts.\n"
            "- common_lock_waiting_short (budget 75dp at 14sp): de \u00abWarten auf\u00bb, %.1fdp of 75dp; "
            "pt-PT \u00abAguard.\u00bb, %.1fdp of 75dp\n\n[" % (de, pt), prompt)
        self.assertEqual(prompt.count("Width hints"), 1)
        # The strings themselves are what they were: hints never enter a string's model view.
        self.assertEqual(json.loads(prompt[prompt.index("\n\n[") + 2:]),
                         [council.model_view(by[k]) for k in ("common_cancel", "common_lock_waiting", self.SHORT)])

    def test_off_by_default_leaves_pack_and_prompts_unchanged(self):
        off, on = self.build(), self.build(["de", "pt-PT"])
        self.assertNotIn("width_hints", off)
        self.assertEqual(json.dumps(off, ensure_ascii=False), json.dumps(self.build([]), ensure_ascii=False))
        self.assertEqual(dict((k, v) for k, v in on.items() if k not in ("width_hints", "pack_sha1")),
                         dict((k, v) for k, v in off.items() if k != "pack_sha1"))
        entries = off["entries"]
        head = "Translate these %d strings from res/values/strings_common.xml (area common)." % len(entries)
        self.assertEqual(council.user_prompt(off, entries),
                         head + "\n\n" + json.dumps([council.model_view(e) for e in entries], ensure_ascii=False,
                                                     indent=1))
        # With the option on, a chunk without a hinted key gets the prompt it would get with the option off.
        others = [e for e in entries if e["key"] != self.SHORT]
        self.assertEqual(council.user_prompt(on, others), council.user_prompt(off, others))

    def test_cli_option(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java",
                                        "--width-hints", "de, pt-PT,de"]), 0)
        self.assertIn("width hints for 1 keys", out.getvalue())
        p = json.loads(self.repo.read("l10n/packs/es/common.json"))
        self.assertEqual(p["width_hints"]["languages"], ["de", "pt-PT"])
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java"]), 0)
        self.assertNotIn("width_hints", json.loads(self.repo.read("l10n/packs/es/common.json")))
        self.assertNotIn("width hints", out.getvalue())
        with contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(pack.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java",
                                        "--width-hints", "fr"]), 1)
        self.assertIn("l10n/lang/fr.tsv does not exist", err.getvalue())


if __name__ == "__main__":
    unittest.main()
