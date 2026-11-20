package com.jjricks.volt;

/** Fresh battery-side charging measurements, model defaults and optional personal charge curves. */
public final class ChargingEstimate {
    /**
     * Bump whenever the prediction algorithm/reference changes; independent of app versions. It names every layer since
     * v11: stitched estimates (stitched-v1), normal-temperature learning (temps-v1), learned usable capacity (ucap-v1) and
     * the learned charger match (chg-match-v1). At most 64 characters: every recording sample stores it (Estimate).
     */
    public static final String ESTIMATOR_VERSION = "ut5-fast-cvpoint-v11.stitched-v1.temps-v1.ucap-v1.chg-match-v1";
    public static final double MINIMUM_ETA_CURRENT_A = 0.05;
    /** Short UI label when measured current is too low for a time estimate. */
    public static final String LOW_CURRENT = "Low current";

    private ChargingEstimate() {}

    /** Preserve exactly the result used at capture time, including unavailable predictions. */
    public static ChargeSessionStore.Estimate recordingSnapshot(Result result) {
        if(result==null)return new ChargeSessionStore.Estimate(ESTIMATOR_VERSION,null,null,null,null,null,0);
        return new ChargeSessionStore.Estimate(ESTIMATOR_VERSION,result.remainingMinutesToFull,result.remainingMinutesToLimit,result.targetPercent,
                result.learnedToFull?ChargeSessionStore.Source.PERSONAL:result.referenceToFull?referenceSource(result):ChargeSessionStore.Source.CURRENT_RATE,
                result.learnedToLimit?ChargeSessionStore.Source.PERSONAL:result.referenceToLimit?referenceSource(result):ChargeSessionStore.Source.CURRENT_RATE,
                result.learnedSessions);
    }

    private static ChargeSessionStore.Source referenceSource(Result result) {
        return result.referenceCharger==ChargeSessionStore.Charger.HIGH_SPEED
                ?ChargeSessionStore.Source.FAST_REFERENCE:ChargeSessionStore.Source.STOCK_REFERENCE;
    }

    /**
     * A reading whose freshness has already been checked by its caller. Voltage and current must
     * share the same positive source-frame timestamp; adjacent frames are not interchangeable.
     * observedMs may use any consistent clock and is never interpreted as a duration or date here.
     */
    public static final class Value {
        public final Double number;
        public final boolean fresh;
        public final long observedMs;

        public Value(Double number, boolean fresh, long observedMs) {
            this.number = number;
            this.fresh = fresh;
            this.observedMs = observedMs;
        }
    }

    public static final class Result {
        public final boolean charging;
        public final Double inputWatts, inputAmps;
        public final Double remainingMinutesToLimit, remainingMinutesToFull, targetPercent;
        /** Full diagnostic wording for tooltips and accessibility; {@code label} is the short UI state. */
        public final String status, label;
        /**
         * The short UI state for display, rendered when shown. {@code label} keeps its English, which
         * code compares (LOW_CURRENT, still an id: this message is that English verbatim until B13
         * gives it a display label).
         */
        public final Msg labelMsg;
        public final boolean learnedToLimit, learnedToFull;
        public final boolean referenceToLimit, referenceToFull;
        public final ChargeSessionStore.Charger referenceCharger;
        public final int learnedSessions;
        /** A paired-voltage stock reference takes priority over SOC-only personal bulk timing. */
        public final boolean stockVoltageToFull;
        /**
         * Minutes for Charge done by, before its separate safety buffer. Use the same
         * estimate shown on the dashboard rather than stacking pessimistic model assumptions.
         * The one exception: while a high-speed charge's own taper places the shown time
         * (ut5-fast-taper-v8), a longer personal time that v7 would show for the same reading
         * (past charges that still match it, or its briefly held personal finish) plans that
         * target instead, uncapped by the own taper's time to 100% at a lower limit. The own
         * taper can take up to about three minutes to see its decay stop, and Charge done by
         * programs its start once, so planning keeps the longer evidence; the display keeps the
         * own taper. Everywhere else these equal the displayed minutes.
         */
        public final Double planningMinutesToLimit, planningMinutesToFull;
        /** The standard-charger pack-voltage model supplied the bulk-stage estimate. */
        public final boolean stockPackModel;
        /**
         * Minutes a percent past charges took over the last LIMIT_RATE_SPAN percent to a limit below 100%, set only
         * where the time to the limit shown is those charges' own time, never capped, and the time to 100% is not
         * theirs (the current rate or a reference); null everywhere else. minutesToFullBesideLimit carries the time to
         * the limit on to 100% at this rate.
         */
        public final Double limitRate;
        /**
         * A learned time here rests on provisional evidence: a percent only one past charge covers, or a join of
         * per-percent times with a completed charge's own finish (withObservedCurve). Charge done by then plans with
         * the longer of it and the estimate without past charges (planningMinutesToLimit, planningMinutesToFull).
         */
        public final boolean provisional;
        /** withObservedCurve's sentence naming what a provisional estimate used, which withHistory's status leads with. */
        final String provisionalNote;
        /**
         * The time to 100% itself rests on provisional evidence (not only the time to the limit): a held finish
         * (withHeldPersonal) is remembered only without it.
         */
        final boolean provisionalFull;
        private final Ut5MaxStockVoltageReference.Match stockVoltageMatch;

