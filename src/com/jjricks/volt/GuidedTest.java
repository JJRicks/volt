package com.jjricks.volt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * One owner-approved, reversible setting test: capture a fresh baseline, write the target once,
 * confirm it by a readback that cannot be a pre-write poll reply, ask the owner what they saw,
 * write the baseline back once and confirm that too. Every write is journalled (synced) before
 * writeNow() hands it out. Any interruption leaves the test OUTSTANDING with its restoration
 * outstanding; nothing is ever written again on reconnect or restart, only after a new explicit
 * owner decision (restoreFrom). Pure and Android-free: BleController feeds it on the main looper.
 */
final class GuidedTest {
    enum Phase { PRECHECK, BASELINE, TARGET_PENDING, TARGET_AWAIT, OBSERVE, RESTORE_PENDING, RESTORE_AWAIT, DONE, OUTSTANDING, CONFLICT }
    /** The compatibility outcome vocabulary (handoff docs/03). */
    enum Outcome {
        READ_SUPPORTED("Read supported"), REJECTED("Rejected, code N"), ACK_UNCONFIRMED("Acknowledged, unconfirmed"),
        READBACK_UNCHANGED("Readback unchanged"), READBACK_DIFFERENT("Readback different"),
        SETTING_CONFIRMED("Setting confirmed"), EFFECT_OBSERVED("Effect observed"),
        RESTORATION_CONFIRMED("Restoration confirmed"), INTERRUPTED("Interrupted/no response"),
        /** A try's write Volt refused or couldn't send, so nothing reached the scooter (guided tests record INTERRUPTED). */
        NOT_SENT("Not sent");
        final String text;
        Outcome(String text){this.text=text;}
        /** A rejection carries its code; a readback carries the value the scooter actually showed. */
        boolean carries(){return this==REJECTED||this==READBACK_UNCHANGED||this==READBACK_DIFFERENT;}
        /** REJECTED substitutes its code for N; a readback shows the value read. */
        String text(int code){return this==REJECTED?"Rejected, code "+code:carries()?text+", value "+code:text;}
        /** Journal token: the lower-case name, with "_N" (0..255) appended when it carries a code or value. */
        String token(int code){String name=name().toLowerCase(java.util.Locale.ROOT);return carries()?name+"_"+code:name;}
        static Outcome parse(String token){
            if(token==null)return null;
            int split=token.lastIndexOf('_');
            for(Outcome o:values()){
                String name=o.name().toLowerCase(java.util.Locale.ROOT);
                if(!o.carries()){if(name.equals(token))return o;continue;}
                if(split==name.length()&&token.startsWith(name)&&token.substring(split+1).matches("0|[1-9][0-9]{0,2}"))
                    return Integer.parseInt(token.substring(split+1))<=255?o:null;
            }
            return null;
        }
        /** The code or value of a carrying token ("rejected_N", "readback_different_N"), else -1. */
        static int code(String token){Outcome o=parse(token);return o!=null&&o.carries()?Integer.parseInt(token.substring(token.lastIndexOf('_')+1)):-1;}
        /** Display text for a journal token. */
        static String describe(String token){Outcome o=parse(token);return o==null?"":o.text(code(token));}
        /**
         * The outcome as the panel shows it, in the current language; text(code) stays the English that command
         * results, the journal and reports keep (plan 1.5).
         */
        String label(int code){
            switch(this){
                case READ_SUPPORTED: return T.compatOutcomeReadSupported();
                case REJECTED: return T.compatOutcomeRejected(code);
                case ACK_UNCONFIRMED: return T.compatOutcomeAckUnconfirmed();
                case READBACK_UNCHANGED: return T.compatOutcomeReadbackUnchanged(code);
                case READBACK_DIFFERENT: return T.compatOutcomeReadbackDifferent(code);
                case SETTING_CONFIRMED: return T.compatOutcomeSettingConfirmed();
                case EFFECT_OBSERVED: return T.compatOutcomeEffectObserved();
                case RESTORATION_CONFIRMED: return T.compatOutcomeRestorationConfirmed();
                case INTERRUPTED: return T.compatOutcomeInterrupted();
                default: return T.compatOutcomeNotSent();
            }
        }
        /** describe(token) as the panel shows it, in the current language. */
        static String label(String token){Outcome o=parse(token);return o==null?"":o.label(code(token));}
        /** Report token (the docs/03 wording in snake case); the code or value travels separately. */
        String reportToken(){return this==ACK_UNCONFIRMED?"acknowledged_unconfirmed":name().toLowerCase(java.util.Locale.ROOT);}
    }

