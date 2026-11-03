package com.jjricks.volt;

/**
 * Volt's versionName text. A release is plain numbers ("1.11.1"); a build from main that isn't a
 * published release carries build.sh's dev marker and commit ("1.11.1-dev+abc1234", "-dirty" when
 * built from uncommitted changes). Pure Java; nothing here throws on unexpected text.
 */
final class VersionName {
    private VersionName() {}

    /** Leading numeric parts: "v1.5.1" is 1.5.1 and "1.11.1-dev+abc1234" is 1.11.1; null when there are none. */
    static int[] numbers(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int end = 0;
        while (end < s.length() && (digit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        if (end == 0) return null;
        String[] parts = s.substring(0, end).split("\\.");
        if (parts.length == 0 || parts.length > 4) return null;
        int[] result = new int[parts.length];
        try { for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]); }
        catch (NumberFormatException unreadable) { return null; }
        return result;
    }

    /** ASCII digits only: Character.isDigit would also take other scripts' digits. */
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }

    /** Missing parts count as 0, so 1.5 equals 1.5.0. */
    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** A development build from build.sh: "-dev" right after the numbers, then its commit or nothing. */
    static boolean dev(String text) {
        if (text == null) return false;
        String s = text.trim();
        int end = 0;
        while (end < s.length() && (digit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        if (end == 0) return false;
        String rest = s.substring(end);
        return rest.equals("-dev") || rest.startsWith("-dev+") || rest.startsWith("-dev-");
    }
}
