import contextlib
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import budget_headroom  # noqa: E402
import council_fixture  # noqa: E402
import fontmetrics  # noqa: E402
import pack  # noqa: E402
import translib  # noqa: E402

GAUGE = """<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">
    <string name="gauge_pill" volt:screen="Riding" volt:max="120dp@17sp" volt:ladder="full" volt:overflow="short">Reconnecting to %1$s</string>
    <string name="gauge_pill_short" volt:ladder="short" volt:max="120dp@17sp">Reconnecting</string>
    <string name="gauge_roomy" volt:max="300dp@14sp">Speed</string>
    <string name="gauge_tile" volt:max="64dp@12sp*2">Experimental controls</string>
    <string name="gauge_word" volt:max="80dp@14sp">Regen</string>
    <string name="gauge_label" volt:max="80dp@14sp">Trip energy</string>
    <plurals name="gauge_count" volt:max="96dp@14sp">
        <item quantity="one">%1$d reminder</item>
        <item quantity="other">%1$d reminders</item>
    </plurals>
    <string name="gauge_free">No budget at all</string>
</resources>
"""

SHIPPED = """<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">
    <!-- src:{gauge_pill} -->
    <string name="gauge_pill">Reconectando con %1$s</string>
    <!-- src:{gauge_word} -->
    <string name="gauge_word">Recup.</string>
    <!-- src:{old} -->
    <string name="gauge_label">Energía del viaje</string>
</resources>
"""
OLD = "0" * 40  # the source hash of English that has since changed


SHA = {}  # today's source hash per fixture key, filled in setUp


def decision(key, outcome="agreed", text="x", checks=(), judge=None, alternatives=(), sha=None):
    return {"format": "volt-l10n-decision-v1", "key": key, "kind": "string", "outcome": outcome, "text": text,
            "reason": "", "checks": list(checks), "judge": judge, "alternatives": list(alternatives),
            "english": "", "safety": [], "source_sha1": sha or SHA[key], "stale_drafts": [], "models": {},
            "backtranslation": None}


def width(message, severity="warn"):
    return {"check": "width", "severity": severity, "message": message}


