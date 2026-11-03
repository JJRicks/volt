import collections
import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
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
        for tag in ("es", "de", "ru", "pt-BR"):
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

    def test_script_leakage(self):
        self.assertEqual(self.checks(self.run_check("common_cancel", "Cancelar отмена")), ["glyphs", "script"])
        ru = self.run_check("common_disclaimer", "Volt не связан с NAVEE", tag="ru")
        self.assertNotIn("script", self.checks(ru) + self.checks(ru, check.WARN))
        ru = self.run_check("common_cancel", "Stop отмена", tag="ru")
        self.assertIn("script", self.checks(ru, check.WARN))


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
        entries = [(k, self.by[k]["kind"], translib.items_of(self.by[k]["kind"], v), 1) for k, v in texts.items()]
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