        private Result(
                boolean charging,
                Double inputWatts,
                Double inputAmps,
                Double remainingMinutesToLimit,
                Double remainingMinutesToFull,
                Double targetPercent,
                CharSequence label,
                String status) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,false,false,0);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,false,false);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,
                    referenceToLimit||referenceToFull?ChargeSessionStore.Charger.STOCK:null);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,null);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,stockVoltageMatch,
                    remainingMinutesToLimit,remainingMinutesToFull,false);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch,
                Double planningMinutesToLimit,Double planningMinutesToFull,boolean stockPackModel) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,stockVoltageMatch,
                    planningMinutesToLimit,planningMinutesToFull,stockPackModel,null);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch,
                Double planningMinutesToLimit,Double planningMinutesToFull,boolean stockPackModel,Double limitRate) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,stockVoltageMatch,
                    planningMinutesToLimit,planningMinutesToFull,stockPackModel,limitRate,false,null);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch,
                Double planningMinutesToLimit,Double planningMinutesToFull,boolean stockPackModel,Double limitRate,boolean provisional,String provisionalNote) {
            this(charging,inputWatts,inputAmps,remainingMinutesToLimit,remainingMinutesToFull,targetPercent,label,status,
                    learnedToLimit,learnedToFull,learnedSessions,referenceToLimit,referenceToFull,referenceCharger,stockVoltageMatch,
                    planningMinutesToLimit,planningMinutesToFull,stockPackModel,limitRate,provisional,provisionalNote,provisional);
        }

        private Result(boolean charging,Double inputWatts,Double inputAmps,Double remainingMinutesToLimit,Double remainingMinutesToFull,
                Double targetPercent,CharSequence label,String status,boolean learnedToLimit,boolean learnedToFull,int learnedSessions,
                boolean referenceToLimit,boolean referenceToFull,ChargeSessionStore.Charger referenceCharger,Ut5MaxStockVoltageReference.Match stockVoltageMatch,
                Double planningMinutesToLimit,Double planningMinutesToFull,boolean stockPackModel,Double limitRate,boolean provisional,String provisionalNote,
                boolean provisionalFull) {
            this.provisional=provisional;this.provisionalNote=provisional?provisionalNote:null;this.provisionalFull=provisional&&provisionalFull;
            this.limitRate=limitRate;
            this.planningMinutesToLimit=planningMinutesToLimit;this.planningMinutesToFull=planningMinutesToFull;
            this.stockPackModel=stockPackModel;
            this.stockVoltageMatch=stockVoltageMatch;
            this.stockVoltageToFull=stockVoltageMatch!=null;
            this.labelMsg = label == null ? null : label instanceof Msg ? (Msg) label : Msg.verbatim(label);
            this.label = labelMsg == null ? null : labelMsg.english();
            this.learnedToLimit=learnedToLimit;this.learnedToFull=learnedToFull;this.learnedSessions=learnedSessions;
            this.referenceToLimit=referenceToLimit;this.referenceToFull=referenceToFull;
            this.referenceCharger=referenceToLimit||referenceToFull?referenceCharger:null;
            this.charging = charging;
            this.inputWatts = inputWatts;
            this.inputAmps = inputAmps;
            this.remainingMinutesToLimit = remainingMinutesToLimit;
            this.remainingMinutesToFull = remainingMinutesToFull;
            this.targetPercent = targetPercent;
            this.status = status;
        }
    }

    /**
     * Reports |V × I| and |I| only with a fresh active-charging state and paired electrical values.
     * Polarity is deliberately not inferred: the scooter's current sign convention is unverified.
     * These are battery-side measurements, not charger AC input or wall power.
     *
     * Time estimates assume the current remains constant: rated Ah × missing SOC / |current|.
     * Charging taper, balancing, temperature and SOC error can increase actual time. The estimate
     * to 100% is hypothetical if a lower cutoff is configured or the cutoff is unknown.
     * A charge limit is never guessed from the model or silently replaced with 100%.
     */
    public static Result calculate(
            Value chargingState,
            Value voltageV,
            Value currentA,
            Value batteryPercent,
            Value ratedAh,
            Value chargeLimitPercent) {
        Double target =
                valid(chargeLimitPercent)
                                && chargeLimitPercent.number > 0
                                && chargeLimitPercent.number <= 100
                        ? chargeLimitPercent.number
                        : null;
        if (!valid(chargingState))
            return result(false, null, null, null, null, target, M.chargingEstimateWaitingData(), "Waiting for a fresh charging state.");
        if (chargingState.number != 1d)
            return result(false, null, null, null, null, target, M.chargingEstimateNotCharging(), "Not charging.");
        if (!valid(voltageV)
                || voltageV.number <= 0
                || !valid(currentA)
                || voltageV.observedMs <= 0
                || voltageV.observedMs != currentA.observedMs)
            return result(true, null, null, null, null, target, M.chargingEstimateWaitingReadings(), "Waiting for paired voltage and current readings.");

        double amps = Math.abs(currentA.number);
        double watts = voltageV.number * amps;
        if (!Double.isFinite(watts))
            return result(true, null, null, null, null, target, M.chargingEstimateOutOfRange(), "Charging measurements are outside a usable range.");
        if (amps < MINIMUM_ETA_CURRENT_A)
            return result(true, watts, amps, null, null, target, LOW_CURRENT, "Charging current is too low for a useful time estimate.");
        if (!valid(ratedAh) || ratedAh.number <= 0)
            return result(true, watts, amps, null, null, target, M.chargingEstimateCapacityUnknown(), "A fresh rated battery capacity is needed for a time estimate.");
        if (!valid(batteryPercent) || batteryPercent.number < 0 || batteryPercent.number > 100)
            return result(true, watts, amps, null, null, target, M.chargingEstimateWaitingBattery(), "Waiting for a fresh battery percentage.");

        Double full = minutes(ratedAh.number, 100 - batteryPercent.number, amps);
        Double limit = target == null ? null : minutes(ratedAh.number, target - batteryPercent.number, amps);
        if (full == null || (target != null && limit == null))
            return result(true, watts, amps, null, null, target, M.chargingEstimateNoEstimate(), "Charging time is outside a usable range.");
        String status = "At the current rate; taper and balancing can lengthen charging.";
        if (target == null)
            status += " Charge cutoff unknown; the 100% time assumes charging continues.";
        else if (target < 100)
            status += " The 100% time assumes a higher charge limit.";
        return result(true, watts, amps, limit, full, target, M.chargingEstimateCurrentRate(), status);
    }

    /**
     * Compose the bundled model and personal evidence independently from the original live-rate
     * baseline, then choose each target's source. An unavailable personal curve must not erase a
     * valid default or describe that default as a personal/current-rate estimate.
     */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal) {
        return forScooter(baseline,soc,ratedAh,productId,selectedCharger,personal,null,null,0,0);
    }

    /** The caller supplies a manual or confidently detected family; null remains unknown. */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs) {
        return forScooter(baseline,soc,ratedAh,productId,selectedCharger,personal,gate,scope,frameMs,nowMs,null);
    }

    /**
     * progress describes this charge's age and starting percentage; null means its start was
     * not seen, which the standard-charger model treats as a charge that has just begun.
     */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs, ChargeProgressTracker.Progress progress) {
        return forScooter(baseline,soc,ratedAh,productId,selectedCharger,personal,gate,scope,frameMs,nowMs,progress,null);
    }

    /**
     * taper is this profile's high-speed runtime state, or null. It supplies the charge's own
     * taper rate and briefly holds a personal finish when live power slips between saved charges.
     */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs, ChargeProgressTracker.Progress progress,
            FastChargeTaper taper) {
        return forScooter(baseline,soc,ratedAh,productId,selectedCharger,personal,gate,scope,frameMs,nowMs,progress,taper,null);
    }

    /**
     * slowdown is ChargeSlowdown's state for this charge, or null, which leaves every estimate exactly as
     * ut5-stock-finish-v9 made it. With a state, a UT5 MAX fast charge gets the temperature-aware layer
     * (ut5-fast-thermal-v10, withSlowdown): while the state is slowed, the time shown and Charge done by's
     * minutes; otherwise only Charge done by's (a cold stretch's slower taper, and the voltage check on bulk).
     * Every other charger and model is returned unchanged.
     */
    public static Result forScooter(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs, ChargeProgressTracker.Progress progress,
            FastChargeTaper taper, ChargeSlowdown.State slowdown) {
        Result shown=withoutSlowdown(baseline,soc,ratedAh,productId,selectedCharger,personal,gate,scope,frameMs,nowMs,progress,taper);
        return slowdown==null?shown:withSlowdown(shown,baseline,soc,ratedAh,productId,selectedCharger,personal,scope,frameMs,nowMs,slowdown);
    }

    /** The v9 estimate: everything forScooter does without a slowdown state. */
    private static Result withoutSlowdown(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeSessionStore.Curve personal,
            ChargeReferenceGate gate, String scope, long frameMs, long nowMs, ChargeProgressTracker.Progress progress,
            FastChargeTaper taper) {
        if(selectedCharger==null){if(gate!=null)gate.reset();return baseline;}
        boolean fast=selectedCharger==ChargeSessionStore.Charger.HIGH_SPEED;
        Result prior=fast
                ?withFastTaper(withFastReference(baseline,soc,ratedAh,productId,gate,scope,frameMs,nowMs),baseline,soc,ratedAh,productId,taper)
                :withStockReference(baseline,soc,ratedAh,productId,selectedCharger,gate,scope,frameMs,nowMs);
        // Below 89% the pack model replaces both the bundled bulk timing and percentage-only
        // personal history: the gauge's late catch-up varies between charges, pack voltage does not.
        Result pack=selectedCharger==ChargeSessionStore.Charger.STOCK?withStockPackModel(prior,baseline,soc,ratedAh,productId,progress):null;
        if (pack!=null) return pack;
        // ut5-fast-taper-v8: in the high-speed taper, once FastChargeTaper.ownTaper holds (still
        // tapering; at least three decay points spanning FIT_FULL minutes with a usable fit; the
        // newest no older than RECENT_MS; the points of the last RECENT_FIT minutes, at least three
        // and fitted alone, giving a tau at most tau() x (1 + SLOWER); and none of those falling
        // from the one before faster than STEP_TAU and by at least STEP_AMPS), voltage and current
        // place the finish instead of the percentage-indexed reference or past charges. Where the
        // gauge sits in the taper varies between charges, the decay does not, so the estimate stops
        // switching between those sources as the gauge steps. Only the time to 100% changes: a
        // lower cutoff keeps v7's evidence, including a personal time to that cutoff
        // (withPersonalLimit), never longer than the time to 100%. v7's choice is made first, so a
        // plateau, a stopped, creeping or slow decay, a charger's step down faster than STEP_TAU
        // (while it is in the RECENT_FIT window), or a declining taper model returns it unchanged,
        // with v7's held personal finish kept up to date throughout. Charge done by plans with the
        // longer of the own taper and v7's personal time
        // (withPersonalPlanning), the one case where display and planning differ, because a
        // stopped decay can keep the own taper for up to RECENT_FIT minutes and a one-shot start
        // set from it then cannot be corrected.
        Result v7=withHistory(prior,baseline,soc,ratedAh,productId,selectedCharger,personal,gate,taper,nowMs);
        if (fast&&taper!=null&&baseline!=null&&baseline.charging&&baseline.inputAmps!=null&&baseline.inputWatts!=null
                &&baseline.inputAmps>=MINIMUM_ETA_CURRENT_A&&productId==Ut5MaxFastChargeReference.PRODUCT_ID
                &&valid(soc)&&valid(ratedAh)&&soc.number<100
                &&taper.ownTaper(baseline.inputWatts/baseline.inputAmps,baseline.inputAmps,nowMs)) {
            Result own=withFastTaper(withoutFullReference(prior,baseline),baseline,soc,ratedAh,productId,taper);
            if(own!=null&&own.referenceToFull)return withPersonalPlanning(withPersonalLimit(own,baseline,soc,selectedCharger,personal),v7);
        }
        return withCvPoint(v7,baseline,soc,ratedAh,productId,selectedCharger);
    }

    /**
     * ut5-fast-cvpoint-v11: before the charger's voltage limit, at a normal current level, where past charges do
     * not set the time to 100% (a fresh install, or history that does not cover this reading), the time comes from
     * FastChargeTaper.cvPoint ahead of the 72%-anchored percentage table: from pack voltage it places where the
     * limit will come, so a top-up gets the BMS's 80% step to 8 A that the table (fitted on a deep charge) lacks.
     * Past charges' time to a lower limit is kept, up to the time to 100%. A charge's own taper (forScooter) and
     * past charges keep their priority; the standard charger, other models and anything cvPoint declines are
     * unchanged.
     */
    private static Result withCvPoint(Result r,Result baseline,Value soc,Value ratedAh,int productId,ChargeSessionStore.Charger selectedCharger) {
        if(selectedCharger!=ChargeSessionStore.Charger.HIGH_SPEED||productId!=Ut5MaxFastChargeReference.PRODUCT_ID||r==null||r.remainingMinutesToFull==null
                ||r.learnedToFull||baseline==null||!baseline.charging||baseline.inputAmps==null||baseline.inputWatts==null
                ||baseline.inputAmps<MINIMUM_ETA_CURRENT_A||!valid(soc)||!valid(ratedAh))return r;
        double amps=baseline.inputAmps;
        FastChargeTaper.CvPoint cv=FastChargeTaper.cvPoint(soc.number,baseline.inputWatts/amps,amps,ratedAh.number);
        if(cv==null)return r;
        Double target=r.targetPercent,limit=r.remainingMinutesToLimit;
        boolean personalLimit=target!=null&&target<100&&r.learnedToLimit&&limit!=null;
        if(personalLimit)limit=Math.min(limit,cv.full);
        else if(target!=null)limit=cv.minutesTo(target);
        String status="Experimental UT5 MAX high-speed estimate from pack voltage and charging current: constant current until the charger's voltage limit, "
                +"with the battery's step down at 80% when the limit comes later, then its observed taper. ";
        if(personalLimit)status+="Time to the limit uses personal history, up to the time to 100%. ";
        status+="Time to displayed 100%; balancing may continue."+cutoffNote(target);
        return new Result(r.charging,r.inputWatts,r.inputAmps,limit,cv.full,target,personalLimit?r.labelMsg:highSpeedReferenceLabel(),status,
                personalLimit,false,personalLimit?r.learnedSessions:0,target!=null&&!personalLimit,true,ChargeSessionStore.Charger.HIGH_SPEED,null,
                limit,cv.full,false,personalLimit&&same(limit,r.remainingMinutesToLimit)?r.limitRate:null);
    }

    /**
     * ut5-fast-thermal-v10 (ChargeSlowdown; docs/validation/fast-charge-slowdown.md), for a UT5 MAX fast charge
     * only: shown (the v9 estimate) comes back unchanged for anything else, or without a usable live rate, battery
     * level, rated capacity or time to 100%.
     *
     * While slowed, the time shown is the estimate at the expected current plus the extra minutes the rest of the
     * bulk stage (to the 80% step, to 85% inside the 8 A stage, or to a lower limit) takes at the current predicted
     * for it (extraBulkMinutes): for a cold battery the BMS's cold levels as it warms at WARM_SHOWN, never below
     * the live current; for a hot or unexplained slowdown the live current, with no recovery assumed. The estimate
     * at the expected current is FastChargeTaper's voltage model with the pack voltage moved by PACK_OHMS for the
     * change in current (the v9 estimate re-run at that current where the model has no answer); a lower limit
     * keeps the re-run's time there, up to the time to 100%. Charge done by plans with the longer of that and the
     * same with the battery warming at WARM_PLAN from the bottom of its reading, plus COLD_TAIL_MINUTES once this
     * charge was slowed cold.
     *
     * Outside a slowed stretch the time shown stays v9's. Charge done by adds COLD_TAIL_MINUTES after a
     * cold-slowed stretch, and in the bulk stage at full current where the bundled reference (not past charges)
     * sets the time to 100%, plans with the longer of it and the voltage model (withVoltagePlanning).
     * Per reading this costs a few comparisons, plus one more pure estimate while slowed.
     */
    private static Result withSlowdown(Result shown,Result baseline,Value soc,Value ratedAh,int productId,
            ChargeSessionStore.Charger selectedCharger,ChargeSessionStore.Curve personal,String scope,long frameMs,long nowMs,
            ChargeSlowdown.State slowdown) {
        if(selectedCharger!=ChargeSessionStore.Charger.HIGH_SPEED||productId!=Ut5MaxFastChargeReference.PRODUCT_ID||shown==null
                ||shown.remainingMinutesToFull==null||baseline==null||!baseline.charging||baseline.inputAmps==null||baseline.inputWatts==null
                ||baseline.inputAmps<MINIMUM_ETA_CURRENT_A||!valid(soc)||!valid(ratedAh)||!FastChargeTaper.calibratedPack(ratedAh.number))return shown;
        double amps=baseline.inputAmps,volts=baseline.inputWatts/amps,level=soc.number,rated=ratedAh.number;
        Double target=shown.targetPercent;
        boolean toFull=target!=null&&target==100;
        if(!slowdown.slowed){
            Result r=shown;
            if(slowdown.coldTail&&r.planningMinutesToFull!=null){
                double full=r.planningMinutesToFull+ChargeSlowdown.COLD_TAIL_MINUTES;
                r=planned(r,toFull?Double.valueOf(full):r.planningMinutesToLimit,full);
            }
            return withVoltagePlanning(r,level,volts,amps,rated);
        }
        double expected=ChargeSlowdown.expectedAmps(level),end=slowedBulkEnd(level,volts,amps,rated);
        double nominalVolts=volts+ChargeSlowdown.PACK_OHMS*(expected-amps);
        // The same pure estimate at the expected current: no gate, progress or taper state, so nothing is changed.
        Result rerun=withoutSlowdown(calculate(new Value(1d,true,1),new Value(nominalVolts,true,1),new Value(expected,true,1),soc,ratedAh,
                new Value(target,true,1)),soc,ratedAh,productId,selectedCharger,personal,null,scope,frameMs,nowMs,null,null);
        if(rerun==null||rerun.remainingMinutesToFull==null)return shown;
        // Past charges that still cover the reading at the expected current keep their time and label; otherwise the
        // bundled voltage model sets it, labelled and recorded as the bundled reference.
        boolean historyFull=rerun.learnedToFull;
        Double voltage=historyFull?null:depthAwareMinutes(level,nominalVolts,expected,rated);
        double nominalFull=voltage==null?rerun.remainingMinutesToFull:voltage;
        Double nominalLimit=rerun.remainingMinutesToLimit==null||voltage==null?rerun.remainingMinutesToLimit
                :toFull?Double.valueOf(nominalFull):Double.valueOf(Math.min(nominalFull,rerun.remainingMinutesToLimit));
        boolean historyLimit=nominalLimit!=null&&(toFull?historyFull:rerun.learnedToLimit);
        // The cold model (the BMS's levels as the battery warms) applies only where a cold level explains the live
        // current; any other slowdown, cold or not, is assumed to last (review of #561).
        boolean cold=ChargeSlowdown.coldExplains(slowdown.batteryTempC,amps,expected);
        Double temperature=cold?slowdown.batteryTempC:null;
        double fullShown=nominalFull+extraBulkMinutes(level,end,amps,expected,temperature,ChargeSlowdown.WARM_SHOWN,.5,rated);
        double fullPlan=Math.max(fullShown,nominalFull+extraBulkMinutes(level,end,amps,expected,temperature,ChargeSlowdown.WARM_PLAN,0,rated)
                +(slowdown.coldTail?ChargeSlowdown.COLD_TAIL_MINUTES:0));
        Double limitShown=shown.remainingMinutesToLimit,limitPlan=shown.planningMinutesToLimit;
        if(nominalLimit!=null){
            double limitEnd=Math.min(end,target);
            limitShown=Math.min(fullShown,nominalLimit+extraBulkMinutes(level,limitEnd,amps,expected,temperature,ChargeSlowdown.WARM_SHOWN,.5,rated));
            limitPlan=Math.max(limitShown,Math.min(fullPlan,
                    nominalLimit+extraBulkMinutes(level,limitEnd,amps,expected,temperature,ChargeSlowdown.WARM_PLAN,0,rated)));
        }
        // A slowdown never makes Charge done by shorter than the same reading's estimate without it, nor the time shown,
        // except where the cold model applies (a BMS cold level explains the live current) and predicts it rising as the
        // battery warms (review of #561). There the estimate without a slowdown assumes the live current lasts, and
        // drops by up to 25 minutes at each step up; Charge done by still keeps the longer time.
        if(!cold){
            fullShown=Math.max(fullShown,shown.remainingMinutesToFull);
            if(limitShown!=null&&shown.remainingMinutesToLimit!=null)limitShown=Math.max(limitShown,shown.remainingMinutesToLimit);
        }
        fullPlan=Math.max(fullPlan,Math.max(fullShown,shown.planningMinutesToFull==null?fullShown:shown.planningMinutesToFull));
        if(limitPlan!=null&&limitShown!=null)limitPlan=Math.max(limitPlan,limitShown);
        if(limitPlan!=null&&shown.planningMinutesToLimit!=null)limitPlan=Math.max(limitPlan,shown.planningMinutesToLimit);
        String status=(shown.status==null?"":shown.status)+(cold
                ?" Charging is slowed by a cold battery; the time assumes the current rises in steps as the battery warms."
                :" Charging is slowed; the time assumes the current stays this low to the end of the bulk stage.");
        boolean learned=historyFull||historyLimit;
        // limitRate is only for a time to the limit that is past charges' own, uncapped; this one is not.
        return new Result(shown.charging,shown.inputWatts,shown.inputAmps,limitShown,fullShown,target,learned?rerun.labelMsg:highSpeedReferenceLabel(),status,
                historyLimit,historyFull,learned?rerun.learnedSessions:0,limitShown!=null&&!historyLimit,!historyFull,ChargeSessionStore.Charger.HIGH_SPEED,
                null,limitPlan,fullPlan,false,null);
    }

    /**
     * Where the slowed estimate's bulk stage ends: the BMS's 80% step below it; in the 8 A stage, where this reading
     * meets the charger's voltage limit at the live current (FastChargeTaper's headroom table), since the live
     * current is assumed to last, not to recover, until then.
     */
    private static double slowedBulkEnd(double soc,double volts,double amps,double ratedAh) {
        if(soc<ChargeSlowdown.STEP_SOC)return ChargeSlowdown.STEP_SOC;
        Double ah=FastChargeTaper.bulkAh(FastChargeTaper.CV_SET-FastChargeTaper.CV_DROP*amps-volts);
        return ah==null?ChargeSlowdown.STEP_END_SOC:Math.min(100,soc+100*ah/ratedAh);
    }

    /** The label of every bundled high-speed estimate (the reference, the voltage model and cvPoint). */
    private static Msg highSpeedReferenceLabel(){return M.chargingEstimateHighSpeedReference();}

    /** Below this level and from this current, withVoltagePlanning checks a bundled reference's bulk time against the voltage model. */
    private static final double VOLTAGE_PLAN_SOC=72,VOLTAGE_PLAN_AMPS=9;

    /**
     * r with Charge done by planning the longer of its time to 100% and FastChargeTaper's voltage model, in the bulk
     * stage (below VOLTAGE_PLAN_SOC, at VOLTAGE_PLAN_AMPS or more) where the bundled reference sets that time, not
     * past charges. The reference's percentage table runs short on charges whose voltage limit comes later; the
     * voltage model reads that from the pack. The time shown is unchanged; a lower limit's planning too.
     */
    private static Result withVoltagePlanning(Result r,double soc,double volts,double amps,double ratedAh) {
        if(r.learnedToFull||!r.referenceToFull||soc>=VOLTAGE_PLAN_SOC||amps<VOLTAGE_PLAN_AMPS||r.planningMinutesToFull==null)return r;
        Double voltage=depthAwareMinutes(soc,volts,amps,ratedAh);
        if(voltage==null||voltage<=r.planningMinutesToFull)return r;
        boolean toFull=r.targetPercent!=null&&r.targetPercent==100;
        return planned(r,toFull&&r.planningMinutesToLimit!=null?voltage:r.planningMinutesToLimit,voltage);
    }

    /** The bundled voltage model's minutes to 100%: cvPoint's where it applies, else FastChargeTaper.minutes without a watched decay. */
    private static Double depthAwareMinutes(double soc,double volts,double amps,double ratedAh) {
        FastChargeTaper.CvPoint cv=FastChargeTaper.cvPoint(soc,volts,amps,ratedAh);
        return cv!=null?Double.valueOf(cv.full):FastChargeTaper.minutes(volts,amps,ratedAh,null);
    }

    /** r with Charge done by's minutes replaced; everything shown is kept. */
    private static Result planned(Result r,Double planningLimit,Double planningFull) {
        return new Result(r.charging,r.inputWatts,r.inputAmps,r.remainingMinutesToLimit,r.remainingMinutesToFull,r.targetPercent,r.labelMsg,r.status,
                r.learnedToLimit,r.learnedToFull,r.learnedSessions,r.referenceToLimit,r.referenceToFull,r.referenceCharger,r.stockVoltageMatch,
                planningLimit,planningFull,r.stockPackModel,r.limitRate);
    }

    /** Longest bulk time extraBulkMinutes counts, in minutes. */
    private static final double MAX_BULK_MINUTES=600;

    /**
     * Minutes beyond the expected current's time that charging from soc to endSoc takes at the current predicted
     * for it. Hot or unexplained (temperatureC null or at least COLD_FULL_C): the live amps last. Cold: the BMS's
     * levels (COLDEST_AMPS below COLD_LOW_C, COOL_AMPS below COLD_FULL_C, then the expected current), never below
     * amps, as the battery warms at warm C a minute from temperatureC + offset (offset 0.5: the middle of a 1 C
     * reading). Worked out per stage, so the cost is fixed; the total is capped at MAX_BULK_MINUTES.
     */
    static double extraBulkMinutes(double soc,double endSoc,double amps,double expected,Double temperatureC,double warm,double offset,double ratedAh) {
        if(!(endSoc>soc)||!(amps>0)||!(expected>0))return 0;
        double ah=ratedAh*(endSoc-soc)/100,atExpected=ah/expected*60;
        if(temperatureC==null||!Double.isFinite(temperatureC)||temperatureC>=ChargeSlowdown.COLD_FULL_C)
            return Math.min(MAX_BULK_MINUTES,ah/amps*60)-atExpected;
        double temperature=temperatureC+offset,minutes=0,left=ah;
        double[] edges={ChargeSlowdown.COLD_LOW_C,ChargeSlowdown.COLD_FULL_C},levels={ChargeSlowdown.COLDEST_AMPS,ChargeSlowdown.COOL_AMPS};
        for(int i=0;i<edges.length;i++){
            if(temperature>=edges[i])continue;
            double a=Math.min(expected,Math.max(amps,levels[i])),span=(edges[i]-temperature)/warm,delivered=a*span/60;
            if(left<=delivered)return Math.min(MAX_BULK_MINUTES,minutes+left/a*60)-atExpected;
            left-=delivered;minutes+=span;temperature=edges[i];
        }
        return Math.min(MAX_BULK_MINUTES,minutes+left/expected*60)-atExpected;
    }

    /**
     * shown (the own taper's result) with Charge done by's minutes to each target no shorter than
     * the personal time v7 shows there for the same reading: past charges that still match it, or
     * v7's held personal finish (withHistory decides both, as without the own taper). At a lower
     * limit that is past charges' time to it, not capped at the own taper's time to 100% as the
     * display is. Without such a time, or with a shorter one, planning stays the displayed time.
     */
    private static Result withPersonalPlanning(Result shown,Result v7) {
        Double limit=atLeast(shown.planningMinutesToLimit,v7.learnedToLimit?v7.remainingMinutesToLimit:null);
        Double full=atLeast(shown.planningMinutesToFull,v7.learnedToFull?v7.remainingMinutesToFull:null);
        if(same(limit,shown.planningMinutesToLimit)&&same(full,shown.planningMinutesToFull))return shown;
        return new Result(shown.charging,shown.inputWatts,shown.inputAmps,shown.remainingMinutesToLimit,shown.remainingMinutesToFull,
                shown.targetPercent,shown.labelMsg,shown.status,shown.learnedToLimit,shown.learnedToFull,shown.learnedSessions,
                shown.referenceToLimit,shown.referenceToFull,shown.referenceCharger,shown.stockVoltageMatch,limit,full,shown.stockPackModel);
    }

    /**
     * Minutes to a charge alert's level, from alert (the same estimate run with that level as its target), never
     * longer than the time to 100% shown beside it. Null when there is no alert time; an unknown time to 100%
     * bounds nothing.
     */
    public static Double minutesToAlert(Result alert,Result shown) {
        if(alert==null||alert.remainingMinutesToLimit==null)return null;
        return shown==null||shown.remainingMinutesToFull==null?alert.remainingMinutesToLimit
                :Double.valueOf(Math.min(alert.remainingMinutesToLimit,shown.remainingMinutesToFull));
    }

    /**
     * Minutes to 100% for the charging tile's reading beside a scooter's own limit. One definition, continuous in the
     * battery level (review of #445): the larger of the estimate's own time to 100% (current rate or reference) and the
     * time to the limit carried on to 100%. Carried on means: where the estimate has a limitRate (the time to the limit
     * is past charges' own time, uncapped, and the time to 100% is not theirs) and soc (the battery level the estimate
     * used) is below the limit, the time to the limit plus the percent above it at the rate those charges took over
     * the last LIMIT_RATE_SPAN percent to it (owner, Nov 12 2026), so 225 minutes from 50% to a 95% limit at five
     * minutes a percent gives 250 to 100%. A limit's past charges stop at the limit, so no learned time covers the
     * rest. That rate is fixed by the past charges, not by the level, so along a charge the carried time falls with
     * the time to the limit (review of #445 at 6805913a: a rate taken from the level to the limit rises near a slow
     * last percent). Otherwise (no limitRate, soc unknown or not below the limit) it is the time to the limit itself;
     * so a time to the limit capped at the time to 100% (a UT5 MAX charge's own taper, or the stock reference) is
     * never carried on, and the charge's own time to 100% stands. Both sides move smoothly with the level, so their
     * larger one does too: no step where they cross, and the reading is never shorter than the time to the limit
     * shown beside it. The estimate's own reading (the one the single tile, the live notification and Charge done by
     * use) never changes.
     * Beside an alert the estimate's own reading is the time to 100%, so the alert time gives way instead
     * (minutesToAlert). Without a limit below 100%, or with either time unknown, this is the estimate's own time to
     * 100%.
     */
    public static Double minutesToFullBesideLimit(Result shown,Double soc) {
        if(shown==null)return null;
        Double full=shown.remainingMinutesToFull,carried=carriedToFull(shown,soc);
        return carried==null||!(carried>full)?full:carried;
    }

    /**
     * The status spoken with minutesToFullBesideLimit's reading: when the reading is the time to the limit carried on
     * to 100%, the estimate's own status with what it says of the time to 100% it did not use (the current rate, or a
     * reference covering 100%) replaced by CARRIED_TO_FULL, which names the source used. Otherwise the reading is the
     * estimate's own time to 100% and its own status names that source, unchanged. Only withHistory's own status
     * carries a limitRate, so no other source sentence (such as a UT5 MAX charge's own taper) is ever beside it.
     */
    public static String statusBesideLimit(Result shown,Double soc) {
        if(shown==null||shown.status==null)return shown==null?null:shown.status;
        Double full=shown.remainingMinutesToFull,carried=carriedToFull(shown,soc);
        if(carried==null||!(carried>full)||learnedRateToFull(shown,soc)==null)return shown.status;
        // A limitRate comes only with withHistory's status, which then says exactly one of the two of the time to 100%.
        return shown.status.replace(FULL_AT_CURRENT_RATE,CARRIED_TO_FULL).replace(REFERENCE_FOR_OTHER,CARRIED_TO_FULL);
    }

    /** The time to the limit carried on to 100% (see minutesToFullBesideLimit); null without a limit below 100% or either time. */
    private static Double carriedToFull(Result shown,Double soc) {
        Double full=shown.remainingMinutesToFull,limit=shown.remainingMinutesToLimit,target=shown.targetPercent;
        if(full==null||limit==null||target==null||!(target>0&&target<100))return null;
        Double learned=learnedRateToFull(shown,soc);
        return learned==null?limit:learned;
    }

    /** The time to the limit learned from past charges, extended to 100% at their limitRate; null without one. */
    private static Double learnedRateToFull(Result shown,Double soc) {
        Double limit=shown.remainingMinutesToLimit,target=shown.targetPercent,rate=shown.limitRate;
        if(rate==null||limit==null||target==null||soc==null||!Double.isFinite(soc)||soc<0||!(soc<target))return null;
        double minutes=limit+(100-target)*rate;
        return Double.isFinite(minutes)&&minutes>=limit?Double.valueOf(minutes):null;
    }

    /** The percent below a limit over which past charges' rate to it is taken (limitRate). */
    static final double LIMIT_RATE_SPAN=5;

    /** Minutes a percent the curve's charges took over the last LIMIT_RATE_SPAN percent to target; null for 100%, uncovered or none. */
    private static Double limitRate(ChargeSessionStore.Curve curve,Double target) {
        if(curve==null||target==null||!(target>LIMIT_RATE_SPAN&&target<100))return null;
        ChargeSessionStore.Duration span=curve.estimate(target-LIMIT_RATE_SPAN,target);
        return span==null||!(span.minutes>0)||!Double.isFinite(span.minutes)?null:Double.valueOf(span.minutes/LIMIT_RATE_SPAN);
    }

    private static final String FULL_AT_CURRENT_RATE="Time to 100% uses the current rate. ";
    private static final String CARRIED_TO_FULL="Time to 100% carries the time to the limit on at the rate past charges took over its last 5%. ";
    private static final String REFERENCE_FOR_OTHER="Experimental UT5 MAX charger reference covers the other target. ";

    /** planned, or personal where both exist and personal is longer; an unavailable plan stays unavailable. */
    private static Double atLeast(Double planned,Double personal) {
        return planned==null||personal==null?planned:Double.valueOf(Math.max(planned,personal));
    }

    /** The v7 evidence choice after the bundled references: past charges, a held personal finish or prior. */
    private static Result withHistory(Result prior,Result baseline,Value soc,Value ratedAh,int productId,ChargeSessionStore.Charger selectedCharger,
            ChargeSessionStore.Curve personal,ChargeReferenceGate gate,FastChargeTaper taper,long nowMs) {
        boolean fast=selectedCharger==ChargeSessionStore.Charger.HIGH_SPEED;
        if (personal==null || personal.charger!=selectedCharger) return fast?withHeldPersonal(prior,soc,taper,nowMs):prior;
        Result learned=withObservedCurve(baseline,soc,personal,provisionalAllowed(productId,selectedCharger));
        if (learned==null || !learned.learnedToFull&&!learned.learnedToLimit) return fast?withHeldPersonal(prior,soc,taper,nowMs):prior;
        double planningWeight=prior.referenceToFull
                &&selectedCharger==ChargeSessionStore.Charger.STOCK
                &&productId==Ut5MaxStockChargeReference.PRODUCT_ID
                &&valid(soc)&&valid(ratedAh)
                ?Ut5MaxStockChargeReference.bulkPlanningWeight(soc.number,ratedAh.number):0;
        if (prior.stockVoltageToFull || planningWeight>0) {
            // Outside a confident voltage match, shorter SOC-only history must not
            // erase the conservative first guess. At full match weight, measured
            // voltage progress still determines the estimate; 89%+ is untouched.
            Double stockFull=Ut5MaxStockChargeReference.estimate(soc.number,100,ratedAh.number,
                    baseline.inputAmps,baseline.inputWatts,gate!=null);
            double fallback=stockFull==null?prior.remainingMinutesToFull:stockFull;
            boolean guardedHistory=learned.learnedToFull&&learned.remainingMinutesToFull<fallback&&planningWeight>0;
            boolean historyContributes=learned.learnedToFull&&(!guardedHistory||planningWeight<1);
            if(learned.learnedToFull)fallback=guardedHistory
                    ?learned.remainingMinutesToFull+(fallback-learned.remainingMinutesToFull)*planningWeight
                    :learned.remainingMinutesToFull;
            double full=prior.stockVoltageMatch==null?fallback:prior.stockVoltageMatch.blend(fallback);
            boolean personalFull=learned.learnedToFull&&!guardedHistory&&prior.stockVoltageMatch==null;
            historyContributes=historyContributes&&(prior.stockVoltageMatch==null||prior.stockVoltageMatch.weight<1);
            // Lower cutoffs retain their own evidence. Full-charge observations do not
            // establish a new SOC-to-time curve for an 80% or 95% cutoff.
            Double stockLimit=prior.targetPercent==null?null:Ut5MaxStockChargeReference.estimate(
                    soc.number,prior.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
            boolean personalLimit=prior.targetPercent!=null&&prior.targetPercent<100&&learned.learnedToLimit;
            Double limit=personalLimit?learned.remainingMinutesToLimit
                    :stockLimit==null?baseline.remainingMinutesToLimit:stockLimit;
            if(prior.targetPercent!=null&&prior.targetPercent==100)limit=full;
            else if(limit!=null)limit=Math.min(limit,full);
            boolean limitFromPersonal=prior.targetPercent!=null&&prior.targetPercent==100?personalFull:personalLimit;
            boolean referenceLimit=prior.targetPercent!=null&&!limitFromPersonal
                    &&(prior.targetPercent==100||prior.referenceToLimit||!same(limit,baseline.remainingMinutesToLimit));
            String status=personalLimit?prior.status.replace("Time to the limit uses the current rate. ","")
                    +" Time to the limit uses personal history, bounded by time to full.":prior.status;
            if(personalFull)status+=" Time to full uses longer personal history.";
            else if(historyContributes)status+=" Near the reference boundary, full time blends with personal history.";
            if(guardedHistory)status+=" Uncertain bulk timing limits how much shorter percentage-only history can reduce full time.";
            int sessions=personalFull||personalLimit||historyContributes?learned.learnedSessions:0;
            return new Result(prior.charging,prior.inputWatts,prior.inputAmps,limit,full,
                    prior.targetPercent,personalFull?learned.labelMsg:prior.labelMsg,status,
                    limitFromPersonal,personalFull,sessions,referenceLimit,!personalFull,
                    ChargeSessionStore.Charger.STOCK,prior.stockVoltageMatch);
        }
        boolean referenceFull=!learned.learnedToFull&&prior.referenceToFull;
        boolean referenceLimit=!learned.learnedToLimit&&prior.referenceToLimit;
        String status=learned.provisional?learned.provisionalNote:learned.learnedSessions==1?"Provisional personal estimate from 1 completed charge. "
                :"Personal estimate from "+learned.learnedSessions+" qualified charges. ";
        if(referenceFull||referenceLimit)status+=REFERENCE_FOR_OTHER;
        if(!learned.learnedToFull&&!referenceFull)status+=FULL_AT_CURRENT_RATE;
        if(prior.targetPercent!=null&&!learned.learnedToLimit&&!referenceLimit)status+="Time to the limit uses the current rate. ";
        status+="Time to displayed 100%; balancing may continue.";
        status+=cutoffNote(prior.targetPercent);
        if(learned.provisional)status+=PLANS_LONGER;
        // The one place a learned time to the limit is shown as it is, uncapped, beside a time to 100% that is not
        // learned: only here does limitRate let the tile carry it on to 100% (review of #445 at 6805913a).
        Double rate=learned.learnedToLimit&&!learned.learnedToFull?limitRate(personal,prior.targetPercent):null;
        Double limit=learned.learnedToLimit?learned.remainingMinutesToLimit:prior.remainingMinutesToLimit;
        // Provisional evidence plans with the longer of it and the estimate without past charges (withObservedCurve
        // compares with the current rate; prior is the same here unless a bundled reference set it).
        Double planLimit=learned.learnedToLimit?atLeast(learned.planningMinutesToLimit,learned.provisional?prior.planningMinutesToLimit:null):limit;
        // Only a finish from completed charges is held through a power dropout (withHeldPersonal): a provisional or joined
        // one is never remembered, so the hold can never hand Charge done by a provisional time without its longer-of rule.
        if(fast&&taper!=null&&learned.learnedToFull&&!learned.provisionalFull)taper.rememberPersonal(learned.remainingMinutesToFull,learned.learnedSessions,nowMs);
        else if(fast&&!learned.learnedToFull)return withHeldPersonal(new Result(prior.charging,prior.inputWatts,prior.inputAmps,
                limit,prior.remainingMinutesToFull,
                prior.targetPercent,M.chargingEstimatePastCharges(learned.learnedSessions,learned.learnedSessions),status,learned.learnedToLimit,false,learned.learnedSessions,
                referenceLimit,referenceFull,prior.referenceCharger,null,planLimit,prior.remainingMinutesToFull,false,rate,learned.provisional,learned.provisionalNote),soc,taper,nowMs);
        Double full=learned.learnedToFull?learned.remainingMinutesToFull:prior.remainingMinutesToFull;
        Double planFull=learned.learnedToFull?atLeast(learned.planningMinutesToFull,learned.provisionalFull?prior.planningMinutesToFull:null):full;
        return new Result(prior.charging,prior.inputWatts,prior.inputAmps,limit,full,
                prior.targetPercent,M.chargingEstimatePastCharges(learned.learnedSessions,learned.learnedSessions),status,learned.learnedToLimit,learned.learnedToFull,learned.learnedSessions,
                referenceLimit,referenceFull,prior.referenceCharger,null,planLimit,planFull,false,rate,learned.provisional,learned.provisionalNote);
    }

    private static Result withFastReference(Result baseline,Value soc,Value ratedAh,int productId,
            ChargeReferenceGate gate,String scope,long frameMs,long nowMs) {
        if(baseline==null||!baseline.charging||baseline.inputWatts==null||baseline.inputAmps==null
                ||baseline.remainingMinutesToFull==null||productId!=Ut5MaxFastChargeReference.PRODUCT_ID
                ||!valid(soc)||!valid(ratedAh)){if(gate!=null)gate.reset();return baseline;}
        ChargeSessionStore.Charger fast=ChargeSessionStore.Charger.HIGH_SPEED;
        String context=scope==null||scope.isEmpty()?null:scope+":"+productId+":"+fast;
        if(gate!=null&&!gate.accepts(context,fast,soc.number,baseline.inputWatts,frameMs,nowMs))return baseline;
        Double full=Ut5MaxFastChargeReference.estimate(soc.number,100,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        Double limit=baseline.targetPercent==null?null:Ut5MaxFastChargeReference.estimate(
                soc.number,baseline.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        if(full==null&&limit==null)return baseline;
        String status="Experimental UT5 MAX high-speed reference from one observed charge. ";
        if(soc.number<72)status+="Bulk time uses rated Ah and live current, followed by the observed taper. ";
        if(full==null)status+="Time to 100% uses the current rate. ";
        if(limit==null&&baseline.targetPercent!=null)status+="Time to the limit uses the current rate. ";
        status+="Time to displayed 100%; balancing may continue."+cutoffNote(baseline.targetPercent);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                limit==null?baseline.remainingMinutesToLimit:limit,full==null?baseline.remainingMinutesToFull:full,
                baseline.targetPercent,highSpeedReferenceLabel(),status,false,false,0,limit!=null,full!=null,ChargeSessionStore.Charger.HIGH_SPEED);
    }

    /**
     * High-speed time from pack voltage and current where the SOC-indexed reference declined: the
     * gauge position of the charger's voltage limit varies between charges, the electrical taper
     * does not. Only a current-rate time to 100% is replaced. A lower cutoff keeps its own
     * evidence (or the current rate), never longer than the time to 100%.
     */
    private static Result withFastTaper(Result prior,Result baseline,Value soc,Value ratedAh,int productId,FastChargeTaper taper) {
        if(prior==null||prior.referenceToFull||baseline==null||!baseline.charging||baseline.inputAmps==null||baseline.inputWatts==null
                ||baseline.remainingMinutesToFull==null||baseline.inputAmps<MINIMUM_ETA_CURRENT_A||productId!=Ut5MaxFastChargeReference.PRODUCT_ID||!valid(soc)||soc.number>=100||!valid(ratedAh))return prior;
        double amps=baseline.inputAmps,volts=baseline.inputWatts/amps;
        Double full=FastChargeTaper.minutes(volts,amps,ratedAh.number,taper);
        if(full==null)return prior;
        Double target=prior.targetPercent,limit=prior.remainingMinutesToLimit;
        boolean referenceLimit=prior.referenceToLimit;
        if(target!=null&&target==100){limit=full;referenceLimit=true;}
        else if(limit!=null)limit=Math.min(limit,full);
        String status="Experimental UT5 MAX high-speed estimate from pack voltage and charging current: constant current until the charger's voltage limit, then its observed taper. ";
        if(target!=null&&target<100&&!prior.referenceToLimit)status+="Time to the limit uses the current rate, up to the time to 100%. ";
        status+="Time to displayed 100%; balancing may continue."+cutoffNote(target);
        return new Result(prior.charging,prior.inputWatts,prior.inputAmps,limit,full,target,highSpeedReferenceLabel(),status,
                false,false,0,referenceLimit,true,ChargeSessionStore.Charger.HIGH_SPEED);
    }

    /**
     * prior with its time to 100% returned to the live current rate, so withFastTaper replaces it
     * from voltage and current. A lower cutoff keeps prior's evidence and source.
     */
    private static Result withoutFullReference(Result prior,Result baseline) {
        return new Result(prior.charging,prior.inputWatts,prior.inputAmps,prior.remainingMinutesToLimit,baseline.remainingMinutesToFull,
                prior.targetPercent,prior.labelMsg,prior.status,false,false,0,prior.referenceToLimit,false,prior.referenceCharger);
    }

    /**
     * own (the charge's own taper to 100%) with the personal time to a lower cutoff that v7 shows
     * there: past charges' time to an 80% or 95% cutoff, bounded by the time to 100%, with its
     * source, sessions and label. Without one, own keeps its reference or current-rate cutoff.
     */
    private static Result withPersonalLimit(Result own,Result baseline,Value soc,ChargeSessionStore.Charger selectedCharger,
            ChargeSessionStore.Curve personal) {
        if(own.targetPercent==null||own.targetPercent>=100||personal==null||personal.charger!=selectedCharger)return own;
        Result learned=withObservedCurve(baseline,soc,personal,provisionalAllowed(Ut5MaxFastChargeReference.PRODUCT_ID,selectedCharger));
        if(learned==null||!learned.learnedToLimit||learned.remainingMinutesToLimit==null)return own;
        int sessions=learned.learnedSessions;
        String status="Time to the limit uses personal history from "+sessions+" completed high-speed charge"+(sessions==1?"":"s")
                +", up to the time to 100%. Time to 100% uses pack voltage, charging current and this charge's own taper. "
                +"Time to displayed 100%; balancing may continue."+cutoffNote(own.targetPercent);
        return new Result(own.charging,own.inputWatts,own.inputAmps,Math.min(learned.remainingMinutesToLimit,own.remainingMinutesToFull),
                own.remainingMinutesToFull,own.targetPercent,learned.labelMsg,status,
                true,false,sessions,false,true,ChargeSessionStore.Charger.HIGH_SPEED);
    }

    /**
     * A personal finish shown moments ago keeps counting down rather than jumping to a fallback.
     * Only a dropped power match is bridged: without a usable live estimate (low current, missing
     * readings) nothing is held, so Charge done by never receives a duration the live check refused.
     */
    private static Result withHeldPersonal(Result prior,Value soc,FastChargeTaper taper,long nowMs) {
        double[] held=taper==null||prior==null||prior.learnedToFull||!prior.charging||prior.remainingMinutesToFull==null
                ||prior.inputAmps==null||prior.inputAmps<MINIMUM_ETA_CURRENT_A||!valid(soc)||soc.number>=100?null:taper.heldPersonal(nowMs);
        if(held==null)return prior;
        int sessions=(int)held[1];
        Double limit=prior.targetPercent!=null&&prior.targetPercent==100?Double.valueOf(held[0]):prior.remainingMinutesToLimit;
        boolean personalLimit=prior.targetPercent!=null&&prior.targetPercent==100||prior.learnedToLimit;
        String status="Holding the personal finish estimate from "+sessions+" completed high-speed charge"+(sessions==1?"":"s")
                +" while live power moves between saved charges. Time to displayed 100%; balancing may continue."+cutoffNote(prior.targetPercent);
        return new Result(prior.charging,prior.inputWatts,prior.inputAmps,limit,held[0],prior.targetPercent,
                M.chargingEstimatePastCharges(sessions,sessions),status,personalLimit,true,sessions,
                !personalLimit&&prior.referenceToLimit,false,prior.referenceCharger);
    }

    private static Result withStockReference(Result baseline, Value soc, Value ratedAh, int productId,
            ChargeSessionStore.Charger selectedCharger, ChargeReferenceGate gate, String scope, long frameMs, long nowMs) {
        if (baseline==null || !baseline.charging || baseline.inputWatts==null || baseline.inputAmps==null
                || baseline.remainingMinutesToFull==null || productId!=Ut5MaxStockChargeReference.PRODUCT_ID
                || !valid(soc) || !valid(ratedAh)) {
            if(gate!=null)gate.reset(); return baseline;
        }
        String context=scope==null||scope.isEmpty()?null:scope+":"+productId+":"+selectedCharger;
        if(gate!=null&&!gate.accepts(context,soc.number,baseline.inputWatts,frameMs,nowMs))return baseline;
        Double full=Ut5MaxStockChargeReference.estimate(soc.number,100,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        Double limit=baseline.targetPercent==null?null:Ut5MaxStockChargeReference.estimate(
                soc.number,baseline.targetPercent,ratedAh.number,baseline.inputAmps,baseline.inputWatts,gate!=null);
        if(full==null&&limit==null)return baseline;
        Ut5MaxStockVoltageReference.Match voltageMatch=full==null?null:Ut5MaxStockVoltageReference.match(
                soc.number,ratedAh.number,baseline.inputAmps,baseline.inputWatts);
        if(voltageMatch!=null) {
            full=voltageMatch.blend(full);
            if(baseline.targetPercent!=null&&baseline.targetPercent==100)limit=full;
        }
        String status="Experimental UT5 MAX stock-charger estimate based on observed charges; live power is compatible. ";
        if(voltageMatch!=null)status+="Bulk progress matches pack voltage and charging current. ";
        else if(soc.number<89)status+="Bulk time uses rated Ah and current charging rate. ";
        if(Ut5MaxStockChargeReference.bulkPlanningWeight(soc.number,ratedAh.number)>0)
            status+="Uncertain full-charge timing includes up to ten extra minutes. ";
        if(limit==null&&baseline.targetPercent!=null)status+="Time to the limit uses the current rate. ";
        status+="This does not identify the charger. Time to displayed 100%; balancing may continue.";
        status+=cutoffNote(baseline.targetPercent);
        Double finalLimit=limit==null?baseline.remainingMinutesToLimit:limit;
        if(baseline.targetPercent!=null&&baseline.targetPercent==100&&full!=null)finalLimit=full;
        else if(full!=null&&finalLimit!=null&&(voltageMatch!=null
                ||Ut5MaxStockChargeReference.bulkPlanningWeight(soc.number,ratedAh.number)>0))finalLimit=Math.min(finalLimit,full);
        boolean referenceLimit=limit!=null||!same(finalLimit,baseline.remainingMinutesToLimit);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                finalLimit,full==null?baseline.remainingMinutesToFull:full,
                baseline.targetPercent,M.chargingEstimateStockReference(),status,false,false,0,referenceLimit,full!=null,
                ChargeSessionStore.Charger.STOCK,voltageMatch);
    }

    /** Null unless the admitted standard-charger reference covers this bulk-stage reading. */
    private static Result withStockPackModel(Result prior, Result baseline, Value soc, Value ratedAh, int productId,
            ChargeProgressTracker.Progress progress) {
        if (prior==null || prior==baseline || !prior.referenceToFull || prior.referenceCharger!=ChargeSessionStore.Charger.STOCK
                || productId!=Ut5MaxStockPackModel.PRODUCT_ID || !valid(soc) || !valid(ratedAh)
                || soc.number>=Ut5MaxStockPackModel.LAST_SOC || baseline.inputAmps==null || baseline.inputWatts==null
                || baseline.inputAmps<=0) return null;
        double amps=baseline.inputAmps, volts=baseline.inputWatts/amps;
        double age=progress==null?0:progress.ageMinutes, start=progress==null?Double.NaN:progress.startSoc;
        Ut5MaxStockPackModel.Estimate full=Ut5MaxStockPackModel.estimate(soc.number,100,volts,amps,age,start,ratedAh.number);
        if (full==null) return null;
        Double target=baseline.targetPercent, limit=null;
        if (target!=null) {
            Ut5MaxStockPackModel.Estimate cutoff=Ut5MaxStockPackModel.estimate(soc.number,target,volts,amps,age,start,ratedAh.number);
            if (cutoff==null) return null;
            limit=Math.min(cutoff.display,full.display);
        }
        String status="Experimental UT5 MAX stock-charger estimate from pack voltage, charging current and observed finishes. ";
        if (progress==null || !progress.startObserved && !progress.ageInferred)
            status+="Charging start not seen, so it assumes charging just began until the current trend is known. ";
        status+="Charge done by adds its safety buffer to this estimate. This does not identify the charger. "
                +"Time to displayed 100%; balancing may continue."+cutoffNote(target);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,limit,full.display,target,
                M.chargingEstimateStockReference(),status,false,false,0,target!=null,true,ChargeSessionStore.Charger.STOCK,null,
                limit,full.display,true);
    }

    private static boolean same(Double a,Double b){return a==null?b==null:a.equals(b);}

    private static String cutoffNote(Double target) {
        return target==null?" Cutoff unknown; 100% assumes charging continues."
                :target<100?" The 100% time assumes a higher charge limit.":"";
    }

    /** withUsableCapacity's last words: with ratedPlan the rated-capacity plan, else the learned value's sentence ends there. */
    private static String usableTail(boolean ratedPlan) {
        String tail=" Ah per 100%); Charge done by plans with the rated capacity.";
        return ratedPlan?tail:tail.substring(0,tail.indexOf(';'))+".";
    }

    /**
     * usable-capacity-v1, a display-only last pass over the finished estimate (forScooter's result, shown): every time
     * it shows that is still baseline's rated-capacity current-rate time (no past charges, reference or model set it)
     * is shown instead with the part below UsableCapacity.BULK_LIMIT at the usable capacity this scooter's recordings
     * taught (UsableCapacity.usable); above that level the rated capacity stays, the taper's cushion. Nothing else in
     * shown changes: Charge done by's planning minutes are exactly the estimate's without this pass, so it never plans
     * with a shorter time than before, whatever path set them. baseline is calculate()'s result forScooter was given.
     * shown itself without a learned value, at or above BULK_LIMIT, without a usable reading, or where no shown time is
     * baseline's. The label stays; the status says what was used.
     */
    public static Result withUsableCapacity(Result shown,Result baseline,Value soc,Value ratedAh,UsableCapacity.Learned learned) {
        if(shown==null||baseline==null||!baseline.charging||baseline.remainingMinutesToFull==null||baseline.inputAmps==null
                ||baseline.inputAmps<MINIMUM_ETA_CURRENT_A||!valid(soc)||!valid(ratedAh)||ratedAh.number<=0||soc.number>=UsableCapacity.BULK_LIMIT)return shown;
        Double usable=UsableCapacity.usable(learned,ratedAh.number);
        if(usable==null)return shown;
        boolean fullRate=shown.remainingMinutesToFull!=null&&shown.remainingMinutesToFull.equals(baseline.remainingMinutesToFull);
        boolean limitRate=shown.remainingMinutesToLimit!=null&&shown.remainingMinutesToLimit.equals(baseline.remainingMinutesToLimit)
                &&shown.targetPercent!=null&&shown.targetPercent.equals(baseline.targetPercent);
        Double full=fullRate?UsableCapacity.minutes(soc.number,100,baseline.inputAmps,usable,ratedAh.number):shown.remainingMinutesToFull;
        Double limit=limitRate?UsableCapacity.minutes(soc.number,baseline.targetPercent,baseline.inputAmps,usable,ratedAh.number):shown.remainingMinutesToLimit;
        if(full==null||limit==null&&shown.remainingMinutesToLimit!=null||!fullRate&&!limitRate)return shown;
        // The status names the rated capacity as Charge done by's plan only where every time it plans with is that rated
        // current-rate time: a learned or provisional time to the limit, or a provisional fill-in planned with the longer of
        // it and the rated time, plans otherwise, and its own sentence (withHistory, withObservedCurve) says so (#567 review, F01).
        boolean ratedPlan=same(shown.planningMinutesToFull,baseline.remainingMinutesToFull)
                &&(shown.planningMinutesToLimit==null||same(shown.planningMinutesToLimit,baseline.remainingMinutesToLimit));
        String status=(shown.status==null?"":shown.status)+" Below "+Math.round(UsableCapacity.BULK_LIMIT)+"% the current rate uses the charge this battery's gauge took per percent in "
                +learned.recordings+" past charge"+(learned.recordings==1?", provisionally":"s")+" ("+Math.round(usable*10)/10.0
                +usableTail(ratedPlan);
        return new Result(shown.charging,shown.inputWatts,shown.inputAmps,limit,full,shown.targetPercent,shown.labelMsg,status,
                shown.learnedToLimit,shown.learnedToFull,shown.learnedSessions,shown.referenceToLimit,shown.referenceToFull,shown.referenceCharger,
                shown.stockVoltageMatch,shown.planningMinutesToLimit,shown.planningMinutesToFull,shown.stockPackModel,shown.limitRate,
                shown.provisional,shown.provisionalNote,shown.provisionalFull);
    }

    /**
     * Learned curves may replace a usable current-rate estimate, never bypass its fresh paired-data gate. Without
     * provisional evidence: withHistory chooses it per model and charger (provisionalAllowed).
     */
    public static Result withObservedCurve(Result baseline,Value soc,ChargeSessionStore.Curve curve) {
        return withObservedCurve(baseline,soc,curve,false);
    }

    /**
     * provisional: besides today's evidence (a matching finish, then per-percent times two or more past charges agree
     * on), use provisional evidence (stitched-estimate-v1) where that gives none: first per-percent times up to where a
     * completed finish recording starts, joined to that recording's own time from there to the target
     * (Curve.estimateJoined), then per-percent stretches from any past charges, a percent only one of them covers
     * counting provisionally (Curve.estimateProvisional). Live power is checked first as always, against the one past
     * charge's power at a percent only it covers. A finish recording that covers this level but cannot place it
     * (unresolvedFinish) still blocks it. Charge done by plans with the longer of a provisional time and the current
     * rate; the time shown is the provisional one. false is the estimate before stitched-estimate-v1.
     */
    static Result withObservedCurve(Result baseline,Value soc,ChargeSessionStore.Curve curve,boolean provisional) {
        if(baseline==null||!baseline.charging||baseline.inputWatts==null||baseline.remainingMinutesToFull==null
                ||!valid(soc)||!Double.isFinite(soc.number)||soc.number<0||soc.number>100||curve==null)return baseline;
        // Prefer the finishing trajectory even after a second recording becomes available:
        // a SOC-only average would otherwise freeze the ETA throughout a long 99% plateau.
        Double volts=baseline.inputAmps==null||baseline.inputAmps<=0?null:baseline.inputWatts/baseline.inputAmps;
        ChargeSessionStore.Duration finishing=curve.estimateFinishing(soc.number,baseline.inputWatts,volts);
        ChargeSessionStore.Curve.PowerMatch power=curve.powerMatch(soc.number,baseline.inputWatts,provisional);
        String charger=curve.charger==ChargeSessionStore.Charger.STOCK?"stock":"high-speed";
        if(finishing!=null){
            ChargeSessionStore.Duration limit=baseline.targetPercent==null?null:baseline.targetPercent==100
                    ?finishing:power==ChargeSessionStore.Curve.PowerMatch.MATCH?curve.estimate(soc.number,baseline.targetPercent):null;
            if(provisional&&limit==null&&baseline.targetPercent!=null&&power==ChargeSessionStore.Curve.PowerMatch.MATCH)
                limit=provisionalDuration(curve,soc.number,baseline.targetPercent);
            boolean provisionalLimit=limit!=null&&limit.provisional;
            String status=(finishing.sessions==1?"Provisional finish estimate from 1 completed ":"Approximate finish estimate from "+finishing.sessions+" completed ")
                    +charger+" charge"+(finishing.sessions==1?"":"s")
                    +"; matched battery level and charging power. Time to displayed 100%; balancing may continue.";
            if(baseline.targetPercent==null)status+=" Cutoff unknown; 100% assumes charging continues.";
            else if(baseline.targetPercent<100)status+=" The 100% time assumes a higher charge limit.";
            if(limit==null&&baseline.targetPercent!=null)status+=" Time to the limit uses the current-rate estimate.";
            String note=provisionalLimit?provisionalNote(limit,charger,false):null;
            if(provisionalLimit)status+=" "+note.trim()+PLANS_LONGER;
            // A provisional time to a lower limit is never longer than the matched finish to 100%.
            Double limitMinutes=limit==null?baseline.remainingMinutesToLimit:provisionalLimit?Double.valueOf(Math.min(limit.minutes,finishing.minutes)):Double.valueOf(limit.minutes);
            return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                    limitMinutes,finishing.minutes,baseline.targetPercent,
                    M.chargingEstimatePastCharges(finishing.sessions,finishing.sessions),status,limit!=null,true,finishing.sessions,
                    false,false,null,null,provisionalLimit?atLeast(limitMinutes,baseline.remainingMinutesToLimit):limitMinutes,finishing.minutes,false,null,
                    provisionalLimit,note,false);
        }
        if(power!=ChargeSessionStore.Curve.PowerMatch.MATCH){
            String reason=power==ChargeSessionStore.Curve.PowerMatch.DIFFERENT
                    ?"Live power differs from the selected charger's saved charges at this battery level. "
                    :"Saved charges lack consistent power at this battery level. ";
            return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                    baseline.remainingMinutesToLimit,baseline.remainingMinutesToFull,baseline.targetPercent,
                    M.chargingEstimateChargerMismatch(),reason+baseline.status);
        }
        // If a complete trajectory cannot locate this stage safely, its older SOC-only bins
        // must not silently resurrect the same frozen countdown we rejected above.
        boolean unresolvedFinish=curve.hasFinishingCoverage(soc.number);
        ChargeSessionStore.Duration full=unresolvedFinish?null:curve.estimate(soc.number,100);
        if(provisional&&full==null&&!unresolvedFinish)full=provisionalDuration(curve,soc.number,100);
        ChargeSessionStore.Duration limit=baseline.targetPercent==null||unresolvedFinish&&baseline.targetPercent==100
                ?null:curve.estimate(soc.number,baseline.targetPercent);
        if(provisional&&limit==null&&baseline.targetPercent!=null&&!unresolvedFinish)
            limit=baseline.targetPercent==100?full:provisionalDuration(curve,soc.number,baseline.targetPercent);
        if(full==null&&limit==null)return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                baseline.remainingMinutesToLimit,baseline.remainingMinutesToFull,baseline.targetPercent,
                M.chargingEstimateRateBased(),"Current-rate estimate; not enough matching charge curves cover the remaining range. Taper can lengthen charging. "
                        +(baseline.targetPercent==null?"Cutoff unknown; 100% assumes charging continues.":baseline.targetPercent<100?"100% assumes a higher cutoff.":""));
        boolean provisionalFull=full!=null&&full.provisional,provisionalLimit=limit!=null&&limit.provisional;
        int sessions=full==null?limit.sessions:limit==null?full.sessions
                :provisionalFull||provisionalLimit?Math.max(full.sessions,limit.sessions):Math.min(full.sessions,limit.sessions);
        String note=provisionalFull?provisionalNote(full,charger,true):provisionalLimit?provisionalNote(limit,charger,false):null;
        String status=note!=null?note+"Live power is compatible at this battery level."
                :"Observed average from "+sessions+" "+charger+" charges; live power is compatible at this battery level.";
        if(full==null)status+=" Full-charge taper is not covered; 100% uses the current-rate estimate.";
        if(limit==null&&baseline.targetPercent!=null)status+=" Time to the limit uses the current-rate estimate.";
        if(baseline.targetPercent==null)status+=" Cutoff unknown; 100% assumes charging continues.";
        else if(baseline.targetPercent<100)status+=" The 100% time assumes a higher charge limit.";
        if(note!=null)status+=PLANS_LONGER;
        double fullMinutes=full==null?baseline.remainingMinutesToFull:full.minutes;
        // A provisional time to a lower limit is never longer than a learned time to 100%.
        Double limitMinutes=limit==null?baseline.remainingMinutesToLimit
                :provisionalLimit&&full!=null?Double.valueOf(Math.min(limit.minutes,fullMinutes)):Double.valueOf(limit.minutes);
        return new Result(baseline.charging,baseline.inputWatts,baseline.inputAmps,
                limitMinutes,fullMinutes,baseline.targetPercent,M.chargingEstimatePastCharges(sessions,sessions),status,limit!=null,full!=null,sessions,
                false,false,null,null,
                provisionalLimit?atLeast(limitMinutes,baseline.remainingMinutesToLimit):limitMinutes,
                provisionalFull?atLeast(fullMinutes,baseline.remainingMinutesToFull):Double.valueOf(fullMinutes),false,null,
                provisionalFull||provisionalLimit,note,provisionalFull);
    }

    private static final String PLANS_LONGER=" Charge done by plans with the longer of this and the estimate without past charges.";

    /** A finish recording joined to per-percent times first, then per-percent stretches with single-charge percents. */
    private static ChargeSessionStore.Duration provisionalDuration(ChargeSessionStore.Curve curve,double soc,double target) {
        ChargeSessionStore.Duration joined=curve.estimateJoined(soc,target,true);
        return joined!=null?joined:curve.estimateProvisional(soc,target);
    }

    /** What a provisional estimate joined, as its status's first sentence; full: it covers the time to 100%, else only the limit. */
    private static String provisionalNote(ChargeSessionStore.Duration used,String charger,boolean full) {
        String note=(full?"Provisional estimate from ":"Provisional time to the limit from ")+used.sessions+" past "+charger+" charge"+(used.sessions==1?"":"s")+": ";
        if(Double.isFinite(used.joinedAt))note+="per-percent times up to "+Math.round(used.joinedAt)
                +"%, then a completed charge's own time from there"+(used.single&&used.sessions>1?"; some percents rest on one charge":"");
        else note+=used.sessions==1?"its per-percent times":"per-percent times joined across charges"+(used.single?", some percents resting on one charge":"");
        return note+". ";
    }

    /**
     * Whether provisional evidence (withObservedCurve) is used for this model and charger: everywhere except where
     * Volt bundles a reference for the pair (the UT5 MAX on either charger), whose precedence stays as it was.
     */
    static boolean provisionalAllowed(int productId,ChargeSessionStore.Charger charger) {
        return !(charger==ChargeSessionStore.Charger.STOCK&&productId==Ut5MaxStockChargeReference.PRODUCT_ID
                ||charger==ChargeSessionStore.Charger.HIGH_SPEED&&productId==Ut5MaxFastChargeReference.PRODUCT_ID);
    }

    private static boolean valid(Value value) {
        return value != null && value.fresh && value.number != null && Double.isFinite(value.number);
    }

    private static Double minutes(double ratedAh, double missingPercent, double amps) {
        double result = ratedAh * (Math.max(0, missingPercent) / 100d) / amps * 60d;
        return Double.isFinite(result) && result >= 0 ? result : null;
    }

    private static Result result(
            boolean charging,
            Double watts,
            Double amps,
            Double limit,
            Double full,
            Double target,
            CharSequence label,
            String status) {
        return new Result(charging, watts, amps, limit, full, target, label, status);
    }
}