    private final TestJournal journal;
    final String profileId,feature; final FeatureVariant variant; final int target;
    private final DeviceDescriptor descriptor;
    private final long wallOffset;
    private final Random random;
    private Phase phase;
    private Integer baseline; private int related=-1;
    /** The value the scooter should show until the restore write: the target, or the value the owner reviewed. */
    private int expected;
    private TestJournal.Entry entry;
    private long approvedMs=-1;
    private boolean writeIssued,acked,verifyOnly,readWanted;
    // There are no wire request IDs. Drain older requests first, THEN transmit the post-ACK
    // (or post-start) probe. Counting a mixed batch would let an older reply arriving last win.
    private int outstanding,needed=-1,replies;private boolean armed;
    /** TestJournal.TEST for an older guided test, else an Experimental controls try (EXPERIMENTAL or VERIFY), and its layer. */
    private String kind=TestJournal.TEST,layer="";
    /** Sees each journalled outcome as it is added (BleController's command log); never changes the test. */
    interface Observer { void outcome(GuidedTest test,Outcome outcome,int code,boolean writeInFlight,boolean restoring); }
    private Observer observer;
    /** Why the test ended or what it waits for, as a catalog message; note() is its English. */
    private Msg note=Msg.verbatim("");
    void observe(Observer o){observer=o;}
    /** A try starts from the value the owner saw in its sheet; any other baseline ends it with nothing written. */
    private Integer expectedBaseline;
    private static final long READ_WAIT_MS=10000;
    private long readDeadlineMs;

    private GuidedTest(TestJournal journal,String profileId,DeviceDescriptor d,FeatureVariant v,String feature,int target,long nowMs,long wallMs,Random random){
        this.journal=journal;this.profileId=profileId==null?"":profileId;this.descriptor=d;this.variant=v;this.feature=feature;this.target=target;
        this.wallOffset=wallMs-nowMs;this.random=random;this.expected=target;this.readDeadlineMs=nowMs+READ_WAIT_MS;
    }

    /**
     * Prepares a test of one value; nothing is written. The baseline comes from a 0x70 read
     * requested after this call (wantsRead), and approve() is the owner's explicit consent.
     */
    GuidedTest(TestJournal journal,String profileId,DeviceDescriptor d,FeatureVariant v,int target,long nowMs,long wallMs,Random random){
        this(journal,profileId,d,v,v.feature,target,nowMs,wallMs,random);
        if(!ModelRegistry.testable(v.feature)||v.lab||!v.readable||!v.allows(target))throw new IllegalArgumentException("Not offered as a test");
        phase=Phase.PRECHECK;arm();
    }

    /**
     * Prepares an Experimental controls try (kind EXPERIMENTAL or VERIFY) of one value, from the value the owner
     * saw (expectedBaseline). It runs like a guided test (fresh baseline, journal before every write, readback,
     * the owner's answer) except that the value stays afterwards: Set back is the owner's separate tap (setBack).
     * The caller approves it as soon as the baseline is captured (the Try tap was the owner's consent).
     */
    static GuidedTest trial(TestJournal journal,String profileId,DeviceDescriptor d,FeatureVariant v,String kind,int target,int expectedBaseline,
            long nowMs,long wallMs,Random random){
        if(!TestJournal.EXPERIMENTAL.equals(kind)&&!TestJournal.VERIFY.equals(kind)||!v.readable||!v.allows(target))throw new IllegalArgumentException("Not offered as a try");
        GuidedTest t=new GuidedTest(journal,profileId,d,v,v.feature,target,nowMs,wallMs,random);
        t.kind=kind;t.layer=d==null?"":ModelRegistry.resolve(d).layer.name().toLowerCase(java.util.Locale.ROOT);t.expectedBaseline=expectedBaseline;
        t.phase=Phase.PRECHECK;t.arm();return t;
    }
    /**
     * The value Set back would write for this try, or null: a kept try whose baseline the current try variant (v)
     * still offers, under the same variant it was journalled with. restoreFrom refuses anything else ("Test rules
     * changed", "Nothing to restore"), so Set back isn't offered then: for example a try kept under an earlier
     * registry's 0–5 km/h kick-start where 3–5 km/h is offered now, or one that started below 3 km/h there.
     */
    static Integer setBackValue(TestJournal.Entry e,FeatureVariant v){
        return e!=null&&e.resolved&&e.isTry()&&"kept".equals(e.resolution)&&v!=null&&v.id.equals(e.variantId)&&v.allows(e.baseline)?e.baseline:null;
    }
    /** TestJournal.TEST, EXPERIMENTAL or VERIFY. */
    String kind(){return kind;}
    /** A try keeps the value after the owner's answer; a guided test sets it back by itself. */
    boolean keeps(){return !TestJournal.TEST.equals(kind);}

