"""ship_check.py on fixture roots: each way a shipped language can be broken fails, and drafts never do."""

import contextlib
import io
import os
import re
import sys
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import council_fixture as cf  # noqa: E402
import merge  # noqa: E402
import ship_check  # noqa: E402
import translib  # noqa: E402

BLE = {"ble_unlock": "Desbloquear patinete", "ble_searching": "Buscando 2 patinetes"}
MANIFEST = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x.y">'
            '<application%s /></manifest>\n')
NAMED = ' android:localeConfig="@xml/locales_config"'
SHIPPED_FILE = "res/values-es/strings_common.xml"


def locales_config(*tags):
    return ('<locale-config xmlns:android="http://schemas.android.com/apk/res/android">%s</locale-config>\n' %
            "".join('<locale android:name="%s" />' % tag for tag in tags))


class Base(unittest.TestCase):
    def setUp(self):
        self.repo = cf.FixtureRepo()
        self.addCleanup(self.repo.close)
        self.paths = self.repo.paths
        self.repo.write("AndroidManifest.xml", MANIFEST % "")
        self.repo.write("res/xml/locales_config.xml", locales_config("en"))
        self.network = cf.NoNetwork()
        self.network.__enter__()
        self.addCleanup(self.network.__exit__, None, None, None)
        # No key in the environment: nothing here may need one.
        names = [n for n in os.environ if n.endswith("_API_KEY")]
        patch = unittest.mock.patch.dict(os.environ)
        patch.start()
        self.addCleanup(patch.stop)
        for name in names:
            del os.environ[name]

    def set_status(self, tag, status):
        path = "l10n/lang/%s.tsv" % tag
        self.repo.write(path, re.sub(r"(?m)^status\t.*$", "status\t" + status, self.repo.read(path)))

    def decide(self, tag="es", fallback=()):
        sources, _ = translib.load_source(self.paths)
        texts = dict(cf.SPANISH, **BLE)
        for area, rows in sources.items():
            out = []
            safety = translib.effective_safety(rows)
            for s in rows:
                if s.translatable:
                    blocking = set(safety[s.key]) & set(translib.SAFETY_BLOCKING)
                    out.append({"key": s.key, "kind": s.kind, "source_sha1": translib.source_sha1(s.kind, s.items),
                                "outcome": ("fallback-english" if s.key in fallback else
                                            "safety-agreed" if blocking else "agreed"),
                                "text": None if s.key in fallback else texts[s.key]})
            translib.write_jsonl(self.paths.decisions(tag, area), out)

    def edit_decision(self, area, key, **fields):
        """Change one recorded decision, as a hand edit would, and merge again: the file then agrees with it."""
        path = self.paths.decisions("es", area)
        rows = translib.read_jsonl(path)
        for row in rows:
            if row["key"] == key:
                row.update(fields)
        translib.write_jsonl(path, rows)
        self.merge_all()

    def merge_all(self):
        for area in merge.decided_areas(self.paths, "es"):
            merge.merge_area(self.paths, "es", area)

    def ship_spanish(self, fallback=(), status="beta"):
        """A language that passes every rule, through the real merge."""
        self.decide(fallback=fallback)
        self.merge_all()
        self.set_status("es", status)
        self.repo.write("res/xml/locales_config.xml", locales_config("en", "es"))
        self.repo.write("AndroidManifest.xml", MANIFEST % NAMED)

    def run_tool(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = ship_check.main(["--root", self.repo.root, "--aapt", "off"] + list(extra))
        return code, out.getvalue() + err.getvalue()

    def assert_fails(self, *expected, release=False):
        code, out = self.run_tool(*(["--release"] if release else []))
        self.assertEqual(code, 1, out)
        for text in expected:
            self.assertIn(text, out)
        self.assertIn("ship_check: FAILED", out)
        return out

    def assert_passes_with_fallback(self, count):
        """Pull request mode: English fallback is a reference line, never a failure."""
        code, out = self.run_tool()
        self.assertEqual(code, 0, out)
        self.assertIn("es (values-es): %d of 13 keys fall back to English" % count, out)
        self.assertIn("reference only; a release needs 0", out)
        self.assertIn("ship_check: PASS", out)
        return out


class NothingShipsTest(Base):
    def test_says_plainly_that_it_checked_nothing(self):
        code, out = self.run_tool()
        self.assertEqual(code, 0, out)
        self.assertIn("no language has status beta or stable, so no translated text was checked", out)
        self.assertIn("ship_check: PASS", out)

    def test_a_draft_with_decisions_and_drafts_is_not_read(self):
        self.decide()
        # Not even broken work files can fail a draft: they are not read.
        self.repo.write("l10n/decisions/de/common.jsonl", "not json\n")
        self.repo.write("l10n/work/es/common/drafts/claude.xml", "<not closed")
        code, out = self.run_tool()
        self.assertEqual(code, 0, out)
        self.assertIn("not read: de, es, pt-BR, pt-PT, ru", out)

    def test_a_folder_with_no_language(self):
        self.repo.write("res/values-fr/strings_common.xml", '<resources><string name="common_cancel">Annuler</string>'
                                                            "</resources>\n")
        self.assert_fails("res/values-fr/strings_common.xml", "only language folders from l10n/lang")

    def test_a_folder_whose_language_is_a_draft(self):
        self.repo.write("res/values-de/strings_common.xml", '<resources><string name="common_cancel">Abbrechen</string>'
                                                            "</resources>\n")
        self.assert_fails("res/values-de holds text, but de has status draft")

    def test_a_folder_with_no_text_is_not_a_language(self):
        self.repo.write("res/values-night/colors.xml", '<resources><color name="x">#000</color></resources>\n')
        self.assertEqual(self.run_tool()[0], 0)

    def test_locales_config_and_manifest_stay_english(self):
        self.repo.write("res/xml/locales_config.xml", locales_config("en", "es"))
        self.assert_fails("locales_config.xml lists es, which does not ship")
        self.repo.write("res/xml/locales_config.xml", locales_config("en"))
        self.repo.write("AndroidManifest.xml", MANIFEST % NAMED)
        self.assert_fails("names android:localeConfig, but no language besides English ships")


class ShippedLanguageTest(Base):
    def test_a_good_language_passes(self):
        for status in ("beta", "stable"):
            self.ship_spanish(status=status)
            code, out = self.run_tool()
            self.assertEqual(code, 0, out)
            self.assertIn("ship_check: %s: es (values-es): 0 of 13 keys fall back" % status, out)
            self.assertNotIn("no language has status", out)

    def test_a_hand_edited_string(self):
        self.ship_spanish()
        self.repo.write(SHIPPED_FILE, self.repo.read(SHIPPED_FILE).replace(">Cancelar<", ">Anular<"))
        out = self.assert_fails("strings_common.xml: not what merge.py --tag es --all writes")
        self.assertIn("never the file", out)

    def test_a_hand_edited_file_with_no_decisions(self):
        self.ship_spanish()
        self.repo.write("res/values-es/strings_extra.xml", "<resources></resources>\n")
        self.assert_fails("strings_extra.xml: no decisions would write this file")

    def test_a_merged_file_that_was_deleted(self):
        self.ship_spanish()
        os.remove(self.repo.path("res/values-es/strings_ble.xml"))
        self.assert_fails("strings_ble.xml: missing; merge.py --tag es --all writes it")

    def test_a_stale_hash(self):
        self.ship_spanish()
        path = "res/values/strings_common.xml"
        self.repo.write(path, self.repo.read(path).replace(">Cancel<", ">Cancel now<"))
        self.assert_fails("common_cancel: stale: the English changed")

    def test_a_stale_hash_fails_in_release_mode_too(self):
        self.ship_spanish()
        path = "res/values/strings_common.xml"
        self.repo.write(path, self.repo.read(path).replace(">Cancel<", ">Cancel now<"))
        self.assert_fails("common_cancel: stale: the English changed", release=True)

    def test_merging_again_clears_a_stale_hash_and_the_fallback_still_counts_it(self):
        self.ship_spanish()
        path = "res/values/strings_common.xml"
        self.repo.write(path, self.repo.read(path).replace(">Cancel<", ">Cancel now<"))
        merge.merge_area(self.paths, "es", "common")
        out = self.assert_passes_with_fallback(1)
        self.assertNotIn("stale", out)
        out = self.assert_fails("es is not fully translated", "1 key(s) fall back to English",
                                "common_cancel (the English changed since the decision)", release=True)
        self.assertNotIn("stale", out)

    def test_a_beta_language_with_no_folder(self):
        self.decide()
        self.set_status("es", "beta")
        self.repo.write("res/xml/locales_config.xml", locales_config("en", "es"))
        self.repo.write("AndroidManifest.xml", MANIFEST % NAMED)
        self.assert_fails("res/values-es has no text, but es has status beta")

    def test_a_language_over_the_beta_bar_passes_a_pull_request(self):
        # One ordinary key of 13 is over the 2% beta bar; everything else about the language is right.
        self.ship_spanish(fallback=("common_cancel",))
        out = self.assert_passes_with_fallback(1)
        self.assertIn("(7.69%)", out)
        self.assertNotIn("problem:", out)

    def test_release_mode_fails_on_any_fallback_and_names_the_keys(self):
        self.ship_spanish(fallback=("common_cancel",))
        out = self.assert_fails("es is not fully translated, so no release ships",
                                "1 key(s) fall back to English: common_cancel (falls back to English)",
                                "translate them first", "release mode: a release needs 0", release=True)
        self.assertEqual(out.count("problem:"), 1, out)

    def test_release_mode_passes_with_no_fallback(self):
        for status in ("beta", "stable"):
            self.ship_spanish(status=status)
            code, out = self.run_tool("--release")
            self.assertEqual(code, 0, out)
            self.assertIn("ship_check: %s: es (values-es): 0 of 13 keys fall back" % status, out)
            self.assertIn("ship_check: PASS", out)

    def test_release_mode_lists_the_first_keys_and_counts_the_rest(self):
        self.ship_spanish(fallback=("common_cancel", "ble_searching"))
        with unittest.mock.patch.object(ship_check, "RELEASE_KEYS_SHOWN", 1):
            out = self.assert_fails("2 key(s) fall back to English", "and 1 more", release=True)
        self.assertEqual(len(re.findall(r"common_cancel|ble_searching", out.split("problem:")[1])), 1, out)

    def test_the_count_matches_the_beta_bar(self):
        self.ship_spanish(fallback=("common_cancel", "ble_unlock"))
        _, lines = ship_check.council.beta_report(self.paths, "es")
        total, fallback, _ = ship_check.fallback_report(self.paths, "es")
        self.assertIn("%d of %d keys fall back" % (len(fallback), total), lines[0])

    def test_a_lock_key_falling_back(self):
        self.ship_spanish(fallback=("ble_unlock",))
        self.assert_passes_with_fallback(1)
        self.assert_fails("ble_unlock (falls back to English; lock)", release=True)

    def assert_left_to_english(self, key, why):
        """A translated decision merge.py refuses: not in the folder, counted as fallback, and named in release mode."""
        self.assertNotIn('name="%s"' % key, "".join(
            self.repo.read(os.path.join("res/values-es", f)) for f in os.listdir(self.repo.path("res/values-es"))))
        self.assert_passes_with_fallback(1)
        self.assert_fails("1 key(s) fall back to English", "%s (%s" % (key, why), release=True)

    def test_a_lock_key_merge_refuses_to_write(self):
        self.ship_spanish()
        for text in (None, {"other": "Desbloquear"}, 7):
            self.edit_decision("ble", "ble_unlock", text=text)
            self.assert_left_to_english("ble_unlock", "the decision's text has the wrong shape")

    def test_a_blank_lock_key(self):
        self.ship_spanish()
        for text in ("", "   ", "\u00a0\n"):
            self.edit_decision("ble", "ble_unlock", text=text)
            self.assert_left_to_english("ble_unlock", "the decision's text is blank")

    def test_a_blank_ordinary_key_falls_back_and_counts(self):
        self.ship_spanish()
        self.edit_decision("common", "common_cancel", text=" ")
        self.assert_left_to_english("common_cancel", "the decision's text is blank")

    def test_a_plural_with_a_form_missing(self):
        self.ship_spanish()
        self.edit_decision("common", "common_scooters_skipped", text={"other": "%1$d patinetes omitidos"})
        self.assert_left_to_english("common_scooters_skipped", "missing plural forms one, many")

    def test_a_plural_with_a_blank_form(self):
        self.ship_spanish()
        self.edit_decision("common", "common_scooters_skipped", text={
            "one": "", "many": "%1$d de patinetes omitidos", "other": "%1$d patinetes omitidos"})
        self.assert_left_to_english("common_scooters_skipped", "the decision's text is blank")

    def test_a_plural_category_added_after_the_decisions(self):
        """No decision is edited: the language simply needs a form it does not have."""
        self.ship_spanish()
        self.repo.write("l10n/lang/es.tsv", re.sub(r"(?m)^plurals\t.*$", "plurals\tone,few,many,other",
                                                   self.repo.read("l10n/lang/es.tsv")))
        self.merge_all()
        self.assert_left_to_english("common_scooters_skipped", "missing plural forms few")

    def test_a_blank_string_written_by_hand(self):
        self.ship_spanish()
        self.repo.write(SHIPPED_FILE, self.repo.read(SHIPPED_FILE).replace(">Cancelar<", "><"))
        self.assert_fails("common_cancel[other]: blank, and the English is not")

    def test_a_lock_or_alarm_key_only_ships_safety_agreed(self):
        """decide() gives these no other translated outcome; Claude's drafts alone leave them falling back."""
        self.ship_spanish()
        for outcome in ("agreed", "judged"):
            self.edit_decision("ble", "ble_unlock", outcome=outcome)
            for release in (False, True):
                self.assert_fails("not safety-agreed", "ble_unlock (lock)", "0 of 13 keys fall back", release=release)
        self.edit_decision("ble", "ble_unlock", outcome="safety-agreed")
        self.assertEqual(self.run_tool()[0], 0)

    def test_the_fallback_reads_only_recorded_decisions(self):
        """No decisions at all is fallback, not a request for keys."""
        self.ship_spanish()
        os.remove(self.paths.decisions("es", "ble"))
        self.assert_fails("ble_unlock (no decision; lock)", "ble_searching (no decision)", release=True)

    def test_no_l10n_tag(self):
        self.ship_spanish()
        self.repo.write(SHIPPED_FILE, self.repo.read(SHIPPED_FILE).replace('    <string name="l10n_tag">es</string>\n', ""))
        self.assert_fails("no l10n_tag, so the app treats this folder as English")

    def test_missing_from_locales_config(self):
        self.ship_spanish()
        self.repo.write("res/xml/locales_config.xml", locales_config("en"))
        self.assert_fails("locales_config.xml does not list es")
        os.remove(self.repo.path("res/xml/locales_config.xml"))
        self.assert_fails("locales_config.xml is missing or unreadable")

    def test_manifest_does_not_name_locales_config(self):
        self.ship_spanish()
        self.repo.write("AndroidManifest.xml", MANIFEST % "")
        self.assert_fails('needs android:localeConfig="@xml/locales_config"')

    def test_a_draft_beside_a_shipped_language_is_still_not_read(self):
        self.ship_spanish()
        self.repo.write("l10n/decisions/de/common.jsonl", "not json\n")
        self.assertEqual(self.run_tool()[0], 0)


class FolderMappingTest(Base):
    """A wrong folder in l10n/lang fails the whole gate before any rule filters languages by folder."""

    def set_folder(self, tag, folder):
        path = "l10n/lang/%s.tsv" % tag
        self.repo.write(path, re.sub(r"(?m)^folder\t.*$", "folder\t" + folder, self.repo.read(path)))

    def test_a_beta_language_in_the_english_folder(self):
        # Codex's fixture: no Spanish text, decisions or manifest declaration; only the mapping and locales_config.
        self.set_status("es", "beta")
        self.set_folder("es", "values")
        self.repo.write("res/xml/locales_config.xml", locales_config("en", "es"))
        out = self.assert_fails("l10n/lang/es.tsv: folder values belongs to the source language en")
        self.assertNotIn("PASS", out)
        self.assertNotIn("no language has status beta or stable", out)

    def test_a_draft_in_the_english_folder(self):
        self.set_folder("es", "values")
        self.assert_fails("l10n/lang/es.tsv: folder values belongs to the source language en")

    def test_two_languages_sharing_a_folder(self):
        self.set_folder("pt-PT", "values-pt-rBR")  # pt-PT belongs in values-pt
        self.assert_fails("l10n/lang: pt-BR, pt-PT all name folder values-pt-rBR",
                          "l10n/lang/pt-PT.tsv: folder values-pt-rBR does not match the tag pt-PT")

    def test_a_folder_that_does_not_match_the_tag(self):
        self.ship_spanish()
        self.set_folder("es", "values-de")
        self.assert_fails("l10n/lang/es.tsv: folder values-de does not match the tag es")

    def test_the_fixture_languages_map_cleanly(self):
        languages = ship_check.gen_catalog.load_languages(self.paths.lang_dir())
        self.assertEqual(ship_check.mapping_problems(languages), [])


if __name__ == "__main__":
    unittest.main()
