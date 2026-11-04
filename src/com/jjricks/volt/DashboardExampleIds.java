package com.jjricks.volt;

import java.util.UUID;

/**
 * The ids the shipped example (AllWidgetsDashboard) gives its widgets, which say where a widget came from
 * without a new saved field. Only the example's own widgets show their titles and note in the current
 * language; text on any other widget is the owner's and shows as saved, whatever it says.
 *
 * An example id is a random UUID in the same canonical 36-character form as every other widget id, so
 * saves, exports and imports carry it, older versions read it as they always have, and two examples (two
 * profiles, or a reseeded slot) never share one. Two fixed parts mark it: version 8 (RFC 9562's custom
 * layout), where UUID.randomUUID(), which makes every other widget's id (DashboardConfig.Widget, also
 * when it duplicates one), always writes version 4; and "Volt" in ASCII as its first eight hex digits.
 * An example saved by an older version has random version 4 ids too, so it counts as the owner's.
 *
 * Pure Java: tests/l10n compiles it (DashboardModelTextTest).
 */
final class DashboardExampleIds {
    /** "Volt" in ASCII: the first 32 bits of every example id. */
    private static final long TAG = 0x566f6c74L;
    /** The version nibble of a UUID's high half, and version 8 in it. */
    private static final long VERSION = 0xf000L, VERSION_8 = 0x8000L;

    private DashboardExampleIds() {}

    /** A new example widget id: 90 random bits, the tag and version 8. */
    static String create() {
        UUID random = UUID.randomUUID();
        long high = (TAG << 32) | (random.getMostSignificantBits() & 0xffffffffL & ~VERSION) | VERSION_8;
        return new UUID(high, random.getLeastSignificantBits()).toString();
    }

    /** True for an id {@link #create} made, exactly as it wrote it; false for null and anything else. */
    static boolean is(String id) {
        if (id == null || id.length() != 36) return false;
        try {
            UUID uuid = UUID.fromString(id);
            return uuid.toString().equals(id) && uuid.getMostSignificantBits() >>> 32 == TAG
                    && uuid.version() == 8 && uuid.variant() == 2;
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
    }
}
