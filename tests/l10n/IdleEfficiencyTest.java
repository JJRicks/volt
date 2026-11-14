package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The idle efficiency fixes: NoticeRepeat (the connection notice posts only when it changed or no longer shows),
 * KeepScreenOn (the window flag is touched only on a change), and WorkReasons' simulator answer for the ten-second stop.
 * Synthesized values only.
 */
public final class IdleEfficiencyTest {
    private static int checks;

    public static void main(String[] args) {
        noticeRepeat();
        noticeKeys();
        keepScreenOn();
        simulatorStop();
        System.out.println("IdleEfficiencyTest: " + checks + " checks passed");
    }

    /** A notice built as the service builds it: title, text, progress, action, flags and extras. */
    private static Object notice(String title, String text, int progress, String action, int flags, Object intent) {
        Map<String, Object> extras = new HashMap<>();
        extras.put("android.title", title);
        extras.put("android.text", new StringBuilder(text));
        extras.put("android.progress", progress);
        extras.put("android.progressMax", 100);
        extras.put("android.showWhen", false);
        List<Object> actions = new ArrayList<>();
        actions.add(Arrays.asList(action, intent));
        return NoticeRepeat.key(Arrays.asList(Arrays.asList(false, action), Arrays.asList("scooter", flags, actions, extras)));
    }

    private static void noticeRepeat() {
        Object intent = new Object();
        NoticeRepeat repeat = new NoticeRepeat();
        int[] asked = {0};
        long t = 1_000_000L;
        Object first = notice("Connected", "UT5 MAX · 62%", -1, "Disconnect", 2, intent);
        require(repeat.due(first, false, t, () -> { asked[0]++; return true; }), "Nothing posted yet: the first notice posts");
        require(asked[0] == 0, "With nothing posted Android isn't asked");
        repeat.posted(first, t);

        Object same = notice("Connected", "UT5 MAX · 62%", -1, "Disconnect", 2, intent);
        require(!repeat.due(same, false, t + 5_000, () -> { asked[0]++; return true; }), "The same notice, still showing, doesn't post again");
        require(asked[0] == 1, "An unchanged notice asks Android whether it still shows");
        require(!repeat.due(same, false, t + 3_600_000, () -> true), "Without a post time on show, an unchanged notice never needs a refresh");
        require(repeat.due(same, false, t + 5_000, () -> false), "Swiped away or removed: the same notice posts again");

        require(repeat.due(notice("Connected", "UT5 MAX · 61%", -1, "Disconnect", 2, intent), false, t + 1, () -> true), "Text changed: posts");
        require(repeat.due(notice("Recording", "UT5 MAX · 62%", -1, "Disconnect", 2, intent), false, t + 1, () -> true), "Title changed: posts");
        require(repeat.due(notice("Connected", "UT5 MAX · 62%", 40, "Disconnect", 2, intent), false, t + 1, () -> true), "Progress changed: posts");
        require(repeat.due(notice("Connected", "UT5 MAX · 62%", -1, "Stop", 2, intent), false, t + 1, () -> true), "Action changed: posts");
        require(repeat.due(notice("Connected", "UT5 MAX · 62%", -1, "Disconnect", 0, intent), false, t + 1, () -> true), "Ongoing flag changed: posts");
        require(repeat.due(notice("Connected", "UT5 MAX · 62%", -1, "Disconnect", 2, new Object()), false, t + 1, () -> true),
                "Another intent object (compared by identity): posts");

        // The header's "now": an unchanged notice that shows its post time posts again before Android would show "1m".
        require(NoticeRepeat.REFRESH_MS + 15_000 < 60_000, "Refresh age plus the slowest tick stays under a minute");
        require(!repeat.due(same, true, t + NoticeRepeat.REFRESH_MS - 1, () -> true), "A post time younger than the refresh age: no post");
        require(repeat.due(same, true, t + NoticeRepeat.REFRESH_MS, () -> true), "A post time at the refresh age: posts again");
        repeat.posted(same, t + NoticeRepeat.REFRESH_MS);
        require(!repeat.due(same, true, t + NoticeRepeat.REFRESH_MS + 5_000, () -> true), "Refreshed: the age starts again");
        require(!repeat.due(same, false, t + 10 * NoticeRepeat.REFRESH_MS, () -> true), "A notice without a post time is never refreshed for age");

        repeat.posted(notice("Connected", "UT5 MAX · 61%", -1, "Disconnect", 2, intent), t);
        require(!repeat.due(notice("Connected", "UT5 MAX · 61%", -1, "Disconnect", 2, intent), false, t + 1, () -> true), "The new baseline holds");
        require(repeat.due(first, false, t + 1, () -> true), "Going back to the earlier text posts");
        repeat.reset();
        require(repeat.due(notice("Connected", "UT5 MAX · 61%", -1, "Disconnect", 2, intent), false, t + 1, () -> true), "After a reset the next notice posts");
    }

