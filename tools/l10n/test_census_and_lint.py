"""G7 (census_diff.py) and G8 (hardcoded_lint.py)."""

import contextlib
import io
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import census_diff  # noqa: E402
import hardcoded_lint  # noqa: E402
import l10nlib  # noqa: E402
from repo_fixture import TempRepo, java_class  # noqa: E402

NNBSP = chr(0x202F)


class MaskTest(unittest.TestCase):
    def test_masks_clock_duration_and_date_values_only(self):
        cases = {
            "Updated 7:45" + NNBSP + "PM": "Updated <n>:<n>" + NNBSP + "<a:upper>",
            "Done by 19:05": "Done by <n>:<n>",
            "Ride 0:12:34": "Ride <n>:<n>:<n>",
            "Mar 5": "<MMM> <n>",
            "Mon, Mar 5": "<EEE>, <MMM> <n>",
            "Mar 5, 2026 · 12 km": "<MMM> <n>, <n> · 12 km",
            "October 5": "<MMMM> <n>",
            "05 MAR": "<n> <MMM:upper>",
            "2026-03-05T12:00:01.123Z": "<n>-<n>-<n>T<n>:<n>:<n>Z",
            "1h 05m left": "<n>h <n>m left",
            "3 minutes ago": "<n> minute(s) ago",
            "1 y 2 mo 3 d 4 h 5 s": "<n> y <n> mo(s) <n> d <n> h <n> s",
            "Battery 80%": "Battery 80%",
            "18.5 km/h": "18.5 km/h",
            "12 mph": "12 mph",
            "3 scooters": "3 scooters",
            "Lock scooter": "Lock scooter",
            "Deceleration 3": "Deceleration 3",
            "Mark 2": "Mark 2",
        }
        for value, masked in cases.items():
            self.assertEqual(census_diff.mask(value), masked, value)

    def test_values_may_change(self):
        for a, b in (("Updated 9:59 AM", "Updated 10:01 PM"), ("Oct 31", "Nov 1"), ("12 min left", "13 min left"),
                     ("1 minute ago", "2 minutes ago"), ("Mon, Mar 5", "Tue, Mar 6"), ("2026-03-05", "2026-11-02")):
            self.assertTrue(census_diff.same_after_masking(a, b), (a, b))

    def test_wording_and_form_changes_still_count(self):
        """Findings 5 and 18: unit words, plural forms, month forms and words that start like months."""
        for a, b in (("1 seconds left", "1 second left"), ("1 minutes ago", "1 minute ago"),
                     ("Charged in 2 hrs", "Charged in 2 h"), ("5 secs", "5 s"), ("12 min left", "12 minutes left"),
                     ("Oct 5", "October 5"), ("Mar 5", "05 MAR"), ("Mark 2", "Marker 2"), ("Deceleration 3", "Decel 3"),
                     ("7:45 PM", "7:45 pm"), ("Mar 5", "Mar 5, 2026")):
            self.assertFalse(census_diff.same_after_masking(a, b), (a, b))

    def test_plural_padding_and_date_order_changes_count(self):
        """Re-review finding 8: the forms may not change just because the number did."""
        for a, b in (("2 seconds left", "3 second left"), ("5 hours", "7 hour"), ("05 min", "5 min"),
                     ("Ride 0:05", "Ride 00:07"), ("13/5/2026", "5/14/2026"), ("10/5/2026", "10/5/26")):
            self.assertFalse(census_diff.same_after_masking(a, b), (a, b))
        for a, b in (("1 minute ago", "2 minutes ago"), ("2 minutes ago", "1 minute ago"), ("05 min", "15 min"),
                     ("9:59", "10:01"), ("13/5/2026", "14/6/2026"), ("5/13/2026", "6/14/2026"), ("1h 05m", "2h 15m")):
            self.assertTrue(census_diff.same_after_masking(a, b), (a, b))


