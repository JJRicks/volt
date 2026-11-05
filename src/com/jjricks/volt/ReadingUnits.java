package com.jjricks.volt;

/**
 * Where a shown reading's number and unit sit, for the Home dashboard's big values and gauges. Speed and temperature
 * come as {@link Formatted} (number and unit ranges), so a language's own space or word order keeps working. Every
 * other reading is Telemetry's canonical text with a space before its unit, or a converted distance from fmt_quantity,
 * so that text is split at its space, a no-break space included.
 *
 * Pure Java: tests/l10n compiles it (DashboardFollowupsTest).
 */
final class ReadingUnits {
    private ReadingUnits() {}

    /**
     * {start, end} of the part drawn smaller, or null to draw none. With a number before its unit the part starts at
     * the space. A value with a slash (km/h, a dual reading) and a unit over five characters draw none, as ever.
     */
    static int[] smallPart(String value, Formatted parts) {
        if (parts == null) return smallPart(value);
        if (parts.numbers() != 1 || parts.units() != 1 || slashed(value)) return null;
        int numberStart = parts.numberStart(0), numberEnd = parts.numberEnd(0);
        int unitStart = parts.unitStart(0), unitEnd = parts.unitEnd(0);
        if (!unitFits(value.substring(unitStart, unitEnd)) || numberEnd <= numberStart
                || !Character.isDigit(value.charAt(numberEnd - 1))) return null;
        return unitStart >= numberEnd ? new int[]{numberEnd, unitEnd} : new int[]{unitStart, numberStart};
    }

    private static int[] smallPart(String value) {
        int space = lastSpace(value);
        if (space <= 0 || space >= value.length() - 1 || slashed(value)) return null;
        if (!unitFits(value.substring(space + 1)) || !Character.isDigit(value.charAt(space - 1))) return null;
        return new int[]{space, value.length()};
    }

    /** A slash means km/h or a dual reading, which keep one size. */
    private static boolean slashed(String value) { return value.contains("/"); }

    private static boolean unitFits(String unit) {
        if (unit.isEmpty()) return false;
        char first = unit.charAt(0);
        return unit.length() <= 5 && (Character.isLetter(first) || first == '%' || first == '°');
    }

    /** {amount, unit, secondary} of a gauge's reading with a number; amount alone when there is no split. */
    static String[] gaugeParts(String formatted, Formatted parts) {
        if (parts != null) {
            int end = parts.numbers() > 0 ? parts.numberEnd(0) : 0;
            if (parts.units() == 0 || end <= parts.numberStart(0) || !Character.isDigit(formatted.charAt(end - 1)))
                return new String[]{formatted, "", ""};
            String secondary = parts.numbers() > 1 && parts.units() > 1
                    ? formatted.substring(Math.min(parts.numberStart(1), parts.unitStart(1)), Math.max(parts.numberEnd(1), parts.unitEnd(1))) : "";
            return new String[]{formatted.substring(parts.numberStart(0), end),
                    formatted.substring(parts.unitStart(0), parts.unitEnd(0)), secondary};
        }
        String amount = formatted, unit = "", secondary = "";
        int split = firstSpace(formatted);
        if (split > 0 && Character.isDigit(formatted.charAt(split - 1))) {
            amount = formatted.substring(0, split);
            unit = formatted.substring(split + 1).trim();
            int dual = unit.indexOf(" / ");
            if (dual >= 0) {
                secondary = unit.substring(dual + 3);
                unit = unit.substring(0, dual);
            }
        }
        return new String[]{amount, unit, secondary};
    }

    private static boolean space(char c) { return c == ' ' || c == ' ' || c == ' '; }

    private static int lastSpace(String text) {
        for (int i = text.length() - 1; i >= 0; i--) if (space(text.charAt(i))) return i;
        return -1;
    }

    private static int firstSpace(String text) {
        for (int i = 0; i < text.length(); i++) if (space(text.charAt(i))) return i;
        return -1;
    }
}
