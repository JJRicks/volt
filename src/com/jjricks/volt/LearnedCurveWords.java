package com.jjricks.volt;

import java.util.Locale;

/** The catalog's words for Charge history &amp; recordings' What Volt has learned cards (LearnedCurveCard). */
final class LearnedCurveWords implements LearnedCurveCard.Words {
    @Override public String title(ChargeSessionStore.Charger charger) {
        return charger == ChargeSessionStore.Charger.HIGH_SPEED ? T.statsChargeHighSpeedCharger() : T.statsChargeStockCharger();
    }
    @Override public String recordings(int count) { return T.moreLearnedRecordings(count, count); }
    @Override public String axis() { return T.moreLearnedAxis(); }
    @Override public String minutes(double value) {
        boolean whole = value == 0 || value >= 1;
        return T.statsChargeDurationMinutes(whole ? Fmt.integer(Math.round(value)) : Fmt.decimal(Locale.getDefault(), value, 1));
    }
    @Override public String percent(int value) { return T.fmtPercent(Fmt.integer(value)); }
    @Override public String kind(ChargeSessionStore.Summary.Coverage kind) {
        switch (kind) {
            case PROVISIONAL: return T.moreLearnedKindOne();
            case ROBUST: return T.moreLearnedKindAgree();
            case DISAGREEING: return T.moreLearnedKindDisagree();
            default: return T.moreLearnedKindNone();
        }
    }
    @Override public String finish() { return T.moreLearnedKindFinish(); }
    @Override public String learned(int from, int to) { return T.moreLearnedSpan(Fmt.integer(from), Fmt.integer(to)); }
    @Override public String finishFrom(int from) { return T.moreLearnedFinishFrom(Fmt.integer(from)); }
    @Override public String gap(LearnedCurveChart.Gap gap) {
        switch (gap.where) {
            case BELOW: return T.moreLearnedGapBelow(Fmt.integer(gap.to));
            case ABOVE: return T.moreLearnedGapAbove(Fmt.integer(gap.from));
            case DISAGREE: return T.moreLearnedGapDisagree(Fmt.integer(gap.from), Fmt.integer(gap.to));
            default: return T.moreLearnedGapBetween(Fmt.integer(gap.from), Fmt.integer(gap.to));
        }
    }
    @Override public String more(int count) { return T.moreLearnedGapMore(count, count); }
    @Override public String toFull(int from, double minutes) {
        long whole = Math.round(minutes);
        String duration = whole >= 60 ? T.statsChargeDurationHours(Fmt.integer(whole / 60), Fmt.integer(whole % 60)) : T.statsChargeDurationMinutes(Fmt.integer(whole));
        return T.moreLearnedToFull(Fmt.integer(from), duration);
    }
    @Override public String spokenRun(ChargeSessionStore.Summary.Coverage kind, int from, int to) {
        return T.moreLearnedSpokenRun(kind(kind), Fmt.integer(from), Fmt.integer(to));
    }
    @Override public String emptyTitle() { return T.moreLearnedEmptyTitle(); }
    @Override public String empty() { return T.moreLearnedEmpty(); }
    @Override public String unusable() { return T.moreLearnedNoneUsable(); }
}