class CensusTest(unittest.TestCase):
    BASE = [("text", "tag=lock_label", "10,20,300,48", "Lock scooter"),
            ("desc", "tag=lock_switch", "10,80,300,96", "Locked\nTap to unlock"),
            ("text", "DecorView/TextView[3]", "0,0,100,20", "Updated 9:59 AM"),
            ("notif-title", "notification:scooter/1", "-", "Scooter connected")]

    def records(self, rows):
        return census_diff.parse(census_diff.write(rows))

    def test_format_round_trip(self):
        records = self.records(self.BASE + [("text", "tab\there", "-", "back" + chr(92) + "slash\r")])
        self.assertEqual(records[1].value, "Locked\nTap to unlock")
        self.assertEqual(records[4].locator, "tab\there")
        self.assertEqual(records[4].value, "back" + chr(92) + "slash\r")

    def test_parse_errors(self):
        for text, message in (("nope\n", "first line"), (census_diff.MAGIC + "\ntext\tx\t-\n", "4 tab-separated"),
                              (census_diff.MAGIC + "\nlabel\tx\t-\ty\n", "unknown field"),
                              (census_diff.MAGIC + "\ntext\tx\t1,2\ty\n", "bounds")):
            with self.assertRaises(census_diff.CensusError) as caught:
                census_diff.parse(text)
            self.assertIn(message, str(caught.exception))

    def test_clock_and_bounds_changes_do_not_fail(self):
        head = list(self.BASE)
        head[2] = ("text", "DecorView/TextView[3]", "0,0,104,20", "Updated 10:03 PM")
        head[0] = ("text", "tag=lock_label", "10,20,310,48", "Lock scooter")
        text, bounds = census_diff.compare(self.records(self.BASE), self.records(head))
        self.assertEqual(text, [])
        self.assertEqual(len(bounds), 2)

    def test_a_preserved_plural_bug_cannot_be_fixed_unseen(self):
        base = [("text", "tag=eta", "0,0,10,10", "1 seconds left"), ("text", "tag=decel", "0,20,10,10", "Deceleration 3")]
        head = [("text", "tag=eta", "0,0,10,10", "1 second left"), ("text", "tag=decel", "0,20,10,10", "Decel 3")]
        text, _ = census_diff.compare(self.records(base), self.records(head))
        self.assertEqual(len(text), 4, text)

    def test_text_changes_fail(self):
        head = list(self.BASE)
        head[1] = ("desc", "tag=lock_switch", "10,80,300,96", "Locked\nTap to unlock.")
        del head[3]
        text, _ = census_diff.compare(self.records(self.BASE), self.records(head))
        self.assertEqual(len(text), 3)
        self.assertTrue(text[0].startswith("- desc tag=lock_switch"))
        self.assertTrue(any(t.startswith("- notif-title") for t in text))

    def test_folders(self):
        work = tempfile.mkdtemp(prefix="l10n-census-")
        try:
            for side, rows in (("base", self.BASE), ("head", self.BASE[:3])):
                l10nlib.write_text(os.path.join(work, side, "ut5-home.census.tsv"), census_diff.write(rows))
            l10nlib.write_text(os.path.join(work, "base", "ut5-more.census.tsv"), census_diff.write(self.BASE))
            problems, _, screens = census_diff.diff_paths(os.path.join(work, "base"), os.path.join(work, "head"))
            self.assertEqual(screens, 2)
            self.assertTrue(any("ut5-more.census.tsv: only in base" in p for p in problems))
            self.assertTrue(any("notif-title" in p for p in problems))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(census_diff.main([os.path.join(work, "base"), os.path.join(work, "head")]), 1)
                self.assertEqual(census_diff.main([os.path.join(work, "base"), os.path.join(work, "base")]), 0)
        finally:
            shutil.rmtree(work, ignore_errors=True)


FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "census")


def fixture(run, name):
    """A real census (or an excerpt of one) from two sweeps of builds with the same text."""
    path = os.path.join(FIXTURES, run, name + census_diff.SUFFIX)
    return census_diff.parse(l10nlib.read_text(path), path)


