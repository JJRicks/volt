package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bounded private charge journals and conservative, charger-specific SOC curves. No Android API.
 *
 * <p>Storage (owner, Nov 19 2026: "bump the limit to say like, 100-200MB compressed, and auto clear old ones in a
 * rotating buffer instead"; "auto and manual recordings could be classed the same in that way. rotate away."): a
 * running recording is a plain journal, `<id>.active`. Once it ends it is `<id>.csv`, still plain, and the analysis
 * worker ({@link #maintain}) rewrites it gzip-compressed under the same name, with a one-line summary of it
 * ({@link Facts}) in the gzip header, so listing, pruning and choosing what to learn from never parse hundreds of
 * journals. Every reader takes either form: older files and restored ones are plain until the worker compresses them.
 * Backups and exports always carry the plain journal (BackupArchive), so an older Volt restores them unchanged.
 *
 * <p>Rotation: up to {@link #MAX_RECORDINGS} saved recordings and about {@link #BUDGET_BYTES} on disk, shared by every
 * scooter. Over either, the oldest go first, automatic and manual alike, keeping each scooter and charger's curve
 * ({@link #pruneTo}). The running recording and the one just saved are never removed.
 */
public final class ChargeSessionStore {
    public static final long SAMPLE_INTERVAL_MS = 10000, MAX_FRAME_AGE_MS = 5000, MAX_GAP_MS = 25000;
    // Estimate metadata keeps the same sample/session limits; even maximum-length rows fit.
    public static final long MAX_SESSION_MS = 18 * 60 * 60 * 1000L, MAX_FILE_BYTES = 3_500_000;
    public static final int MAX_SAMPLES = 6500, MAX_PAUSES = 24;
    /** The saved recordings' budget on disk, measured on the compressed files (the running journal aside). */
    public static final long BUDGET_BYTES = 150L * 1024 * 1024;
    /**
     * The count guard beside the budget, so a flood of tiny recordings cannot run away. A typical charge compresses to
     * a few tens of kilobytes, so for most owners this, not the budget, is the limit: about three years of daily charges.
     */
    public static final int MAX_RECORDINGS = 1000;
    /**
     * The learner reads at most this many recordings per scooter and charger, the newest that qualify, and capacity
     * evidence from at most this many completed charges per scooter. It is also the most a saved estimate can claim to
     * have learned from, so an older Volt (which allows at most 20) still reads every saved prediction.
     */
    public static final int LEARN_SESSIONS = 20;
    /** After its plan is cancelled or starts, a recorded pause waits this long for current to return. */
    public static final long PAUSE_RELEASE_GRACE_MS = 2 * 60_000L;
    /**
     * A charger that cuts out below 99% (often one that overheats) usually comes back once cool. The
     * recording waits this long, marked as a charger pause, before it ends as CHARGING_ENDED.
     */
    public static final long CHARGER_PAUSE_MAX_MS = 30 * 60_000L;
    private static final int MIN_BINS = 10;
    public enum Charger { STOCK, HIGH_SPEED }
    public enum Stop { STOPPED, COMPLETE, CHARGING_ENDED, DISCONNECTED, PROFILE_CHANGED, READINGS_STOPPED, LIMIT, ERROR, PROCESS_ENDED, INTERRUPTED, RECOVERY_TIMEOUT, CONNECTION_FAILED, BLUETOOTH_OFF, PERMISSION_LOST, IDENTITY_CHANGED }
    public enum Source { CURRENT_RATE, STOCK_REFERENCE, PERSONAL, FAST_REFERENCE }
    /**
     * How an automatic recording's charger label was chosen (Record charges automatically). Manual recordings have
     * none. It lives in the recording's id, not in the journal: a UUID's version digit (index 14) is always 4, and an
     * automatic recording's id carries a, b or c there instead. The name still matches every reader's pattern, so an
     * older Volt reads, prunes, backs up and restores it unchanged, and old files need nothing new.
     *
     * CHOSEN ('a'): the owner chose the charger during this recording (Charger for estimates, while the label was a guess
     * or detected); detection never changes it. Recordings saved before SETTING existed carry 'a' for the charger setting
     * at their start too. DETECTED ('b'): charger detection settled on it. GUESS ('c'): no setting and no detection; it
     * teaches no curve. SETTING ('d', Volt 2.0.1 and later): the charger setting at the start, provisional: detection
     * replaces it when it settles. An older Volt that does not know 'd' reads such a file as a manual recording (it still
     * reads, lists, learns from and restores it; it only stops counting it as automatic).
     */
    public enum LabelSource { CHOSEN, DETECTED, GUESS, SETTING }
    /**
     * What an automatic recording's id also records about its label (Volt 2.0.1 and later), for audit: the charger setting
     * when it started (null: Automatic) and the family charger detection last settled on during it (null: never). Kept in
     * the id beside the source, because a journal line or header field an older Volt does not know makes the whole file
     * unreadable to it. The UUID's variant digit (index 19) is always 8, 9, a or b; an automatic recording that carries the
     * audit has 0, 1 or 2 there (setting: none, STOCK, HIGH_SPEED) and the next digit (index 20) 0, 1 or 2 (detection: none,
     * STOCK, HIGH_SPEED). Any other id has none ({@link #labelAudit} null).
     */
    public static final class LabelAudit {
        public final Charger setting,detected;
        LabelAudit(Charger setting,Charger detected){this.setting=setting;this.detected=detected;}
    }
    /** Which automatic recordings are kept (ChargeAutoRecordPolicy). Manual ones never ask. */
    public interface AutomaticRules {
        /** ranMs: start to last saved sample; gainedPoints: battery percent from first to last saved sample. */
        boolean keep(long ranMs,double gainedPoints);
    }
    private static final AutomaticRules KEEP_ALL=(ranMs,gainedPoints)->true;
    /** A prediction made at capture time, never recalculated when a recording is read. */
    public static final class Estimate {
        public final String version;
        public final Double remainingMinutesToFull, remainingMinutesToLimit, targetPercent;
        public final Source fullSource, limitSource;
        public final int learnedSessions;
        public Estimate(String version, Double full, Double limit, Double target, Source fullSource, Source limitSource, int learnedSessions) {
            if(version==null||!version.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid estimator version");
            this.version=version;this.remainingMinutesToFull=duration(full);this.targetPercent=target!=null&&between(target,0,100)&&target>0?target:null;
            this.remainingMinutesToLimit=this.targetPercent==null?null:duration(limit);
            this.fullSource=this.remainingMinutesToFull==null?null:fullSource;
            this.limitSource=this.remainingMinutesToLimit==null?null:limitSource;
            this.learnedSessions=this.fullSource==Source.PERSONAL||this.limitSource==Source.PERSONAL?Math.max(0,Math.min(LEARN_SESSIONS,learnedSessions)):0;
        }
        private static Double duration(Double value){return value!=null&&Double.isFinite(value)&&value>=0?value:null;}
    }
    public static final class HistoryPoint {
        public final long wall,mono;public final double batteryPercent,voltage,current,power;public final Double temperature;
        public final boolean gap;public final Estimate estimate;
        private HistoryPoint(Point point){wall=point.wall;mono=point.mono;batteryPercent=point.soc;voltage=point.voltage;current=point.current;power=point.signedPower;temperature=point.temperature;gap=point.gap;estimate=point.estimate;}
    }
    /**
     * Immutable, profile-scoped history for charts. Completion means observed displayed 100%. A summary ({@link #light})
     * carries every field but the points, read from the recording's {@link Facts} without parsing it: the list of
     * recordings and the HISTORY card use those; a chart reads the whole recording ({@link #recording}).
     */
    public static final class HistorySession {
        public final String id,profile;public final Charger charger;public final Stop stop;
        public final long startedWall,startedMono;public final List<HistoryPoint> points;
        public final Long observedCompleteMono,observedCompleteWall;
        /** Time a Volt charging schedule held current off inside this recording, and how often. */
        public final long pausedMs;public final int pauses;
        /** Times the charger itself stopped below 99% and the recording waited, and for how long in all. */
        public final long chargerPausedMs;public final int chargerPauses;
        /** Null for a recording started by hand. */
        public final LabelSource labelSource;public final boolean automatic;
        /** Null for a recording started by hand or an automatic one saved before the audit existed. */
        public final LabelAudit labelAudit;
        /** The first and last finite battery percent it saved (NaN for none), and its last sample's time (its start for none). */
        public final double firstPercent,lastPercent;public final long lastSampleMono;
        /** A summary: no points (they are read when the recording is opened). */
        public final boolean light;
        private HistorySession(String id,Facts facts,Session session){
            this.id=id;labelSource=labelSource(id);labelAudit=labelAudit(id);automatic=labelSource!=null;profile=facts.profile;charger=facts.charger;stop=facts.stop;startedWall=facts.startedWall;startedMono=facts.startedMono;
            List<HistoryPoint> copy=new ArrayList<>();if(session!=null)for(Point point:session.points)copy.add(new HistoryPoint(point));points=Collections.unmodifiableList(copy);
            light=session==null;
            observedCompleteMono=facts.observedCompleteMono;observedCompleteWall=observedCompleteMono==null?null:clockAt(observedCompleteMono);
            pausedMs=facts.pausedMs;chargerPausedMs=facts.chargerPausedMs;pauses=facts.pauses;chargerPauses=facts.chargerPauses;
            firstPercent=facts.firstPercent;lastPercent=facts.lastPercent;lastSampleMono=facts.lastSampleMono;
        }
        /** One stable wall-clock anchor avoids artificial jumps when the phone clock changes. */
        public long clockAt(long mono){return startedWall+(mono-startedMono);}
    }
    public static final class Value {
        public final Double value; public final long mono; public final int source;
        public Value(Double value, long mono, int source) { this.value = value; this.mono = mono; this.source = source; }
    }
    public static final class Frame {
        public final long wall, mono; public final Value state, soc, voltage, current, temperature;
        public Frame(long wall, long mono, Value state, Value soc, Value voltage, Value current, Value temperature) {
            this.wall=wall;this.mono=mono;this.state=state;this.soc=soc;this.voltage=voltage;this.current=current;this.temperature=temperature;
        }
        public boolean usable() {
            return fresh(state,mono) && state.value==1 && measurementsUsable();
        }
        public boolean usableCompletion() {
            return fresh(state,mono) && (state.value==0 || state.value==1) && measurementsUsable() && soc.value==100;
        }
        public boolean usableChargingEnded() {
            return fresh(state,mono) && state.value==0 && measurementsUsable();
        }
        private boolean measurementsUsable() {
            return wall > 0 && mono > 0 && fresh(soc,mono) && between(soc.value,0,100)
                    && fresh(voltage,mono) && between(voltage.value,1,100) && fresh(current,mono) && Math.abs(current.value)<=30
                    && voltage.mono==current.mono && voltage.source==current.source && voltage.value*Math.abs(current.value)<=3000;
        }
    }
    public static final class Duration {
        public final double minutes; public final int sessions;
        /**
         * Some of it rests on one recording, or it joins a finish recording's own time (Curve.estimateProvisional,
         * Curve.estimateJoined). sessions then counts the recordings it uses, not the fewest behind any percent.
         */
        public final boolean provisional;
        /** Whether some percent of it rests on a single recording. */
        public final boolean single;
        /** The level where a finish recording's own time takes over (the lowest, with several), or NaN for no join. */
        public final double joinedAt;
        Duration(double minutes,int sessions){this(minutes,sessions,false,false,Double.NaN);}
        Duration(double minutes,int sessions,boolean provisional,boolean single,double joinedAt){
            this.minutes=minutes;this.sessions=sessions;this.provisional=provisional;this.single=single;this.joinedAt=joinedAt;
        }
    }
    public static final class Curve {
        public enum PowerMatch { MATCH, DIFFERENT, UNKNOWN }
        public final String profile; public final Charger charger; public final int qualifiedSessions;
        private final double[] minutes, watts; private final int[] counts;
        /**
         * The recordings behind each percent's minutes, bit i for the i-th qualified recording (the first 64), so a
         * provisional or joined estimate can say how many recordings it uses.
         */
        private final long[] sources;
        /** A percent whose power comes from one recording: the guard for a provisional estimate only (powerMatch). */
        private final boolean[] singleWatts;
        /** How many recordings have minutes for each percent, agreeing or not (summary's disagreeing percents). */
        private final int[] covering;
        private final List<FinishingCurve> finishing = new ArrayList<>();
        /** finishing's recordings, as bits like sources. */
        private final List<Long> finishingSource = new ArrayList<>();
        Curve(String profile, Charger charger, List<Evidence> sessions) {
            this.profile=profile;this.charger=charger;qualifiedSessions=sessions.size();minutes=new double[100];watts=new double[100];counts=new int[100];
            sources=new long[100];singleWatts=new boolean[100];covering=new int[100];
            for (int i=0;i<sessions.size();i++) if (sessions.get(i).finishing != null){finishing.add(sessions.get(i).finishing);finishingSource.add(bit(i));}
            Arrays.fill(minutes,Double.NaN);Arrays.fill(watts,Double.NaN);
            for(int bin=0;bin<100;bin++) {
                List<Double> values=new ArrayList<>();int only=-1;
                for(int i=0;i<sessions.size();i++)if(Double.isFinite(sessions.get(i).minutes[bin])){values.add(sessions.get(i).minutes[bin]);only=i;}
                covering[bin]=values.size();
                if(values.size()==1){
                    // One recording: provisional minutes and power, which only estimateProvisional, estimateJoined and
                    // powerMatch(…, true) use. A second agreeing recording turns this into the average below.
                    Evidence session=sessions.get(only);
                    minutes[bin]=values.get(0);counts[bin]=1;sources[bin]=bit(only);
                    if(Double.isFinite(session.watts[bin])&&session.watts[bin]>=1){watts[bin]=session.watts[bin];singleWatts[bin]=true;}
                    continue;
                }
                Collections.sort(values);if(values.size()<2)continue;
                double median=median(values);
                // With just two strongly disagreeing sessions there is no reliable majority.
                if(values.size()==2&&values.get(1)>values.get(0)*2.5)continue;
                double sum=0;List<Double> powers=new ArrayList<>();long used=0;
                for(int i=0;i<sessions.size();i++){Evidence session=sessions.get(i);if(session.minutes[bin]>=median*.4&&session.minutes[bin]<=median*2.5){
                    sum+=session.minutes[bin];counts[bin]++;used|=bit(i);
                    if(Double.isFinite(session.watts[bin])&&session.watts[bin]>=1)powers.add(session.watts[bin]);
                }}
                if(counts[bin]>=2){minutes[bin]=sum/counts[bin];sources[bin]=used;}
                // Use the same duration-qualified sessions, at this SOC, not a bulk-power guess
                // during taper. Two disagreeing records cannot identify compatible live power.
                if(powers.size()>=2){
                    double center=median(powers);List<Double> compatible=new ArrayList<>();
                    for(double power:powers)if(power>=center*.75&&power<=center*1.25)compatible.add(power);
                    if(compatible.size()>=2)watts[bin]=median(compatible);
                }
            }
            familyWatts=familyPowers(sessions);
        }
        /**
         * The family's power per 1% bin for telling chargers apart (LearnedChargerMatch, ChargeAutoRecordPolicy's labels and
         * RecordChargerHint), never for timing: it is built from every qualified session's own bin power, one session
         * included, so a first recording is already a baseline. Two or more sessions give the median of those within 25%
         * of their median, and need two of them to agree; a lone disagreeing pair gives nothing, as in watts.
         */
        private final double[] familyWatts;
        private static double[] familyPowers(List<Evidence> sessions){
            double[] result=new double[100];Arrays.fill(result,Double.NaN);
            for(int bin=0;bin<100;bin++){
                List<Double> powers=new ArrayList<>();
                for(Evidence session:sessions)if(Double.isFinite(session.watts[bin])&&session.watts[bin]>=1)powers.add(session.watts[bin]);
                if(powers.isEmpty())continue;
                if(powers.size()==1){result[bin]=powers.get(0);continue;}
                double center=median(powers);List<Double> compatible=new ArrayList<>();
                for(double power:powers)if(power>=center*.75&&power<=center*1.25)compatible.add(power);
                if(compatible.size()>=2)result[bin]=median(compatible);
            }
            return result;
        }
        /** The family's saved power at this battery level (familyWatts), or null where no session saved one. */
        public Double familyWatts(double soc){
            if(!between(soc,0,100))return null;
            double value=familyWatts[Math.min(99,(int)Math.floor(soc))];
            return Double.isFinite(value)?value:null;
        }
        /** powerMatch's band against familyWatts: one recording is enough. For telling chargers apart only. */
        public PowerMatch familyPowerMatch(double soc,Double liveWatts){
            Double expected=familyWatts(soc);
            if(expected==null||liveWatts==null||!Double.isFinite(liveWatts)||liveWatts<1)return PowerMatch.UNKNOWN;
            return liveWatts>=expected*.65&&liveWatts<=expected*1.35?PowerMatch.MATCH:PowerMatch.DIFFERENT;
        }
        private static long bit(int index){return index<64?1L<<index:0;}
        /** Read-only: the curve's coverage per percent, for a chart of the saved curve. */
        public Summary summary() {
            List<Double> starts=new ArrayList<>();
            for(FinishingCurve curve:finishing)starts.add(curve.points.get(0).soc);
            Collections.sort(starts);
            List<Summary.Bin> bins=new ArrayList<>();
            for(int bin=0;bin<100;bin++){
                boolean usable=Double.isFinite(minutes[bin])&&counts[bin]>=1;
                Summary.Coverage coverage=usable?counts[bin]>=2?Summary.Coverage.ROBUST:Summary.Coverage.PROVISIONAL
                        :covering[bin]>=2?Summary.Coverage.DISAGREEING:Summary.Coverage.NONE;
                boolean finish=false;for(double start:starts)if(bin>=start){finish=true;break;}
                bins.add(new Summary.Bin(bin,coverage,usable?counts[bin]:covering[bin],usable?Double.valueOf(minutes[bin]):null,
                        Double.isFinite(watts[bin])?Double.valueOf(watts[bin]):null,finish));
            }
            return new Summary(charger,qualifiedSessions,starts,bins);
        }
        /** A plausibility check, not charger identification; missing local power fails closed. */
        public PowerMatch powerMatch(double soc,Double liveWatts) {return powerMatch(soc,liveWatts,false);}
        /** single: a percent one recording covers also checks against that recording's power (a provisional estimate). */
        public PowerMatch powerMatch(double soc,Double liveWatts,boolean single) {
            if(!between(soc,0,100)||liveWatts==null||!Double.isFinite(liveWatts)||liveWatts<1)return PowerMatch.UNKNOWN;
            int bin=Math.min(99,(int)Math.floor(soc));double expected=watts[bin];
            if(!Double.isFinite(expected)||singleWatts[bin]&&!single)return PowerMatch.UNKNOWN;
            return liveWatts>=expected*.65&&liveWatts<=expected*1.35?PowerMatch.MATCH:PowerMatch.DIFFERENT;
        }
        /** Every remaining interval, including the final taper bin, needs two independent sessions. */
        public Duration estimate(double soc,double target) {
            if(!between(soc,0,100)||!between(target,0,100)||qualifiedSessions<2)return null;
            if(soc>=target)return new Duration(0,qualifiedSessions);
            double total=0;int supported=Integer.MAX_VALUE;
            for(int bin=(int)Math.floor(soc);bin<Math.ceil(target);bin++) {
                if(bin<0||bin>=100||!Double.isFinite(minutes[bin])||counts[bin]<2)return null;
                double fraction=Math.min(target,bin+1)-Math.max(soc,bin);
                if(fraction>0){total+=fraction*minutes[bin];supported=Math.min(supported,counts[bin]);}
            }
            return Double.isFinite(total)?new Duration(total,supported):null;
        }
        /**
         * Like estimate, but a percent that one recording covers counts too, provisionally, and per-percent stretches
         * from different recordings join wherever every percent from soc to target is covered. A percent two
         * recordings strongly disagree on stays uncovered, as in estimate. When every percent has two or more agreeing
         * recordings this is estimate itself (not provisional). Null at or above target: there is nothing to learn.
         */
        public Duration estimateProvisional(double soc,double target) {
            if(!between(soc,0,100)||!between(target,0,100)||soc>=target||qualifiedSessions<1)return null;
            Duration robust=estimate(soc,target);
            if(robust!=null)return robust;
            double[] span=span(soc,target);
            return span==null?null:new Duration(span[0],Long.bitCount((long)span[1]),true,span[2]>0,Double.NaN);
        }
        /**
         * Per-percent times from soc up to where a completed finish recording starts, then that recording's own time
         * from its start to target (its first reading at target, or its observed 100%). Only finish recordings that
         * start above soc and below target join; one already covering soc is estimateFinishing's. single: percents
         * one recording covers may fill the stretch below the join (otherwise only those two or more agree on). With
         * several finish recordings, each joined time counts and they are combined like estimateFinishing (two that
         * disagree by more than 2.5 times give none). Always provisional. Null when nothing joins.
         */
        public Duration estimateJoined(double soc,double target,boolean single) {
            if(!between(soc,0,100)||!between(target,0,100)||soc>=target)return null;
            List<Double> values=new ArrayList<>();List<Long> used=new ArrayList<>();List<Boolean> singles=new ArrayList<>();List<Double> starts=new ArrayList<>();
            for(int i=0;i<finishing.size();i++){
                FinishingCurve curve=finishing.get(i);double start=curve.points.get(0).soc;
                if(!(start>soc&&start<target))continue;
                double[] span=span(soc,start);
                if(span==null||!single&&span[2]>0)continue;
                Double tail=curve.minutesFromStart(target);
                if(tail==null)continue;
                values.add(span[0]+tail);used.add((long)span[1]|finishingSource.get(i));singles.add(span[2]>0);starts.add(start);
            }
            if(values.isEmpty())return null;
            List<Double> sorted=new ArrayList<>(values);Collections.sort(sorted);
            if(sorted.size()==2&&sorted.get(1)>sorted.get(0)*2.5)return null;
            double center=median(sorted),sum=0;int count=0;long sources=0;boolean anySingle=false;double joinedAt=Double.NaN;
            for(int i=0;i<values.size();i++){double value=values.get(i);if(value>=center*.4&&value<=center*2.5){
                sum+=value;count++;sources|=used.get(i);anySingle|=singles.get(i);
                if(!(starts.get(i)>=joinedAt))joinedAt=starts.get(i);
            }}
            return count==0?null:new Duration(sum/count,Math.max(1,Long.bitCount(sources)),true,anySingle,joinedAt);
        }
        /**
         * {minutes, recordings as bits, 1 when some percent rests on one recording} from soc to target over percents
         * that one or more agreeing recordings cover; null at the first uncovered percent.
         */
        private double[] span(double soc,double target) {
            double total=0;long used=0;boolean single=false;
            for(int bin=(int)Math.floor(soc);bin<Math.ceil(target);bin++) {
                if(bin<0||bin>=100||!Double.isFinite(minutes[bin])||counts[bin]<1)return null;
                double fraction=Math.min(target,bin+1)-Math.max(soc,bin);
                if(fraction>0){total+=fraction*minutes[bin];used|=sources[bin];single|=counts[bin]<2;}
            }
            return Double.isFinite(total)?new double[]{total,used,single?1:0}:null;
        }
        /** Complete observed finishes can provide a provisional estimate after just one charge. */
        public Duration estimateFinishing(double soc, Double liveWatts) {
            return estimateFinishing(soc, liveWatts, null);
        }
        /** Paired voltage disambiguates a low-power bulk plateau from a later taper stage. */
        public Duration estimateFinishing(double soc, Double liveWatts, Double liveVolts) {
            if (!between(soc, 0, 100) || liveWatts == null || !Double.isFinite(liveWatts) || liveWatts < 1) return null;
            if (liveVolts != null && (!Double.isFinite(liveVolts) || liveVolts <= 0)) return null;
            List<Double> estimates = new ArrayList<>();
            for (FinishingCurve curve : finishing) {
                Double value = curve.estimate(soc, liveWatts, liveVolts);
                if (value != null) estimates.add(value);
            }
            if (estimates.isEmpty()) return null;
            Collections.sort(estimates);
            if (estimates.size() == 2 && estimates.get(1) > estimates.get(0) * 2.5) return null;
            double center = median(estimates), sum = 0; int count = 0;
            for (double value : estimates) if (value >= center * .4 && value <= center * 2.5) { sum += value; count++; }
            return count == 0 ? null : new Duration(sum / count, count);
        }
        public boolean hasFinishingCoverage(double soc) {
            for (FinishingCurve curve : finishing) if (soc >= curve.points.get(0).soc && soc < 100) return true;
            return false;
        }
    }
    /** A curve's coverage per 1% (Curve.summary): an immutable copy, for a chart of the saved curve. */
    public static final class Summary {
        /**
         * NONE: no recording has this percent. PROVISIONAL: one does (estimateProvisional and estimateJoined use it).
         * ROBUST: two or more agree (estimate uses it). DISAGREEING: two or more have it but no majority agrees, so
         * nothing uses it unless a finish recording covers it.
         */
        public enum Coverage { NONE, PROVISIONAL, ROBUST, DISAGREEING }
        public static final class Bin {
            /** The percent from percent to percent + 1. */
            public final int percent;
            public final Coverage coverage;
            /** Recordings behind the minutes (agreeing ones), or for DISAGREEING how many have the percent. */
            public final int sessions;
            /** Minutes for this 1%, null unless PROVISIONAL or ROBUST. */
            public final Double minutes;
            /** The saved charging power here in watts (one recording's for PROVISIONAL), or null. */
            public final Double watts;
            /** A completed finish recording covers this percent (estimateFinishing can use it). */
            public final boolean finishCovered;
            Bin(int percent,Coverage coverage,int sessions,Double minutes,Double watts,boolean finishCovered){
                this.percent=percent;this.coverage=coverage;this.sessions=sessions;this.minutes=minutes;this.watts=watts;this.finishCovered=finishCovered;
            }
        }
        public final Charger charger;public final int qualifiedSessions;
        /** Where each finish recording starts (its first full percent), lowest first. */
        public final List<Double> finishStarts;
        /** 100 bins, 0% to 99%. */
        public final List<Bin> bins;
        Summary(Charger charger,int qualifiedSessions,List<Double> finishStarts,List<Bin> bins){
            this.charger=charger;this.qualifiedSessions=qualifiedSessions;
            this.finishStarts=Collections.unmodifiableList(new ArrayList<>(finishStarts));this.bins=Collections.unmodifiableList(new ArrayList<>(bins));
        }
    }
    public static final class Snapshot {
        public final Map<String,Curve> curves; public final int sessions; public final long bytes;
        /** Per profile: charge accepted per gauge point in completed charges (any charger). */
        public final Map<String,BatteryCapacityEstimate.ChargeEvidence> gaugeCharge;
        /**
         * Per scooter and charger ({@link #key}): of the scooter's newest LEARN_SESSIONS recordings that could teach a curve,
         * those with this charger. "The charger you have recorded more charges with" counts these, so after a switch to the
         * other charger the guess follows within about LEARN_SESSIONS / 2 charges, not hundreds. (A cap per charger would
         * not: both would sit at LEARN_SESSIONS, a tie, for good.)
         */
        private final Map<String,Integer> qualified;
        Snapshot(Map<String,Curve> curves,int sessions,long bytes){this(curves,sessions,bytes,Collections.emptyMap(),Collections.emptyMap());}
        Snapshot(Map<String,Curve> curves,int sessions,long bytes,Map<String,BatteryCapacityEstimate.ChargeEvidence> gaugeCharge,Map<String,Integer> qualified){
            this.curves=Collections.unmodifiableMap(curves);this.sessions=sessions;this.bytes=bytes;
            this.gaugeCharge=Collections.unmodifiableMap(new LinkedHashMap<>(gaugeCharge));
            this.qualified=Collections.unmodifiableMap(new LinkedHashMap<>(qualified));
        }
        public int qualifiedRecordings(String profile,Charger charger){Integer n=qualified.get(key(profile,charger));return n==null?0:n;}
    }
    /** Longest sample spacing still integrated as continuous charging, unless a gap was recorded there. */
    static final long GAUGE_CHARGE_MAX_STEP_MS = 30*60000L;
    /** A flagged step longer than this was an outage: charging may have stopped unseen. Shorter
     *  flags mark rejected frames between normal samples (at most 0.2% of a full stock charge). */
    static final long GAUGE_CHARGE_GAP_MS = 30000L;
    /** A saved or running journal's name, and what an interrupted relabel or compression leaves (compiled once: app start lists them all). */
    private static final java.util.regex.Pattern JOURNAL=java.util.regex.Pattern.compile("[a-f0-9-]{36}\\.(csv|active)"),
            LEFTOVER=java.util.regex.Pattern.compile("[a-f0-9-]{36}\\.(active\\.mapping|csv\\.repair)");
    private final File directory;
    private final AutomaticRules rules;
    private final int maxRecordings;
    private final long budgetBytes;
    // Each saved file's facts, by name, checked against its length and modification time. snapshot() and maintain() prime
    // it on the analysis worker, so a stop's pruning on the main thread reads at most the file it just saved.
    private final Map<String,Known> known=new HashMap<>();
    /** The recording last saved or recovered: never the one pruning removes. */
    private final Set<String> justSaved=new HashSet<>();
    // Written under the lock; read without it by active() and profile(), which the service asks from the main thread many
    // times a second, so they never wait for a long walk of the folder.
    private volatile File active;
    private volatile String profile;
    private Charger charger;
    private LabelSource labelSource;
    private double firstSoc;
    private long startWall,startMono,lastWriteMono,lastElectricalMono,lastFreshMono,pauseGraceUntilMono,pausedSinceMono;
    private Double lastSoc,lastPower,lastVoltage;
    private int samples,pauses;
    private boolean gap,paused,chargerPaused;
    private double candidatePower,candidateVoltage;
    private long candidateMono,candidateElectricalMono;
    private int candidateSamples;

    public ChargeSessionStore(File directory) throws IOException {this(directory,KEEP_ALL);}
    public ChargeSessionStore(File directory,AutomaticRules rules) throws IOException {this(directory,rules,MAX_RECORDINGS,BUDGET_BYTES);}
    /** Tests choose smaller limits. */
    ChargeSessionStore(File directory,AutomaticRules rules,int maxRecordings,long budgetBytes) throws IOException {
        this.directory=directory;this.rules=rules==null?KEEP_ALL:rules;
        if(maxRecordings<1||budgetBytes<1)throw new IllegalArgumentException();
        this.maxRecordings=maxRecordings;this.budgetBytes=budgetBytes;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Charge storage unavailable");
        // This runs at app start on the main thread: names only, no file is read but a journal a process death left open.
        String[] names=directory.list();
        if(names==null)throw new IOException("Charge recordings unavailable");
        int saved=0;
        for(String name:names){
            // A relabel or a compression that a process death interrupted before its rename: the journal itself is untouched.
            if(LEFTOVER.matcher(name).matches()){new File(directory,name).delete();continue;}
            if(!JOURNAL.matcher(name).matches())continue;
            if(name.endsWith(".csv")){saved++;continue;}
            // A process restart never silently resumes a capture. Interrupted journals remain inspectable,
            // but do not enter learned curves because their stop/continuity was not observed.
            File file=new File(directory,name);
            // A stop that a process death cut short after its end line keeps that line: a second one would make it unreadable.
            if(!ended(file))append(file,"end,INTERRUPTED\n");
            File recovered=new File(directory,name.replace(".active",".csv"));
            if(!file.renameTo(recovered))throw new IOException("Could not finalize interrupted charge");
            // An interrupted automatic recording is judged like any other automatic one: a short one is not kept.
            Session session=labelSource(recovered.getName())==null?null:read(recovered);
            if(session!=null&&!keeps(session)){recovered.delete();continue;}
            justSaved.add(recovered.getName());saved++;
        }
        // Over the count (a recovered recording, or one saved by a stop that a process death cut short before it made
        // room): the oldest go now. The budget in bytes waits for maintain(), on the analysis worker.
        if(saved>maxRecordings)pruneTo(maxRecordings,Long.MAX_VALUE);
    }
    private boolean keeps(Session session){
        if(session.points.isEmpty())return false;
        Point first=session.points.get(0),last=session.points.get(session.points.size()-1);
        return rules.keep(last.mono-session.startedMono,last.soc-first.soc);
    }
    /** Null for a manual recording's id or file name. */
    public static LabelSource labelSource(String id){
        if(id==null||id.length()<36||id.charAt(8)!='-'||id.charAt(13)!='-')return null;
        switch(id.charAt(14)){case 'a':return LabelSource.CHOSEN;case 'b':return LabelSource.DETECTED;case 'c':return LabelSource.GUESS;case 'd':return LabelSource.SETTING;default:return null;}
    }
    /** Null for a manual recording, an automatic one saved before the audit existed, or a malformed id. */
    public static LabelAudit labelAudit(String id){
        if(labelSource(id)==null||id.length()<36||id.charAt(18)!='-'||auditDigit(id.charAt(19))<0||auditDigit(id.charAt(20))<0)return null;
        return new LabelAudit(auditCharger(id.charAt(19)),auditCharger(id.charAt(20)));
    }
    private static int auditDigit(char digit){return digit>='0'&&digit<='2'?digit-'0':-1;}
    private static Charger auditCharger(char digit){return digit=='1'?Charger.STOCK:digit=='2'?Charger.HIGH_SPEED:null;}
    private static char auditChar(Charger charger){return charger==null?'0':(char)('1'+charger.ordinal());}
    private static String automaticId(String id,LabelSource source){
        char[] chars=id.substring(0,36).toCharArray();chars[14]=(char)('a'+source.ordinal());return new String(chars);
    }
    /** The id with the audit's setting and detection digits. */
    private static String auditId(String id,Charger setting,Charger detected){
        char[] chars=id.substring(0,36).toCharArray();chars[19]=auditChar(setting);chars[20]=auditChar(detected);return new String(chars);
    }
    public boolean active(){return active!=null;}
    public String profile(){return profile;}
    public synchronized Charger charger(){return charger;}
    public synchronized int sampleCount(){return samples;}
    public synchronized long lastFreshMono(){return lastFreshMono;}
    public synchronized long startedMono(){return startMono;}
    /** Null for a manual recording or none. */
    public synchronized LabelSource labelSource(){return active==null?null:labelSource;}
    public synchronized boolean automatic(){return active!=null&&labelSource!=null;}
    /**
     * A recording started by hand. Like an automatic one, it deletes nothing when it starts: room is made once it is
     * saved ({@link #stop}), so the folder holds one journal over the limit while it runs.
     */
    public synchronized void start(String profile,Charger charger,long wall,long mono) throws IOException {
        begin(profile,charger,null,false,null,wall,mono);
    }
    /**
     * An automatic recording. Nothing saved is deleted for it until it is kept: once saved (stop, or recovery after a
     * process death) it makes room as any recording does, oldest first, manual or automatic alike.
     */
    public synchronized void startAutomatic(String profile,Charger charger,LabelSource source,long wall,long mono) throws IOException {
        if(source==null)throw new IllegalArgumentException();
        begin(profile,charger,source,false,null,wall,mono);
    }
    /**
     * An automatic recording whose id also records, for audit ({@link LabelAudit}), the charger setting at its start
     * (null: Automatic); a DETECTED start also records detection's family, its charger.
     */
    public synchronized void startAutomatic(String profile,Charger charger,LabelSource source,Charger setting,long wall,long mono) throws IOException {
        startAutomatic(profile,charger,source,setting,source==LabelSource.DETECTED?charger:null,wall,mono);
    }
    /**
     * As above, with the family charger detection (ChargerDetection) has settled on for the audit given apart from the
     * label: a DETECTED label from this scooter's learned curves (LearnedChargerMatch) audits no detection.
     */
    public synchronized void startAutomatic(String profile,Charger charger,LabelSource source,Charger setting,Charger detected,long wall,long mono) throws IOException {
        if(source==null)throw new IllegalArgumentException();
        begin(profile,charger,source,true,setting,detected,wall,mono);
    }
    private void begin(String profile,Charger charger,LabelSource source,boolean audit,Charger setting,long wall,long mono) throws IOException {
        begin(profile,charger,source,audit,setting,source==LabelSource.DETECTED?charger:null,wall,mono);
    }
    private void begin(String profile,Charger charger,LabelSource source,boolean audit,Charger setting,Charger detected,long wall,long mono) throws IOException {
        if(active!=null)throw new IllegalStateException("A charge capture is already active");
        if(profile==null||!profile.matches("[A-Za-z0-9_-]{1,128}")||charger==null||wall<=0||mono<=0||wall>Long.MAX_VALUE-MAX_SESSION_MS||mono>Long.MAX_VALUE-MAX_SESSION_MS)throw new IllegalArgumentException("Choose a scooter and charger");
        String id=UUID.randomUUID().toString();
        if(source!=null){
            id=automaticId(id,source);
            if(audit)id=auditId(id,setting,detected);
        }
        File file=new File(directory,id+".active");
        append(file,"charge-v2,"+profile+","+charger.name()+","+wall+","+mono+"\n");
        this.active=file;this.profile=profile;this.charger=charger;labelSource=source;startWall=wall;startMono=mono;lastFreshMono=mono;pauseGraceUntilMono=0;
        lastWriteMono=lastElectricalMono=0;lastSoc=lastPower=lastVoltage=null;firstSoc=Double.NaN;samples=0;gap=true;paused=false;pauses=0;
        resetElectricalCandidate();
    }
    /**
     * Changes a running automatic recording's charger label and how it was chosen. The header is rewritten through a
     * temporary file renamed over the journal, then the journal is renamed to the id that carries the new source. A
     * process death between the two leaves the new charger with the old source in the id, nothing worse.
     */
    public synchronized boolean relabel(Charger to,LabelSource source) throws IOException {
        return relabel(to,source,true);
    }
    /**
     * As above; auditDetection false leaves the audit's detected family as it is (a DETECTED label from the learned
     * curves, LearnedChargerMatch, is not charger detection: noteDetected keeps the audit to ChargerDetection's family).
     */
    public synchronized boolean relabel(Charger to,LabelSource source,boolean auditDetection) throws IOException {
        if(active==null||labelSource==null||to==null||source==null)return false;
        if(to!=charger){
            byte[] bytes=java.nio.file.Files.readAllBytes(active.toPath());int end=0;
            while(end<bytes.length&&end<=512&&bytes[end]!='\n')end++;
            if(end>=bytes.length||end>512)return false;
            String[] header=new String(bytes,0,end,StandardCharsets.UTF_8).split(",",-1);
            if(header.length!=5)return false;
            header[2]=to.name();File temporary=new File(active.getPath()+".mapping");
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(String.join(",",header).getBytes(StandardCharsets.UTF_8));out.write(bytes,end,bytes.length-end);out.getFD().sync();}
            if(!temporary.renameTo(active)){temporary.delete();return false;}
            charger=to;
        }
        String id=automaticId(active.getName(),source);
        LabelAudit audit=labelAudit(id);
        if(audit!=null&&source==LabelSource.DETECTED&&auditDetection)id=auditId(id,audit.setting,to);
        File renamed=new File(directory,id+".active");
        if(!renamed.equals(active)&&(renamed.exists()||!active.renameTo(renamed)))return true;
        active=renamed;labelSource=source;return true;
    }
    /**
     * Records in a running automatic recording's id (its {@link LabelAudit}) the family charger detection has settled on,
     * whatever its label: under a label the owner chose, detection changes nothing else. A recording without the audit
     * is left alone. A failed rename keeps the old note; nothing else depends on it.
     */
    public synchronized void noteDetected(Charger detected){
        if(active==null||labelSource==null||detected==null)return;
        LabelAudit audit=labelAudit(active.getName());
        if(audit==null||audit.detected==detected)return;
        File renamed=new File(directory,auditId(active.getName(),audit.setting,detected)+".active");
        if(!renamed.exists()&&active.renameTo(renamed))active=renamed;
    }
    /** The running automatic recording's audit, or null (none running, manual, or started without it). */
    public synchronized LabelAudit labelAudit(){return active==null?null:labelAudit(active.getName());}
    /** Returns true only for a newly committed, fresh paired sample. Rejected samples break continuity. */
    public synchronized boolean sample(Frame frame) throws IOException {
        return sample(frame,ChargingEstimate.recordingSnapshot(null));
    }
    public synchronized boolean sample(Frame frame,Estimate estimate) throws IOException {
        return sample(frame,false,estimate);
    }
    /** A final fresh 100% packet may arrive after charging flips off. It never starts a capture. */
    public synchronized boolean sampleCompletion(Frame frame) throws IOException {
        return sampleCompletion(frame,ChargingEstimate.recordingSnapshot(null));
    }
    public synchronized boolean sampleCompletion(Frame frame,Estimate estimate) throws IOException {
        return samples>0 && sample(frame,true,estimate);
    }
    private boolean sample(Frame frame,boolean completion,Estimate estimate) throws IOException {
        if(active==null)return false;
        if(frame==null||!(completion?frame.usableCompletion():frame.usable())){gap=true;resetElectricalCandidate();return false;}
        if(frame.mono<startMono||frame.mono-startMono>MAX_SESSION_MS||samples>=MAX_SAMPLES||active.length()>=MAX_FILE_BYTES){stop(Stop.LIMIT);return false;}
        if(frame.voltage.mono<=lastElectricalMono)return false;
        lastFreshMono=frame.mono;
        if(lastWriteMono>0&&frame.mono-lastWriteMono<SAMPLE_INTERVAL_MS&&frame.soc.value<100)return false;
        double power=frame.voltage.value*frame.current.value;
        boolean discontinuity=lastWriteMono>0&&(frame.mono-lastWriteMono>MAX_GAP_MS||frame.mono<=lastWriteMono);
        boolean spike=lastPower!=null&&Math.max(Math.abs(power),Math.abs(lastPower))>3*Math.min(Math.abs(power),Math.abs(lastPower))
                &&Math.abs(power-lastPower)>50;
        // Current falling to zero at the cutoff is expected, unlike an upward electrical spike.
        if(completion&&lastPower!=null&&Math.abs(power)<=Math.abs(lastPower))spike=false;
        spike|=lastVoltage!=null&&Math.abs(frame.voltage.value-lastVoltage)>3;
        if(spike){gap=true;if(!confirmElectricalChange(frame,power))return false;}
        if(lastSoc!=null&&(frame.soc.value<lastSoc||frame.soc.value-lastSoc>1))gap=true;
        Double temperature=fresh(frame.temperature,frame.mono)&&between(frame.temperature.value,-40,100)?frame.temperature.value:null;
        Estimate saved=estimate==null?ChargingEstimate.recordingSnapshot(null):estimate;
        String line="s,"+frame.wall+","+frame.mono+","+frame.soc.value+","+frame.voltage.value+","+frame.current.value+","+power+","+(temperature==null?"":temperature)+","+(gap||discontinuity?1:0)
                +","+saved.version+","+csv(saved.remainingMinutesToFull)+","+csv(saved.remainingMinutesToLimit)+","+csv(saved.targetPercent)
                +","+csv(saved.fullSource)+","+csv(saved.limitSource)+","+saved.learnedSessions+"\n";
        append(active,line);if(samples==0)firstSoc=frame.soc.value;samples++;lastWriteMono=frame.mono;lastElectricalMono=frame.voltage.mono;
        lastSoc=frame.soc.value;lastPower=power;lastVoltage=frame.voltage.value;gap=false;
        resetElectricalCandidate();
        return true;
    }
    /** An isolated spike stays excluded, but a sustained new charging level must not latch
     * against the old sample forever. Require three compatible, independent measurements
     * at the recording cadence, within 25% (or 25 W) and 1 V of the first candidate.
     * Only the confirming real sample starts the new segment. */
    private boolean confirmElectricalChange(Frame frame,double power){
        if(candidateSamples>0&&(frame.mono<candidateMono||frame.mono-candidateMono>MAX_GAP_MS
                ||frame.voltage.mono<candidateElectricalMono
                ||Math.abs(power-candidatePower)>Math.max(25,Math.abs(candidatePower)*.25)
                ||Math.abs(frame.voltage.value-candidateVoltage)>1))resetElectricalCandidate();
        if(candidateSamples==0){
            candidatePower=power;candidateVoltage=frame.voltage.value;candidateSamples=1;
            candidateMono=frame.mono;candidateElectricalMono=frame.voltage.mono;
            return false;
        }
        if(frame.voltage.mono==candidateElectricalMono||frame.mono-candidateMono<SAMPLE_INTERVAL_MS)return false;
        candidateMono=frame.mono;candidateElectricalMono=frame.voltage.mono;
        return ++candidateSamples>=3;
    }
    private void resetElectricalCandidate(){candidateSamples=0;candidateMono=candidateElectricalMono=0;}
    /** A new observed segment must not be compared with electrical values from before a loss. */
    public synchronized void markGap(){gap=true;lastPower=lastVoltage=null;resetElectricalCandidate();}
    /**
     * Volt's own charging schedule holds current off until its start. The journal stays open and
     * marks the interval ("p" lines); the first sample after it starts a new segment.
     */
    public synchronized boolean paused(){return paused;}
    public synchronized void pause(long wall,long mono) throws IOException {
        pause(wall,mono,false);
    }
    /** charger: the charger stopped on its own ("p,…,2"); otherwise a schedule holds current off ("p,…,1"). */
    private void pause(long wall,long mono,boolean charger) throws IOException {
        if(active==null||paused)return;
        if(pauses>=MAX_PAUSES||wall<=0||mono<startMono){stop(Stop.LIMIT);return;}
        append(active,"p,"+wall+","+mono+","+(charger?"2":"1")+"\n");paused=true;chargerPaused=charger;pausedSinceMono=mono;pauses++;
        lastFreshMono=Math.max(lastFreshMono,mono);markGap();
    }
    public synchronized boolean chargerPaused(){return paused&&chargerPaused;}
    public enum ChargerOff { PAUSED, WAITING, ENDED }
    /**
     * A fresh charging=off reading below 99% with no schedule holding current off. The first one opens a
     * charger pause; later ones keep it alive until CHARGER_PAUSE_MAX_MS, then the recording should end.
     * An open schedule pause whose grace has run out ends it as before.
     */
    public synchronized ChargerOff chargerStopped(long wall,long mono) throws IOException {
        return chargerStopped(wall,mono,CHARGER_PAUSE_MAX_MS);
    }
    /** maxMs: how long the pause may last (an automatic recording waits less, ChargeAutoRecordPolicy). */
    public synchronized ChargerOff chargerStopped(long wall,long mono,long maxMs) throws IOException {
        if(active==null)return ChargerOff.ENDED;
        if(!paused){pause(wall,mono,true);return active==null?ChargerOff.ENDED:ChargerOff.PAUSED;}
        if(!chargerPaused||mono-pausedSinceMono>=maxMs)return ChargerOff.ENDED;
        heartbeat(mono);return ChargerOff.WAITING;
    }

    /**
     * scheduled: Volt's own plan is holding current off now. A schedule can be confirmed off before
     * charging resumes, so an open pause keeps holding for PAUSE_RELEASE_GRACE_MS after that.
     */
    public synchronized boolean holdsSchedulePause(boolean scheduled,long mono){
        if(scheduled){pauseGraceUntilMono=mono+PAUSE_RELEASE_GRACE_MS;return true;}
        return active!=null&&paused&&mono<pauseGraceUntilMono;
    }
    /** Fresh not-charging readings keep a paused capture alive; nothing is sampled. */
    public synchronized void heartbeat(long mono){if(active!=null&&paused&&mono>lastFreshMono)lastFreshMono=mono;}
    public synchronized void resume(long wall,long mono) throws IOException {
        if(active==null||!paused)return;
        append(active,"p,"+wall+","+mono+",0\n");paused=false;chargerPaused=false;lastFreshMono=Math.max(lastFreshMono,mono);markGap();
    }
    /**
     * Ends the recording. Returns whether it was kept: an automatic recording the rules judge too short (time from its
     * start to its last saved sample, or battery points gained between its first and last saved samples) is deleted.
     */
    public synchronized boolean stop(Stop reason) throws IOException {
        if(active==null)return false;
        boolean automatic=labelSource!=null,kept=!automatic||samples>0&&rules.keep(lastWriteMono-startMono,lastSoc-firstSoc);
        File file=active;active=null;profile=null;charger=null;labelSource=null;
        // Close an open schedule pause at its last fresh reading so its length survives the stop.
        if(paused)append(file,"p,"+(startWall+(lastFreshMono-startMono))+","+lastFreshMono+",0\n");
        paused=false;chargerPaused=false;resetElectricalCandidate();
        append(file,"end,"+reason.name()+"\n");
        boolean discarded=!kept&&file.delete();
        File saved=new File(directory,file.getName().replace(".active",".csv"));
        if(!discarded&&!file.renameTo(saved))throw new IOException("Charge could not be finalized");
        // Saved first, then room made, by count only (the budget in bytes waits for maintain(), once it is compressed): a
        // process death in between leaves one file too many, which the next start trims the same way. There is no moment
        // when the old recording and the new one are both gone.
        if(!discarded){justSaved.clear();justSaved.add(saved.getName());}
        pruneTo(maxRecordings,Long.MAX_VALUE);
        return !discarded&&saved.exists();
    }
    /** Run outside the UI thread. Only the list is taken under the lock: reading up to MAX_RECORDINGS files' facts is not. */
    public void clear(String targetProfile) throws IOException {
        final List<File> source;final File current;
        synchronized(this){
            if(active!=null&&(targetProfile==null||targetProfile.equals(profile)))throw new IllegalStateException("Stop charge recording first");
            source=files();current=active;
        }
        for(File file:source) {
            if(file.equals(current))continue;
            Facts facts;
            try{facts=file.getName().endsWith(".csv")?facts(file):null;}catch(FileNotFoundException removed){if(!file.exists())continue;throw removed;}
            if(targetProfile==null||facts!=null&&targetProfile.equals(facts.profile))remove(file,"Charge history could not be cleared");
        }
    }
    /**
     * Run outside the UI thread. Only closed, complete files are considered for learning, and of those only the newest
     * {@link #LEARN_SESSIONS} that qualify for each scooter and charger are read; the others are known by their facts.
     */
    public Snapshot snapshot() throws IOException {
        final List<File> source;final File current;
        synchronized(this){source=files();current=active;}
        Map<String,List<File>> chosen=new LinkedHashMap<>();Map<String,Facts> subjects=new LinkedHashMap<>();int sessions=0;long bytes=0;
        Map<String,Integer> qualified=new HashMap<>(),recent=new HashMap<>();
        Map<String,double[]> gauge=new LinkedHashMap<>();
        for(int i=source.size()-1;i>=0;i--) {
            File file=source.get(i);
            bytes+=file.length();if(file.equals(current)||!file.getName().endsWith(".csv"))continue;
            Facts facts;
            try{facts=facts(file);}catch(FileNotFoundException removed){if(!file.exists())continue;throw removed;}
            if(facts==null)continue;sessions++;
            if(facts.gaugeAh!=null){
                double[] sum=gauge.computeIfAbsent(facts.profile,ignored->new double[3]);
                if(sum[2]<LEARN_SESSIONS){sum[0]+=facts.gaugeAh;sum[1]+=facts.gaugePoints;sum[2]++;}
            }
            // A recording that ended with a guessed charger label teaches no curve (it stays in the list, marked as a guess).
            // Its gauge evidence above counts: ampere-hours per gauge point does not depend on which charger supplied them.
            if(!facts.qualified||!learnsCurve(file.getName()))continue;
            String key=key(facts.profile,facts.charger);List<File> files=chosen.computeIfAbsent(key,ignored->new ArrayList<>());
            if(recent.merge(facts.profile,1,Integer::sum)<=LEARN_SESSIONS)qualified.merge(key,1,Integer::sum);
            if(files.size()<LEARN_SESSIONS){files.add(0,file);subjects.put(key,facts);}
        }
        Map<String,Curve> curves=new LinkedHashMap<>();
        for(Map.Entry<String,List<File>> item:chosen.entrySet()){
            // Oldest first, as before: the newest LEARN_SESSIONS, read now in full.
            List<Evidence> evidence=new ArrayList<>();
            for(File file:item.getValue()){
                Session session;
                try{session=readSaved(file);}catch(FileNotFoundException removed){if(!file.exists())continue;throw removed;}
                Evidence bins=session==null?null:session.evidence();
                if(bins!=null)evidence.add(bins);
            }
            Facts facts=subjects.get(item.getKey());
            if(!evidence.isEmpty())curves.put(item.getKey(),new Curve(facts.profile,facts.charger,evidence));
        }
        Map<String,BatteryCapacityEstimate.ChargeEvidence> charge=new LinkedHashMap<>();
        for(Map.Entry<String,double[]> item:gauge.entrySet())
            charge.put(item.getKey(),new BatteryCapacityEstimate.ChargeEvidence(item.getValue()[0],item.getValue()[1],(int)item.getValue()[2]));
        return new Snapshot(curves,sessions,bytes,charge,qualified);
    }
    /**
     * The analysis worker's upkeep, after a snapshot: compresses every finished plain recording (an older Volt's, a
     * restored one, or one just saved) and rotates the folder to the count and the budget. Compression happens outside
     * the lock, so the running recording is never kept waiting; a file that changed meanwhile is left for the next run.
     * Returns how many recordings it removed.
     */
    public int maintain() throws IOException {
        final List<File> source;final File current;
        synchronized(this){source=files();current=active;}
        for(File file:source){
            if(file.equals(current)||!file.getName().endsWith(".csv"))continue;
            // One that failed (storage full, say) stays plain for the next run; rotation below still runs.
            try{if(!settled(file))compress(file);facts(file);}catch(IOException skipped){}
        }
        synchronized(this){return pruneTo(maxRecordings,budgetBytes);}
    }
    /** Rewrites one finished plain journal gzip-compressed under its own name, keeping its modification time. */
    private boolean compress(File file)throws IOException{
        long length=file.length(),modified=file.lastModified();
        if(length<=0||length>MAX_FILE_BYTES+1024)return false;
        byte[] plain;
        if(compressed(file)){
            // Compressed without facts (or with facts this version cannot read): written again with them.
            if(Facts.parse(gzipComment(file))!=null)return false;
            try(InputStream in=open(file)){plain=readAll(in);}catch(java.util.zip.ZipException|EOFException corrupt){return false;}
        }else plain=java.nio.file.Files.readAllBytes(file.toPath());
        Session session=parse(plain);
        // An unreadable journal stays as it is: it is no recording to Volt, and pruning takes it first.
        if(session==null)return false;
        Facts facts=Facts.of(session);
        File temporary=new File(directory,file.getName()+".repair");
        try{
            writeCompressed(temporary,plain,facts.line());
            byte[] check;try(InputStream in=open(temporary)){check=readAll(in);}
            if(!Arrays.equals(check,plain))throw new IOException("Compressed charge did not read back");
            // Rotation is by modification time: the compressed file keeps the recording's.
            if(!temporary.setLastModified(modified)||temporary.lastModified()!=modified)return false;
            synchronized(this){
                if(file.equals(active)||!file.isFile()||file.length()!=length||file.lastModified()!=modified)return false;
                if(!temporary.renameTo(file))return false;
                synchronized(known){known.put(file.getName(),new Known(file.length(),file.lastModified(),facts,true));}
                return true;
            }
        }finally{if(temporary.exists())temporary.delete();}
    }
    /**
     * {Ah, gauge points} from a completed charge's first percentage change to its first displayed
     * 100%, including the gauge's late catch-up; null unless that stretch starts below 80% and stays
     * continuous. A recorded gap longer than GAUGE_CHARGE_GAP_MS means charge went unobserved,
     * so counting restarts at the next percentage change after it.
     */
    static double[] gaugeCharge(Session session){
        if(session.stop!=Stop.COMPLETE||session.points.size()<2)return null;
        List<Point> points=session.points;int start=-1;double ah=0;
        for(int i=1;i<points.size();i++){
            Point a=points.get(i-1),b=points.get(i);
            if(b.gap&&b.mono-a.mono>GAUGE_CHARGE_GAP_MS){start=-1;ah=0;continue;}
            if(start<0){if(b.soc>a.soc)start=i;continue;}
            long step=b.mono-a.mono;
            if(step<=0||step>GAUGE_CHARGE_MAX_STEP_MS||!Double.isFinite(a.current)||!Double.isFinite(b.current))return null;
            ah+=step/3600000d*(Math.abs(a.current)+Math.abs(b.current))/2;
            if(b.soc>=100){double from=points.get(start).soc,gaugePoints=100-from;return from<80&&ah>0&&gaugePoints>0?new double[]{ah,gaugePoints}:null;}
        }
        return null;
    }
    /** Call on an analysis worker. Active files are excluded, so each returned session is stable. Newest first, in full. */
    public List<HistorySession> recordings(String targetProfile)throws IOException {return history(targetProfile,Integer.MAX_VALUE);}
    /**
     * Call on an analysis worker. One scooter's saved recordings, newest first: the newest `full` with their points, the
     * rest as summaries ({@link HistorySession#light}) from their facts, so a long history costs no parsing or memory.
     */
    public List<HistorySession> history(String targetProfile,int full)throws IOException {
        if(targetProfile==null||!targetProfile.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Select a scooter");
        final List<File> source;synchronized(this){source=files();}
        List<HistorySession> result=new ArrayList<>();
        for(int i=source.size()-1;i>=0;i--){
            File file=source.get(i);
            if(!file.getName().endsWith(".csv"))continue;
            try{
                Facts facts=facts(file);
                if(facts==null||!targetProfile.equals(facts.profile))continue;
                Session session=result.size()<full?readSaved(file):null;
                if(result.size()<full&&session==null)continue;
                result.add(new HistorySession(file.getName().replace(".csv",""),session==null?facts:Facts.of(session),session));
            }catch(FileNotFoundException removed){if(!file.exists())continue;throw removed;}
        }
        return Collections.unmodifiableList(result);
    }
    /** Call on an analysis worker. One saved recording of this scooter in full, or null when it is gone or unreadable. */
    public HistorySession recording(String targetProfile,String id)throws IOException {
        if(targetProfile==null||!targetProfile.matches("[A-Za-z0-9_-]{1,128}")||id==null||!id.matches("[a-f0-9-]{36}"))return null;
        File file=new File(directory,id+".csv");
        Session session;
        try{session=readSaved(file);}catch(FileNotFoundException removed){return null;}
        return session==null||!targetProfile.equals(session.profile)?null:new HistorySession(id,Facts.of(session),session);
    }
    /**
     * Stream saved journals for one scooter. No owner credentials, names, or radio identifiers. Only the list is taken under
     * the lock: reading, inflating and writing up to MAX_RECORDINGS recordings is not, so the service's main-thread calls
     * never wait for an export. A recording rotated away meanwhile is left out.
     */
    public int exportJson(String targetProfile, Writer out) throws IOException {
        if(targetProfile==null||!targetProfile.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Select a scooter");
        final List<File> files;
        synchronized(this){
            if(active!=null)throw new IllegalStateException("Stop charge recording before exporting");
            files=files();
        }
        out.write("{\"format\":\"navee-charge-recordings-v1\",\"charger_selection\":\"manual\",\"sessions\":[");
        int count=0;
        for(File file:files) {
            if(!file.getName().endsWith(".csv"))continue;
            Facts facts;Session session;
            try{
                facts=facts(file);
                if(facts==null||!targetProfile.equals(facts.profile))continue;
                session=readSaved(file);
            }catch(FileNotFoundException removed){if(!file.exists())continue;throw removed;}
            if(session==null||!targetProfile.equals(session.profile))continue;
            if(count++>0)out.write(",");
            // Additive fields: whether Record charges automatically started it, and how its charger label was chosen.
            LabelSource source=labelSource(file.getName());
            out.write("{\"id\":\""+file.getName().replace(".csv","")+"\",\"profile_id\":\""+session.profile
                    +"\",\"charger\":\""+session.charger.name()+"\",\"started_automatically\":"+(source!=null)
                    +",\"label_source\":"+(source==null?"null":"\""+source.name().toLowerCase(Locale.ROOT)+"\"")+",\"label_audit\":"+auditJson(labelAudit(file.getName()))+",\"started_wall_ms\":"+session.startedWall
                    +",\"started_elapsed_ms\":"+session.startedMono+",\"stop_reason\":"+(session.stop==null?"null":"\""+session.stop.name()+"\"")
                    +",\"qualified_for_learning\":"+(session.evidence()!=null&&learnsCurve(file.getName()))+",\"observed_complete_elapsed_ms\":"+session.observedCompletion()+",\"schedule_pauses\":[");
            writePauses(out,session,0);
            // Additive field: times the charger itself stopped below 99% and the recording waited for it.
            out.write("],\"charger_pauses\":[");
            writePauses(out,session,1);
            out.write("],\"samples\":[");
            boolean first=true;
            for(Point point:session.points) {
                if(!first)out.write(",");first=false;
                out.write("{\"wall_ms\":"+point.wall+",\"elapsed_ms\":"+point.mono+",\"battery_percent\":"+point.soc
                        +",\"voltage_v\":"+point.voltage+",\"current_a\":"+point.current+",\"power_w\":"+point.signedPower
                        +",\"temperature_c\":"+point.temperature+",\"gap_before\":"+point.gap+",\"estimate\":");
                writeEstimateJson(out,point.estimate);out.write("}");
            }
            out.write("]}");
        }
        out.write("]}");out.flush();return count;
    }
    /** Additive field: an automatic recording's charger setting at its start and detection's settled family, or null. */
    private static String auditJson(LabelAudit audit){
        if(audit==null)return "null";
        return "{\"charger_setting_at_start\":\""+(audit.setting==null?"automatic":audit.setting.name())
                +"\",\"detected_charger\":"+(audit.detected==null?"null":"\""+audit.detected.name()+"\"")+"}";
    }
    public static String key(String profile,Charger charger){return profile+":"+charger.name();}
    /** The battery temperatures (C, inclusive) a fast charge learns from: 68–100 F (owner, Nov 19 2026). */
    public static final double LEARN_MIN_C=20,LEARN_MAX_C=38;
    /**
     * Whether a reading on this charger at this battery temperature may teach a learned curve (owner, Nov 19 2026: on
     * every model, a fast charger's percents with the battery outside LEARN_MIN_C–LEARN_MAX_C don't feed the curve,
     * because the battery limits its current there). The stock charger always learns: its current stays below the
     * battery's thermal limits. No temperature reported excludes nothing. The recording itself is never changed.
     */
    public static boolean learnsAtTemperature(Charger charger,Double batteryC){
        return charger!=Charger.HIGH_SPEED||batteryC==null||!Double.isFinite(batteryC)||batteryC>=LEARN_MIN_C&&batteryC<=LEARN_MAX_C;
    }
    /**
     * Whether a saved recording may teach its charger's curve: a manual one, or an automatic one whose label was chosen,
     * detected or the charger setting's. A label that is still a guess when the recording ends labels the file and nothing
     * else. The curve it teaches is its final label (the header, which a relabel rewrites).
     */
    public static boolean learnsCurve(String id){return labelSource(id)!=LabelSource.GUESS;}
    private static String csv(Object value){return value==null?"":value.toString();}
    private static void writeEstimateJson(Writer out,Estimate value)throws IOException{
        if(value==null){out.write("null");return;}
        out.write("{\"estimator_version\":\""+value.version+"\",\"remaining_minutes_to_full\":"+value.remainingMinutesToFull
                +",\"remaining_minutes_to_limit\":"+value.remainingMinutesToLimit+",\"target_percent\":"+value.targetPercent
                +",\"full_source\":"+(value.fullSource==null?"null":"\""+value.fullSource.name()+"\"")
                +",\"limit_source\":"+(value.limitSource==null?"null":"\""+value.limitSource.name()+"\"")+",\"learned_sessions\":"+value.learnedSessions+"}");
    }
    public static boolean fresh(Value value,long now){return value!=null&&value.value!=null&&Double.isFinite(value.value)&&value.source>=0&&value.mono>0&&now>=value.mono&&now-value.mono<=MAX_FRAME_AGE_MS;}
    private static boolean between(double value,double low,double high){return Double.isFinite(value)&&value>=low&&value<=high;}
    private static void append(File file,String text)throws IOException{
        try(FileOutputStream out=new FileOutputStream(file,true)){out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();}
    }
    /** Saved and running journals, oldest first (modification time, then name), each file's time read once. */
    private List<File> files() throws IOException {
        File[] items=directory.listFiles((dir,name)->JOURNAL.matcher(name).matches());
        if(items==null)throw new IOException("Charge recordings unavailable");
        Map<File,Long> times=new HashMap<>();for(File item:items)times.put(item,item.lastModified());
        List<File> result=new ArrayList<>(Arrays.asList(items));
        result.sort(Comparator.comparingLong((File file)->times.get(file)).thenComparing(File::getName));return result;
    }
    /** Whether a journal already has its end line (a stop wrote it before a process death cut the stop short). */
    private static boolean ended(File file)throws IOException{
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null)if(line.startsWith("end,"))return true;
        }
        return false;
    }
    /**
     * Rotates the saved recordings to at most maxCount and maxBytes on disk (the running journal is neither counted nor
     * touched), and returns how many went. Automatic and manual recordings are alike here. A file that is no usable
     * recording goes first: one Volt cannot read (not listed, learned or exported), then, oldest first, one too short or
     * broken to teach a curve or one whose charger label is still a guess. Then the oldest file of the scooter and charger with
     * the most recordings, so frequent stock charges never evict a minority high-speed curve: about two per scooter and
     * charger are protected while the limits can hold them. Never the recording just saved or recovered, whatever its
     * modification time (a restore dates a recording by its saved start).
     */
    private int pruneTo(int maxCount,long maxBytes)throws IOException{
        if(maxBytes==Long.MAX_VALUE){
            // By count alone (a stop, on the main thread): names suffice until it is over.
            String[] names=directory.list();int saved=0;
            if(names!=null)for(String name:names)if(JOURNAL.matcher(name).matches()&&(active==null||!name.equals(active.getName())))saved++;
            if(names!=null&&saved<=maxCount)return 0;
        }
        List<File> entries=files();int count=0;long bytes=0;
        for(File file:entries)if(!file.equals(active)){count++;bytes+=file.length();}
        if(count<=maxCount&&bytes<=maxBytes)return 0;
        Map<String,List<File>> qualified=new LinkedHashMap<>();List<File> rejected=new ArrayList<>(),unreadable=new ArrayList<>();
        for(File file:entries){
            if(file.equals(active)||justSaved.contains(file.getName()))continue;
            String bucket;Facts facts;
            try{facts=file.getName().endsWith(".csv")?facts(file):null;bucket=bucket(file);}
            catch(FileNotFoundException removed){if(!file.exists()){count--;continue;}throw removed;}
            if(facts==null)unreadable.add(file);
            else if(bucket==null)rejected.add(file);
            else qualified.computeIfAbsent(bucket,ignored->new ArrayList<>()).add(file);
        }
        // A file Volt cannot read is no recording (not listed, learned or exported): it never costs one its place.
        rejected.addAll(0,unreadable);
        int removed=0;
        // Failed/short captures must not evict the only usable curve for another charger.
        for(File file:rejected){
            if(count<=maxCount&&bytes<=maxBytes)break;
            long length=file.length();remove(file,"Old charge recordings could not be removed");count--;bytes-=length;removed++;
        }
        // Keep buckets balanced, preferring the oldest file in the most populated bucket.
        while(count>maxCount||bytes>maxBytes){
            List<File> largest=null;
            for(List<File> bucket:qualified.values())if(!bucket.isEmpty()&&(largest==null||bucket.size()>largest.size()
                    ||bucket.size()==largest.size()&&bucket.get(0).lastModified()<largest.get(0).lastModified()))largest=bucket;
            if(largest==null)break;
            File file=largest.remove(0);long length=file.length();
            remove(file,"Old charge recordings could not be removed");count--;bytes-=length;removed++;
        }
        return removed;
    }
    private void remove(File file,String failure)throws IOException{
        if(!file.delete()&&file.exists())throw new IOException(failure);
        synchronized(known){known.remove(file.getName());}
    }
    /** The scooter and charger whose curve a saved file protects, or null: unreadable, unqualified, a guess or not saved. */
    private String bucket(File file)throws IOException{
        // A guessed label is in no charger's bucket: it protects no curve, so pruning may remove it first.
        if(!file.getName().endsWith(".csv")||!learnsCurve(file.getName()))return null;
        Facts facts=facts(file);
        return facts==null||!facts.qualified?null:key(facts.profile,facts.charger);
    }
    /**
     * A saved recording read in full. Facts come from the header alone, so a compressed file damaged after it was written
     * is found out here: from then on it is no recording (not listed, learned or exported, and pruned first).
     */
    private Session readSaved(File file)throws IOException{
        long length=file.length(),modified=file.lastModified();
        Session session=read(file);
        if(session==null)synchronized(known){known.put(file.getName(),new Known(length,modified,null,false));}
        return session;
    }
    private static final class Known {
        final long length,modified;final Facts facts;
        /** Its facts came from its compressed header: nothing left for maintain() to do. */
        final boolean packed;
        Known(long length,long modified,Facts facts,boolean packed){this.length=length;this.modified=modified;this.facts=facts;this.packed=packed;}
    }
    /** Unchanged since it was found compressed with its facts, or unreadable: maintain() leaves it. */
    private boolean settled(File file){
        long length=file.length(),modified=file.lastModified();
        synchronized(known){Known cached=known.get(file.getName());return cached!=null&&cached.length==length&&cached.modified==modified&&(cached.packed||cached.facts==null);}
    }
    /**
     * A saved file's facts, or null when Volt cannot read it: from the cache while the file is unchanged, else from a
     * compressed file's header, else by reading it. Safe on any thread; reads happen outside every lock.
     */
    private Facts facts(File file)throws IOException{
        long length=file.length(),modified=file.lastModified();
        synchronized(known){Known cached=known.get(file.getName());if(cached!=null&&cached.length==length&&cached.modified==modified)return cached.facts;}
        if(!file.exists())throw new FileNotFoundException(file.getName());
        Facts facts=Facts.parse(gzipComment(file));boolean packed=facts!=null;
        if(facts==null){Session session=read(file);facts=session==null?null:Facts.of(session);}
        synchronized(known){known.put(file.getName(),new Known(length,modified,facts,packed));}
        return facts;
    }
    private static double median(List<Double> values){
        Collections.sort(values);int middle=values.size()/2;
        return values.size()%2==1?values.get(middle):(values.get(middle-1)+values.get(middle))/2;
    }
    private static final class Point {
        final long wall,mono;final double soc,power,voltage,current,signedPower;final Double temperature;final boolean gap;
        final Estimate estimate;
        Point(long wall,long mono,double soc,double voltage,double current,double signedPower,Double temperature,boolean gap,Estimate estimate){
            this.wall=wall;this.mono=mono;this.soc=soc;this.voltage=voltage;this.current=current;this.signedPower=signedPower;
            this.power=Math.abs(signedPower);this.temperature=temperature;this.gap=gap;this.estimate=estimate;
        }
    }
    private static final class Evidence {
        final double[] minutes=new double[100],watts=new double[100];
        FinishingCurve finishing;
        Evidence(){Arrays.fill(minutes,Double.NaN);Arrays.fill(watts,Double.NaN);}
    }
    /**
     * A complete terminal trajectory, not an inferred battery capacity. Retain real short SOC
     * transitions: a gauge can rush through several percentages then spend much longer at 99%.
     * Only the observed range is usable; no bulk-power or below-range extrapolation is made.
     */
    private static final class FinishingCurve {
        /** Pack/sensor offset allowance between charges, as in the high-speed reference. */
        static final double FLAT_VOLT_TOLERANCE = .35;
        final List<Point> points;
        final boolean recoveredAfterBreak;
        /** Minutes from the first point to the first at or above each whole level (NaN below the start), for joins. */
        private final double[] reach = new double[101];
        FinishingCurve(List<Point> points, boolean recoveredAfterBreak) {
            this.points = new ArrayList<>(points); this.recoveredAfterBreak = recoveredAfterBreak;
            Arrays.fill(reach, Double.NaN);
            Point first = this.points.get(0); int level = (int) Math.ceil(first.soc);
            for (Point point : this.points) while (level <= 100 && point.soc >= level) reach[level++] = (point.mono - first.mono) / 60000d;
        }

        static FinishingCurve from(Session session) {return from(session, false);}
        /**
         * normalTemperatures: a reading the charger and battery temperature keep from learning (learnsAtTemperature)
         * also invalidates the prefix, like a break, so the finish starts after the last such reading. It does not mark
         * recoveredAfterBreak: the recording's qualification is judged without it (Session.evidence).
         */
        static FinishingCurve from(Session session, boolean normalTemperatures) {
            List<Point> points = session.points;
            if (session.stop != Stop.COMPLETE || points.size() < 61 || points.get(points.size()-1).soc != 100) return null;
            int start = -1;
            boolean recoveredAfterBreak = false;
            for (int i = 1; i < points.size(); i++) {
                Point before = points.get(i-1), point = points.get(i);
                if (point.gap || point.mono <= before.mono || point.mono - before.mono > MAX_GAP_MS
                        || Math.abs((point.wall-before.wall)-(point.mono-before.mono)) > 2000
                        || point.soc < before.soc || point.soc-before.soc > 1
                        || point.soc != Math.floor(point.soc) || before.soc != Math.floor(before.soc)
                        || before.soc >= 100 || before.power < 1
                        || (point.soc < 100 && point.power < 1)) {
                    start = -1;
                    recoveredAfterBreak = true;
                    continue;
                }
                if (normalTemperatures && (!learnsAtTemperature(session.charger, point.temperature)
                        || !learnsAtTemperature(session.charger, before.temperature))) { start = -1; continue; }
                // A break invalidates only the prefix. Wait for a later observed rise so the
                // first partially recorded SOC bin after a reconnect is never included.
                if (start < 0 && point.soc > before.soc) start = i;
            }
            if (start < 0 || points.size()-start < 61 || 100-points.get(start).soc < 10
                    || points.get(points.size()-1).mono-points.get(start).mono < 600000) return null;
            return new FinishingCurve(points.subList(start, points.size()), recoveredAfterBreak);
        }

        /**
         * The recording's own minutes from its start (its first reading at its starting level) to its first reading at
         * target or above, its observed 100% for 100; null below its start.
         */
        Double minutesFromStart(double target) {
            if (!(target >= points.get(0).soc) || target > 100) return null;
            double minutes = reach[(int) Math.ceil(target)];
            return Double.isFinite(minutes) ? minutes : null;
        }

        Double estimate(double soc, double liveWatts, Double liveVolts) {
            if (soc != Math.floor(soc) || soc < points.get(0).soc || soc >= 100) return null;
            List<Point> stage = new ArrayList<>(); Point next = null;
            for (Point point : points) {
                if (point.soc == soc) stage.add(point);
                else if (!stage.isEmpty()) { next = point; break; }
            }
            if (stage.size() < 2 || next == null) return null;
            double low = Double.POSITIVE_INFINITY, high = 0, runningLow = Double.POSITIVE_INFINITY;
            for (Point point : stage) {
                // A substantial rebound makes power an ambiguous indicator of progress.
                if (point.power > runningLow + Math.max(5, runningLow*.1)) return null;
                runningLow = Math.min(runningLow, point.power);
                low = Math.min(low, point.power); high = Math.max(high, point.power);
            }
            // A plausibility gate only; the charger choice remains manual. Never substitute its
            // nominal wattage for a live reading, or map a different charger into this finish.
            if (liveWatts < low*.85 || liveWatts > high*1.15) return null;
            double at;
            if (low > high*.8) {
                // Nearly flat power cannot locate us within this SOC plateau. Use its midpoint
                // only for short intervals, without presenting a fabricated exact countdown.
                if (next.mono-stage.get(0).mono > 600000) return null;
                // Flat bulk power matches any stage at the same current; paired pack voltage
                // must still lie near this stage (the gauge can sit volts away from it).
                if (liveVolts != null) {
                    double lowVolts = Double.POSITIVE_INFINITY, highVolts = 0;
                    for (Point point : stage) { lowVolts = Math.min(lowVolts, point.voltage); highVolts = Math.max(highVolts, point.voltage); }
                    if (liveVolts < lowVolts - FLAT_VOLT_TOLERANCE || liveVolts > highVolts + FLAT_VOLT_TOLERANCE) return null;
                }
                at = (stage.get(0).mono + next.mono) / 2d;
            } else if (liveVolts != null) {
                double[][] samples = new double[stage.size()][3];
                long finish = points.get(points.size()-1).mono;
                for (int i = 0; i < stage.size(); i++) {
                    Point point = stage.get(i);
                    samples[i] = new double[] {point.power, point.voltage, (finish-point.mono)/60000d};
                }
                return ChargeTaperMatcher.estimate(liveWatts, liveVolts, samples);
            } else {
                double closest = Double.POSITIVE_INFINITY;
                for (Point point : stage) closest = Math.min(closest, Math.abs(point.power-liveWatts));
                double tolerance = Math.max(1.5, liveWatts*.02), sum = 0;
                int count = 0; long first = 0, last = 0;
                for (Point point : stage) if (Math.abs(point.power-liveWatts) <= closest+tolerance) {
                    if (count == 0) first = point.mono;
                    last = point.mono; sum += point.mono; count++;
                }
                if (count == 0 || last-first > 600000) return null;
                at = sum/count;
            }
            return Math.max(0, (points.get(points.size()-1).mono-at)/60000d);
        }
    }
    private static final class Session {
        String profile;Charger charger;Stop stop;long startedWall,startedMono;int format;List<Point> points=new ArrayList<>();
        /** {startWall,startMono,endWall,endMono,charger}; end 0 while a pause was still open; charger 1 for a charger pause. */
        List<long[]> pauses=new ArrayList<>();
        long pausedMs(long from,long to,long kind){long total=0;for(long[] pause:pauses)if(pause[4]==kind)total+=Math.max(0,Math.min(pause[3]==0?to:pause[3],to)-Math.max(pause[1],from));return total;}
        long pausedMs(long from,long to){long total=0;for(long[] pause:pauses)total+=Math.max(0,Math.min(pause[3]==0?to:pause[3],to)-Math.max(pause[1],from));return total;}
        Long observedCompletion(){
            if(stop!=Stop.COMPLETE||points.size()<2)return null;
            long previous=startedMono-1;
            for(Point point:points){if(point.mono<startedMono||point.mono<=previous||point.mono-startedMono>MAX_SESSION_MS)return null;previous=point.mono;}
            Point end=points.get(points.size()-1),before=points.get(points.size()-2);
            // A reconnect already at 100% tells us it finished during the gap, not when.
            return end.soc==100&&before.soc<100&&!end.gap&&end.mono-before.mono<=MAX_GAP_MS?end.mono:null;
        }
        Evidence evidence(){
            if(stop==null||stop==Stop.INTERRUPTED||stop==Stop.PROCESS_ENDED||stop==Stop.ERROR||points.size()<61)return null;
            // The recording qualifies as before; percents a fast charge recorded outside the learning temperatures
            // (learnsAtTemperature) then teach nothing, and the finish starts after them.
            FinishingCurve whole=FinishingCurve.from(this);
            Evidence result=new Evidence();result.finishing=whole;boolean[] repeated=new boolean[100],outsideBins=new boolean[100];List<Double> powerSamples=new ArrayList<>();
            Point previous=null;double anchorSoc=Double.NaN;long anchorTime=0,covered=0;int bins=0;boolean outside=false;
            for(Point point:points){
                boolean continuous=previous!=null&&!point.gap&&point.mono>previous.mono&&point.mono-previous.mono<=MAX_GAP_MS
                        &&Math.abs((point.wall-previous.wall)-(point.mono-previous.mono))<=2000;
                if(!continuous||previous!=null&&(point.soc<previous.soc||point.soc-previous.soc>1)){anchorSoc=Double.NaN;anchorTime=0;powerSamples.clear();outside=false;}
                else covered+=point.mono-previous.mono;
                if(continuous&&point.soc>previous.soc){
                    if(Double.isFinite(anchorSoc)&&point.soc-anchorSoc==1&&anchorSoc==Math.floor(anchorSoc)){
                        int bin=(int)anchorSoc;double minutes=(point.mono-anchorTime)/60000d;
                        if(bin>=0&&bin<100&&minutes>=.5&&minutes<=90&&!repeated[bin]){
                            if(Double.isFinite(result.minutes[bin])){result.minutes[bin]=Double.NaN;result.watts[bin]=Double.NaN;repeated[bin]=true;bins--;}
                            else{result.minutes[bin]=minutes;if(powerSamples.size()>=3)result.watts[bin]=median(powerSamples);bins++;}
                            if(outside||!learnsAtTemperature(charger,point.temperature))outsideBins[bin]=true;
                        }
                    }
                    anchorSoc=point.soc;anchorTime=point.mono;powerSamples.clear();outside=false;
                }
                // The next SOC's first sample (including a zero-current cutoff packet) belongs
                // to the next interval, so it cannot dilute power from the completed interval.
                if(point.soc==anchorSoc){powerSamples.add(point.power);outside|=!learnsAtTemperature(charger,point.temperature);}
                previous=point;
            }
            long first=points.get(0).mono,last=points.get(points.size()-1).mono;
            // A schedule or charger pause is waiting, not missing data: coverage is judged on charging time.
            long elapsed=last-first-pausedMs(first,last);
            boolean binsQualified=bins>=MIN_BINS&&elapsed>=600000&&covered>=elapsed*.9
                    || result.finishing!=null&&!result.finishing.recoveredAfterBreak;
            if(!binsQualified){
                // A valid finishing suffix does not qualify the rest of a sparse recording
                // for the independent per-SOC model, including bins before a long gap.
                Arrays.fill(result.minutes,Double.NaN);Arrays.fill(result.watts,Double.NaN);
            }
            boolean qualified=whole!=null||binsQualified;
            for(int bin=0;bin<100;bin++)if(outsideBins[bin]){result.minutes[bin]=Double.NaN;result.watts[bin]=Double.NaN;}
            if(whole!=null&&charger==Charger.HIGH_SPEED)result.finishing=FinishingCurve.from(this,true);
            return qualified?result:null;
        }
    }
    /** Header facts of a saved journal, read without recovery or any write to its folder. */
    static final class Journal {
        final String profile;final long startedWall,startedMono;final boolean finished;
        Journal(String profile,long wall,long mono,boolean finished){this.profile=profile;startedWall=wall;startedMono=mono;this.finished=finished;}
    }
    /** A compressed journal answers from its facts; a plain one is read. */
    static Journal inspectJournal(File file)throws IOException{
        if(!file.isFile()||java.nio.file.Files.isSymbolicLink(file.toPath()))throw new IOException("Unexpected charge file.");
        Facts facts=Facts.parse(gzipComment(file));
        if(facts!=null)return new Journal(facts.profile,facts.startedWall,facts.startedMono,facts.stop!=null);
        Session session=read(file);if(session==null)throw new IOException("The backup contains an unreadable charge.");
        return new Journal(session.profile,session.startedWall,session.startedMono,session.stop!=null);
    }
    /** Private restore staging only: the header names the destination scooter, samples are unchanged. Backups hold plain journals. */
    static void reassignJournal(File source,String profile)throws IOException{
        if(profile==null||!profile.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Choose a destination scooter.");
        if(compressed(source))throw new IOException("The backup contains an unreadable charge.");
        inspectJournal(source);byte[] bytes=java.nio.file.Files.readAllBytes(source.toPath());int end=0;
        while(end<bytes.length&&end<=512&&bytes[end]!='\n')end++;
        if(end>=bytes.length||end>512)throw new IOException("The backup contains an unreadable charge.");
        String[] header=new String(bytes,0,end,StandardCharsets.UTF_8).split(",",-1);
        if(header.length!=5)throw new IOException("The backup contains an unreadable charge.");
        header[1]=profile;File temporary=new File(source.getPath()+".mapping");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(String.join(",",header).getBytes(StandardCharsets.UTF_8));out.write(bytes,end,bytes.length-end);out.getFD().sync();}
        try{temporary.setLastModified(source.lastModified());if(!profile.equals(inspectJournal(temporary).profile)||!temporary.renameTo(source))throw new IOException("Could not associate the restored charge.");}
        catch(IOException failure){temporary.delete();throw failure;}
    }
    private static void writePauses(Writer out,Session session,long kind) throws IOException {
        int count=0;
        for(long[] pause:session.pauses){if(pause[4]!=kind)continue;
            out.write((count++>0?",":"")+"{\"start_wall_ms\":"+pause[0]+",\"start_elapsed_ms\":"+pause[1]
                    +",\"end_wall_ms\":"+(pause[3]==0?"null":String.valueOf(pause[2]))+",\"end_elapsed_ms\":"+(pause[3]==0?"null":String.valueOf(pause[3]))+"}");}
    }
    /** A saved journal, plain or compressed; null when it is no readable recording (a corrupt compressed file included). */
    private static Session read(File file)throws IOException{
        if(file.length()>MAX_FILE_BYTES+1024)return null;
        try(InputStream in=open(file)){return read(in);}
        catch(java.util.zip.ZipException|EOFException corrupt){return null;}
    }
    private static Session parse(byte[] plain)throws IOException{return read(new ByteArrayInputStream(plain));}
    private static Session read(InputStream input)throws IOException{
        Session session=new Session();int lines=0;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null){
                if(++lines>MAX_SAMPLES+3+2*MAX_PAUSES||line.length()>512)return null;
                String[] parts=line.split(",",-1);
                try{
                    if(lines==1){if(parts.length!=5||!(parts[0].equals("charge-v1")||parts[0].equals("charge-v2"))||!parts[1].matches("[A-Za-z0-9_-]{1,128}"))return null;session.format=parts[0].equals("charge-v1")?1:2;session.profile=parts[1];session.charger=Charger.valueOf(parts[2]);session.startedWall=Long.parseLong(parts[3]);session.startedMono=Long.parseLong(parts[4]);if(session.startedWall<=0||session.startedMono<=0||session.startedWall>Long.MAX_VALUE-MAX_SESSION_MS||session.startedMono>Long.MAX_VALUE-MAX_SESSION_MS)return null;}
                    else if(parts.length==2&&parts[0].equals("end")){if(session.stop!=null)return null;session.stop=Stop.valueOf(parts[1]);}
                    else if(parts.length==4&&parts[0].equals("p")&&session.format==2&&session.stop==null){
                        long wall=Long.parseLong(parts[1]),mono=Long.parseLong(parts[2]);boolean charger=parts[3].equals("2"),begins=charger||parts[3].equals("1");
                        long[] open=session.pauses.isEmpty()?null:session.pauses.get(session.pauses.size()-1);boolean isOpen=open!=null&&open[3]==0;
                        if(wall<=0||mono<=0||!(begins||parts[3].equals("0"))||begins==isOpen)return null;
                        if(begins){if(session.pauses.size()>=MAX_PAUSES)return null;session.pauses.add(new long[]{wall,mono,0,0,charger?1:0});}
                        else{if(mono<open[1])return null;open[2]=wall;open[3]=mono;}
                    }
                    else if(parts.length==(session.format==1?9:16)&&parts[0].equals("s")&&session.stop==null){
                        if(session.points.size()>=MAX_SAMPLES)return null;
                        long wall=Long.parseLong(parts[1]),mono=Long.parseLong(parts[2]);double soc=Double.parseDouble(parts[3]);
                        double volts=Double.parseDouble(parts[4]),amps=Double.parseDouble(parts[5]),power=Double.parseDouble(parts[6]);
                        if(wall<=0||mono<=0||!between(soc,0,100)||!between(volts,1,100)||!between(amps,-30,30)||!between(power,-3000,3000)
                                ||Math.abs(volts*amps-power)>.001||!(parts[8].equals("0")||parts[8].equals("1")))return null;
                        if(!parts[7].isEmpty()&&!between(Double.parseDouble(parts[7]),-40,100))return null;
                        session.points.add(new Point(wall,mono,soc,volts,amps,power,parts[7].isEmpty()?null:Double.valueOf(parts[7]),parts[8].equals("1"),session.format==1?null:readEstimate(parts)));
                    }else return null;
                }catch(IllegalArgumentException bad){return null;}
            }
        }
        return session.profile==null?null:session;
    }
    /** A corrupt optional prediction must not erase sound raw measurements or learned evidence. */
    private static Estimate readEstimate(String[] parts){
        try{
            Double full=optionalNumber(parts[10]),limit=optionalNumber(parts[11]),target=optionalNumber(parts[12]);
            int sessions=Integer.parseInt(parts[15]);
            if(full!=null&&full<0||limit!=null&&limit<0||target!=null&&(target<=0||target>100)||sessions<0||sessions>LEARN_SESSIONS)return null;
            return new Estimate(parts[9],full,limit,target,parts[13].isEmpty()?null:Source.valueOf(parts[13]),parts[14].isEmpty()?null:Source.valueOf(parts[14]),sessions);
        }catch(IllegalArgumentException malformed){return null;}
    }
    private static Double optionalNumber(String value){
        if(value.isEmpty())return null;double number=Double.parseDouble(value);if(!Double.isFinite(number))throw new IllegalArgumentException("Invalid estimate");return number;
    }

    // ---- Compressed journals ----------------------------------------------------------------------------------------

    private static final int GZIP_COMMENT_FLAG=0x10, MAX_COMMENT=1024;
    /** Whether a file starts as gzip does. A plain journal starts with its "charge-v" header. */
    static boolean compressed(File file)throws IOException{
        try(InputStream in=new FileInputStream(file)){return in.read()==0x1f&&in.read()==0x8b;}
    }
    /**
     * A saved journal's text, decompressed when it is compressed, and never more than a journal can hold: a compressed
     * file that would expand past that, or is corrupt or cut short, fails with a ZipException or EOFException.
     */
    static InputStream open(File file)throws IOException{
        BufferedInputStream raw=new BufferedInputStream(new FileInputStream(file),65536);
        try{
            raw.mark(2);int first=raw.read(),second=raw.read();raw.reset();
            if(first!=0x1f||second!=0x8b)return raw;
            return new Bounded(new java.util.zip.GZIPInputStream(raw,65536),MAX_FILE_BYTES+1024);
        }catch(IOException|RuntimeException failure){raw.close();throw failure;}
    }
    /** The journal's plain length: exact for a plain file; for a compressed one, what it decompresses to, or -1 if corrupt. */
    static long plainLength(File file)throws IOException{
        if(!compressed(file))return file.length();
        long total=0;byte[] buffer=new byte[65536];
        try(InputStream in=open(file)){for(int n;(n=in.read(buffer))!=-1;)total+=n;}
        catch(java.util.zip.ZipException|EOFException corrupt){return -1;}
        return total;
    }
    /** A cheap guess at the plain length for listings: a compressed file's own size field (gzip ISIZE). */
    static long plainLengthHint(File file)throws IOException{
        if(!compressed(file))return file.length();
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            long length=in.length();if(length<18)return length;
            in.seek(length-4);byte[] size=new byte[4];in.readFully(size);
            return (size[0]&0xffL)|(size[1]&0xffL)<<8|(size[2]&0xffL)<<16|(size[3]&0xffL)<<24;
        }
    }
    /** SHA-256 of the journal's plain text, so a compressed copy and a plain one of the same recording match. */
    static byte[] digest(File file)throws IOException{
        java.security.MessageDigest hash;
        try{hash=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException missing){throw new AssertionError(missing);}
        byte[] buffer=new byte[65536];
        try(InputStream in=open(file)){for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);}
        return hash.digest();
    }
    /** The gzip header's comment (where Volt keeps a journal's facts), or null: plain, another header layout, or none. */
    static String gzipComment(File file)throws IOException{
        try(InputStream in=new BufferedInputStream(new FileInputStream(file),MAX_COMMENT+16)){
            int[] header=new int[10];
            for(int i=0;i<header.length;i++)if((header[i]=in.read())<0)return null;
            if(header[0]!=0x1f||header[1]!=0x8b||header[2]!=8||header[3]!=GZIP_COMMENT_FLAG)return null;
            StringBuilder comment=new StringBuilder();
            for(int b;(b=in.read())!=0;){if(b<0||comment.length()>=MAX_COMMENT)return null;comment.append((char)b);}
            return comment.toString();
        }
    }
    /** A gzip file (RFC 1952) whose header holds the comment; any gzip reader reads it. Synced before it returns. */
    static void writeCompressed(File target,byte[] plain,String comment)throws IOException{
        byte[] text=comment.getBytes(StandardCharsets.ISO_8859_1);
        if(text.length>=MAX_COMMENT)throw new IOException("Charge facts too long");
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(plain,0,plain.length);
        try(FileOutputStream file=new FileOutputStream(target)){
            BufferedOutputStream out=new BufferedOutputStream(file,65536);
            out.write(new byte[]{0x1f,(byte)0x8b,8,GZIP_COMMENT_FLAG,0,0,0,0,0,(byte)255});
            out.write(text);out.write(0);
            java.util.zip.Deflater deflater=new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION,true);
            try{java.util.zip.DeflaterOutputStream body=new java.util.zip.DeflaterOutputStream(out,deflater,65536);body.write(plain);body.finish();}
            finally{deflater.end();}
            long sum=crc.getValue();int size=plain.length;
            out.write(new byte[]{(byte)sum,(byte)(sum>>8),(byte)(sum>>16),(byte)(sum>>24),(byte)size,(byte)(size>>8),(byte)(size>>16),(byte)(size>>24)});
            out.flush();file.getFD().sync();
        }
    }
    private static byte[] readAll(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[65536];
        for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);
        return out.toByteArray();
    }
    /** Stops a decompression at a journal's largest size, as a corrupt file. */
    private static final class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream in,long limit){super(in);left=limit;}
        @Override public int read()throws IOException{int b=super.read();if(b>=0&&--left<0)throw new java.util.zip.ZipException("Charge journal too large");return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{
            int n=super.read(b,off,len);if(n>0&&(left-=n)<0)throw new java.util.zip.ZipException("Charge journal too large");return n;
        }
    }
    /**
     * What a saved recording is, kept in its compressed file's header so nothing needs to parse it to list, prune or
     * choose what to learn from: its header, its stop, whether it qualifies to teach a curve, its capacity evidence and
     * the summary its row shows. Written from the parsed journal when it is compressed, and the file never changes after.
     * A recording read in full recomputes them. A change to what qualifies (Session.evidence) or to these fields needs a
     * new VERSION: files with an older one are then read in full and written again (maintain).
     */
    static final class Facts {
        static final String VERSION="volt-charge-facts-1";
        final String profile;final Charger charger;final long startedWall,startedMono;final Stop stop;final boolean qualified;
        /** Capacity evidence (gaugeCharge): ampere-hours and gauge points, both null for none. */
        final Double gaugeAh,gaugePoints;
        final double firstPercent,lastPercent;final long lastSampleMono;
        final Long observedCompleteMono;final long pausedMs,chargerPausedMs;final int pauses,chargerPauses;
        private Facts(String profile,Charger charger,long startedWall,long startedMono,Stop stop,boolean qualified,Double gaugeAh,Double gaugePoints,
                double firstPercent,double lastPercent,long lastSampleMono,Long observedCompleteMono,long pausedMs,int pauses,long chargerPausedMs,int chargerPauses){
            this.profile=profile;this.charger=charger;this.startedWall=startedWall;this.startedMono=startedMono;this.stop=stop;this.qualified=qualified;
            this.gaugeAh=gaugeAh;this.gaugePoints=gaugePoints;this.firstPercent=firstPercent;this.lastPercent=lastPercent;this.lastSampleMono=lastSampleMono;
            this.observedCompleteMono=observedCompleteMono;this.pausedMs=pausedMs;this.pauses=pauses;this.chargerPausedMs=chargerPausedMs;this.chargerPauses=chargerPauses;
        }
        static Facts of(Session session){
            double first=Double.NaN,last=Double.NaN;long end=session.startedMono;
            for(Point point:session.points){
                if(Double.isFinite(point.soc)){if(Double.isNaN(first))first=point.soc;last=point.soc;}
                end=Math.max(end,point.mono);
            }
            long until=session.points.isEmpty()?session.startedMono:session.points.get(session.points.size()-1).mono;
            for(long[] pause:session.pauses)until=Math.max(until,pause[3]);// A recording can end inside its pause.
            int charger=0;for(long[] pause:session.pauses)if(pause[4]==1)charger++;
            double[] gauge=gaugeCharge(session);
            return new Facts(session.profile,session.charger,session.startedWall,session.startedMono,session.stop,session.evidence()!=null,
                    gauge==null?null:gauge[0],gauge==null?null:gauge[1],first,last,end,session.observedCompletion(),
                    session.pausedMs(session.startedMono,until,0),session.pauses.size()-charger,session.pausedMs(session.startedMono,until,1),charger);
        }
        String line(){
            return VERSION+","+profile+","+charger.name()+","+startedWall+","+startedMono+","+(stop==null?"":stop.name())+","+(qualified?1:0)
                    +","+csv(gaugeAh)+","+csv(gaugePoints)+","+(Double.isNaN(firstPercent)?"":firstPercent)+","+(Double.isNaN(lastPercent)?"":lastPercent)
                    +","+lastSampleMono+","+csv(observedCompleteMono)+","+pausedMs+","+pauses+","+chargerPausedMs+","+chargerPauses;
        }
        /** Null for anything but this version's line, whole and in range. */
        static Facts parse(String line){
            if(line==null)return null;
            String[] p=line.split(",",-1);
            if(p.length!=17||!p[0].equals(VERSION)||!p[1].matches("[A-Za-z0-9_-]{1,128}")||!(p[6].equals("0")||p[6].equals("1"))||p[7].isEmpty()!=p[8].isEmpty())return null;
            try{
                long wall=Long.parseLong(p[3]),mono=Long.parseLong(p[4]),end=Long.parseLong(p[11]),paused=Long.parseLong(p[13]),chargerPaused=Long.parseLong(p[15]);
                int pauses=Integer.parseInt(p[14]),chargerPauses=Integer.parseInt(p[16]);
                if(wall<=0||mono<=0||wall>Long.MAX_VALUE-MAX_SESSION_MS||mono>Long.MAX_VALUE-MAX_SESSION_MS||end<mono||paused<0||chargerPaused<0
                        ||pauses<0||chargerPauses<0||pauses+chargerPauses>MAX_PAUSES)return null;
                double first=p[9].isEmpty()?Double.NaN:Double.parseDouble(p[9]),last=p[10].isEmpty()?Double.NaN:Double.parseDouble(p[10]);
                if(!p[9].isEmpty()&&!between(first,0,100)||!p[10].isEmpty()&&!between(last,0,100)||p[9].isEmpty()!=p[10].isEmpty())return null;
                Double ah=p[7].isEmpty()?null:Double.parseDouble(p[7]),points=p[8].isEmpty()?null:Double.parseDouble(p[8]);
                if(ah!=null&&!(Double.isFinite(ah)&&ah>0&&Double.isFinite(points)&&points>0&&points<=100))return null;
                Long observed=p[12].isEmpty()?null:Long.parseLong(p[12]);
                if(observed!=null&&(observed<mono||observed>end))return null;
                return new Facts(p[1],Charger.valueOf(p[2]),wall,mono,p[5].isEmpty()?null:Stop.valueOf(p[5]),p[6].equals("1"),ah,points,first,last,end,observed,paused,pauses,chargerPaused,chargerPauses);
            }catch(IllegalArgumentException bad){return null;}
        }
    }
}
