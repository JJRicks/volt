package com.jjricks.volt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

/** One-shot charging intentions. Reconciliation never sends commands or arms another day. */
public final class ChargeScheduleController {
    public static final long BUFFER_MS = 20 * 60_000L;
    private static final long SETTLE_MS = 30_000L;
    /** A start this recent may still begin charging; charge recordings wait through it. */
    static final long START_GRACE_MS = 120_000L;
    /** How an intention ended. Persisted by ordinal: append only. */
    public enum Outcome { NONE, TARGET_REACHED, ENDED, START_PASSED, MISSED, STARTED_EARLY, CHANGED_ON_SCOOTER, TURNED_OFF }
    static final int ALERT_NONE=0, ALERT_PENDING=1, ALERT_SHOWN=2;
    public interface Store { String read(String scope); void write(String scope, String value); }
    public interface Driver {
        Context context();
        void clock(Consumer<Boolean> completion);
        void time(long wallMs, int offsetSeconds, Consumer<Boolean> completion);
        void enabled(boolean enabled, Consumer<Boolean> completion);
    }
    /** All readings must come from the current authenticated transport, never a display cache. */
    public static final class Context {
        public String scope = "", timeReason, toggleReason, estimateUnavailableReason;
        public boolean ready, wholeHours;
        public int generation;
        public long now, sampleMs;
        /** Elapsed realtime when now was read; 0 when unknown. */
        public long mono;
        public Integer charging, enabled;
        public Long wireStart;
        public Double soc, target, estimateMinutes;
        public TimeZone zone = TimeZone.getDefault();
    }
    /** A Volt-armed start that passed without charging; shown once, kept until acknowledged or re-armed. */
    public static final class Missed {
        /** doneBy: Charge done by armed it; test: a Scheduled charging test that was interrupted. */
        public final boolean doneBy, test, shown;
        public final long startWallMs;
        public final int soc, armedSoc;
        private Missed(State s){doneBy=s.outcomeMode==2;test=testIntention(s);shown=s.alert==ALERT_SHOWN;startWallMs=s.outcomeStart;soc=(int)Math.round(s.outcomeSoc);armedSoc=(int)Math.round(s.outcomeArmedSoc);}
    }
    /** Numbers and enums only, for the redacted diagnostics bundle. Never a scope or other identity. */
    public static final class Diagnostics {
        public final int mode, outcomeMode, alert;
        public final long armedWallMs, startWallMs, deadlineWallMs, wireStart, outcomeWallMs, outcomeStartWallMs;
        public final Integer clockOffsetSeconds, armedSoc, target, outcomeArmedSoc, outcomeSoc, outcomeEnabled, readEnabled;
        public final Long outcomeWireStart, readWireStart, readWallMs;
        public final Outcome outcome;
        /** The last Scheduled charging test: testScheduleOff is null when no cleanup ran (an interruption), else 1 when OFF was confirmed. */
        public final ChargeScheduleTest.Outcome testOutcome;
        public final long testWallMs, testStartWallMs;
        public final Integer testOffsetSeconds, testScheduleOff;
        public final boolean testPaused;
        private Diagnostics(State s,Context c){
            mode=s.mode;outcomeMode=s.outcomeMode;alert=s.alert;armedWallMs=s.armed;startWallMs=s.start;deadlineWallMs=s.mode==2?s.deadline:0;wireStart=s.wire;
            // The offset sent with the start is implied by the two; a pair from different writes is not reported.
            long offset=s.wire-Math.floorDiv(s.start,1000L);clockOffsetSeconds=s.wire>0&&s.start>0&&Math.abs(offset)<=18*3600?(int)offset:null;
            armedSoc=s.armedSoc<0?null:(int)Math.round(s.armedSoc);target=(int)Math.round(s.target);
            outcome=Outcome.values()[s.outcome];outcomeWallMs=s.outcomeMs;outcomeStartWallMs=s.outcomeStart;outcomeSoc=s.outcomeSoc<0?null:(int)Math.round(s.outcomeSoc);outcomeArmedSoc=s.outcomeArmedSoc<0?null:(int)Math.round(s.outcomeArmedSoc);
            outcomeEnabled=s.outcomeEnabled<0?null:s.outcomeEnabled;outcomeWireStart=s.outcomeWire<0?null:s.outcomeWire;
            testOutcome=ChargeScheduleTest.Outcome.values()[s.testOutcome];testWallMs=s.testWall;testStartWallMs=s.testStart;testOffsetSeconds=testOffset(s);testPaused=s.testPaused;testScheduleOff=s.testOff<0?null:s.testOff;
            boolean live=c.ready&&!c.scope.isEmpty();readEnabled=live&&c.enabled!=null&&(c.enabled==0||c.enabled==1)?c.enabled:null;readWireStart=live?c.wireStart:null;readWallMs=live?c.now:null;
        }
    }
    public static final class View {
        public final boolean manualEnabled, doneByEnabled, busy, chargerConnected;
        public final Missed missed;
        public final int doneByMinuteOfDay;
        public final long startWallMs, manualStartWallMs, doneByWallMs;
        public final String manualBlockedReason, manualTimeBlockedReason, doneByBlockedReason, timeBlockedReason, testBlockedReason, status;
        /** A running test's phase, else null; its start, or the last test's. */
        public final ChargeScheduleTest.Phase testPhase;
        public final long testStartWallMs;
        public final ChargeScheduleTest.Outcome lastTest;
        public final Integer lastTestOffsetSeconds;
        private View(State state, Context c, boolean busy, ChargeScheduleTest test) {
            this.busy=busy;doneByEnabled=state.mode==2;
            manualEnabled=state.mode==1 || state.mode==0&&!state.retired&&Integer.valueOf(1).equals(c.enabled);
            chargerConnected=c.ready&&Integer.valueOf(1).equals(c.charging);
            doneByMinuteOfDay=state.minute;startWallMs=state.start;manualStartWallMs=state.manualStart;
            doneByWallMs=state.mode==2?state.deadline:0;
            status=state.status;missed=state.alert!=ALERT_NONE&&state.outcome==Outcome.MISSED.ordinal()?new Missed(state):null;
            testPhase=test==null?null:test.phase();testStartWallMs=test==null?state.testStart:test.startWallMs;
            lastTest=ChargeScheduleTest.Outcome.values()[state.testOutcome];lastTestOffsetSeconds=testOffset(state);
            String wait=busy?"Wait for the current change to finish":test!=null?"Wait for the schedule test to finish":null;
            manualBlockedReason=wait!=null?wait:doneByEnabled?"Disable Charge done by first":baseReason(c,false);
            manualTimeBlockedReason=manualBlockedReason!=null?manualBlockedReason:baseReason(c,true);
            String unavailable=wait!=null?wait:!chargerConnected?"Connect a charger first":baseReason(c,true);
            doneByBlockedReason=unavailable!=null?unavailable:c.estimateUnavailableReason;
            // The picker remains available offline. Connected active plans are
            // validated and explicitly reprogrammed by setDoneTime instead.
            timeBlockedReason=wait!=null?wait:c.scope.isEmpty()?"Choose a scooter first":null;
            // Other models take whole-hour starts only, too coarse to time a two-minute resume.
            testBlockedReason=test!=null?null:wait!=null?wait:c.scope.isEmpty()||!c.ready?"Connect the scooter first":c.wholeHours?"Not available on this model"
                    :baseReason(c,true)!=null?baseReason(c,true):doneByEnabled?"Turn off Charge done by first"
                    :manualEnabled||Integer.valueOf(1).equals(c.enabled)?"Turn off Scheduled charging first":!chargerConnected?"Connect a charger first"
                    :!finite(c.soc)||!finite(c.target)?"Wait for fresh charging readings":c.soc>=c.target?"Battery is already at its charge limit":null;
        }
    }
    static final class State {
        int mode, minute=8*60;
        long start, manualStart, wire, deadline, armed, calculatedWire;
        double target=100, estimate, capturedSoc=-1;
        /** Battery at arming, for every mode; capturedSoc stays done-by's estimate guard. */
        double armedSoc=-1, outcomeSoc=-1, outcomeArmedSoc=-1;
        /** Volt saw this intention charging at or after its start: a later stop is not a missed start. */
        boolean started;
        int outcome, outcomeMode, outcomeEnabled=-1, alert;
        long outcomeMs, outcomeStart, outcomeWire=-1;
        boolean retired;
        String status="";
        long earlyFirst, earlyLast;
        /** The last Scheduled charging test: began, its start, the resume Volt saw (0 none); testOff -1 no cleanup, 0 unconfirmed, 1 OFF confirmed. */
        long testWall, testStart, testResume;
        int testOutcome, testOff=-1;
        boolean testPaused;
        State copy(){State n=new State();n.mode=mode;n.minute=minute;n.start=start;n.manualStart=manualStart;n.wire=wire;n.deadline=deadline;n.armed=armed;n.calculatedWire=calculatedWire;n.target=target;n.estimate=estimate;n.capturedSoc=capturedSoc;n.retired=retired;n.status=status;
            n.armedSoc=armedSoc;n.outcomeSoc=outcomeSoc;n.outcomeArmedSoc=outcomeArmedSoc;n.started=started;n.outcome=outcome;n.outcomeMode=outcomeMode;n.outcomeEnabled=outcomeEnabled;n.alert=alert;n.outcomeMs=outcomeMs;n.outcomeStart=outcomeStart;n.outcomeWire=outcomeWire;
            n.testWall=testWall;n.testStart=testStart;n.testResume=testResume;n.testOutcome=testOutcome;n.testOff=testOff;n.testPaused=testPaused;return n;}
    }
    /** How a Scheduled charging test ended, and whether its cleanup left the schedule off. */
    public static final class TestResult {
        public final ChargeScheduleTest.Outcome outcome;
        public final long startWallMs;
        public final Integer offsetSeconds;
        public final boolean paused, scheduleOff;
        /** failure: why arming stopped (NOT_STARTED); cleanup: why OFF or the Charge start restore is unconfirmed. Null when fine. */
        public final String failure, cleanup;
        TestResult(ChargeScheduleTest t,boolean off,String failure,String cleanup){outcome=t.outcome();startWallMs=t.startWallMs;offsetSeconds=t.offsetSeconds();paused=t.paused();scheduleOff=off;this.failure=failure;this.cleanup=cleanup;}
    }
    public static final class Plan {
        public final long deadlineWallMs, startWallMs;
        public final int offsetSeconds;
        public final boolean startNow;
        Plan(long deadline,long start,int offset,boolean now){deadlineWallMs=deadline;startWallMs=start;offsetSeconds=offset;startNow=now;}
    }
    private final Store store;
    private final Driver driver;
    private final Map<String,State> states=new HashMap<>();
    private long operation;
    private boolean busy;
    private Context pendingContext;
    private Consumer<String> pendingCompletion;
    private ChargeScheduleTest test;
    private Context testContext;
    private Consumer<TestResult> testDone;
    public ChargeScheduleController(Store store,Driver driver){this.store=store;this.driver=driver;}
    private Context context(){
        Context raw=driver.context(),c=new Context();
        c.scope=raw.scope;c.ready=raw.ready;c.wholeHours=raw.wholeHours;c.generation=raw.generation;c.now=raw.now;c.mono=raw.mono;c.sampleMs=raw.sampleMs;
        c.charging=raw.charging;c.enabled=raw.enabled;c.wireStart=raw.wireStart;c.soc=raw.soc;c.target=raw.target;c.estimateMinutes=raw.estimateMinutes;
        c.timeReason=raw.timeReason;c.toggleReason=raw.toggleReason;c.estimateUnavailableReason=raw.estimateUnavailableReason;c.zone=(TimeZone)raw.zone.clone();return c;
    }
    private State state(Context c){return states.computeIfAbsent(c.scope,k->decode(store.read(k)));}
    public boolean busy(){return busy;}
    public View view(){Context c=context();reconcile(c);State s=state(c);State shown=s;
        if(s.start==0&&c.wireStart!=null&&c.wireStart>0){shown=s.copy();shown.start=fromWire(c.wireStart,c.zone.getOffset(c.now));
            // Without a prior confirmed start, any readback after an attempted
            // calculated write may be ours (including an earlier failed attempt).
            // Only genuinely unowned native times can seed a manual preference.
            if(shown.manualStart==0&&s.calculatedWire==0)shown.manualStart=shown.start;}
        return new View(shown,c,busy,test!=null&&c.scope.equals(testContext.scope)?test:null);}
    /** Fresh observations may retire a remembered intention; this method never touches the radio. */
    public void observe(){Context c=context();
        // A transport can remove its queued callback when it closes between two steps.
        // The service's connection notification must release that operation too.
        if(busy&&pendingContext!=null&&!sameContext(pendingContext,c))interrupt();
        observeTest(c);reconcile(c);
    }
    private void reconcile(Context c){
        State s=state(c);if(busy||!c.ready||c.scope.isEmpty())return;
        // A running test judges its own intention; an interrupted one is left to the checks below.
        if(test!=null&&c.scope.equals(testContext.scope))return;
        // Positive changed readback is useful evidence; absent/zero after reboot is ambiguous.
        if(s.wire>0&&c.wireStart!=null&&c.wireStart>0&&c.wireStart!=s.wire){
            long offset=s.wire*1000L-s.start;
            if(Math.abs(offset)>86_400_000L)offset=c.zone.getOffset(c.now);
            boolean manualContext=s.mode!=2&&s.manualStart>0&&s.start==s.manualStart
                    &&s.wire!=s.calculatedWire&&c.wireStart!=s.calculatedWire;
            // A start that already passed without charging is still reported, even if the time then changed.
            if(s.mode!=0)settle(c,s,s.mode,missed(c,s)?Outcome.MISSED:Outcome.CHANGED_ON_SCOOTER);
            s.mode=0;s.retired=false;s.start=fromWire(c.wireStart,offset);s.wire=c.wireStart;s.armedSoc=-1;s.started=false;
            // Follow genuine manual edits made on the scooter/OEM app, but do not
            // mistake a late readback from our calculated write for a manual choice.
            if(manualContext)s.manualStart=s.start;
            s.status="Charging time changed on the scooter";save(c,s);return;
        }
        if(s.mode==0){
            // An ON whose confirmation was lost (timeout or a dropped link after the scooter applied it)
            // still runs the start Volt wrote. Track it, so a start it then misses is reported too.
            if(s.retired||s.armedSoc<0||s.start<=0||!Integer.valueOf(1).equals(c.enabled)||c.wireStart==null||c.wireStart!=s.wire
                    ||c.now>=s.start+24*60*60_000L)return;
            double armedSoc=c.now<s.start&&finite(c.soc)&&c.soc>=0&&c.soc<=100?c.soc:s.armedSoc;
            newIntention(s,armedSoc);s.mode=1;s.deadline=s.start+24*60*60_000L;s.target=finite(c.target)&&c.target>0&&c.target<=100?c.target:100;s.status="";save(c,s);
        }
        if(finite(c.target)&&c.target>0&&c.target<=100&&c.target!=s.target){
            // A ceiling edit changes the ETA contract. Preserve the actual scheduled start,
            // but stop presenting an obsolete finish deadline as a calculated plan.
            if(s.mode==2){s.mode=1;s.deadline=s.start+24*60*60_000L;s.status="Charge limit changed; update Charge done by";}
            s.target=c.target;save(c,s);
        }
        if(finite(c.soc)&&c.soc>=s.target){retire(c,s,"Charge target reached",Outcome.TARGET_REACHED);return;}
        if(c.charging==null)return;
        if(c.charging==1&&c.now>=s.start&&!s.started){s.started=true;save(c,s);}
        boolean ended=c.now>=s.deadline+START_GRACE_MS;
        // The battery decides whether a passed start ever charged: no rise since arming is a missed
        // start, a rise is a charge that ran and stopped (unplugged or done), and unknown stays quiet.
        if(c.charging==0&&c.now>=s.start+START_GRACE_MS){
            if(missed(c,s))retire(c,s,"Scheduled charging didn't start",Outcome.MISSED);
            else retire(c,s,ended?"Charging schedule ended":"Scheduled start has passed",ended?Outcome.ENDED:Outcome.START_PASSED);return;
        }
        if(ended){retire(c,s,"Charging schedule ended",Outcome.ENDED);return;}
        if(c.charging==1&&c.now<s.start&&c.now>=s.armed+SETTLE_MS){
            if(c.sampleMs>0&&c.sampleMs!=s.earlyLast){
                if(s.earlyFirst==0||c.sampleMs<s.earlyLast)s.earlyFirst=c.sampleMs;
                s.earlyLast=c.sampleMs;
                if(c.sampleMs-s.earlyFirst>=5000)retire(c,s,"Charging started before the schedule",Outcome.STARTED_EARLY);
            }
        }else{s.earlyFirst=s.earlyLast=0;}
    }
    private void retire(Context c,State s,String status,Outcome outcome){
        if(s.mode!=0)settle(c,s,s.mode,outcome);s.mode=0;s.retired=true;s.status=status;s.earlyFirst=s.earlyLast=0;save(c,s);
    }
    private static boolean missed(Context c,State s){
        return s.mode!=0&&!s.started&&Integer.valueOf(0).equals(c.charging)&&c.now>=s.start+START_GRACE_MS
                &&s.armedSoc>=0&&finite(c.soc)&&c.soc<s.target&&c.soc<=s.armedSoc+1;
    }
    /** Records how an armed intention ended, with the readback at that moment; a miss raises its one alert. */
    private static void settle(Context c,State s,int mode,Outcome outcome){
        s.outcome=outcome.ordinal();s.outcomeArmedSoc=s.armedSoc;s.outcomeMs=c.now;s.outcomeStart=s.start;s.outcomeMode=mode;s.outcomeSoc=finite(c.soc)&&c.soc>=0&&c.soc<=100?c.soc:-1;
        s.outcomeEnabled=c.enabled!=null&&(c.enabled==0||c.enabled==1)?c.enabled:-1;s.outcomeWire=c.wireStart!=null&&c.wireStart>=0&&c.wireStart<=0xffffffffL?c.wireStart:-1;
        if(outcome==Outcome.MISSED)s.alert=ALERT_PENDING;
    }
    /** Battery at arming from the freshest reading available; -1 when none is fresh. */
    private double armingSoc(Context c){Double now=context().soc;return finite(now)&&now>=0&&now<=100?now:finite(c.soc)&&c.soc>=0&&c.soc<=100?c.soc:-1;}
    private static void newIntention(State s,double soc){s.armedSoc=soc;s.started=false;s.outcomeArmedSoc=-1;s.outcome=Outcome.NONE.ordinal();s.outcomeMs=0;s.outcomeStart=0;s.outcomeMode=0;s.outcomeSoc=-1;s.outcomeEnabled=-1;s.outcomeWire=-1;s.alert=ALERT_NONE;}
    /** The owner saw the dialog or notification for the current scooter's missed start. */
    public void missedShown(){Context c=context();State s=state(c);if(s.alert==ALERT_PENDING){s.alert=ALERT_SHOWN;save(c,s);}}
    public void acknowledgeMissed(){Context c=context();State s=state(c);if(s.alert!=ALERT_NONE){s.alert=ALERT_NONE;save(c,s);}}
    /** A missed start not yet shown anywhere, for any loaded scooter; it is marked shown as it is taken. */
    public Missed takeUnshownMissed(){
        for(Map.Entry<String,State> e:states.entrySet()){State s=e.getValue();
            if(s.alert==ALERT_PENDING&&s.outcome==Outcome.MISSED.ordinal()){s.alert=ALERT_SHOWN;if(!e.getKey().isEmpty())store.write(e.getKey(),encode(s));return new Missed(s);}}
        return null;
    }
    /** Sends OFF for a missed start's schedule; done-by also restores the manual start time, as its own switch does. */
    public void turnOffMissed(Consumer<String> completion){Context c=context();State s=state(c);
        disable(s.outcomeMode==2||testIntention(s),testIntention(s)?"Scheduled charging is off; ":"Charge done by is off; ",completion);}
    /** The selected scooter's schedule record, or null without one. */
    public Diagnostics diagnostics(){Context c=context();return c.scope.isEmpty()?null:new Diagnostics(state(c),c);}
    private void save(Context c,State s){if(!c.scope.isEmpty())store.write(c.scope,encode(s));}
    private static String baseReason(Context c,boolean needsTime){
        if(c.scope.isEmpty()||!c.ready)return "Connect the scooter first";
        if(c.toggleReason!=null)return c.toggleReason;
        return needsTime?c.timeReason:null;
    }
    public void setDoneTime(int hour,int minute,Consumer<String> completion){
        Context c=context();View view=view();
        if(hour<0||hour>23||minute<0||minute>59){completion.accept("Choose a valid time");return;}
        if(view.timeBlockedReason!=null){completion.accept(view.timeBlockedReason);return;}
        if(c.ready&&view.doneByEnabled){enableDone(hour*60+minute,completion);return;}
        State s=state(c);s.minute=hour*60+minute;save(c,s);completion.accept(null);
    }
    public void setDoneEnabled(boolean enabled,Consumer<String> completion){
        Context c=context();if(enabled){enableDone(state(c).minute,completion);return;}
        if(state(c).mode!=2){completion.accept(null);return;}
        disable(true,"Charge done by is off; ",completion);
    }
    private void enableDone(int minute,Consumer<String> completion){
        Context c=context();View view=view();
        String blocked=view.doneByEnabled?(busy?"Wait for the current change to finish":baseReason(c,true)):view.doneByBlockedReason;
        if(blocked!=null){completion.accept(blocked);return;}
        State previous=state(c);boolean paused=!Integer.valueOf(1).equals(c.charging);
        if(!paused&&c.estimateUnavailableReason!=null){completion.accept(c.estimateUnavailableReason);return;}
        if(paused){
            // Native scheduled charging intentionally pauses current flow. Reuse its
            // captured ETA only while fresh electrical readings still prove that the
            // same amount of charging remains before the owned start time.
            if(previous.mode!=2||!Integer.valueOf(0).equals(c.charging)||c.now>=previous.start
                    ||previous.capturedSoc<0||!finite(c.soc)||c.soc!=previous.capturedSoc
                    ||!finite(c.target)||c.target!=previous.target||previous.estimate<=0){
                completion.accept("A fresh charging estimate is needed; disable Charge done by and charge briefly");return;
            }
            c.estimateMinutes=previous.estimate;
        }
        if(!finite(c.soc)||c.soc<0||c.soc>100||!finite(c.target)||c.target<=0||c.target>100||!finite(c.estimateMinutes)||c.estimateMinutes<=0){
            completion.accept(finite(c.soc)&&finite(c.target)&&c.soc>=c.target?"Battery is already at its charge limit":"Wait for a charging time estimate");return;
        }
        final Plan plan;
        try{plan=plan(c.now,minute,c.estimateMinutes,c.zone,c.wholeHours);}catch(IllegalArgumentException bad){completion.accept("Charging time is outside a usable range");return;}
        // Keep an unowned native manual time before replacing it with our calculated
        // start. An old owned done-by time is intentionally not treated as manual.
        if(state(c).manualStart==0&&view.manualStartWallMs>0){state(c).manualStart=view.manualStartWallMs;save(c,state(c));}
        if(plan.startNow){
            disable(result->{if(result==null){State s=state(c);s.minute=minute;save(c,s);completion.accept("Charging now — not enough time to wait before the deadline");}else completion.accept(result);});return;
        }
        program(c,plan.startWallMs,plan.offsetSeconds,()->{
            State s=state(c);s.mode=2;s.minute=minute;s.start=plan.startWallMs;s.wire=plan.startWallMs/1000+plan.offsetSeconds;
            s.deadline=plan.deadlineWallMs;s.armed=context().now;s.target=c.target;s.estimate=c.estimateMinutes;s.capturedSoc=c.soc;s.retired=false;s.status="";newIntention(s,armingSoc(c));save(c,s);
        },true,c.target,paused?c.soc:null,completion);
    }
    public void setManualEnabled(boolean enabled,Consumer<String> completion){
        Context c=context();View view=view();
        if(view.manualBlockedReason!=null){completion.accept(view.manualBlockedReason);return;}
        if(!enabled){disable(completion);return;}
        long start=view.manualStartWallMs;
        if(start<=c.now){
            if(start<=0){completion.accept("Choose Charge start first");return;}
            Calendar old=Calendar.getInstance(c.zone);old.setTimeInMillis(start);
            start=nextOccurrence(c.now,old.get(Calendar.HOUR_OF_DAY)*60+old.get(Calendar.MINUTE),c.zone);
        }
        final long chosen=start;int offset=c.zone.getOffset(c.now)/1000;
        // Enabling an existing native time is also an explicit manual choice. Keep
        // it even if confirmation fails after the native timestamp was rewritten.
        state(c).manualStart=chosen;save(c,state(c));
        program(c,chosen,offset,()->{state(c).manualStart=chosen;state(c).calculatedWire=0;armManual(c,chosen,offset);},true,null,null,completion);
    }
    public void setManualStart(long wallMs,Consumer<String> completion){
        Context c=context();View view=view();
        String reason=view.manualTimeBlockedReason;
        if(reason!=null){completion.accept(reason);return;}
        if(wallMs<=c.now||wallMs-c.now>48*60*60_000L){completion.accept("Choose a charge start within 48 hours");return;}
        if(c.wholeHours){Calendar cal=Calendar.getInstance(c.zone);cal.setTimeInMillis(wallMs);if(cal.get(Calendar.MINUTE)!=0||cal.get(Calendar.SECOND)!=0){completion.accept("Choose a whole hour within 48 hours");return;}}
        int offset=c.zone.getOffset(c.now)/1000;
        boolean enabled=view.manualEnabled;
        program(c,wallMs,offset,()->{
            state(c).manualStart=wallMs;state(c).calculatedWire=0;
            if(enabled)armManual(c,wallMs,offset);
            else{State s=state(c);s.start=wallMs;s.wire=wallMs/1000+offset;s.retired=false;save(c,s);}
        },false,null,null,completion);
    }
    private void armManual(Context c,long start,int offset){State s=state(c);s.mode=1;s.start=start;s.wire=start/1000+offset;s.armed=context().now;
        s.deadline=start+24*60*60_000L;s.target=finite(c.target)&&c.target>0&&c.target<=100?c.target:100;s.retired=false;s.status="";newIntention(s,armingSoc(c));save(c,s);}
    private void disable(Consumer<String> completion){
        disable(false,"",completion);
    }
    /** OFF, then optionally the manual Charge start; off names what is off if that restore fails. */
    private void disable(boolean restoreManual,String off,Consumer<String> completion){
        Context c=context();String reason=busy?"Wait for the current change to finish":baseReason(c,false);
        if(reason!=null){completion.accept(reason);return;}
        long token=++operation;busy=true;pendingContext=c;pendingCompletion=completion;
        driver.enabled(false,success->{
            if(!valid(c,token,completion))return;
            if(!success){finishBusy();completion.accept("Schedule cancellation was not confirmed; reconnect and try again");return;}
            // OFF is a confirmed change even if a later clock/time restoration fails.
            // Persist it immediately, and never retry this operation on reconnect.
            State done=state(c);done.alert=ALERT_NONE;if(test!=null)done.testOff=1;retire(c,done,"",Outcome.TURNED_OFF);
            if(!restoreManual||state(c).manualStart==0){finishBusy();completion.accept(null);return;}
            restoreManualStart(c,token,off,completion);
        });
    }
    private void restoreManualStart(Context c,long token,String off,Consumer<String> completion){
        Context latest=context();
        if(latest.timeReason!=null){restoreFailed(c,off+"Charge start could not be restored: "+latest.timeReason,completion);return;}
        driver.clock(clock->{
            if(!valid(c,token,completion))return;
            if(!clock){restoreFailed(c,off+"the scooter clock could not be confirmed",completion);return;}
            long start=state(c).manualStart;Context now=context();
            if(start<=now.now){Calendar old=Calendar.getInstance(c.zone);old.setTimeInMillis(start);
                start=nextOccurrence(now.now,old.get(Calendar.HOUR_OF_DAY)*60+old.get(Calendar.MINUTE),c.zone);}
            final long restored=start;int offset=c.zone.getOffset(c.now)/1000;
            driver.time(restored,offset,time->{
                if(!valid(c,token,completion))return;
                if(!time){restoreFailed(c,off+"restoring Charge start was not confirmed",completion);return;}
                State s=state(c);s.manualStart=restored;s.start=restored;s.wire=restored/1000+offset;s.calculatedWire=0;s.status="";save(c,s);
                finishBusy();completion.accept(null);
            });
        });
    }
    private void restoreFailed(Context c,String status,Consumer<String> completion){
        State s=state(c);s.status=status;save(c,s);finishBusy();completion.accept(s.status);
    }
    private void program(Context c,long start,int offset,Runnable commit,boolean enable,Double deadlineTarget,Double stableSoc,Consumer<String> completion){
        if(busy){completion.accept("Wait for the current change to finish");return;}
        long token=++operation;busy=true;pendingContext=c;pendingCompletion=completion;
        driver.clock(clock->{
            if(!valid(c,token,completion))return;
            if(!clock){finishBusy();completion.accept("Could not set the scooter clock; schedule unchanged");return;}
            if(stableSoc!=null&&!stableSoc.equals(context().soc)){
                finishBusy();completion.accept("Battery level changed; a fresh charging estimate is needed");return;
            }
            // A transmitted calculated time may apply despite a lost confirmation.
            // Retain its identity across restart so its later readback cannot replace
            // the separate manual preference during reconciliation.
            if(deadlineTarget!=null){State s=state(c);s.calculatedWire=start/1000+offset;save(c,s);}
            driver.time(start,offset,time->{
                if(!valid(c,token,completion))return;
                if(!time){finishBusy();completion.accept("Charge start was not confirmed; check the scooter schedule");return;}
                // This is a confirmed partial change even if enabling later fails. Keep its time,
                // but never claim that the calculated deadline was successfully armed.
                State s=state(c);boolean wasEnabled=s.mode!=0||Integer.valueOf(1).equals(c.enabled);
                s.start=start;s.wire=start/1000+offset;s.mode=wasEnabled?1:0;s.retired=false;
                s.deadline=start+24*60*60_000L;s.armed=context().now;s.armedSoc=armingSoc(c);s.started=false;save(c,s);
                if(!enable){commit.run();finishBusy();completion.accept(null);return;}
                Context latest=context();
                if(deadlineTarget!=null&&(!finite(latest.target)||!deadlineTarget.equals(latest.target))){
                    finishBusy();completion.accept("Charge limit changed; check the schedule and try again");return;
                }
                if(stableSoc!=null&&!stableSoc.equals(latest.soc)){
                    finishBusy();completion.accept("Charge start changed, but a fresh charging estimate is needed");return;
                }
                if(start<=latest.now){
                    // ACK/readback can consume a start only seconds away. Never enable a
                    // newly expired timestamp or silently roll its start to another day.
                    driver.enabled(false,disabled->{
                        if(!valid(c,token,completion))return;
                        finishBusy();
                        if(disabled){retire(c,state(c),"",Outcome.TURNED_OFF);completion.accept("Charging now — the calculated start time has passed");}
                        else completion.accept("Start time passed, but schedule cancellation was not confirmed");
                    });return;
                }
                driver.enabled(true,switched->{
                    if(!valid(c,token,completion))return;
                    finishBusy();
                    if(switched){
                        Context after=context();
                        if(deadlineTarget!=null&&(!finite(after.target)||!deadlineTarget.equals(after.target))){
                            armManual(after,start,offset);completion.accept("Charge limit changed; the charge start is saved without a finish estimate");
                        }else if(stableSoc!=null&&!stableSoc.equals(after.soc)){
                            armManual(after,start,offset);completion.accept("Battery level changed; the charge start is saved without a finish estimate");
                        }else{commit.run();completion.accept(null);}
                    }
                    else completion.accept("Charge start changed, but enabling the schedule was not confirmed");
                });
            });
        });
    }
    private boolean valid(Context original,long token,Consumer<String> completion){
        if(token!=operation)return false;
        Context current=context();
        if(!sameContext(original,current)){interrupt();return false;}
        return true;
    }
    private static boolean sameContext(Context original,Context current){
        return current.ready&&original.scope.equals(current.scope)&&original.generation==current.generation
                &&original.zone.getID().equals(current.zone.getID())
                &&original.zone.getOffset(original.now)==current.zone.getOffset(current.now);
    }
    private void finishBusy(){busy=false;pendingContext=null;pendingCompletion=null;}
    private void interrupt(){Consumer<String> callback=pendingCompletion;++operation;finishBusy();
        if(callback!=null)callback.accept("Connection changed; check the scooter schedule before trying again");}
    public boolean testing(){return test!=null;}
    /**
     * Starts the owner's Scheduled charging test: a start about two minutes ahead, armed through the usual clock, time
     * and ON transaction as a Volt-tracked manual intention, so the missed-start checks cover a test that is cut off.
     * Returns why it cannot start, or null; finished then hears the result once, after OFF and the Charge start restore.
     */
    public String startTest(Consumer<TestResult> finished){
        Context c=context();View view=view();
        if(test!=null)return "Wait for the schedule test to finish";
        if(view.testBlockedReason!=null)return view.testBlockedReason;
        State s=state(c);
        // As Charge done by does: an unowned native time becomes the preference restored afterwards.
        if(s.manualStart==0&&view.manualStartWallMs>0)s.manualStart=view.manualStartWallMs;
        ChargeScheduleTest t=new ChargeScheduleTest(c.now);test=t;testContext=c;testDone=finished;
        s.testWall=c.now;s.testStart=t.startWallMs;s.testResume=0;s.testPaused=false;s.testOff=-1;s.testOutcome=ChargeScheduleTest.Outcome.RUNNING.ordinal();save(c,s);
        int offset=c.zone.getOffset(c.now)/1000;long wire=t.startWallMs/1000+offset;
        // A target makes program() record the test's wire as calculated, so its readback never becomes the manual preference.
        program(c,t.startWallMs,offset,()->armManual(c,t.startWallMs,offset),true,c.target,null,result->{
            if(test!=t)return;
            if(result==null){Context now=context();t.armed(now.now);if(t.cancelRequested())endTest(ChargeScheduleTest.Outcome.CANCELLED,true,null);else observeTest(now);return;}
            // A changed connection gets nothing more; otherwise a start that may have applied is undone.
            boolean same=sameContext(testContext,context());
            endTest(same?ChargeScheduleTest.Outcome.NOT_STARTED:ChargeScheduleTest.Outcome.INTERRUPTED,same&&state(c).calculatedWire==wire,result);
        });
        return null;
    }
    /** The owner's Cancel: the same OFF and Charge start restore as a finished test. */
    public void cancelTest(){ChargeScheduleTest t=test;if(t==null)return;
        if(t.phase()==ChargeScheduleTest.Phase.ARMING)t.requestCancel();
        else if(t.phase()!=ChargeScheduleTest.Phase.ENDING)endTest(ChargeScheduleTest.Outcome.CANCELLED,true,null);
    }
    private void observeTest(Context c){
        ChargeScheduleTest t=test;if(t==null||t.phase()==ChargeScheduleTest.Phase.ARMING||t.phase()==ChargeScheduleTest.Phase.ENDING)return;
        // Disconnects and new sessions send nothing: the tracked intention's own checks take over.
        if(!sameContext(testContext,c)){endTest(ChargeScheduleTest.Outcome.INTERRUPTED,false,null);return;}
        if(t.observe(c.now,c.mono,c.charging,c.soc,c.target)!=ChargeScheduleTest.Outcome.NONE)endTest(t.outcome(),true,null);
    }
    private void endTest(ChargeScheduleTest.Outcome reason,boolean cleanup,String failure){
        ChargeScheduleTest t=test;if(t==null)return;Context c=testContext;State s=state(c);
        if(t.phase()==ChargeScheduleTest.Phase.ENDING){test=null;testContext=null;deliver(t,s.testOff==1,failure,"Volt stopped before it finished; check that Scheduled charging is off");return;}
        t.stop(reason);
        // A resume the test saw is the intention's start: an OFF that fails or a lost link must not later call it missed.
        if(t.resumeWallMs()>0)s.started=true;
        s.testOutcome=t.outcome().ordinal();s.testPaused=t.paused();s.testResume=t.resumeWallMs();s.testOff=cleanup?0:-1;save(c,s);
        if(!cleanup){test=null;testContext=null;deliver(t,false,failure,null);return;}
        t.ending();
        disable(true,"Scheduled charging is off; ",result->{if(test!=t)return;test=null;testContext=null;deliver(t,state(c).testOff==1,failure,result);});
    }
    private void deliver(ChargeScheduleTest t,boolean off,String failure,String cleanup){
        Consumer<TestResult> done=testDone;testDone=null;if(done!=null)done.accept(new TestResult(t,off,failure,cleanup));
    }
    private static boolean testIntention(State s){return s.testStart>0&&s.outcomeStart==s.testStart;}
    private static Integer testOffset(State s){return s.testResume>0&&s.testStart>0?(int)Math.round((s.testResume-s.testStart)/1000d):null;}
    /** Stops callback continuations only. It never guesses whether a transmitted command applied. A running test is interrupted. */
    public void cancel(){++operation;finishBusy();if(test!=null)endTest(ChargeScheduleTest.Outcome.INTERRUPTED,false,null);}
    public static Plan plan(long now,int minute,double estimateMinutes,TimeZone zone,boolean wholeHours){
        if(now<=0||minute<0||minute>=1440||!Double.isFinite(estimateMinutes)||estimateMinutes<=0||estimateMinutes>48*60)
            throw new IllegalArgumentException("Invalid charging plan");
        long deadline=nextOccurrence(now,minute,zone);
        long start=deadline-(long)Math.ceil(estimateMinutes*60_000d)-BUFFER_MS;
        int offset=zone.getOffset(now)/1000;
        // The scooter RTC retains the offset sent now; it does not perform a later DST change.
        // Round its wire-local hour earlier, including zones with half-hour DST transitions.
        if(wholeHours)start=Math.floorDiv(start+offset*1000L,3_600_000L)*3_600_000L-offset*1000L;
        else start=Math.floorDiv(start,60_000L)*60_000L;
        return new Plan(deadline,Math.max(now,start),offset,start<=now);
    }
    public static long nextOccurrence(long now,int minute,TimeZone zone){
        ZoneId id=zone.toZoneId();LocalDate today=Instant.ofEpochMilli(now).atZone(id).toLocalDate();
        LocalTime chosen=LocalTime.of(minute/60,minute%60);
        for(int day=0;day<3;day++){
            // Rebuild the requested wall time for each day. Normalizing a spring gap
            // must not carry its shifted hour into tomorrow's perfectly valid time.
            LocalDateTime wall=LocalDateTime.of(today.plusDays(day),chosen);
            List<ZoneOffset> offsets=id.getRules().getValidOffsets(wall);
            if(offsets.isEmpty()){
                long shifted=wall.atZone(id).toInstant().toEpochMilli();
                if(shifted>now)return shifted; // A nonexistent time moves forward by the gap.
            }else{
                long next=Long.MAX_VALUE;
                for(ZoneOffset offset:offsets){long candidate=wall.toInstant(offset).toEpochMilli();if(candidate>now)next=Math.min(next,candidate);}
                if(next!=Long.MAX_VALUE)return next; // Earliest remaining occurrence in a repeated hour.
            }
        }
        throw new IllegalArgumentException("No upcoming charge time");
    }
    private static long fromWire(long raw,long clockOffsetMs){return raw*1000L-clockOffsetMs;}
    private static boolean finite(Double n){return n!=null&&Double.isFinite(n);}
    private static boolean soc(double n){return Double.isFinite(n)&&n>=-1&&n<=100;}
    static String encode(State s){return "5|"+s.mode+"|"+s.minute+"|"+s.start+"|"+s.wire+"|"+s.deadline+"|"+s.armed+"|"+s.target+"|"+s.estimate+"|"+s.retired+"|"+s.manualStart+"|"+s.calculatedWire+"|"+s.capturedSoc
            +"|"+s.armedSoc+"|"+s.outcome+"|"+s.outcomeMs+"|"+s.outcomeStart+"|"+s.outcomeSoc+"|"+s.outcomeMode+"|"+s.outcomeEnabled+"|"+s.outcomeWire+"|"+s.alert+"|"+s.outcomeArmedSoc+"|"+s.started
            +"|"+s.testWall+"|"+s.testStart+"|"+s.testResume+"|"+s.testOutcome+"|"+s.testOff+"|"+s.testPaused;}
    static State decode(String text){
        State s=new State();if(text==null)return s;
        try{String[] v=text.split("\\|",-1);boolean legacy=v.length==10&&v[0].equals("1"),v2=v.length==11&&v[0].equals("2"),v3=v.length==13&&v[0].equals("3"),v4=v.length==24&&v[0].equals("4"),v5=v.length==30&&v[0].equals("5");if(!legacy&&!v2&&!v3&&!v4&&!v5)return s;
            State n=new State();n.mode=Integer.parseInt(v[1]);n.minute=Integer.parseInt(v[2]);n.start=Long.parseLong(v[3]);n.wire=Long.parseLong(v[4]);n.deadline=Long.parseLong(v[5]);n.armed=Long.parseLong(v[6]);n.target=Double.parseDouble(v[7]);n.estimate=Double.parseDouble(v[8]);n.retired=Boolean.parseBoolean(v[9]);
            // Version 1 did not save manual and calculated starts separately. Only
            // a record without a calculated estimate can safely supply that preference.
            n.manualStart=legacy?(n.mode!=2&&n.estimate==0?n.start:0):Long.parseLong(v[10]);
            n.calculatedWire=legacy||v2?(n.mode==2?n.wire:0):Long.parseLong(v[11]);
            n.capturedSoc=legacy||v2?-1:Double.parseDouble(v[12]);
            // Before version 4 nothing recorded the battery at arming or an outcome: never infer a miss.
            if(v4||v5){n.armedSoc=Double.parseDouble(v[13]);n.outcome=Integer.parseInt(v[14]);n.outcomeMs=Long.parseLong(v[15]);n.outcomeStart=Long.parseLong(v[16]);n.outcomeSoc=Double.parseDouble(v[17]);
                n.outcomeMode=Integer.parseInt(v[18]);n.outcomeEnabled=Integer.parseInt(v[19]);n.outcomeWire=Long.parseLong(v[20]);n.alert=Integer.parseInt(v[21]);n.outcomeArmedSoc=Double.parseDouble(v[22]);
                if(!v[23].equals("true")&&!v[23].equals("false"))return s;n.started=Boolean.parseBoolean(v[23]);
                // Version 4 (#129) had no schedule test.
                if(v5){if(!v[29].equals("true")&&!v[29].equals("false"))return s;
                n.testWall=Long.parseLong(v[24]);n.testStart=Long.parseLong(v[25]);n.testResume=Long.parseLong(v[26]);n.testOutcome=Integer.parseInt(v[27]);n.testOff=Integer.parseInt(v[28]);n.testPaused=Boolean.parseBoolean(v[29]);
                if(n.testWall<0||n.testWall>0xffffffffL*1000L||n.testStart<0||n.testStart>0xffffffffL*1000L||n.testResume<0||n.testResume>0xffffffffL*1000L
                        ||n.testOutcome<0||n.testOutcome>=ChargeScheduleTest.Outcome.values().length||n.testOff<-1||n.testOff>1)return s;
                // A test still running when its record is loaded was cut off by the process ending.
                if(n.testOutcome==ChargeScheduleTest.Outcome.RUNNING.ordinal())n.testOutcome=ChargeScheduleTest.Outcome.INTERRUPTED.ordinal();}
                if(!soc(n.armedSoc)||!soc(n.outcomeSoc)||!soc(n.outcomeArmedSoc)||n.outcome<0||n.outcome>=Outcome.values().length||n.outcomeMs<0||n.outcomeMs>0xffffffffL*1000L||n.outcomeStart<0||n.outcomeStart>0xffffffffL*1000L||n.outcomeMode<0||n.outcomeMode>2
                        ||n.outcomeEnabled<-1||n.outcomeEnabled>1||n.outcomeWire<-1||n.outcomeWire>0xffffffffL||n.alert<ALERT_NONE||n.alert>ALERT_SHOWN)return s;}
            if(n.mode<0||n.mode>2||n.minute<0||n.minute>=1440||n.start<0||n.start>0xffffffffL*1000L||n.manualStart<0||n.manualStart>0xffffffffL*1000L||n.wire<0||n.wire>0xffffffffL||n.calculatedWire<0||n.calculatedWire>0xffffffffL||n.deadline<0||n.deadline>0xffffffffL*1000L||n.armed<0||n.armed>0xffffffffL*1000L||!Double.isFinite(n.target)||n.target<=0||n.target>100||!Double.isFinite(n.estimate)||n.estimate<0||n.estimate>2880||!Double.isFinite(n.capturedSoc)||n.capturedSoc< -1||n.capturedSoc>100)return s;
            if(n.mode!=0&&(n.start==0||n.armed==0||n.deadline<n.start))return s;return n;
        }catch(RuntimeException invalid){return s;}
    }
}