class IdentifierMaskTest(unittest.TestCase):
    def test_uuids_in_locators_and_values(self):
        self.assertEqual(census_diff.mask_ids("tag=scooter-edit-c896149b-2f4a-38a1-a4eb-d8bdc678b97c"), "tag=scooter-edit-<uuid>")
        self.assertEqual(census_diff.mask_ids("Profile C896149B-2F4A-38A1-A4EB-D8BDC678B97C saved"), "Profile <uuid> saved")
        key = next(iter(census_diff.keyed(census_diff.parse(census_diff.write(
            [("desc", "tag=scooter-edit-5a0af0c0-afe0-3b5e-9d6f-2b2df8e2b325", "-", "Edit")])))))
        self.assertEqual(key, ("desc", "tag=scooter-edit-<uuid>", 0))

    def test_hex_ids_and_report_timings(self):
        cases = {
            '"report_id": "1458068b7d6e6467",': '"report_id": "<hex-id>",',
            '"report_id": "1234567890123456"': '"report_id": "<hex-id>"',
            '"id": "0123abcd"': '"id": "<hex-id>"',
            "Report 1458068b7d6e6467 · Volt 1.11.1": "Report <hex-id> · Volt 1.11.1",
            "Report 61A6F9281269FDFC": "Report <hex-id>",
            "key a1b2c3d4e5f6": "key <hex-id>",
            '"wall_ms": 1793691898829, "first_ms": 51': '"wall_ms": <ms>, "first_ms": <ms>',
        }
        for value, masked in cases.items():
            self.assertEqual(census_diff.mask_ids(value), masked, value)

    def test_numbers_words_and_short_hex_stay(self):
        for value in ("137 W", "18.7 W", "18,7 W", "1,234,567 km", "1234567890", "123456789012345", "deadbeefcafe",
                      "Decaf", "A1B2C3", "0x74", "Q2538ZZ9US7777777", "C0:FF:EE:00:25:38", "1458068B7d6e6467",
                      '"first": 51', '"wall_ms": 1.5', "Battery 80%", "2026-03-05"):
            self.assertEqual(census_diff.mask_ids(value), value, value)

    def test_number_formatting_changes_still_count(self):
        """B1 changes number formatting; masking ids and clocks must not hide it."""
        for a, b in (("18.7 W", "18,7 W"), ("137 W", "137W"), ("137 W", "137 kW"), ("2 rides", "2 ride"),
                     ("Run check", "Run Check"), ("08:00", "8:00"), ("95% of 716 Wh", "95 % of 716 Wh"),
                     ("Report 1458068b7d6e6467 · 18.7 W", "Report 61a6f9281269fdfc · 18,7 W")):
            self.assertFalse(census_diff.same_after_masking(a, b), (a, b))
        self.assertTrue(census_diff.same_after_masking("Report 1458068b7d6e6467 · 18.7 W", "Report 61a6f9281269fdfc · 18.7 W"))

    def test_real_captures_differ_only_in_ids(self):
        for name in ("import-scooters", "mapped-compatibility-json"):
            base, head = fixture("harness", name), fixture("runtime", name)
            self.assertNotEqual([r.value for r in base] + [r.locator for r in base],
                                [r.value for r in head] + [r.locator for r in head], name)
            self.assertEqual(census_diff.compare(base, head)[0], [], name)


