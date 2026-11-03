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
