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

    def test_numbers_with_english_thousands_groups(self):
        english = "Use a backup password of 8 to 1,024 characters."
        same = (collections.Counter(), collections.Counter())
        # One number, 1024: Spanish style writes it with no separator, and any grouping of it is the same value.
        for target in ("De 8 a 1024 caracteres.", "De 8 a 1.024 caracteres.", "De 8 a 1\u00a0024 caracteres.",
                       "De 8 a 1\u202f024 caracteres.", "De 8 a 1 024 caracteres.", "De 8 a 1,024 caracteres.",
                       "De 8 a \u0661\u0660\u0662\u0664 caracteres."):
            self.assertEqual(check.compare_numbers(english, target), same, target)
        # A changed or missing number is still missing.
        for target in ("De 8 a 1025 caracteres.", "De 8 caracteres.", "De 1024 caracteres.",
                       "De 8 a 1.024.000 caracteres.", "De 8 a 1 0245 caracteres.", "De 8 a 10 24 caracteres."):
            lost, _ = check.compare_numbers(english, target)
            self.assertTrue(lost, target)
        self.assertEqual(check.compare_numbers(english, "De 8 a 1025 caracteres."),
                         (collections.Counter(["1024"]), collections.Counter(["1025"])))
        # A decimal comma still matches an English decimal point, with or without groups.
        self.assertEqual(check.compare_numbers("1.5 km and 12,345.6 m", "1,5 km y 12.345,6 m"), same)
        self.assertEqual(check.compare_numbers("12,345.6 m", "12345,6 m"), same)
        # Digits the English did not group are compared run by run, as before.
        self.assertEqual(check.compare_numbers("Pair 2 of 3", "Par 2 de 3"), same)
        self.assertTrue(check.compare_numbers("1024 bytes", "1 025 bytes")[0])

    def test_grouped_numbers_through_the_check(self):
        """A full check: placeholders stay out of the numbers, and 1,024 may be written 1024."""
        p = copy.deepcopy(self.packs["es"])
        entry = [e for e in p["entries"] if e["key"] == "common_move_widget"][0]
        entry["english"] = "Move %1$s to slot %2$d of 1,024"
        for text, errors in (("Mover %1$s al hueco %2$d de 1024", []), ("Mover %1$s al hueco %2$d de 1.024", []),
                             ("Mover %1$s al hueco %2$d de 1\u202f024", []),
                             ("Mover %1$s al hueco %2$d de 1025", ["numbers"]),
                             ("Mover %1$s al hueco %2$d", ["numbers"])):
            targets = collections.OrderedDict([("common_move_widget", ("string", {"other": text}, []))])
            findings = check.check_targets(p, targets, self.font, self.measurer)["common_move_widget"]
            self.assertEqual([c for c in self.checks(findings) if c == "numbers"], errors, text)

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

    def build_scooters_skipped(self, one, other, tags=("pt-BR", "pt-PT", "es")):
        """Packs of a fixture whose English common_scooters_skipped has these one and other forms."""
        repo = council_fixture.FixtureRepo()
        try:
            path = "res/values/strings_common.xml"
            text = repo.read(path)
            for old, new in (('<item quantity="one">%1$d scooter skipped</item>', '<item quantity="one">%s</item>' % one),
                             ('<item quantity="other">%1$d scooters skipped</item>',
                              '<item quantity="other">%s</item>' % other)):
                self.assertIn(old, text)
                text = text.replace(old, new)
            repo.write(path, text)
            packs = {tag: pack.build_pack(repo.paths, tag, "common", self.measurer) for tag in tags}
        finally:
            repo.close()

        def run(tag, value):
            targets = {"common_scooters_skipped": ("plural", translib.items_of("plural", value), [])}
            return check.check_targets(packs[tag], targets, self.font, self.measurer)["common_scooters_skipped"]
        return run

    def test_a_count_the_english_spells_as_1_is_kept_by_the_placeholder(self):
        # English "1 scooter skipped" for one. Brazilian "one" also covers 0, so it must keep %1$d, and that
        # placeholder stands for the English 1 (dashboard_slot_summary: no draft could pass both rules).
        # Where "one" is exactly 1 (pt-PT, es), the 1 must still appear or the placeholder must be kept.
        run = self.build_scooters_skipped("1 scooter skipped", "%1$d scooters skipped")
        kept = {"one": "%1$d patinete ignorado", "many": "%1$d de patinetes ignorados", "other": "%1$d patinetes ignorados"}
        self.assertEqual(self.checks(run("pt-BR", kept)), [])
        self.assertEqual(self.checks(run("pt-BR", kept), check.WARN), [])
        # Writing the 1 out where "one" also covers 0 still fails, now on the placeholder alone.
        self.assertEqual(self.checks(run("pt-BR", dict(kept, one="1 patinete ignorado"))), ["placeholders"])
        # A form with neither the 1 nor the count still loses the English number.
        self.assertEqual(self.checks(run("pt-BR", dict(kept, one="Um patinete ignorado"))), ["numbers", "placeholders"])
        # Where "one" is exactly 1, writing the 1 out passes, and so does keeping the placeholder.
        self.assertEqual(self.checks(run("pt-PT", dict(kept, one="1 patinete ignorado"))), [])
        self.assertEqual(self.checks(run("es", {"one": "1 patinete omitido", "many": "%1$d de patinetes omitidos",
                                                "other": "%1$d patinetes omitidos"})), [])
        # The other forms gain nothing from the rule: a number the English lacks still warns.
        self.assertEqual(self.checks(run("pt-BR", dict(kept, other="%1$d patinetes ignorados (1)")), check.WARN),
                         ["numbers"])

    def test_a_fixed_1_both_english_forms_share_is_not_the_count(self):
        # "A scooter skipped after 1 minute" / "%1$d scooters skipped after 1 minute": the 1 is a duration, not
        # the count (Codex's counterexample on #293), so keeping %1$d doesn't excuse dropping it.
        run = self.build_scooters_skipped("A scooter skipped after 1 minute", "%1$d scooters skipped after 1 minute",
                                          tags=("pt-BR",))
        good = {"one": "%1$d patinete ignorado após 1 minuto", "many": "%1$d de patinetes ignorados após 1 minuto",
                "other": "%1$d patinetes ignorados após 1 minuto"}
        self.assertEqual(self.checks(run("pt-BR", good)), [])
        self.assertEqual(self.checks(run("pt-BR", dict(good, one="%1$d patinetes ignorados"))), ["numbers"])
        # A spelled count and a fixed 1 together: the placeholder stands for one 1 only.
        run = self.build_scooters_skipped("1 scooter skipped after 1 minute", "%1$d scooters skipped after 1 minute",
                                          tags=("pt-BR",))
        self.assertEqual(self.checks(run("pt-BR", good)), [])
        self.assertEqual(self.checks(run("pt-BR", dict(good, one="%1$d patinete ignorado"))), ["numbers"])

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