class VolatileTest(unittest.TestCase):
    def records(self, rows):
        return census_diff.parse(census_diff.write(rows))

    def runs(self, *values):
        """One record list per run: a stable label, a watt reading per run and a clock."""
        return [self.records([("text", "tag=title", "0,0,10,10", "Battery"), ("text", "tag=power", "0,20,10,10", value),
                              ("text", "tag=updated", "0,40,10,10", "Updated 9:59 AM")]) for value in values]

    def test_stable_mismatch_fails(self):
        a, a2, head = self.runs("18.7 W", "18.7 W", "18,7 W")
        result = census_diff.compare_runs([a, a2], head)
        self.assertEqual(result.text, ['- text tag=power: "18.7 W"', '+ text tag=power: "18,7 W"'])
        self.assertEqual(result.volatile, [])
        a, a2, head = self.runs("137 W", "137 W", "137W")
        self.assertEqual(len(census_diff.compare_runs([a, a2], head).text), 2)

    def test_stable_records_keep_the_clock_masks(self):
        a, a2 = self.runs("18.7 W", "18.7 W")
        head = self.records([("text", "tag=title", "0,0,10,10", "Battery"), ("text", "tag=power", "0,20,10,10", "18.7 W"),
                             ("text", "tag=updated", "0,40,10,10", "Updated 10:03 PM")])
        self.assertEqual(census_diff.compare_runs([a, a2], head).text, [])

    def test_volatile_is_reported_not_compared(self):
        a, a2, head = self.runs("Scooter connected", "Disconnected", "Something else")
        result = census_diff.compare_runs([a, a2], head)
        self.assertEqual(result.text, [])
        self.assertEqual(result.volatile, [(("text", "tag=power", 0), ["Scooter connected", "Disconnected"])])
        # The stable records of the same screen are still compared.
        head = self.records([("text", "tag=title", "0,0,10,10", "battery"), ("text", "tag=power", "0,20,10,10", "1 W"),
                             ("text", "tag=updated", "0,40,10,10", "Updated 9:59 AM")])
        self.assertEqual(len(census_diff.compare_runs([a, a2], head).text), 2)

    def test_drifting_numbers_are_compared_by_form(self):
        """Simulated telemetry drifts between runs: its numbers go unchecked, its formatting does not."""
        for base, base2, head, fails in (("137 W", "145 W", "150 W", False), ("137 W", "145 W", "150W", True),
                                         ("18.7 W", "16.3 W", "17.1 W", False), ("18.7 W", "16.3 W", "17,1 W", True),
                                         ("24 °C", "23 °C", "25 °F", True), ("24 °C", "23 °C", "25 °c", True),
                                         ("3 rides", "4 rides", "5 ride", True), ("Battery power, 20.1 W", "Battery power, 22.0 W",
                                                                                 "Battery power, 21.4 W", False)):
            a, a2, h = self.runs(base, base2, head)
            result = census_diff.compare_runs([a, a2], h)
            self.assertEqual((bool(result.text), result.volatile, result.formed), (fails, [], [("text", "tag=power", 0)]),
                             (base, base2, head))
        # A number whose form changes between the base runs is volatile.
        a, a2, h = self.runs("9.9 W", "10 W", "10W")
        result = census_diff.compare_runs([a, a2], h)
        self.assertEqual((result.text, len(result.volatile)), ([], 1))

    def test_records_only_some_base_runs_have_are_volatile(self):
        a, a2, _ = self.runs("1 W", "1 W", "1 W")
        a2 = a2 + self.records([("toast", "tag=hint", "-", "Saved")])
        result = census_diff.compare_runs([a, a2], a)
        self.assertEqual((result.text, result.volatile), ([], [(("toast", "tag=hint", 0), ["Saved"])]))
        # A record HEAD adds, or one every base run has and HEAD lacks, fails.
        self.assertEqual(census_diff.compare_runs([a, a], a2).text, ['+ toast tag=hint: "Saved"'])
        self.assertEqual(census_diff.compare_runs([a2, a2], a).text, ['- toast tag=hint: "Saved"'])

    def test_a_reordered_list_is_compared_without_order(self):
        rows = ["Jan 1, 2025 · 16:59, 100% observed", "Jan 1, 2025 · 17:59, Finish not observed", "Jan 1, 2025 · 18:59, Finish not observed"]

        def screen(order, *extra):
            return self.records([("text", "tag=title", "-", "Charge recordings")]
                                + [("desc", "tag=charge-history-session:%s" % ("%08x-0000-4000-8000-%012x" % (i, i)), "-", rows[i])
                                   for i in order] + list(extra))
        a, a2 = screen((0, 1, 2)), screen((2, 0, 1))
        result = census_diff.compare_runs([a, a2], screen((1, 2, 0)))
        self.assertEqual((result.text, result.volatile, len(result.reordered)), ([], [], 3))
        rows[0] = rows[0].replace("100%", "100 %")
        changed = census_diff.compare_runs([a, a2], screen((1, 2, 0)))
        self.assertEqual(changed.text, [
            '- desc tag=charge-history-session:<uuid>: "Jan 1, 2025 · 16:59, 100% observed" (a reordered list\'s row)',
            '+ desc tag=charge-history-session:<uuid>: "Jan 1, 2025 · 16:59, 100 % observed" (a reordered list\'s row)'])
        # With one base run a reordered list cannot be told from a changed one.
        self.assertNotEqual(census_diff.compare_runs([a], a2).text, [])

    def test_real_reordered_history(self):
        """The charge history fixture listed its sessions in another order on each run."""
        base, base2 = fixture("harness", "history-list"), fixture("runtime", "history-list")
        self.assertNotEqual(census_diff.compare(base, base2)[0], [])
        for head in (base, base2):
            result = census_diff.compare_runs([base, base2], head)
            self.assertEqual((result.text, result.volatile), ([], []))
            self.assertGreaterEqual(len(result.reordered), 10)
        rows = [(r.field, r.locator, r.bounds, r.value.replace("100% observed", "100 % observed", 1)) for r in base2]
        self.assertNotEqual(census_diff.compare_runs([base, base2], self.records(rows)).text, [])

    def test_real_timing_dependent_captures_are_volatile(self):
        for name, least in (("mapped-compatibility-running", 6), ("n65i-lab-unconfirmed-drop", 2)):
            base, base2 = fixture("harness", name), fixture("runtime", name)
            self.assertNotEqual(census_diff.compare(base, base2)[0], [], name)
            result = census_diff.compare_runs([base, base2], base2)
            self.assertEqual(result.text, [], name)
            self.assertGreaterEqual(len(result.volatile), least, name)
        # The notice's channel and action matched in both runs, so they are still compared.
        result = census_diff.compare_runs([fixture("harness", "n65i-lab-unconfirmed-drop"), fixture("runtime", "n65i-lab-unconfirmed-drop")],
                                          self.records([("notif-action", "notification:scooter/71", "-", "Disconnect now")]))
        self.assertIn('- notif-action notification:scooter/71: "Disconnect"', result.text)

    def folders(self, work, runs):
        for run, screens in runs.items():
            for name, rows in screens.items():
                l10nlib.write_text(os.path.join(work, run, name + census_diff.SUFFIX), census_diff.write(rows))
        return [os.path.join(work, run) for run in runs]

    def sweep(self, folder, app="a" * 64, runner="r" * 64):
        l10nlib.write_text(os.path.join(folder, census_diff.SWEEP), '{"format": "volt-census-sweep-v1", "app_sha256": "%s", "runner_sha256": "%s"}\n' % (app, runner))

    def main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = census_diff.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_multi_base_cli_and_single_base_warning(self):
        work = tempfile.mkdtemp(prefix="l10n-census-")
        try:
            power = lambda v: [("text", "tag=title", "-", "Battery"), ("text", "tag=power", "-", v)]
            a, a2, head = self.folders(work, {"a": {"home": power("Charging"), "only-a": power("1 W")},
                                              "a2": {"home": power("Idle")}, "head": {"home": power("Off")}})
            for folder in (a, a2, head):
                self.sweep(folder)
            code, out, err = self.main(["--base", a, "--base", a2, head, "--show-volatile"])
            self.assertEqual(code, 0, err)
            self.assertNotIn("one base run", err)
            self.assertIn("volatile: home.census.tsv: text tag=power: \"Charging\" | \"Idle\"", out)
            self.assertIn("only-a.census.tsv: text tag=power (screen in 1 of 2 base runs)", out)
            self.assertIn("volatile by step: ", out)
            self.assertIn("PASS: 2 screen(s), 2 base run(s), 0 text difference(s), 3 of 4 record(s) volatile", out)
            code, out, err = self.main([a, head])
            self.assertEqual(code, 1)
            self.assertIn("warning: one base run", err)
            self.assertIn("home.census.tsv: - text tag=power", err)
            self.assertIn("only-a.census.tsv: only in base", err)
            with contextlib.redirect_stderr(io.StringIO()):
                for argv in (["--base", a, a2, head], [head]):
                    with self.assertRaises(SystemExit):
                        census_diff.main(argv)
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def test_runs_must_be_separate_sweeps_of_one_base_apk(self):
        work = tempfile.mkdtemp(prefix="l10n-census-")
        try:
            screen = {"home": [("text", "tag=title", "-", "Battery")]}
            a, a2, head = self.folders(work, {"a": screen, "a2": screen, "head": screen})
            self.assertIn("no sweep.json", " ".join(census_diff.diff_runs([a, a2], head).warnings))
            self.sweep(a); self.sweep(a2); self.sweep(head, app="b" * 64)
            summary = census_diff.diff_runs([a, a2], head)
            self.assertEqual((summary.problems, summary.warnings), ([], []))
            self.assertIn("HEAD", " ".join(census_diff.diff_runs([a, head], head).problems))
            self.assertIn("given twice", " ".join(census_diff.diff_runs([a, a], head).problems))
            self.sweep(a2, app="b" * 64)
            self.assertIn("different app APKs", " ".join(census_diff.diff_runs([a, a2], head).problems))
            self.sweep(a2); self.sweep(head, runner="s" * 64)
            self.assertIn("different test APKs", " ".join(census_diff.diff_runs([a, a2], head).problems))
        finally:
            shutil.rmtree(work, ignore_errors=True)


