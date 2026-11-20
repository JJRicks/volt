package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The scooter's own battery-percent jump on What Volt has learned (ChargeSessionStore.Session.jump, Curve.jump,
 * Summary.jumpFrom, LearnedCurveChart): a jump is found and named, its percents are the jump and not "No data" gaps, and
 * a slow or fast genuine charge, a reconnect's catch-up, a change of current and a single short percent are not jumps.
 * Synthesized recordings only (ChargeJumpRecordings), never a real one.
 */
public final class LearnedJumpTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final ChargeSessionStore.Summary.Coverage JUMP = ChargeSessionStore.Summary.Coverage.JUMP,
            NONE = ChargeSessionStore.Summary.Coverage.NONE, ONE = ChargeSessionStore.Summary.Coverage.PROVISIONAL;

    private static ChargeSessionStore store() throws Exception {
        File folder = Files.createTempDirectory("learned-jump-").toFile();
        return new ChargeSessionStore(folder);
    }
    /** The stock curve's summary from these plans, recorded in order a day apart (complete when they reach 99%). */
    private static ChargeSessionStore.Summary stock(ChargeJumpRecordings.Plan... plans) throws Exception {
        ChargeSessionStore store = store();
        int day = 0;
        for (ChargeJumpRecordings.Plan plan : plans) {
            int last = (int) plan.steps.get(plan.steps.size() - 1)[0];
            ChargeJumpRecordings.record(store, ChargeSessionStore.Charger.STOCK, day++, plan, last == 99);
        }
        List<ChargeSessionStore.Summary> out = new ArrayList<>();
        for (ChargeSessionStore.Curve curve : store.snapshot().curves.values()) out.add(curve.summary());
        check(out.size() == 1, "One stock curve, not " + out.size());
        return out.get(0);
    }
    private static String kinds(ChargeSessionStore.Summary summary, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int bin = from; bin < to; bin++) out.append(bin).append(':').append(summary.bins.get(bin).coverage).append(' ');
        return out.toString().trim();
    }

    public static void main(String[] args) throws Exception {
        // 1. A stock charge from 37% at a steady 1.98 A whose percent jumps from 83% to 95%, then the finish to 100%.
        ChargeSessionStore.Summary jump = stock(ChargeJumpRecordings.jump83());
        check(jump.jumpFrom == 83 && jump.jumpTo == 95 && jump.jumpRecordings == 1, "The jump 83–95%: " + jump.jumpFrom + "–" + jump.jumpTo);
        for (int bin = 84; bin < 95; bin++) {
            ChargeSessionStore.Summary.Bin b = jump.bins.get(bin);
            // 87% and 91% were held 30 s, the learning floor: they keep their half minute (the estimates use it).
            if (bin == 87 || bin == 91) check(b.coverage == ONE && b.minutes != null && Math.abs(b.minutes - .5) < 1e-9, "A 30 s percent inside the jump keeps its minutes: " + kinds(jump, 84, 95));
            else check(b.coverage == JUMP && b.minutes == null, "Inside the jump, no minutes: the jump: " + kinds(jump, 84, 95));
        }
        check(jump.bins.get(83).coverage == ONE && jump.bins.get(95).coverage == ONE, "83% and 95% were held normally: " + kinds(jump, 82, 97));
        LearnedCurveChart chart = LearnedCurveChart.of(jump);
        check(chart.jumps() && chart.jumpFrom == 83 && chart.jumpTo == 95, "The chart names the jump");
        check(chart.gaps.toString().equals("[BELOW 0-38]") && chart.unlisted == 0, "No data only below the recording (no gap inside the jump): " + chart.gaps + " +" + chart.unlisted);
        check(chart.legend().equals(Arrays.asList(NONE, ONE, JUMP)), "Legend: no data, one recording, the jump: " + chart.legend());
        // Drawn as one jump, 84–95% (option B): the two half-minute percents inside it are the jump on the strip and draw no
        // speed, while the Summary above keeps their minutes.
        check(chart.runs.toString().equals("[NONE 0-38, PROVISIONAL 38-84, JUMP 84-95, PROVISIONAL 95-100]"), "Runs: " + chart.runs);
        check(Double.isNaN(chart.minutes[87]) && Double.isNaN(chart.minutes[91]) && chart.minutes[83] > 4 && chart.minutes[95] > 5, "No speed drawn inside the jump");
        check(chart.learned.toString().equals("[PROVISIONAL 38-84, PROVISIONAL 95-100]"), "Two learned stretches around the jump: " + chart.learned);
        check(chart.learnedFrom() == 38 && chart.learnedTo() == 100 && chart.toFullFrom == 95, "Learned 38–100%, time to full from 95% as before: " + chart.learned);

        // The same recording before this change would have read: "No data 84–87%", "No data 88–91%", "And 1 more gap".
        List<ChargeSessionStore.Summary.Bin> plain = new ArrayList<>();
        for (ChargeSessionStore.Summary.Bin b : jump.bins) plain.add(new ChargeSessionStore.Summary.Bin(b.percent, b.coverage == JUMP ? NONE : b.coverage, b.sessions, b.minutes, b.watts, b.finishCovered));
        LearnedCurveChart before = LearnedCurveChart.of(new ChargeSessionStore.Summary(jump.charger, 1, jump.finishStarts, plain));
        check(!before.jumps() && before.gaps.toString().equals("[BELOW 0-38, BETWEEN 84-87, BETWEEN 88-91]") && before.unlisted == 1, "Without the jump: three gaps inside: " + before.gaps);
        check(!chart.signature().equals(before.signature()), "The jump changes what the card shows");

        // 2. A slow genuine charge (about 9 min a percent at 1 A), with the gauge's uneven steps: one 20 s percent, then
        //    two in a row, never three. No jump.
        ChargeJumpRecordings.Plan slow = ChargeJumpRecordings.finish(new ChargeJumpRecordings.Plan().range(40, 60, 540, 1)
                .hold(60, 20, 1).range(61, 70, 540, 1).hold(70, 20, 1).hold(71, 20, 1).range(72, 95, 540, 1));
        ChargeSessionStore.Summary slowSummary = stock(slow);
        check(slowSummary.jumpFrom == -1 && slowSummary.jumpTo == -1 && slowSummary.jumpRecordings == 0, "A slow charge has no jump");
        check(!LearnedCurveChart.of(slowSummary).jumps() && !kinds(slowSummary, 0, 100).contains("JUMP"), "No jump on the strip");
        check(LearnedCurveChart.of(slowSummary).gaps.toString().contains("BETWEEN 60-61"), "A lone short percent stays no data, as before");

        // 2b. A small pack on a strong charger: every percent in 20 s at 10 A. Nothing before is held: no jump.
        check(stock(new ChargeJumpRecordings.Plan().range(30, 100, 20, 10)).jumpFrom == -1, "A genuinely fast charge has no jump");
        // 2c. Uneven but genuine: 50 s, then three of 20 s, at the same current: not three times faster than before.
        check(stock(new ChargeJumpRecordings.Plan().range(30, 60, 50, 6).each(60, new int[]{20, 20, 20}, 6).range(63, 90, 50, 6)).jumpFrom == -1,
                "Three short percents after one only 2.5 times longer: not a jump");

        // 3. A reconnect's catch-up: the phone out of reach for three minutes at 60%, then +3% at once.
        ChargeJumpRecordings.Plan catchUp = new ChargeJumpRecordings.Plan().range(40, 61, 280, 1.98).gap(180_000).range(63, 95, 280, 1.98);
        ChargeSessionStore.Summary catchUpSummary = stock(ChargeJumpRecordings.finish(catchUp));
        check(catchUpSummary.jumpFrom == -1, "A gap then +3% is not a jump");
        check(LearnedCurveChart.of(catchUpSummary).gaps.toString().contains("BETWEEN"), "The gap stays no data: " + LearnedCurveChart.of(catchUpSummary).gaps);
        // 3b. ... or a catch-up that the gauge walks through, a percent each 10 s, right after the reconnect.
        ChargeJumpRecordings.Plan walk = new ChargeJumpRecordings.Plan().range(40, 61, 280, 1.98).gap(180_000)
                .each(61, new int[]{10, 10, 10, 10, 10}, 1.98).range(66, 95, 280, 1.98);
        check(stock(ChargeJumpRecordings.finish(walk)).jumpFrom == -1, "A walked catch-up after a reconnect is not a jump");
        // 3c. The current changed: the percents race while the current is half what it was. Not steady: no jump.
        ChargeJumpRecordings.Plan changed = ChargeJumpRecordings.finish(new ChargeJumpRecordings.Plan().range(37, 84, 280, 1.98).each(84, ChargeJumpRecordings.JUMP_84_TO_94, .9));
        check(stock(changed).jumpFrom == -1, "A jump at a different current is not this jump");
        // 3d. Not held normally again: the recording stops 20 s into 95%.
        ChargeJumpRecordings.Plan stopped = new ChargeJumpRecordings.Plan().range(37, 84, 280, 1.98).each(84, ChargeJumpRecordings.JUMP_84_TO_94, 1.98).hold(95, 20, 1.98);
        check(stock(stopped).jumpFrom == -1, "A jump with no percent held after it is not named");

        // 4. A second recording whose jump starts at 86%: the widest jump, 83–95%, named once; 84–86% learned from the second.
        ChargeSessionStore.Summary two = stock(ChargeJumpRecordings.jump83(), ChargeJumpRecordings.jump86());
        check(two.jumpFrom == 83 && two.jumpTo == 95 && two.jumpRecordings == 2, "Two recordings: the widest jump 83–95%: " + two.jumpFrom + "–" + two.jumpTo + " " + two.jumpRecordings);
        check(two.bins.get(84).coverage == ONE && two.bins.get(85).coverage == ONE && two.bins.get(86).coverage == ONE, "84–86%: the second recording's minutes: " + kinds(two, 83, 95));
        check(two.bins.get(88).coverage == JUMP && two.bins.get(93).coverage == JUMP, "Both jumped 88% and 93%: " + kinds(two, 83, 95));
        LearnedCurveChart twoChart = LearnedCurveChart.of(two);
        boolean inside = false;
        for (LearnedCurveChart.Gap gap : twoChart.gaps) inside |= gap.from < 95 && gap.to > 83;
        check(!inside && twoChart.unlisted == 0 && twoChart.jumpFrom == 83 && twoChart.jumpTo == 95, "No gap inside the jump: " + twoChart.gaps);
        // 84–86% stay learned (the second recording held them normally); 87–94% are one jump; 87% keeps its half minute in the data.
        check(twoChart.runs.toString().equals("[NONE 0-38, PROVISIONAL 38-51, ROBUST 51-84, PROVISIONAL 84-87, JUMP 87-95, ROBUST 95-100]"), "Two recordings drawn: " + twoChart.runs);
        check(two.bins.get(87).minutes != null && Double.isNaN(twoChart.minutes[87]) && twoChart.minutes[86] > 4, "87%: data kept, drawn as the jump; 86%: learned");

        // 4b. Recordings that disagree inside the jump (#595 review): A's jump starts with an 84% that took 40 s, B held
        //     84% normally (about 4.7 min). 84% is DISAGREEING, not the jump: it keeps its own "Recordings disagree"
        //     words and splits the jump stretch on the strip; the jump around it stays.
        ChargeJumpRecordings.Plan slowStart = ChargeJumpRecordings.finish(new ChargeJumpRecordings.Plan().range(37, 84, 280, 1.98)
                .each(84, new int[]{40, 20, 20, 20, 20, 20, 10, 20, 20, 20, 20}, 1.98));
        ChargeSessionStore.Summary split = stock(slowStart, ChargeJumpRecordings.jump86());
        check(split.jumpFrom == 83 && split.jumpTo == 95, "Disagreeing pair: the jump 83–95%: " + split.jumpFrom + "–" + split.jumpTo);
        check(split.bins.get(84).coverage == ChargeSessionStore.Summary.Coverage.DISAGREEING && split.bins.get(84).sessions == 2,
                "84%: two recordings disagree, kept as DISAGREEING: " + kinds(split, 83, 95));
        LearnedCurveChart splitChart = LearnedCurveChart.of(split);
        check(splitChart.gaps.toString().contains("DISAGREE 84-85"), "Recordings disagree 84–85% stays in the words: " + splitChart.gaps);
        check(splitChart.runs.toString().contains("DISAGREEING 84-85") && splitChart.runs.toString().contains("JUMP 87-95"),
                "The strip: the disagreement, then the jump: " + splitChart.runs);

        // Combining the recordings' jumps: overlapping ones join (widest), the group most recordings share wins.
        check(Arrays.toString(ChargeSessionStore.Curve.jump(Arrays.asList(new int[]{83, 95}, new int[]{86, 95}, new int[]{40, 45}))).equals("[83, 95, 2]"), "Widest of the shared group");
        check(Arrays.toString(ChargeSessionStore.Curve.jump(Arrays.asList(new int[]{40, 45}, new int[]{83, 95}))).equals("[83, 95, 1]"), "A tie: the higher jump");
        check(Arrays.toString(ChargeSessionStore.Curve.jump(Arrays.asList(new int[]{90, 95}, new int[]{83, 90}))).equals("[90, 95, 1]"), "Touching jumps are two jumps");
        check(ChargeSessionStore.Curve.jump(new ArrayList<>()) == null, "No jumps: none");

        // A summary with a jump but nothing usable inside the chart's reach still words it, and an old summary has none.
        check(!LearnedCurveChart.of(new ChargeSessionStore.Summary(ChargeSessionStore.Charger.STOCK, 1, new ArrayList<>(), plain)).jumps(), "Old summaries: no jump");
        System.out.println("Learned jump tests passed: " + checks + " checks");
    }
}
