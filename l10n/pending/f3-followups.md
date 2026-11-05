# F3 follow-ups: text logic that still reads shown text back

F3 fixed the items its merged code allowed. These stay open. Only English ships, so none is visible
today; each one is wrong in a translated build. Delete an entry when it is done. Line numbers are
on the named branch as of F3.

## RidesScreen, owner: an ordinary follow-up after B15 merges

F3 may not edit RidesScreen.java while B15 is open (`claude/l10n-b15-rides-screen-r2`). B15 can fix
these itself if it prefers; otherwise they are the first follow-up after it merges.

1. **`splitNumber`** (:344, :1596, :1625) parses `fmt_quantity` output into number and unit. In a
   language whose `fmt_quantity` is not number, ASCII space, unit, it finds no number, and the live
   ride card shows a dash for distance. Fix: `distanceParts`/`speedParts` built from the numbers.
2. **"Not measured"** is a sentinel compared as text in `dash()` and `valueRow` (:1120, :1435, :1560,
   :2114, :2119, :2151-2157). Fix: a null sentinel, rendered as `T.ridesNotMeasured()`; drop the dead
   "Insufficient data".
3. **`sensorSummary`** (:328-333) upper-cases the first letter of rendered catalog words. Fix:
   sentence-start forms in the catalog, or `Fmt.locale()`.

## DashboardScreen, owner: an ordinary follow-up after B13 merges

`claude/l10n-b13-dashboard-screen` is open, so F3 may not edit DashboardScreen.java.

4. **`withUnit`** (:929-937) splits the reading at its last ASCII space. Cosmetic: B13 documents the
   deferral.

## Widest word, owner: the batch that adds a non-Latin language

5. **`split("\\s+")`** measures the widest word in SegmentedSelector:170, StatsScreen:355 and
   DashboardScreen:3063 on B13 (:3019 on main). A language that writes no spaces measures its whole
   label as one word. Fix, in all three together: keep the split while `Fmt.legacy()` and otherwise use
   `BreakIterator.getLineInstance(Fmt.locale())`, as `StatsLabels.matchesShown` gates on `!Fmt.legacy()`.
   The gate keeps English identical (BreakIterator alone would split "Left-right"). No gate blocks
   this. It waits because the sweep marked it low priority, non-Latin languages are deferred, and one
   of the three sites is in an open pull request.

## MainActivity, owner: B19

6. **`lock_expiry`'s `label.replace(" hours", " h")`** (:1762) reads `featureLabel`'s English id, so it
   works today; it becomes a read-back when B19 makes that label display text. Fix: a catalog short-hours
   form built from the option value.
7. **The label-only `toggleRow`/`settingRow` overloads, `Ui.labelIcon` and the String `styleDriveMode`**
   stay while DashboardScreen calls them. UiToolkitTest's legacy count holds them until then.
   `MoreScreen`'s lower-casing of `row.feature.label` into `T.moreLabSetBackRow` is B19/F4 label work.