    /** A journal entry after a restart or for display: OUTSTANDING (or CONFLICT, DONE). It never writes. */
    static GuidedTest fromJournal(TestJournal journal,TestJournal.Entry e){
        GuidedTest t=new GuidedTest(journal,e.profileId,null,null,e.feature,e.target,0,0,null);
        t.entry=e.copy();t.baseline=e.baseline;t.related=e.related;t.kind=e.kind;t.layer=e.layer;
        Phase p=Phase.valueOf(e.phase);
        t.phase=e.resolved?Phase.DONE:p==Phase.CONFLICT?Phase.CONFLICT:Phase.OUTSTANDING;
        return t;
    }

    /**
     * Process start: every unresolved entry still in a pending, awaiting or observing phase becomes
     * OUTSTANDING with "Interrupted/no response". No write is issued. Returns how many changed.
     */
    static int recover(TestJournal journal,long wallMs){
        int changed=0;
        for(TestJournal.Entry e:journal.unresolved()){
            Phase p=Phase.valueOf(e.phase);if(p==Phase.OUTSTANDING||p==Phase.CONFLICT)continue;
            e.phase=Phase.OUTSTANDING.name();TestJournal.append(e,Outcome.INTERRUPTED.token(0),Math.max(0,Math.min(24L*3600*1000,wallMs-e.createdWall)));
            e.updatedWall=Math.max(e.updatedWall,wallMs);
            try{journal.commit(e);changed++;}catch(IOException|IllegalArgumentException kept){/* the saved phase still reads as interrupted */}
        }
        return changed;
    }

    /**
     * The owner's explicit decision to restore an unfinished test after review: the current value
     * (fresh, present) is what the owner saw. The restore write is journalled before writeNow().
     */
    static GuidedTest restoreFrom(TestJournal journal,TestJournal.Entry e,FeatureVariant v,DeviceDescriptor d,StatusFields s,long nowMs,long wallMs) throws IOException{
        // A kept try is set back the same way (Set back), reopened in the journal before the write.
        boolean kept=e.resolved&&e.isTry()&&"kept".equals(e.resolution);
        if(e.resolved&&!kept||v==null||!v.id.equals(e.variantId))throw new IllegalArgumentException("Test rules changed");
        Integer current=v.read(s);
        if(current==null||current==e.baseline||!offered(v,s,e.baseline))throw new IllegalArgumentException("Nothing to restore");
        // The coupled field must be as journalled: a change made meanwhile is a conflict for the owner, never overwritten.
        // A kept try may be set back much later, after the owner changed a coupled setting on purpose: it starts from now.
        if(!kept&&e.related>=0&&relatedValue(e.feature,v,s)!=e.related)throw new IllegalArgumentException("A related setting changed");
        GuidedTest t=new GuidedTest(journal,e.profileId,d,v,e.feature,e.target,nowMs,wallMs,null);
        t.kind=e.kind;t.layer=e.layer;
        t.entry=e.copy();t.baseline=e.baseline;t.related=e.related>=0&&!kept?e.related:t.relatedValue(s);t.expected=current;t.approvedMs=nowMs-Math.max(0,wallMs-e.createdWall);
        // New evidence no sent report contains yet (the journal enforces this on every commit too).
        t.entry.related=t.related;t.entry.reported=false;
        if(kept){t.entry.resolved=false;t.entry.restoration=TestJournal.OUTSTANDING;t.entry.resolution="";}
        t.phase=Phase.RESTORE_PENDING;t.commit(nowMs);
        return t;
    }

    // ---- Owner actions -------------------------------------------------------------------------