    private static void noticeKeys() {
        Object bitmap = new Object();
        require(NoticeRepeat.key(Arrays.asList("a", 1, true, 'c', null)).equals(NoticeRepeat.key(Arrays.asList("a", 1, true, 'c', null))),
                "Plain values compare by content");
        require(!NoticeRepeat.key(Arrays.asList(1)).equals(NoticeRepeat.key(Arrays.asList(1L))), "An int and a long are different parts");
        require(NoticeRepeat.key(new int[]{1, 2, 3}).equals(NoticeRepeat.key(new int[]{1, 2, 3})), "Arrays compare element by element");
        require(!NoticeRepeat.key(new int[]{1, 2, 3}).equals(NoticeRepeat.key(new int[]{1, 2, 4})), "A changed array element is a change");
        require(NoticeRepeat.key(new String[]{"x"}).equals(NoticeRepeat.key(Arrays.asList("x"))), "An array is compared as a list");
        require(NoticeRepeat.key(bitmap).equals(NoticeRepeat.key(bitmap)), "The same object is the same part");
        require(!NoticeRepeat.key(new Object()).equals(NoticeRepeat.key(new Object())), "Another object is never the same part");
        require(NoticeRepeat.key(new StringBuilder("62%")).equals("62%"), "Text compares as its String");

        Map<String, Object> nested = new HashMap<>();
        nested.put("segments", Arrays.asList(map("length", 60, "color", 0xFFE32B2F), map("length", 40, "color", 0xFF878982)));
        Object before = NoticeRepeat.key(nested);
        Map<String, Object> rebuilt = new HashMap<>();
        rebuilt.put("segments", Arrays.asList(map("color", 0xFFE32B2F, "length", 60), map("length", 40, "color", 0xFF878982)));
        require(before.equals(NoticeRepeat.key(rebuilt)), "Nested maps compare by content, whatever their order");
        rebuilt.put("segments", Arrays.asList(map("color", 0xFFE32B2F, "length", 61), map("length", 39, "color", 0xFF878982)));
        require(!before.equals(NoticeRepeat.key(rebuilt)), "A changed bar segment is a change");

        int[] live = {1, 2};
        Object copied = NoticeRepeat.key(live);
        live[1] = 5;
        require(copied.equals(NoticeRepeat.key(new int[]{1, 2})) && !copied.equals(NoticeRepeat.key(live)), "A key keeps its values, not the array");
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static void keepScreenOn() {
        int flag = KeepScreenOn.FLAG;
        require(flag == 0x80, "FLAG_KEEP_SCREEN_ON is 0x80");
        // Every combination against the expression it replaced.
        for (int bits = 0; bits < 32; bits++) {
            boolean resumed = (bits & 1) != 0, setting = (bits & 2) != 0, recording = (bits & 4) != 0, riding = (bits & 8) != 0, serviceUp = (bits & 16) != 0;
            boolean old = resumed && setting && serviceUp && recording;
            old |= resumed && riding;
            require(KeepScreenOn.wanted(resumed, setting, serviceUp && recording, riding) == old, "wanted() matches the old expression, case " + bits);
        }
        int other = 0x01000000 | 0x00000400;
        require(KeepScreenOn.change(true, other) == 1, "Wanted and not set: set it");
        require(KeepScreenOn.change(true, other | flag) == 0, "Wanted and set: leave the window alone");
        require(KeepScreenOn.change(false, other | flag) == -1, "Not wanted and set: clear it");
        require(KeepScreenOn.change(false, other) == 0, "Not wanted and not set: leave the window alone");
        require(KeepScreenOn.change(false, 0) == 0 && KeepScreenOn.change(true, 0) == 1, "A new window (after a recreation) starts without the flag");
    }

    private static void simulatorStop() {
        for (int bits = 0; bits < 256; bits++) {
            boolean[] b = new boolean[8];
            for (int i = 0; i < 8; i++) b[i] = (bits & (1 << i)) != 0;
            WorkReasons reasons = new WorkReasons(b[0], b[1], b[2], b[3], b[4], b[5]);
            boolean visible = b[6], background = b[7];
            require(reasons.keepsServiceUp(visible, background, false) == reasons.keepsServiceUp(visible, background),
                    "Not simulating: the old answer, case " + bits);
            require(reasons.keepsServiceUp(visible, background, true) == visible,
                    "Simulating: only Volt on screen keeps the service, case " + bits);
        }
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
