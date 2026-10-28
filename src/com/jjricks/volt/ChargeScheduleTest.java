package com.jjricks.volt;

/**
 * The owner's "Test scheduled charging": a start about two minutes ahead, armed through the
 * controller's usual transaction, then judged only from fresh charging_state readings. Neither
 * Volt nor the official app can read the scooter's clock back, so the pause and the resume are the
 * evidence. This class never sends a command; ChargeScheduleController acts on its verdict.
 */
public final class ChargeScheduleTest {
    /** Persisted by ordinal: append only. RUNNING found on load means the process ended mid-test. */
    public enum Outcome { NONE, RUNNING, PASSED, RESUMED_EARLY, RESUMED_LATE, NO_PAUSE, NO_RESUME, LIMIT_REACHED, CANCELLED, INTERRUPTED, NOT_STARTED }
    public enum Phase { ARMING, PAUSING, WAITING, ENDING }
    static final long LEAD_MS=120_000L, PAUSE_MS=60_000L, EARLY_MS=30_000L, LATE_MS=90_000L, GIVE_UP_MS=5*60_000L, STALE_MS=30_000L;
    public final long beganWallMs, startWallMs;
    private long onMs, lastReadingMs, resumeMs;
    private boolean paused, cancelRequested, ending;
    private Outcome outcome=Outcome.NONE;

    /** The start is now + 2 min rounded up to a whole minute: enough lead for the arming writes and a pause. */
    public static long start(long now){return Math.floorDiv(now+LEAD_MS+59_999L,60_000L)*60_000L;}
    public ChargeScheduleTest(long now){beganWallMs=now;startWallMs=start(now);}

    public Phase phase(){return ending?Phase.ENDING:onMs==0?Phase.ARMING:paused?Phase.WAITING:Phase.PAUSING;}
    public boolean paused(){return paused;}
    public Outcome outcome(){return outcome;}
    /** Resume minus start, whole seconds; null until charging resumed. */
    public Integer offsetSeconds(){return resumeMs==0?null:(int)Math.round((resumeMs-startWallMs)/1000d);}
    long resumeWallMs(){return resumeMs;}
    boolean cancelRequested(){return cancelRequested;}
    /** ON was confirmed: the pause window opens now. */
    void armed(long now){if(onMs==0){onMs=now;lastReadingMs=now;}}
    void requestCancel(){cancelRequested=true;}
    /** The verdict is in and its schedule-off cleanup is running. */
    void ending(){ending=true;}

    /**
     * One observation from the current connection; charging is null without a fresh reading. Returns the verdict
     * once known, else NONE. A charger unplugged mid-test reads as a pause too: charging_state cannot tell them apart.
     */
    Outcome observe(long now,Integer charging,Double soc,Double target){
        if(outcome!=Outcome.NONE||onMs==0)return outcome;
        // A phone clock set backwards makes every deadline below meaningless.
        if(now<beganWallMs)return end(Outcome.INTERRUPTED);
        if(charging!=null)lastReadingMs=now;
        // The charger stops by itself at the limit, which would look like a pause that never resumes.
        if(soc!=null&&target!=null&&Double.isFinite(soc)&&Double.isFinite(target)&&soc>=target)return end(Outcome.LIMIT_REACHED);
        if(!paused){
            if(Integer.valueOf(0).equals(charging))paused=true;
            // A late first 0 still counts as a pause; only charging seen after the window proves there was none.
            else if(Integer.valueOf(1).equals(charging)&&now>=onMs+PAUSE_MS)return end(Outcome.NO_PAUSE);
        }else if(Integer.valueOf(1).equals(charging)){
            resumeMs=now;
            return end(now<startWallMs-EARLY_MS?Outcome.RESUMED_EARLY:now<=startWallMs+LATE_MS?Outcome.PASSED:Outcome.RESUMED_LATE);
        }
        if(now>=startWallMs+GIVE_UP_MS)return end(paused?Outcome.NO_RESUME:Outcome.INTERRUPTED);
        if(now-lastReadingMs>STALE_MS)return end(Outcome.INTERRUPTED);
        return Outcome.NONE;
    }
    /** Ends without a verdict (cancel, interruption or an arming that failed); a verdict already in stays. */
    Outcome stop(Outcome reason){return outcome!=Outcome.NONE?outcome:end(reason);}
    private Outcome end(Outcome result){outcome=result;return result;}
}
