package com.jjricks.volt;

/**
 * The Reading tiles picker and Home style's overview reading list search with one matcher, DashboardScreen.readingMatches
 * (#565 follow-up: the overview list carried a copy of the tiles picker's matching). Read from the source: DashboardScreen
 * needs Android. The emulator steps overview-readings* and the Reading tiles picker's search exercise it.
 */
public final class ReadingSearchMatcherTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/DashboardScreen.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        check(count(source, "private static boolean readingMatches(Telemetry.Metric metric, String query) {") == 1, "one matcher");
        check(count(source, "readingMatches(") == 3, "defined once and used by both pickers");
        check(source.contains("boolean visible = readingMatches(metrics.get(i), query);"), "the Reading tiles picker uses it");
        check(source.contains("boolean visible = entry.getValue() != null && readingMatches(entry.getValue(), query);"), "the overview reading list uses it");
        // No second copy of the matching: its English line and the shown full names appear in the matcher only.
        check(count(source, "SearchFold.holdsShown(ScooterReadingLabels.full(metric.key, metric.label), ScooterReadingLabels.group(metric.group), query)") == 1
                && count(source, "+ \" \" + ScooterReadingLabels.label(metric.key, metric.label)).toLowerCase(Locale.ROOT).contains(query)") == 1,
                "no copy of the matching is left");
        check(!source.contains("overviewReadingMatches") && !source.contains("READING_SEARCH_JOIN"), "the overview list's copy is gone");
        System.out.println("ReadingSearchMatcher: " + checks + " checks passed");
    }

    private static int count(String text, String part) { int n = 0; for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++; return n; }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