    /**
     * The owner approves the shown baseline, target and restoration. The entry is committed as
     * TARGET_PENDING before anything is written; if that fails the test ends with no write.
     */
    boolean approve(long nowMs){
        if(phase!=Phase.BASELINE||baseline==null)return false;
        TestJournal.Entry e=new TestJournal.Entry();
        StringBuilder id=new StringBuilder();Random r=random==null?new Random():random;for(int i=0;i<16;i++)id.append(Character.forDigit(r.nextInt(16),16));
        e.testId=id.toString();e.profileId=profileId;e.feature=feature;e.variantId=variant.id;
        e.pidPrefix=descriptor==null?"":descriptor.family();e.pidSource=descriptor==null?"none":descriptor.pidSource().name().toLowerCase(java.util.Locale.ROOT);
        e.meterFw=descriptor!=null&&descriptor.firmwareThisSession&&descriptor.firmware.meter!=null&&descriptor.firmware.meter.matches("[0-9A-Za-z.]{1,16}")?descriptor.firmware.meter:"";
        e.registryVersion=ModelRegistry.VERSION;e.baseline=baseline;e.target=target;e.related=related;e.kind=kind;e.layer=layer;
        e.createdWall=e.updatedWall=Math.max(0,nowMs+wallOffset);
        entry=e;approvedMs=nowMs;phase=Phase.TARGET_PENDING;writeIssued=false;
        try{commit(nowMs);return true;}
        catch(IOException|IllegalArgumentException failed){entry=null;phase=Phase.DONE;note=M.compatNoteNotSaved();return false;}
    }
    /** Before approval only: ends the test with nothing written. */
    boolean cancel(){if(phase!=Phase.PRECHECK&&phase!=Phase.BASELINE)return false;phase=Phase.DONE;note=M.compatNoteCancelled();return true;}
    /** Answer to the effect prompt: true observed, false not observed, null skipped. Then the baseline is written back. */
    void ownerEffect(Boolean observed,long nowMs){
        if(phase!=Phase.OBSERVE)return;
        entry.effect=observed==null?"not_checked":observed?"observed":"not_observed";
        if(Boolean.TRUE.equals(observed))add(Outcome.EFFECT_OBSERVED,0,nowMs);
        if(keeps()){kept(nowMs);return;}
        phase=Phase.RESTORE_PENDING;writeIssued=false;
        try{commit(nowMs);}
        catch(IOException|IllegalArgumentException failed){phase=Phase.OUTSTANDING;note=M.compatNoteNotSavedNotSetBack();}
    }

    // ---- Inputs from BleController -------------------------------------------------------------

    /** Seed before stepping a newly created test/restore; older session polls must drain too. */
    void inheritOutstandingReads(int count){
        if(outstanding!=0||needed!=-1||writeIssued)throw new IllegalStateException("Test already started");
        outstanding=Math.max(0,count);
    }

