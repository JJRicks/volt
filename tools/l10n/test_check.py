import collections
import contextlib
import copy
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
import council  # noqa: E402
import council_fixture  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import translib  # noqa: E402


class CheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = council_fixture.FixtureRepo()
        cls.measurer = fontmetrics.Measurer(cls.repo.paths.font(), use_java=False)
        cls.font = fontmetrics.Font(cls.repo.paths.font())
        cls.packs = {}
        for tag in ("es", "de", "ru", "pt-BR", "pt-PT"):
            cls.packs[tag] = pack.build_pack(cls.repo.paths, tag, "common", cls.measurer)
        cls.packs["es-ble"] = pack.build_pack(cls.repo.paths, "es", "ble", cls.measurer)

    @classmethod
    def tearDownClass(cls):
        cls.repo.close()

    def run_check(self, key, value, tag="es", raw_problems=(), pack_name=None, others=None):
        p = self.packs[pack_name or tag]
        entry = [e for e in p["entries"] if e["key"] == key][0]
        targets = collections.OrderedDict()
        for k, v in (others or {}).items():
            e = [x for x in p["entries"] if x["key"] == k][0]
            targets[k] = (e["kind"], translib.items_of(e["kind"], v), [])
        kind = entry["kind"] if translib.items_of(entry["kind"], value) is not None else (
            "plural" if isinstance(value, dict) else "string")
        targets[key] = (kind, translib.items_of(kind, value), list(raw_problems))
        if entry.get("ladder_group") and others is None:
            for k in entry["ladder_group"]:
                if k != key:
                    targets[k] = ("string", {"other": council_fixture.SPANISH[k]}, [])
        return check.check_targets(p, targets, self.font, self.measurer)[key]

    def checks(self, findings, severity=check.ERROR):
        return sorted(set(f["check"] for f in findings if f["severity"] == severity))

    def test_good_spanish_passes_everything(self):
        p = self.packs["es"]
        targets = collections.OrderedDict()
        for e in p["entries"]:
            targets[e["key"]] = (e["kind"], translib.items_of(e["kind"], council_fixture.SPANISH[e["key"]]), [])
        results = check.check_targets(p, targets, self.font, self.measurer)
        for key, findings in results.items():
            self.assertEqual(self.checks(findings), [], (key, findings))

    def test_placeholders(self):
        self.assertEqual(self.checks(self.run_check("common_move_widget", "Mover %1$s a la posición")),
                         ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_move_widget", "Mover %s a la posición %2$d")),
                         ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_move_widget", "Mover %1$d a la posición %2$s")),
                         ["placeholders"])
        # A width or precision on a %n$s argument is refused in a translation too (Fmt.compose's marks).
        self.assertEqual(self.checks(self.run_check("common_move_widget", "Mover %1$.3s a la posición %2$d")),
                         ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_move_widget", "Mover %1$-12s a la posición %2$d")),
                         ["placeholders"])
        # Reordering is allowed.
        self.assertEqual(self.checks(self.run_check("common_move_widget", "A la posición %2$d: mover %1$s")), [])
        self.assertEqual(self.checks(self.run_check("common_battery_percent", "%1$s %")), ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_battery_percent", "%1$s")), ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_battery_percent", "%%%1$s")), [])

    def test_numbers(self):
        findings = self.run_check("ble_searching", "Buscando patinetes", pack_name="es-ble")
        self.assertEqual(self.checks(findings), ["numbers"])
        self.assertEqual(self.checks(self.run_check("ble_searching", "Buscando 2 patinetes", pack_name="es-ble")), [])
        # Digits of another script count as the same number.
        self.assertNotIn("numbers", self.checks(self.run_check("ble_searching", "Buscando ٢ patinetes",
                                                               pack_name="es-ble")))
        self.assertIn("numbers", self.checks(self.run_check("ble_searching", "Buscando 2 patinetes 3",
                                                            pack_name="es-ble"), check.WARN))

    def test_plural_categories(self):
        findings = self.run_check("common_scooters_skipped", {"one": "%1$d patinete", "other": "%1$d patinetes"})
        self.assertEqual(self.checks(findings), ["plurals"])
        de = self.run_check("common_scooters_skipped", {"one": "%1$d Roller übersprungen",
                                                        "other": "%1$d Roller übersprungen"}, tag="de")
        self.assertEqual(self.checks(de), [])
        extra = self.run_check("common_scooters_skipped", {"one": "%1$d Roller", "few": "%1$d Roller",
                                                           "other": "%1$d Roller"}, tag="de")
        self.assertIn("plurals", self.checks(extra, check.WARN))
        # The one form may drop the count; the other form may not.
        ok = self.run_check("common_scooters_skipped", {"one": "Un patinete omitido", "many": "%1$d de patinetes",
                                                        "other": "%1$d patinetes"})
        self.assertEqual(self.checks(ok), [])
        bad = self.run_check("common_scooters_skipped", {"one": "%1$d patinete", "many": "%1$d de patinetes",
                                                         "other": "Patinetes omitidos"})
        self.assertEqual(self.checks(bad), ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_scooters_skipped", "%1$d patinetes")), ["kind"])

    def test_escapes(self):
        findings = self.run_check("common_dont", "No borres el almacenamiento.",
                                  raw_problems=["unescaped apostrophe: aapt rejects it"])
        self.assertEqual(self.checks(findings), ["escapes"])
        self.assertEqual(self.checks(self.run_check("common_dont", "No borres\x07 nada.")), ["escapes"])

    def test_dnt_and_fixed_tokens(self):
        self.assertEqual(self.checks(self.run_check("common_disclaimer", "Volt no está afiliada a Navee")), ["dnt"])
        self.assertEqual(self.checks(self.run_check("common_capture", "Guarda navee-propietario.json en Descargas")),
                         ["dnt"])

    def test_markup(self):
        p = self.packs["es"]
        entry = dict([e for e in p["entries"] if e["key"] == "common_cancel"][0])
        entry["english"] = "Run `adb devices` then **tap** Allow"
        language = p["language"]
        bad = check.check_key(entry, language, "string", {"other": "Ejecuta `adb dispositivos` y **toca Permitir"})
        self.assertEqual(self.checks(bad), ["markup"])
        good = check.check_key(entry, language, "string", {"other": "Ejecuta `adb devices` y **toca** Permitir"})
        self.assertEqual(self.checks(good), [])

    def test_glossary(self):
        findings = self.run_check("common_lock_waiting", "Esperando al scooter",
                                  others={"common_lock_waiting_short": "Esperando"})
        self.assertIn("glossary", self.checks(findings, check.WARN))
        self.assertEqual(self.checks(findings), [])
        # A safety glossary term in a safety key is an error.
        findings = self.run_check("common_disclaimer", "Volt no tiene relación con NAVEE")
        self.assertEqual(self.checks(findings), ["glossary"])

    def test_whitespace(self):
        self.assertEqual(self.checks(self.run_check("common_cancel", "Cancelar ")), ["whitespace"])
        self.assertIn("whitespace", self.checks(self.run_check("common_cancel", "Can  celar"), check.WARN))

    def test_glyphs(self):
        self.assertEqual(self.checks(self.run_check("common_cancel", "Отмена")), ["glyphs", "script"])
        self.assertIn("glyphs", self.checks(self.run_check("common_battery_percent", "%1$s\u202f%%"), check.WARN))
        # System-font languages are not checked against Saira.
        self.assertEqual(self.checks(self.run_check("common_cancel", "Отмена", tag="ru")), [])

    def english_mark(self, english, draft, tag="es"):
        """check_targets for a copy of common_cancel whose English holds a mark."""
        p = copy.deepcopy(self.packs[tag])
        entry = [e for e in p["entries"] if e["key"] == "common_cancel"][0]
        entry["english"] = english
        p["entries"] = [entry]
        targets = collections.OrderedDict(common_cancel=("string", {"other": draft}, []))
        return check.check_targets(p, targets, self.font, self.measurer)["common_cancel"]

    def test_fallback_symbols_follow_glyph_coverage_test(self):
        # The same list as the Java test, which is what reviews an entry; a change to one must change the other.
        path = os.path.join(translib.REPO_ROOT, "tests", "l10n", "GlyphCoverageTest.java")
        with open(path, encoding="utf-8") as handle:
            block = re.search(r"FALLBACK_SYMBOLS = (.*?)\)\)\s*;", handle.read(), re.S).group(1)
        listed = re.findall(r"new Fallback\(0x([0-9A-Fa-f]+), (true|false),", block)
        self.assertEqual({chr(int(code, 16)): emoji == "true" for code, emoji in listed}, check.FALLBACK_SYMBOLS)

    def test_fallback_symbols_pass_in_saira_languages(self):
        for english, draft in (("Sin límite \u2713", "Sin límite \u2713"), ("\u2606 Fijar", "\u2606 Fijar"),
                               ("\u2605 Fijo", "\u2605 Fijo"), ("Ojo \u26a0\ufe0e", "Ojo \u26a0\ufe0e")):
            self.assertEqual([f for f in self.english_mark(english, draft) if f["check"] == "glyphs"], [], english)

    def test_fallback_symbol_near_misses_fail(self):
        for english, draft, needle in (
                ("Ojo \u26a0\ufe0e", "Ojo \u26a0", "needs U+FE0E"),  # dropped the invisible selector
                ("Ojo \u26a0\ufe0e", "Ojo", "keep each mark"),  # dropped the mark
                ("Cancel", "Cancelar \u2713", "keep each mark"),  # added a mark
                ("Listo \u2713", "Listo \u2713\ufe0e", "U+FE0E belongs only"),  # a selector on another symbol
                ("Listo", "Li\ufe0esto", "U+FE0E belongs only"),
                ("Listo", "Listo \u2714", "no glyph"),  # the emoji check mark is not on the list
                ("Listo", "Listo \u25be", "no glyph")):
            found = [f for f in self.english_mark(english, draft) if f["check"] == "glyphs" and f["severity"] == check.ERROR]
            self.assertTrue(any(needle in f["message"] for f in found), (draft, found))
        # U+FE0E's rules bind a system-font language too.
        found = self.english_mark("Ojo \u26a0\ufe0e", "Ojo \u26a0", tag="ru")
        self.assertTrue(any("needs U+FE0E" in f["message"] for f in found), found)

    def test_drafting_prompt_names_the_marks(self):
        prompt = council.instructions(self.packs["es"])
        prompt = prompt if isinstance(prompt, str) else "\n".join(prompt)
        for mark in ("\u2713", "\u26a0\ufe0e", "\u2606", "\u2605", "U+FE0E"):
            self.assertIn(mark, prompt)

    def test_ladder_and_width(self):
        p = self.packs["es"]
        lone = check.check_targets(p, {"common_lock_waiting": ("string", {"other": "Esperando al patinete"}, [])},
                                   self.font, self.measurer)
        self.assertEqual(self.checks(lone["common_lock_waiting"]), ["ladder"])
        wide = self.run_check("common_lock_waiting_short", "Esperando respuesta",
                              others={"common_lock_waiting": "Esperando al patinete"})
        self.assertEqual(self.checks(wide), ["width"])
        ru = self.run_check("common_lock_waiting_short", "Ожидание ответа от самоката", tag="ru",
                            others={"common_lock_waiting": "Ожидание самоката"})
        self.assertEqual(self.checks(ru), [])
        self.assertIn("width", self.checks(ru, check.INFO))

    def test_polarity(self):
        # common_not_reported is not a safety key: a missing negation warns.
        self.assertIn("polarity", self.checks(self.run_check("common_not_reported", "Informado"), check.WARN))
        self.assertEqual(self.checks(self.run_check("common_not_reported", "Sin informar")), [])
        # In a safety key it is an error.
        self.assertEqual(self.checks(self.run_check("common_disclaimer", "Volt está afiliada a NAVEE")),
                         ["glossary", "polarity"])
        de = self.run_check("common_not_reported", "Nicht gemeldet", tag="de")
        self.assertEqual(self.checks(de, check.WARN), [])

    def test_untranslated_and_empty(self):
        self.assertIn("untranslated", self.checks(self.run_check("common_not_reported", "Not reported"), check.WARN))
        self.assertEqual(self.checks(self.run_check("common_cancel", "")), ["empty"])
        for blank in ("\u200b", " \u200b\u2060", "\u00a0", "\u3164", "\u2800"):
            self.assertEqual(self.checks(self.run_check("common_cancel", blank)), ["empty"], repr(blank))

    def test_script_leakage(self):
        self.assertEqual(self.checks(self.run_check("common_cancel", "Cancelar отмена")), ["glyphs", "script"])
        ru = self.run_check("common_disclaimer", "Volt не связан с NAVEE", tag="ru")
        self.assertNotIn("script", self.checks(ru) + self.checks(ru, check.WARN))
        # A Latin word from another language in a Cyrillic target is leakage: an error.
        for leaked in ("Stop отмена", "Abbrechen", "Отмена Cancelar"):
            self.assertEqual(self.checks(self.run_check("common_cancel", leaked, tag="ru")), ["script"], leaked)
        # A technical name or an acronym the English lacks only warns.
        ru = self.run_check("common_cancel", "Отмена (Bluetooth)", tag="ru")
        self.assertEqual(self.checks(ru), [])
        self.assertIn("script", self.checks(ru, check.WARN))
        self.assertIn("script", self.checks(self.run_check("common_cancel", "Отмена USB", tag="ru"), check.WARN))

    def test_plural_forms_covering_many_numbers_keep_the_count(self):
        # Russian "one" covers 21, 31, 101: writing the word "one" there shows "one scooter" for 21.
        ru = {"one": "Пропущен один самокат", "few": "Пропущено %1$d самоката", "many": "Пропущено %1$d самокатов",
              "other": "Пропущено %1$d самоката"}
        findings = self.run_check("common_scooters_skipped", ru, tag="ru")
        self.assertEqual(self.checks(findings), ["placeholders"])
        self.assertEqual([f["quantity"] for f in findings if f["severity"] == check.ERROR], ["one"])
        ru_few = dict(ru, one="Пропущен %1$d самокат", few="Пропущено несколько самокатов")
        self.assertEqual(self.checks(self.run_check("common_scooters_skipped", ru_few, tag="ru")), ["placeholders"])
        good = dict(ru, one="Пропущен %1$d самокат")
        self.assertEqual(self.checks(self.run_check("common_scooters_skipped", good, tag="ru")), [])
        # Brazilian "one" covers 0 and 1; European Portuguese "one" is exactly 1.
        pt = {"one": "Um patinete ignorado", "many": "%1$d de patinetes ignorados", "other": "%1$d patinetes ignorados"}
        self.assertEqual(self.checks(self.run_check("common_scooters_skipped", pt, tag="pt-BR")), ["placeholders"])
        self.assertEqual(self.checks(self.run_check("common_scooters_skipped", pt, tag="pt-PT")), [])
        # Spanish "many" (millions) covers many numbers too.
        es = {"one": "Un patinete omitido", "many": "Muchos patinetes omitidos", "other": "%1$d patinetes omitidos"}
        self.assertEqual(self.checks(self.run_check("common_scooters_skipped", es)), ["placeholders"])
        self.assertEqual(check.single_number_categories("es"), {"one"})
        self.assertEqual(check.single_number_categories("pt-BR"), set())
        self.assertEqual(check.single_number_categories("fr"), set())
        self.assertEqual(check.single_number_categories("cs"), {"one", "many"})

    def test_added_negation(self):
        # Alarm on -> "alarm not on" is an error in a safety key, and so is the off word itself
        # ("desactivada", lexicon.STATE_WORDS); another prefixed word alone warns for the judge.
        self.assertEqual(self.checks(self.run_check("common_alarm_on", "Alarma no activada")), ["polarity"])
        self.assertEqual(self.checks(self.run_check("common_alarm_on", "Alarma desactivada")), ["polarity"])
        findings = self.run_check("common_alarm_on", "Alarma desconectada")
        self.assertEqual(self.checks(findings), [])
        self.assertIn("polarity", self.checks(findings, check.WARN))
        self.assertNotIn("polarity", self.checks(self.run_check("common_alarm_on", "Alarma activada"), check.WARN))
        # In an ordinary key an added negation warns.
        self.assertIn("polarity", self.checks(self.run_check("common_cancel", "No cancelar"), check.WARN))
        # Unlock -> "Desbloquear" is the expected prefixed word, not an added negation.
        findings = self.run_check("ble_unlock", "Desbloquear patinete", pack_name="es-ble")
        self.assertEqual(self.checks(findings) + self.checks(findings, check.WARN), [])


class LockSafetyTest(unittest.TestCase):
    """Lock and unlock stay distinct: "bloquear" inside "desbloquear" is not the glossary's term."""

    @classmethod
    def setUpClass(cls):
        cls.repo = council_fixture.FixtureRepo()
        cls.repo.write("res/values/strings_lockx.xml", (
            '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">\n'
            '    <string name="lockx_unable" volt:safety="lock">Unable to lock the scooter</string>\n'
            '    <string name="lockx_lock_now" volt:safety="lock">Lock scooter</string>\n'
            '    <string name="lockx_unverified" volt:safety="lock">Lock state unverified</string>\n'
            '    <string name="lockx_unknown">Unknown lock state</string>\n'
            '</resources>\n'))
        glossary = cls.repo.read("l10n/glossary/es.tsv")
        cls.repo.write("l10n/glossary/es.tsv", glossary + "lock\tbloquear | bloqueado | bloqueo\tLock.\tverb\tno\tlock\tfixture\n")
        measurer = fontmetrics.Measurer(cls.repo.paths.font(), use_java=False)
        cls.pack = pack.build_pack(cls.repo.paths, "es", "lockx", measurer)
        cls.font = fontmetrics.Font(cls.repo.paths.font())

    @classmethod
    def tearDownClass(cls):
        cls.repo.close()

    def errors(self, key, text):
        findings = check.check_targets(self.pack, {key: ("string", {"other": text}, [])}, self.font, None)[key]
        return sorted(set(f["check"] for f in findings if f["severity"] == check.ERROR))

    def test_unlock_is_not_lock(self):
        self.assertEqual(self.errors("lockx_lock_now", "Bloquear patinete"), [])
        self.assertEqual(self.errors("lockx_lock_now", "Desbloquear patinete"), ["glossary"])
        self.assertEqual(self.errors("lockx_lock_now", "Abrir patinete"), ["glossary"])
        self.assertEqual(self.errors("lockx_unable", "No se puede bloquear el patinete"), [])
        # "You can unlock the scooter": no lock term, and "des-" negates the lock, not the sentence.
        self.assertEqual(self.errors("lockx_unable", "Se puede desbloquear el patinete"), ["glossary", "polarity"])
        self.assertEqual(self.errors("lockx_unable", "Se puede bloquear el patinete"), ["polarity"])

    def test_prefix_alone_is_not_a_negation_in_a_safety_key(self):
        # "información" starts with in-, but it negates nothing.
        self.assertEqual(self.errors("lockx_unverified", "Estado de bloqueo confirmado. Información"), ["polarity"])
        self.assertEqual(self.errors("lockx_unverified", "Estado de bloqueo sin verificar"), [])
        self.assertEqual(self.errors("lockx_unverified", "Estado de bloqueo no verificado"), [])
        # Outside safety keys a prefixed word still counts.
        self.assertEqual(self.errors("lockx_unknown", "Estado de bloqueo desconocido"), [])


class CheckCommandTest(unittest.TestCase):
    def setUp(self):
        self.repo = council_fixture.FixtureRepo()
        p = pack.build_pack(self.repo.paths, "es", "common", fontmetrics.Measurer(self.repo.paths.font(), False))
        pack.write_pack(self.repo.paths, p)
        self.by = dict((e["key"], e) for e in p["entries"])

    def tearDown(self):
        self.repo.close()

    def test_checks_jsonl(self):
        texts = dict(council_fixture.SPANISH)
        texts["common_move_widget"] = "Mover %1$s"
        entries = [(k, self.by[k]["kind"], translib.items_of(self.by[k]["kind"], v), 1, self.by[k]["source_sha1"])
                   for k, v in texts.items()]
        translib.write_draft(self.repo.path("l10n/work/es/common/drafts/google.xml"), "google", "gemini-x", entries)
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(check.main(["--root", self.repo.root, "--tag", "es", "--area", "common", "--no-java"]), 1)
        self.assertIn("common_move_widget: placeholders", out.getvalue())
        rows = translib.read_jsonl(self.repo.path("l10n/work/es/common/checks.jsonl"))
        self.assertEqual(len(rows), len(texts))
        failed = [r for r in rows if not r["ok"]]
        self.assertEqual([r["key"] for r in failed], ["common_move_widget"])
        self.assertEqual(failed[0]["model"], "gemini-x")
        self.assertEqual(failed[0]["width_method"], fontmetrics.METHOD_APPROX)


if __name__ == "__main__":
    unittest.main()
