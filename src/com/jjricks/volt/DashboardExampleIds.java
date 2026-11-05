package com.jjricks.volt;

import java.util.HashSet;
import java.util.UUID;

/**
 * The ids the shipped example (AllWidgetsDashboard) gives its widgets, which say where a widget came from
 * without a new saved field. Only the example's own widgets show their titles and note in the current
 * language; text on any other widget is the owner's and shows as saved, whatever it says.
 *
 * An example id is a random UUID in the same canonical 36-character form as every other widget id, so
 * saves, exports and imports carry it, older versions read it as they always have, and two examples (two
 * profiles, or a reseeded slot) never share one. Two fixed parts mark it: version 8 (RFC 9562's custom
 * layout), where UUID.randomUUID(), which makes every other widget's id (DashboardConfig.Widget),
 * always writes version 4; and "Volt" in ASCII as its first eight hex digits. A duplicate of an example
 * widget that still shows the example's words gets one too (AllWidgetsDashboard.emptyCopy).
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

    /**
     * True for the shipped example's own saved slot (DashboardConfig.exampleSlot), from what the slot
     * saves: slot index 2 (slot 3, where seedExampleSlot and forceExampleSlot save it), the example's
     * name (exampleName: the saved name is DashboardConfig.EXAMPLE_SLOT_NAME), at least one widget, every
     * widget's id one {@link #create} made, and no widget type twice. ids[i] and types[i] are widget i's.
     *
     * Not the example's exact widget count: the example has one widget of every type, so a version that
     * adds a type (and adds it to the example) would otherwise stop recognising every example slot an
     * earlier version saved. So a slot 3 named so that holds only example widgets, all of them or fewer
     * (the owner removed some, or a later version with a type more reads this version's example), still
     * counts. A later version's example never reaches this one's check: its added type is not in
     * DashboardConfig.TYPES here, so validateSlot refuses the slot and it does not read at all. This check
     * does not compare types with TYPES itself; validateSlot does that first. An owner's own widget (a
     * random id) in it, or an example saved by an older version (random ids), makes the name the owner's,
     * shown as saved. A duplicate of an example widget has an example id too (AllWidgetsDashboard.emptyCopy):
     * beside its original its type is there twice, so the name is the owner's; saved without its original, it
     * counts as that widget and the name stays Volt's.
     */
    static boolean exampleSlot(int index, boolean exampleName, String[] ids, String[] types) {
        if (index != 2 || !exampleName || ids.length == 0 || ids.length != types.length) return false;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length; i++)
            if (!is(ids[i]) || !seen.add(types[i])) return false;
        return true;
    }
}