    /** A read frame was transmitted. Only 0x70 matters. */
    void onReadRequested(int cmd,long nowMs){
        if(cmd!=0x70)return;
        outstanding++;
        if(armed){if(outstanding==1){armed=false;needed=1;replies=0;readWanted=false;}}
        else readWanted=false;
    }
    /** Every 0x70 reply (an unaccepted one is still an answer). */
    void onStatus(StatusFields s,long nowMs){
        tick(nowMs);
        boolean requested=outstanding>0;
        if(requested)outstanding--;
        if(requested&&needed>0)replies++;
        if(terminal())return;
        boolean conclusive=requested&&needed>0&&replies>=needed;
        if(s==null||!s.accepted()){if(conclusive)arm();return;}
        Integer value=variant.read(s);
        switch(phase){
            case PRECHECK: if(conclusive){needed=-1;capture(s);} return;
            case BASELINE: if(value==null||!value.equals(baseline))capture(s); return;
            case TARGET_AWAIT: if(conclusive&&acked){needed=-1;confirmTarget(s,value,nowMs);} return; // before the ACK every reply is stale
            case RESTORE_AWAIT: if(conclusive&&(acked||verifyOnly)){needed=-1;if(verifyOnly)verify(s,value,nowMs);else confirmRestore(s,value,nowMs);} return;
            case OBSERVE: case RESTORE_PENDING:
                if(value!=null&&(value!=expected||relatedChanged(s)))conflict(M.compatNoteChangedOutside(variant.text(value)),nowMs);
                return;
            default: return;
        }
    }
    /** A reply with the mutation's command: status 0 is an ACK, anything else a rejection with that code. */
    void onAck(int cmd,int status,long nowMs){
        if(variant==null||cmd!=variant.cmd||acked||verifyOnly||!writeIssued)return;
        if(phase!=Phase.TARGET_AWAIT&&phase!=Phase.RESTORE_AWAIT)return;
        if(status!=0){
            add(Outcome.REJECTED,status,nowMs);
            if(phase==Phase.TARGET_AWAIT){
                // Restoration is not needed, but it is verified: a fresh readback must equal the baseline.
                verifyOnly=true;phase=Phase.RESTORE_AWAIT;readDeadlineMs=nowMs+READ_WAIT_MS;arm();save(nowMs);
            }else outstanding(M.compatNoteRestoreRejected(status),nowMs);
            return;
        }
        acked=true;arm();
    }
    /** BleController's 6.5 s mutation timeout for our write: inconclusive, restoration outstanding. */
    void onMutationTimeout(boolean ackSeen,long nowMs){
        if(phase!=Phase.TARGET_AWAIT&&phase!=Phase.RESTORE_AWAIT)return;
        if(verifyOnly){outstanding(M.compatNoteNoFreshReading(),nowMs);return;}
        add(ackSeen||acked?Outcome.ACK_UNCONFIRMED:Outcome.INTERRUPTED,0,nowMs);
        outstanding(ackSeen||acked?M.compatNoteAcceptedUnconfirmed():M.compatNoteNoReply(),nowMs);
    }
    /** BleController refused or could not send the value writeNow() returned. */
    void onWriteRefused(long nowMs){
        if(phase==Phase.TARGET_AWAIT&&!acked&&!verifyOnly){add(keeps()?Outcome.NOT_SENT:Outcome.INTERRUPTED,0,nowMs);verifyOnly=true;phase=Phase.RESTORE_AWAIT;readDeadlineMs=nowMs+READ_WAIT_MS;arm();save(nowMs);}
        else if(phase==Phase.RESTORE_AWAIT&&!acked&&!verifyOnly){add(Outcome.INTERRUPTED,0,nowMs);outstanding(M.compatNoteRestoreNotSent(),nowMs);}
    }
    /** An unanswered older poll must never leave preparation or verification spinning forever.
     * Do not discard the read counter: a late old reply must never become restoration evidence. */
    void tick(long nowMs){
        if((phase!=Phase.PRECHECK&&!verifying())||nowMs<readDeadlineMs)return;
        if(phase==Phase.PRECHECK)end(M.compatNoteNoBaseline());
        else{add(Outcome.INTERRUPTED,0,nowMs);outstanding(M.compatNoteNotVerified(),nowMs);}
    }
    /** Disconnect, Bluetooth off, background suspension or service end. */
    void onDisconnect(long nowMs){interrupt(M.compatNoteDisconnected(),nowMs);}
    void onProfileSwitch(long nowMs){interrupt(M.compatNoteProfileSwitched(),nowMs);}
    /** A replayed test is already OUTSTANDING; a live one is interrupted like a disconnect. */
    void onProcessStart(long nowMs){interrupt(M.compatNoteRestarted(),nowMs);}

    // ---- Outputs -------------------------------------------------------------------------------