class FakeMeasurer(object):
    """Ten units per character, spaces included: widths a test can work out by hand."""
    method = "fake"

    def measure(self, requests):
        return [max(len(line) for line in text.split("\n")) * 10.0 for _size, text in requests]


class LineBudgetTest(unittest.TestCase):
    """volt:max with *N lines: a greedy whole-word wrap into at most N lines of the budget each."""

    def test_parse_and_pattern(self):
        import gen_catalog
        self.assertEqual(translib.parse_max("96dp@14sp"), (96.0, 14.0, 1))
        self.assertEqual(translib.parse_max("64dp@12sp*2"), (64.0, 12.0, 2))
        for good in ("96dp@14sp", "64dp@12sp*2", "220.5dp@16sp*3"):
            self.assertTrue(gen_catalog.MAX_PATTERN.match(good), good)
        for bad in ("64dp@12sp*1", "64dp@12sp*", "64dp@12sp*10", "64dp*2@12sp", "64dp@12sp x2"):
            self.assertFalse(gen_catalog.MAX_PATTERN.match(bad), bad)
            self.assertIsNone(translib.parse_max(bad), bad)

    def test_wrapped_width(self):
        m = FakeMeasurer()
        # "Luzes de linha": one line is 140; two lines are "Luzes de" (80) / "linha" (50).
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "Luzes de linha", 1)]), [140.0])
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "Luzes de linha", 2)]), [80.0])
        # Every single word must fit: the narrowest width is the widest word when that is the binding limit.
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "Controlos experimentais", 2)]), [130.0])
        # Three words in two lines: the best greedy split, never a broken word.
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "aa bb cc", 2)]), [50.0])
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "aa bb cc", 3)]), [20.0])
        # A no-break space joins its words; a hard break counts as a line.
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "aa\u00a0bb cc", 2)]), [50.0])
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "aa bb\ncc", 2)]), [50.0])
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "aa\nbb\ncc", 2)]), [fontmetrics.NO_FIT])
        # Mixed batches keep their order.
        self.assertEqual(fontmetrics.measure_lines(m, [(12, "abc", 1), (12, "ab cd", 2), (12, "x", 1)]),
                         [30.0, 20.0, 10.0])

    def check_entry(self, budget_dp, lines, english, text):
        p = copy.deepcopy(self.pt_pack)
        entry = [e for e in p["entries"] if e["key"] == "common_cancel"][0]
        entry.update({"budget_dp": budget_dp, "budget_sp": 12.0, "budget_lines": lines,
                      "english_width_dp": english})
        targets = {"common_cancel": ("string", {"other": text}, [])}
        results = check.check_targets(p, targets, fontmetrics.Font(self.repo.paths.font()), FakeMeasurer())
        return [f for f in results["common_cancel"] if f["check"] == "width"]

    @classmethod
    def setUpClass(cls):
        cls.repo = council_fixture.FixtureRepo()
        measurer = fontmetrics.Measurer(cls.repo.paths.font(), use_java=False)
        cls.pt_pack = pack.build_pack(cls.repo.paths, "pt-PT", "common", measurer)

    @classmethod
    def tearDownClass(cls):
        cls.repo.close()

    def test_two_lines_of_whole_words(self):
        # Over 64 on one line, but "Luzes de" / "linha" fits two lines of 80: passes at 80*2, fails at 80*1.
        self.assertEqual(self.check_entry(80, 2, 60, "Luzes de linha"), [])
        one = self.check_entry(80, 1, 60, "Luzes de linha")
        self.assertEqual([f["severity"] for f in one], [check.ERROR])
        # A word wider than a line fails even in two lines.
        long_word = self.check_entry(80, 2, 60, "Controlos experimentais")
        self.assertEqual([f["severity"] for f in long_word], [check.ERROR])
        self.assertIn("in 2 lines of whole words", long_word[0]["message"])
        self.assertIn("130.0dp", long_word[0]["message"])
        # Three lines' worth in a two-line budget fails.
        self.assertEqual([f["severity"] for f in self.check_entry(50, 2, 40, "aaaa bbbb cccc")], [check.ERROR])

    def test_overflowing_english_only_warns(self):
        # The English needs 90 in two lines (its own widest word): a translation over the budget warns.
        warn = self.check_entry(80, 2, 90, "Controlos experimentais")
        self.assertEqual([f["severity"] for f in warn], [check.WARN])

    def test_pack_carries_lines_and_two_line_english_width(self):
        self.repo.write("res/values/strings_lines.xml", (
            '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">'
            '<string name="lines_one" volt:max="64dp@12sp">Line lights</string>'
            '<string name="lines_two" volt:max="64dp@12sp*2">Line lights</string>'
            '<string name="lines_none">Line lights</string></resources>'))
        p = pack.build_pack(self.repo.paths, "pt-PT", "lines", FakeMeasurer())
        by = dict((e["key"], e) for e in p["entries"])
        self.assertEqual((by["lines_one"]["budget_lines"], by["lines_one"]["english_width_dp"]), (1, 110.0))
        self.assertEqual((by["lines_two"]["budget_lines"], by["lines_two"]["english_width_dp"]), (2, 60.0))
        self.assertEqual((by["lines_none"]["budget_lines"], by["lines_none"]["budget_dp"]), (None, None))
        self.assertIn("budget_lines", council.MODEL_FIELDS)
        prompt = council.instructions(p)
        prompt = prompt if isinstance(prompt, str) else "\n".join(prompt)
        self.assertIn("budget_lines", prompt)


