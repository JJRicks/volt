# F3 follow-ups: text logic that still reads shown text back

F3 fixed the items its merged code allowed. These stay open. Only English ships, so none is visible
today; each one is wrong in a translated build. Delete an entry when it is done. Line numbers are
on the named branch as of F3.

## RidesScreen, owner: an ordinary follow-up after B15 merges

B15 merged as #188 without these, because F3 could not edit RidesScreen.java while it was open. They
are the first follow-up after it; branch `claude/l10n-f5-rides-followups` takes them on.

1. **`splitNumber`** (:344, :1596, :1625) parses `fmt_quantity` output into number and unit. In a
   language whose `fmt_quantity` is not number, ASCII space, unit, it finds no number, and the live
   ride card shows a dash for distance. Fix: `distanceParts`/`speedParts` built from the numbers.
2. **"Not measured"** is a sentinel compared as text in `dash()` and `valueRow` (:1120, :1435, :1560,
   :2114, :2119, :2151-2157). Fix: a null sentinel, rendered as `T.ridesNotMeasured()`; drop the dead
   "Insufficient data".
3. **`sensorSummary`** (:328-333) upper-cases the first letter of rendered catalog words. Fix:
   sentence-start forms in the catalog, or `Fmt.locale()`.

## DashboardScreen, owner: an ordinary follow-up after B13 merges

B13 merged as #189 without these, because F3 could not edit DashboardScreen.java while it was open.

4. **`withUnit`** (:929-937) splits the reading at its last ASCII space. Cosmetic: B13 documents the
   deferral.
5. **The String `styleDriveMode`, the label-only `toggleRow`/`settingRow` overloads and `Ui.labelIcon`**
   stay while DashboardScreen calls them. Its callers are `styleDriveMode` at :3457 and :3470 and
   label-only `settingRow` calls such as :5204; no screen on this branch calls the label-only `toggleRow`.
   Fix: pass a DriveMode decoded from the number, as `StatsScreen.driveMode` does, and an icon id to each
   `settingRow`, then delete the overloads. Deleting them also needs a tooling pull request, because
   UiToolkitTest requires exactly those three legacy calls in Ui (`ui.size() == 3`, :151).

## Widest word and non-Latin units, owner: the batch that adds a non-Latin language

6. **`split("\\s+")`** measures the widest word in SegmentedSelector:170, StatsScreen:355 and
   DashboardScreen:3063. A language that writes no spaces measures its whole
   label as one word. Fix, in all three together: keep the split while `Fmt.legacy()` and otherwise use
   `BreakIterator.getLineInstance(Fmt.locale())`, as `StatsLabels.matchesShown` gates on `!Fmt.legacy()`.
   The gate keeps English identical (BreakIterator alone would split "Left-right"). No gate blocks
   this. It waits because the sweep marked it low priority and non-Latin languages are deferred.
7. **`DisplayUnits.unbroken`** joins only after punctuation, symbols and spaces, so a unit written in
   ideographs or kana can still wrap between its letters on a Keep, Set or Restore button. It waits
   because non-Latin languages are deferred. Fix: also join between the letters of a unit that allow a
   break.

## MainActivity, owner: B19

8. **`lock_expiry`'s `label.replace(" hours", " h")`** (:1762) reads `featureLabel`'s English id, so it
   works today; it becomes a read-back when B19 makes that label display text. Fix: a catalog short-hours
   form built from the option value.

## MoreScreen, owner: a later follow-up

9. **`row.feature.label` lower-cased into three spoken descriptions:** `T.moreLabReviewRow`,
   `T.moreLabSetBackRow` and `T.moreLabTryRow` (:1708-1710). A screen reader says the English name of
   the control in a translated build. Fix: use `row.feature.label()`, with a lower-case form in the
   catalog if the sentence needs one. F4 merged without this.

## Needs a decision

10. **Eco and Turbo.** "Now Eco" stays English because `ScooterCommands.shown` treats them as the
    scooter's own names (:483), but the glossary marks `acceleration-eco` and `acceleration-turbo` as
    translatable. One of the two must change before a language ships.
11. **Stats section tabs.** Overview, Readings, Charts and Diagnostics (StatsScreen:73-74) show English,
    and the same strings are compared and saved as the `stats_section` preference. They need a separate
    id and label. G1 allows only the literal swap on the statement holding the tab description (#183),
    so a tooling round comes first. No batch owns this yet.

## Census coverage, nothing to fix for now

12. **No release-sweep step opens MainActivity's real slider sheet.** The only `editFeature` call in
    android-tests is `CompatControlsValidation.choiceSheet`, which covers listed-value controls, and
    GuidedTestValidation's slider is MoreScreen's. So the census has not seen `DisplayUnits.sliderEnds`,
    and a census pass is not proof for it. It is covered by TextLogicTest, and by parity harnesses and a
    language check described in F3's pull request, which are not in this repository. To close this, a validation would open `editFeature` on a slider with no listed
    values, such as on the UT5 MAX, and record the ends row.