    /** The value to send now, exactly once per pending phase and only after its journal commit; else null. */
    Integer writeNow(){
        if(writeIssued||entry==null)return null;
        if(phase==Phase.TARGET_PENDING){writeIssued=true;acked=false;phase=Phase.TARGET_AWAIT;return target;}
        if(phase==Phase.RESTORE_PENDING){writeIssued=true;acked=false;verifyOnly=false;phase=Phase.RESTORE_AWAIT;return baseline;}
        return null;
    }
    /** Whether the write handed out by writeNow() is the restore (GUIDED_RESTORE) rather than the target. */
    boolean restoring(){return phase==Phase.RESTORE_PENDING||phase==Phase.RESTORE_AWAIT;}
    /** Last read-only precondition at actual transmission, after any GATT queue delay. */
    boolean admitsQueuedWrite(StatusFields s,long nowMs){
        if(!awaitingWrite()||terminal()||variant==null)return false;
        Integer value=variant.read(s),wanted=phase==Phase.TARGET_AWAIT?baseline:expected;
        if(value==null||!value.equals(wanted)||relatedChanged(s)){
            conflict(M.compatNoteChangedWaiting(),nowMs);return false;
        }
        return true;
    }
    /** The test wants a 0x70 read sent (baseline or verification). */
    boolean wantsRead(){return readWanted&&outstanding==0&&!terminal();}
    /** Waiting for the ACK or readback of our own write. */
    boolean awaitingWrite(){return (phase==Phase.TARGET_AWAIT||phase==Phase.RESTORE_AWAIT)&&writeIssued&&!verifyOnly;}
    /** Checking by a fresh read that nothing changed after a rejected or unsent write (no restore write). */
    boolean verifying(){return verifyOnly&&phase==Phase.RESTORE_AWAIT;}
    boolean terminal(){return phase==Phase.DONE||phase==Phase.OUTSTANDING||phase==Phase.CONFLICT;}
    /** Nothing was written (cancelled, not testable now, or the record could not be saved). */
    boolean nothingWritten(){return entry==null;}
    Phase phase(){return phase;}
    Integer baseline(){return baseline;}
    int expected(){return expected;}
    String note(){return note.english();}
    /** The note to show, in the current language at display time. */
    Msg noteMsg(){return note;}
    String testId(){return entry==null?"":entry.testId;}
    List<Outcome> outcomes(){
        if(entry==null)return Collections.emptyList();
        List<Outcome> out=new ArrayList<>();for(String t:entry.outcomes)out.add(Outcome.parse(t));return out;
    }
    /** Outcome texts, with the rejection code. */
    List<String> outcomeTexts(){List<String> out=new ArrayList<>();if(entry!=null)for(String t:entry.outcomes)out.add(Outcome.describe(t));return out;}
    /** outcomeTexts() as the panel shows them, in the current language. */
    List<String> outcomeLabels(){List<String> out=new ArrayList<>();if(entry!=null)for(String t:entry.outcomes)out.add(Outcome.label(t));return out;}
    /** "confirmed", "outstanding" or "declined"; "none" before anything was written. */
    String restorationStatus(){return entry==null?"none":entry.restoration;}
    TestJournal.Entry entry(){return entry==null?null:entry.copy();}

    // ---- Review after an interruption (C4.2) ---------------------------------------------------

    enum ReviewState { WAITING, MODEL_CHANGED, FIRMWARE_CHANGED, ALREADY_RESTORED, RESTORE_NEEDED, CHANGED_OUTSIDE }
    static final class Review {
        final ReviewState state; final String text; final Integer current; final boolean restorable;
        Review(ReviewState state,String text,Integer current,boolean restorable){this(state,Msg.verbatim(text),current,restorable);}
        /** The text to show, in the current language at display time; text keeps its English for notices and the event log. */
        final Msg message;
        Review(ReviewState state,Msg text,Integer current,boolean restorable){this.state=state;this.message=text;this.text=text.english();this.current=current;this.restorable=restorable;}
    }
    /**
     * What an unfinished test needs now. It requires the same profile, authenticated and ready
     * (BleController's ready() includes the serial check), the same model evidence and test rules,
     * the same meter firmware when one was recorded (else confirmation), and a fresh 0x70 with the
     * field present. It never writes; Restore is a separate explicit tap.
     */
    static Review review(TestJournal.Entry e,String profileId,boolean ready,DeviceDescriptor d,StatusFields s,long nowMs,FeatureVariant v,boolean firmwareConfirmed){
        if(!ready||d==null||!e.profileId.equals(profileId))return new Review(ReviewState.WAITING,M.compatReviewConnect(),null,false);
        if(!e.pidPrefix.equals(d.family())||!e.pidSource.equals(d.pidSource().name().toLowerCase(java.util.Locale.ROOT)))
            return new Review(ReviewState.MODEL_CHANGED,M.compatReviewModelChanged(),null,false);
        if(v==null||!v.id.equals(e.variantId))return new Review(ReviewState.MODEL_CHANGED,M.compatReviewRulesChanged(),null,false);
        if(!e.meterFw.isEmpty()&&!firmwareConfirmed&&!(d.firmwareThisSession&&e.meterFw.equals(d.firmware.meter)))
            return new Review(ReviewState.FIRMWARE_CHANGED,d.firmwareThisSession?M.compatReviewFirmwareChanged():M.compatReviewFirmwareNotRead(),null,false);
        if(s==null||!s.fresh(nowMs,CapabilityResolver.STATUS_MAX_AGE_MS))return new Review(ReviewState.WAITING,M.compatReviewWaiting(),null,false);
        Integer current=v.read(s);
        if(current==null)return new Review(ReviewState.WAITING,M.compatReviewNotReported(),null,false);
        if(e.related>=0){
            int related=relatedValue(e.feature,v,s);
            if(related<0)return new Review(ReviewState.WAITING,M.compatReviewRelatedNotReported(),null,false);
            // Another actor changed the coupled field: not restorable here; the owner keeps or dismisses it.
            if(related!=e.related)return new Review(ReviewState.CHANGED_OUTSIDE,M.compatReviewRelatedChanged(),current,false);
        }
        boolean restorable=offered(v,s,e.baseline);
        if(current==e.baseline)return new Review(ReviewState.ALREADY_RESTORED,M.compatReviewAlreadyBack(v.text(e.baseline)),current,false);
        if(current==e.target)return new Review(ReviewState.RESTORE_NEEDED,M.compatReviewStillTestValue(v.text(e.target)),current,restorable);
        return new Review(ReviewState.CHANGED_OUTSIDE,M.compatReviewChangedOutside(v.text(current)),current,restorable);
    }
    /** Already back to the baseline: restoration confirmed by a fresh readback, no write. */
    static void confirmAlreadyRestored(TestJournal journal,TestJournal.Entry e,long wallMs) throws IOException{
        TestJournal.Entry next=e.copy();TestJournal.append(next,Outcome.RESTORATION_CONFIRMED.token(0),Math.max(0,Math.min(24L*3600*1000,wallMs-e.createdWall)));
        next.phase=Phase.DONE.name();next.updatedWall=Math.max(next.createdWall,wallMs);journal.commit(next);
        journal.resolve(e.testId,TestJournal.CONFIRMED,"already_restored",wallMs);
    }