class HeadroomTest(unittest.TestCase):
    def setUp(self):
        self.repo = council_fixture.FixtureRepo()
        self.repo.write("res/values/strings_gauge.xml", GAUGE)
        sources, _ = translib.load_source(self.repo.paths)
        SHA.clear()
        SHA.update((s.key, translib.source_sha1(s.kind, s.items)) for s in sources["gauge"])
        self.repo.write("res/values-es/strings_gauge.xml", SHIPPED.format(old=OLD, **SHA))
        short = ("219.0dp at 17sp exceeds the 120dp budget (English 214.4dp; java.awt); the screen shows the short "
                 "rung «Reconectando» where the full does not fit, and it fits (110.0dp)")
        rows = [
            decision("gauge_pill", checks=[width(short)]),
            # A fallback whose every width finding (its own and its drafts') was made against older budgets.
            decision("gauge_tile", outcome="fallback-english", text=None,
                     checks=[width("86.0dp at 12sp exceeds the 64dp budget (English 115.5dp; java.awt)")],
                     alternatives=[{"findings": [width("60.0dp at 12sp exceeds the 48dp budget", "error")]}]),
            # Evidence for English that has since changed (its decision and ruling hold another hash).
            decision("gauge_label", outcome="judged", text="Energía", sha=OLD,
                     checks=[width("95.0dp at 14sp exceeds the 80dp budget (English 70.0dp; java.awt)")],
                     judge={"verdict": "merge", "reason": "«Energía del viaje» is 95 dp against the 80 dp budget"}),
            decision("gauge_word", outcome="judged", text="Recup.",
                     judge={"verdict": "merge", "reason": "«Recuperación» is 83.7 dp against the 80 dp budget"}),
            decision("gauge_roomy", outcome="judged", judge={"verdict": "merge", "reason": "Glossary term"}),
            decision("gauge_count", outcome="judged", judge={"verdict": "pick", "reason": "fine"},
                     alternatives=[{"findings": [width("130dp at 14sp exceeds the 96dp budget", "error")]}]),
        ]
        self.repo.write("l10n/decisions/es/gauge.jsonl", "".join(json.dumps(r) + "\n" for r in rows))
        self.repo.write("l10n/work/es/gauge/judge.jsonl", "".join(json.dumps(r) + "\n" for r in [
            {"key": "gauge_word", "verdict": "fallback", "reason": "Recuperación is 83.7 dp against the 80 dp budget",
             "source_sha1": SHA["gauge_word"]},
            {"key": "gauge_label", "verdict": "fallback", "reason": "Too wide: 95 dp against the 80 dp budget",
             "source_sha1": OLD},
            {"key": "gauge_roomy", "verdict": "fallback", "reason": "Meaning changed", "source_sha1": SHA["gauge_roomy"]},
            {"key": "gauge_tile", "verdict": "fallback", "reason": "Overflows the 48dp tile", "source_sha1": SHA["gauge_tile"]},
        ]))
        self.paths = self.repo.paths

    def tearDown(self):
        self.repo.close()

    def report(self, growth=1.3, tags=("es",)):
        return budget_headroom.analyze(self.paths, growth, list(tags), use_java=False)

    def rows(self, report):
        return dict((r["key"], r) for r in report["keys"])

    def test_measures_as_pack_does(self):
        report = self.report()
        rows = self.rows(report)
        self.assertNotIn("gauge_free", rows)
        self.assertEqual(report["width_method"], fontmetrics.METHOD_APPROX)
        measurer = fontmetrics.Measurer(self.paths.font(), use_java=False)
        built = pack.build_pack(self.paths, "es", "gauge", measurer=measurer)
        for entry in built["entries"]:
            if entry["budget_dp"]:
                self.assertAlmostEqual(rows[entry["key"]]["english_width_dp"], entry["english_width_dp"], places=2)
        count = rows["gauge_count"]
        self.assertEqual(count["english_width_dp"], round(max(fontmetrics.measure_lines(measurer, [
            (14.0, pack.sample_text(t), 1) for t in ("%1$d reminder", "%1$d reminders")])), 2))
        self.assertEqual(rows["gauge_tile"]["budget_lines"], 2)

    def test_tight_sorted_by_allowed_growth(self):
        report = self.report()
        allowed = [r["allowed_growth"] for r in report["keys"]]
        self.assertEqual(allowed, sorted(allowed))
        rows = self.rows(report)
        self.assertFalse(rows["gauge_roomy"]["tight"])
        self.assertTrue(rows["gauge_pill"]["tight"])
        for row in report["keys"]:
            self.assertEqual(row["tight"], row["english_width_dp"] * 1.3 > row["budget_dp"] + 0.01, row["key"])
        loose = self.rows(self.report(growth=0.1))
        self.assertFalse(any(r["tight"] for r in loose.values()))
        with self.assertRaises(ValueError):
            self.report(growth=0)

    def test_ladder_rungs_and_short_at_full_budget(self):
        rows = self.rows(self.report())
        ladder = rows["gauge_pill"]["ladder"]
        self.assertEqual((ladder["group"], ladder["rung"]), ("gauge_pill", "full"))
        self.assertEqual([r["key"] for r in ladder["rungs"]], ["gauge_pill_short"])
        short = rows["gauge_pill_short"]
        self.assertEqual(ladder["rungs"][0]["fits"], not short["tight"])
        self.assertEqual(ladder["short_at_this_budget"]["key"], "gauge_pill_short")
        self.assertEqual(rows["gauge_pill_short"]["ladder"]["rungs"][0]["key"], "gauge_pill")
        self.assertIsNone(rows["gauge_word"]["ladder"])

    def test_evidence_kinds(self):
        rows = self.rows(self.report())

        def kinds(key):
            return [(e["tag"], e["kind"], e["older_budget"]) for e in rows[key]["evidence"]]

        self.assertEqual(kinds("gauge_pill"), [(None, "overflow", None), ("es", "short-rung", None)])
        # Findings made against budgets the key no longer has are labelled, the fallback built on them
        # too, and none of them proves anything about the current budget.
        self.assertEqual(kinds("gauge_tile"), [("es", "over-budget", "64dp@12sp"),
                                               ("es", "fallback-width", "64dp@12sp, 48dp@12sp"),
                                               ("es", "fallback-width", "48dp")])
        self.assertFalse(rows["gauge_tile"]["proven"])
        self.assertFalse(any(e["stale_source"] for e in rows["gauge_tile"]["evidence"]))
        # Evidence for English that has since changed is kept, marked stale, and proves nothing either.
        label = rows["gauge_label"]["evidence"]
        self.assertEqual([(e["kind"], e["older_budget"], e["stale_source"]) for e in label],
                         [("over-budget", None, True), ("merge-width", None, True), ("fallback-width", None, True)])
        self.assertFalse(rows["gauge_label"]["proven"])
        self.assertTrue(rows["gauge_label"]["observed"]["es"]["stale"])
        self.assertFalse(rows["gauge_word"]["observed"]["es"]["stale"])
        self.assertFalse(any(e["stale_source"] for e in rows["gauge_word"]["evidence"]))
        self.assertEqual(kinds("gauge_word"), [("es", "merge-width", None), ("es", "fallback-width", None)])
        self.assertIn("translated now: judged", rows["gauge_word"]["evidence"][1]["detail"])
        self.assertTrue(rows["gauge_word"]["proven"])
        # A merge for another reason, and a fallback for another reason, are no width evidence.
        self.assertEqual(kinds("gauge_roomy"), [])
        # Missing from res/values-es while a draft failed the width check: English for width.
        self.assertEqual(kinds("gauge_count"), [("es", "fallback-width", None)])
        self.assertEqual(rows["gauge_pill_short"]["rung_evidence"][0]["key"], "gauge_pill")
        self.assertEqual(rows["gauge_pill_short"]["evidence"], [])
        self.assertFalse(rows["gauge_pill_short"]["proven"])

    def test_observed_shipped_widths(self):
        rows = self.rows(self.report())
        observed = rows["gauge_word"]["observed"]["es"]
        measurer = fontmetrics.Measurer(self.paths.font(), use_java=False)
        self.assertAlmostEqual(observed["width_dp"], measurer.measure([(14.0, "Recup.")])[0], places=2)
        self.assertNotIn("gauge_roomy", [k for k, r in rows.items() if r["observed"]])
        self.assertEqual(self.rows(self.report(tags=()))["gauge_word"]["observed"], {})

    def test_default_tags_are_the_decision_folders(self):
        self.assertEqual(self.report(tags=())["tags"], [])
        self.assertEqual(budget_headroom.analyze(self.paths, use_java=False)["tags"], ["es"])

    def test_cli_table_and_json(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(budget_headroom.main(["--root", self.repo.root, "--no-java", "--json"]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data["format"], budget_headroom.FORMAT)
        self.assertTrue(all(r["tight"] for r in data["keys"]))
        self.assertTrue(all(not r["tight"] and r["proven"] for r in data["proven_within_growth"]))
        self.assertIn("gauge_word", [r["key"] for r in data["keys"] + data["proven_within_growth"]])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(budget_headroom.main(["--root", self.repo.root, "--no-java", "--all", "--growth", "1.5"]),
                             0)
        text = out.getvalue()
        self.assertIn("growth x1.5", text)
        self.assertIn("gauge_roomy", text)
        self.assertIn("short-rung:es", text)
        self.assertIn("(stale English)", text)
        self.assertIn("one: %1$d reminder | other: %1$d reminders", text)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(budget_headroom.main(["--root", self.repo.root, "--no-java", "--growth", "-1"]), 2)

    def test_parsers(self):
        self.assertEqual(budget_headroom.checked_budget("70.1dp at 12sp exceeds the 64dp budget in 2 lines of whole "
                                                        "words (English 66.1dp; java.awt)"), (64.0, 12.0, 2))
        self.assertEqual(budget_headroom.checked_budget("219.0dp at 17sp exceeds the 210dp budget (English"),
                         (210.0, 17.0, 1))
        self.assertIsNone(budget_headroom.checked_budget("es uses the system font"))
        for reason, dp in (("«%1$d pendentes» is 100.8 dp against the 96 dp budget", 96.0),
                           ("overflows the 48dp badge (57.6dp at 10.5sp)", 48.0),
                           ("the full name Luzes de estrela (94.0dp, over 90dp)", 90.0),
                           ("the old phrase is 238.9 dp against 220 dp", 220.0),
                           ("overflows by 2dp", None), ("Faithful.", None)):
            self.assertEqual(budget_headroom.ruling_budget_dp(reason), dp, reason)
        self.assertTrue(budget_headroom.width_reason("does not fit the tile"))
        self.assertTrue(budget_headroom.width_reason("83.7 dp"))
        self.assertFalse(budget_headroom.width_reason("Faithful; glossary term kept."))
        self.assertFalse(budget_headroom.width_reason(None))

    def test_real_catalog_loads(self):
        # The committed catalog parses and every budgeted key gets a row (widths are not asserted).
        paths = translib.Paths()
        sources, _ = translib.load_source(paths)
        budgeted = [s.key for area in sources for s in sources[area] if s.translatable and translib.parse_max(
            s.meta.get("max"))]
        report = budget_headroom.analyze(paths, tags=[], use_java=False)
        self.assertEqual(sorted(r["key"] for r in report["keys"]), sorted(budgeted))


if __name__ == "__main__":
    unittest.main()