class OverflowShortTest(unittest.TestCase):
    """volt:overflow="short": a full rung over its budget passes, with a warning, when the short rung the
    screen shows instead fits the full's budget (Quick access, QuickAccessGrid.measureContent)."""

    @classmethod
    def setUpClass(cls):
        cls.repo = council_fixture.FixtureRepo()
        # FakeMeasurer: ten units a character. Every English word fits 64 (at most six letters).
        cls.repo.write("res/values/strings_qa.xml", (
            '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">'
            '<string name="qa_pin" volt:ladder="full" volt:max="64dp@12sp*2" volt:overflow="short">PIN prox</string>'
            '<string name="qa_pin_short" volt:ladder="short" volt:max="90dp@12.5sp">PIN prox</string>'
            '<string name="qa_lights" volt:ladder="full" volt:max="64dp@12sp*2">Lights</string>'
            '<string name="qa_lights_short" volt:ladder="short" volt:max="90dp@12.5sp">Lights</string>'
            '</resources>'))
        cls.pack = pack.build_pack(cls.repo.paths, "pt-PT", "qa", FakeMeasurer())
        cls.font = fontmetrics.Font(cls.repo.paths.font())

    @classmethod
    def tearDownClass(cls):
        cls.repo.close()

    def widths(self, key, full, short=None, **kw):
        targets = collections.OrderedDict([(key, ("string", {"other": full}, []))])
        if short is not None:
            targets[key + "_short"] = ("string", {"other": short}, [])
        results = check.check_targets(self.pack, targets, self.font, FakeMeasurer(), **kw)
        return [f for f in results[key] if f["check"] == "width"], results

    def test_pack_carries_the_mark(self):
        by = dict((e["key"], e) for e in self.pack["entries"])
        self.assertEqual(by["qa_pin"]["overflow"], "short")
        self.assertIsNone(by["qa_pin_short"]["overflow"])
        self.assertIsNone(by["qa_lights"]["overflow"])
        self.assertEqual(check.overflow_rung(by, "qa_pin"), "qa_pin_short")
        self.assertIsNone(check.overflow_rung(by, "qa_lights"))
        self.assertIsNone(check.overflow_rung(by, "qa_pin_short"))

    def test_marked_full_over_budget_with_a_fitting_short_warns(self):
        # "proximidade" is 110 wide; "PIN" / "prox." fits two lines of 64 (and 90 on one line).
        found, results = self.widths("qa_pin", "PIN e proximidade", "PIN prox.")
        self.assertEqual([f["severity"] for f in found], [check.WARN])
        self.assertIn("110.0dp", found[0]["message"])
        self.assertIn("the screen shows the short rung \u00abPIN prox.\u00bb where the full does not fit",
                      found[0]["message"])
        self.assertTrue(check.is_ok(results["qa_pin"]))
        self.assertEqual([f for f in results["qa_pin_short"] if f["check"] == "width"], [])

    def test_marked_full_over_budget_with_a_short_that_does_not_fit_fails(self):
        # The short fits its own 90dp line, but "proximid." (90) does not fit the full's 64dp lines.
        found, results = self.widths("qa_pin", "PIN e proximidade", "PIN proximid.")
        self.assertEqual([f["severity"] for f in found], [check.ERROR])
        self.assertIn("the short rung \u00abPIN proximid.\u00bb, shown where the full does not fit, does not fit "
                      "either (90.0dp)", found[0]["message"])
        self.assertFalse(check.is_ok(results["qa_pin"]))

    def test_marked_full_without_its_short_fails_unless_checked_later(self):
        found, _ = self.widths("qa_pin", "PIN e proximidade")
        self.assertEqual([f["severity"] for f in found], [check.ERROR])
        self.assertIn("no short rung was drafted with it", found[0]["message"])
        later, _ = self.widths("qa_pin", "PIN e proximidade", short_checked_later=True)
        self.assertEqual([f["severity"] for f in later], [check.WARN])
        self.assertIn("council.decide", later[0]["message"])

    def test_marked_full_that_fits_has_no_finding(self):
        self.assertEqual(self.widths("qa_pin", "PIN e prox", "PIN")[0], [])

    def test_unmarked_full_over_budget_fails_as_before(self):
        found, _ = self.widths("qa_lights", "Luzinhas grandes", "Luzes")
        self.assertEqual([f["severity"] for f in found], [check.ERROR])
        self.assertEqual(found[0]["message"], "80.0dp at 12sp exceeds the 64dp budget in 2 lines of whole words "
                                              "(English 60.0dp; fake)")
        # short_checked_later changes nothing for an unmarked key.
        later, _ = self.widths("qa_lights", "Luzinhas grandes", short_checked_later=True)
        self.assertEqual(later, found)

    def test_decided_pair(self):
        def row(key, outcome, text):
            return collections.OrderedDict([("key", key), ("outcome", outcome), ("text", text), ("reason", "r")])

        def decide(full, short, short_outcome="agreed"):
            rows = collections.OrderedDict([("qa_pin", row("qa_pin", "judged", full)),
                                            ("qa_pin_short", row("qa_pin_short", short_outcome, short))])
            return council.overflow_pair_fallbacks(self.pack, rows, self.font, FakeMeasurer())

        kept = decide("PIN e proximidade", "PIN prox.")
        self.assertEqual((kept["qa_pin"]["outcome"], kept["qa_pin"]["text"]), ("judged", "PIN e proximidade"))
        dropped = decide("PIN e proximidade", "PIN proximid.")
        self.assertEqual((dropped["qa_pin"]["outcome"], dropped["qa_pin"]["text"]), ("fallback-english", None))
        self.assertIn("with the decided short rung", dropped["qa_pin"]["reason"])
        self.assertIn("was: r", dropped["qa_pin"]["reason"])
        self.assertEqual(dropped["qa_pin_short"]["outcome"], "agreed")  # decide() then falls the group back
        # A short rung that fell back is the ladder rule's to handle; a full that fits is untouched.
        self.assertEqual(decide("PIN e proximidade", None, "fallback-english")["qa_pin"]["outcome"], "judged")
        self.assertEqual(decide("PIN e prox", "PIN proximid.")["qa_pin"]["outcome"], "judged")


if __name__ == "__main__":
    unittest.main()