class LintTest(unittest.TestCase):
    SOURCE = java_class("Screen", '''    @SuppressWarnings("unchecked word")
    void show(TextView v, Ui ui) {
        v.setText("Cancel");
        v.setContentDescription(ready ? "Lock scooter" : label("x"));
        ui.toast(String.format(Locale.US, "%d km", 3));
        v.setText("%1$d%%");
        builder.setPositiveButton("OK", null);
        new Notification.Action.Builder(icon, "Stop", intent);
        canvas.drawText("S", x, y, paint);
        prefs.edit().putString("units_mode", "km").apply();
        Log.w(TAG, "Something went wrong here");
        ui.secondaryButton("Skip", () -> host.toast("Saved"));
        ui.segmentedField("Units", "units_mode", new String[] {"Metric", "Imperial"}, values, "km");
        new SegmentedSelector(ui, "Window", labels, this::pick);
    }
    private TextView button(String label, Runnable action) { return null; }''')

    def test_findings(self):
        found = [(f.rule, f.value) for f in hardcoded_lint.findings(self.SOURCE)]
        self.assertEqual(found, [("sink", "Cancel"), ("sink", "Lock scooter"), ("sink", "%d km"),
                                 ("sink", "OK"), ("sink", "Stop"), ("sink", "S"), ("multiword", "Something went wrong here"),
                                 ("helper", "Skip"), ("sink", "Saved"), ("helper", "Units"), ("helper", "Metric"),
                                 ("helper", "Imperial"), ("helper", "Window")])

    def test_baseline_render_and_parse(self):
        found = hardcoded_lint.findings(self.SOURCE)
        text = hardcoded_lint.render_baseline("src/Screen.java", found)
        self.assertTrue(text.startswith("# Hard-coded English in src/Screen.java"))
        self.assertEqual(sum(hardcoded_lint.parse_baseline(text).values()), len(found))
        self.assertEqual(hardcoded_lint.parse_baseline(text), hardcoded_lint.parse_baseline(
            hardcoded_lint.render_baseline("src/Screen.java", list(reversed(found)))))