    // ---- Internals -----------------------------------------------------------------------------

    private void arm(){armed=true;needed=-1;replies=0;readWanted=true;}
    private void capture(StatusFields s){
        Integer value=variant.read(s);
        if(value==null){end(M.compatNoteNotReported());return;}
        // A try may start from a setting its variant shows but doesn't offer (a kick-start below 3 km/h where only 3–5 km/h
        // is offered): it moves to an offered value, and Set back is never offered for it (setBackValue, offered). A guided
        // test always writes its baseline back, so it still needs an offered one.
        boolean shown=keeps()&&variant.shows(value);
        if(!variant.allows(value)&&!shown){end(M.compatNoteValueUnknown());return;}
        if(expectedBaseline!=null&&!value.equals(expectedBaseline)){end(M.compatNoteBaselineMoved(variant.text(value)));return;}
        if(value==target){end(M.compatNoteAlreadySet(variant.text(target)));return;}
        if(!offered(variant,s,target)){end(M.compatNoteTargetNotOffered(variant.text(target)));return;}
        if(!shown&&!offered(variant,s,value)){end(M.compatNoteCannotSetBack());return;}
        baseline=value;related=relatedValue(s);phase=Phase.BASELINE;
    }
    private void confirmTarget(StatusFields s,Integer value,long nowMs){
        if(value==null){arm();return;} // absent: inconclusive, wait for the next reading
        if(relatedChanged(s)){conflict(M.compatNoteRelatedChanged(),nowMs);return;}
        if(value==target){add(Outcome.SETTING_CONFIRMED,0,nowMs);phase=Phase.OBSERVE;save(nowMs);return;}
        if(value.equals(baseline)){
            add(Outcome.READBACK_UNCHANGED,value,nowMs);add(Outcome.RESTORATION_CONFIRMED,0,nowMs);
            note=M.compatNoteUnchanged(variant.text(baseline));finish(nowMs);return;
        }
        add(Outcome.READBACK_DIFFERENT,value,nowMs);conflict(M.compatNoteNowShows(variant.text(value)),nowMs);
    }
    private void confirmRestore(StatusFields s,Integer value,long nowMs){
        if(value==null){arm();return;}
        if(relatedChanged(s)){conflict(M.compatNoteRelatedChanged(),nowMs);return;}
        if(value.equals(baseline)){add(Outcome.RESTORATION_CONFIRMED,0,nowMs);finish(nowMs);return;}
        if(value==expected){add(Outcome.READBACK_UNCHANGED,value,nowMs);outstanding(M.compatNoteStillShows(variant.text(value)),nowMs);return;}
        add(Outcome.READBACK_DIFFERENT,value,nowMs);conflict(M.compatNoteNowShows(variant.text(value)),nowMs);
    }
    /** After a rejection or an unsent write: nothing should have changed; a fresh readback must show the baseline. */
    private void verify(StatusFields s,Integer value,long nowMs){
        if(value==null){arm();return;}
        if(value.equals(baseline)&&!relatedChanged(s)){add(Outcome.RESTORATION_CONFIRMED,0,nowMs);finish(nowMs);return;}
        if(value==expected){outstanding(M.compatNoteShowsTestValue(variant.text(value)),nowMs);return;}
        conflict(M.compatNoteNowShows(variant.text(value)),nowMs);
    }
    private void interrupt(Msg why,long nowMs){
        if(phase==Phase.PRECHECK||phase==Phase.BASELINE){phase=Phase.DONE;note=M.compatNoteStopped();return;}
        if(terminal())return;
        add(Outcome.INTERRUPTED,0,nowMs);outstanding(why,nowMs);
    }
    private void end(Msg why){phase=Phase.DONE;note=why;}
    private void finish(long nowMs){
        phase=Phase.DONE;entry.phase=Phase.DONE.name();entry.restoration=TestJournal.CONFIRMED;entry.resolved=true;entry.resolution="restored";
        entry.updatedWall=Math.max(entry.createdWall,nowMs+wallOffset);
        try{journal.commit(entry);}catch(IOException|IllegalArgumentException failed){note=M.compatNoteRestoredNotSaved();}
    }
    /** A try's value stays after the owner's answer: resolved as kept (declined restoration); Set back can reopen it. */
    private void kept(long nowMs){
        phase=Phase.DONE;entry.phase=Phase.DONE.name();entry.restoration=TestJournal.DECLINED;entry.resolved=true;entry.resolution="kept";
        entry.updatedWall=Math.max(entry.createdWall,nowMs+wallOffset);
        try{journal.commit(entry);}catch(IOException|IllegalArgumentException failed){note=M.compatNoteKeptNotSaved();}
    }
    private void outstanding(Msg why,long nowMs){phase=Phase.OUTSTANDING;note=why;entry.restoration=TestJournal.OUTSTANDING;save(nowMs);}
    private void conflict(Msg why,long nowMs){phase=Phase.CONFLICT;note=why;entry.restoration=TestJournal.OUTSTANDING;save(nowMs);}
    private void add(Outcome o,int code,long nowMs){TestJournal.append(entry,o.token(code),Math.max(0,Math.min(24L*3600*1000,nowMs-approvedMs)));if(observer!=null)observer.outcome(this,o,code,awaitingWrite(),restoring());}
    /** Commit failures after a write leave the earlier phase in the journal, which replays as OUTSTANDING. */
    private void save(long nowMs){try{commit(nowMs);}catch(IOException|IllegalArgumentException kept){}}
    private void commit(long nowMs) throws IOException{
        entry.phase=phase.name();entry.updatedWall=Math.max(entry.createdWall,nowMs+wallOffset);
        journal.commit(entry);
    }
    /** The coupled value captured with the baseline: the range choice set (offset 15) or the other bits of a masked byte (never a cap's enable bit). */
    private int relatedValue(StatusFields s){return relatedValue(feature,variant,s);}
    private static int relatedValue(String feature,FeatureVariant variant,StatusFields s){
        if(variant==null)return -1;
        if(feature.equals("range_algorithm")){Integer set=s.u8(15);return set==null?-1:set;}
        int bits=variant.valueMask();
        if(variant.offset>=0&&bits!=255){Integer raw=s.u8(variant.offset);return raw==null?-1:raw&~bits&255;}
        return -1;
    }
    private boolean relatedChanged(StatusFields s){return related>=0&&relatedValue(s)!=related;}
    /**
     * A value Volt may send now, as CapabilityResolver.Decision.allows has it: inside the variant's domain (listed
     * values, or a slider's min/max/step such as the UT5 MAX speed caps) and, for dynamic choices, offered by this status.
     */
    private static boolean offered(FeatureVariant v,StatusFields s,int value){return v.allows(value)&&(!v.dynamicChoices||contains(v.choices(s),value));}
    private static boolean contains(int[] values,int value){for(int v:values)if(v==value)return true;return false;}
}