class LintRepoTest(unittest.TestCase):
    PATH = "src/com/jjricks/volt/Screen.java"

    def setUp(self):
        self.repo = TempRepo()
        self.repo.write(self.PATH, java_class("Screen", '    void a(TextView v) { v.setText("Cancel"); v.setHint("Search settings"); }'))
        self.repo.write("src/com/jjricks/volt/Clean.java", java_class("Clean", '    String key = "units_mode";'))
        self.repo.write(hardcoded_lint.TOOL_PATH, "# stands in for the tool\n")
        hardcoded_lint.write_baselines(self.repo.root, hardcoded_lint.scan(self.repo.root))
        self.base = self.repo.commit("base with baselines")

    def tearDown(self):
        self.repo.close()

    def check(self, tight=None):
        return hardcoded_lint.check(self.repo.root, hardcoded_lint.scan(self.repo.root), self.base, tight, self.repo.root)

    def test_only_files_with_findings_get_baselines(self):
        self.assertEqual(sorted(os.listdir(self.repo.path("l10n/baseline"))), ["Screen.txt"])
        self.assertEqual(self.check(), ([], []))

    def test_new_text_fails_even_in_a_clean_file(self):
        self.repo.write("src/com/jjricks/volt/Clean.java", java_class("Clean", '    void a(TextView v) { v.setText("Saved"); }'))
        problems, _ = self.check()
        self.assertTrue(any("Clean.java:4: new hard-coded English (sink) \"Saved\"" in p for p in problems), problems)

    def test_extraction_leaves_a_stale_baseline_until_regenerated(self):
        self.repo.write(self.PATH, java_class("Screen", '    void a(TextView v) { v.setText(T.commonCancel()); v.setHint("Search settings"); }'))
        problems, notes = self.check()
        self.assertEqual(problems, [])
        self.assertTrue(any("can shrink by 1 entry" in n for n in notes))
        problems, _ = self.check(tight={self.PATH})
        self.assertTrue(any("can shrink" in p for p in problems))
        hardcoded_lint.write_baselines(self.repo.root, hardcoded_lint.scan(self.repo.root))
        self.assertEqual(self.check(tight={self.PATH}), ([], []))

    def test_baselines_may_only_shrink(self):
        self.repo.write(self.PATH, java_class("Screen", '    void a(TextView v) { v.setText("Cancel"); v.setHint("Search all settings"); }'))
        hardcoded_lint.write_baselines(self.repo.root, hardcoded_lint.scan(self.repo.root))
        self.repo.commit("grow")
        problems, _ = self.check()
        self.assertTrue(any("Screen.txt grew by 1 entry" in p for p in problems), problems)

    def test_helper_labels_are_findings(self):
        """Findings 9 and 23: single-word labels passed to Volt's own helpers."""
        self.repo.write("src/com/jjricks/volt/Clean.java", java_class("Clean", '    void a(Ui ui) { ui.button("Done", null); }'))
        problems, _ = self.check()
        self.assertTrue(any('new hard-coded English (helper) "Done"' in p for p in problems), problems)

    def test_growth_after_every_baseline_was_deleted_fails(self):
        """Finding 20: a base with the tool but no baselines (every file converted) is not a first run."""
        self.repo.git("rm", "-q", "-r", "l10n/baseline")
        self.repo.write(self.PATH, java_class("Screen", '    void a(TextView v) { v.setText(T.commonCancel()); }'))
        converted = self.repo.commit("every file converted")
        self.repo.write(self.PATH, java_class("Screen", '    void a(TextView v) { v.setTooltipText("Tap here to close"); }'))
        hardcoded_lint.write_baselines(self.repo.root, hardcoded_lint.scan(self.repo.root))
        self.repo.commit("new English with a new baseline")
        problems, notes = hardcoded_lint.check(self.repo.root, hardcoded_lint.scan(self.repo.root), converted, None,
                                               self.repo.root)
        self.assertTrue(any("Screen.txt grew by 1 entry" in p for p in problems), (problems, notes))

    def test_first_run_when_the_base_has_no_tool(self):
        self.repo.git("rm", "-q", hardcoded_lint.TOOL_PATH)
        self.repo.commit("before B0a")
        self.repo.git("checkout", "-q", "-b", "b0a", "HEAD")
        before = self.repo.git("rev-parse", "HEAD")
        self.repo.write(hardcoded_lint.TOOL_PATH, "# the tool arrives\n")
        self.repo.commit("b0a")
        _, notes = hardcoded_lint.check(self.repo.root, hardcoded_lint.scan(self.repo.root), before, None, self.repo.root)
        self.assertTrue(any("first run" in n for n in notes), notes)

    def test_orphan_baseline_fails(self):
        self.repo.remove(self.PATH)
        problems, _ = self.check()
        self.assertTrue(any("Screen.txt has no source file" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
