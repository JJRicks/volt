package com.jjricks.volt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

public final class ChargeScheduleControllerTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static long time(String iso){return Instant.parse(iso).toEpochMilli();}
    /** An older schema's record: the leading fields of the current encoding under that version. */
    private static String legacy(String encoded,int fields,String version){String[] v=encoded.split("\\|",-1);v[0]=version;return String.join("|",java.util.Arrays.copyOf(v,fields));}
    private static final class Rig implements ChargeScheduleController.Store,ChargeScheduleController.Driver {
        final Map<String,String> saved=new HashMap<>();
        final List<String> writes=new ArrayList<>();
        final ChargeScheduleController.Context c=new ChargeScheduleController.Context();
        final List<String> results=new ArrayList<>();
        ChargeScheduleController controller;
        Consumer<Boolean> callback;
        long sentTime;
        int sentOffset;
        Rig(){c.scope="owner-A";c.ready=true;c.generation=1;c.now=ChargeScheduleControllerTest.time("2026-10-20T05:00:00Z");c.zone=TimeZone.getTimeZone("America/Boise");c.charging=1;c.enabled=0;c.soc=79d;c.target=100d;c.estimateMinutes=90d;c.sampleMs=100_000;controller=new ChargeScheduleController(this,this);}
        public String read(String key){return saved.get(key);}
        public void write(String key,String value){saved.put(key,value);}
        public ChargeScheduleController.Context context(){return c;}
        public void clock(Consumer<Boolean> result){check(callback==null,"one pending write");writes.add("clock");callback=result;}
        public void time(long wall,int offset,Consumer<Boolean> result){check(callback==null,"one pending time");writes.add("time");sentTime=wall;sentOffset=offset;callback=result;}
        public void enabled(boolean enabled,Consumer<Boolean> result){check(callback==null,"one pending enable");writes.add(enabled?"on":"off");callback=result;}
        void reply(boolean success){Consumer<Boolean> done=callback;callback=null;done.accept(success);}
        void enable(){controller.setDoneEnabled(true,results::add);}
        void arm(){enable();check(writes.equals(java.util.Arrays.asList("clock")),"clock first");reply(true);check(writes.size()==2&&writes.get(1).equals("time"),"time second");c.wireStart=sentTime/1000+sentOffset;reply(true);check(writes.get(2).equals("on"),"enable last");c.enabled=1;c.charging=0;reply(true);check(results.size()==1&&results.get(0)==null,"confirmed success");}
        void advance(long ms){c.now+=ms;c.sampleMs+=ms;}
        void recreate(){controller=new ChargeScheduleController(this,this);}
    }
    public static void main(String[] args){
        planner();transactions();reconciliation();gating();startupEstimateGuard();doneByConfirmation();persistence();preferences();restoration();externalManualEdits();connectedPlanEdits();missedStarts();scheduleTests();linkedNotReady();staleSettings();startSeenStale();journal();
        System.out.println("ChargeScheduleControllerTest: "+checks+" assertions passed");
    }
    /** The start-sent listener hears only a start the scooter confirmed. */
    private static void doneByConfirmation(){
        List<Long> heard=new ArrayList<>();
        Rig on=new Rig();on.controller.setDoneEnabled(true,on.results::add,heard::add);
        check(heard.isEmpty(),"nothing heard before the clock write");on.reply(true);check(heard.isEmpty(),"nothing heard before the time write");
        on.c.wireStart=on.sentTime/1000+on.sentOffset;on.reply(true);check(heard.isEmpty(),"time ack alone is not a confirmed enable");
        on.c.enabled=1;on.reply(true);
        check(heard.size()==1&&heard.get(0)==on.sentTime&&on.results.get(0)==null,"confirmed enable heard once, with the saved start");
        check(on.controller.view().doneByEnabled&&on.controller.view().startWallMs==heard.get(0),"the heard start is the plan the view shows");
        heard.clear();
        // A confirmed edit while active sends a new start.
        on.controller.setDoneTime(9,0,on.results::add,heard::add);on.reply(true);on.c.wireStart=on.sentTime/1000+on.sentOffset;on.reply(true);check(heard.isEmpty(),"edit waits for confirmation");on.c.enabled=1;on.reply(true);
        check(heard.size()==1&&heard.get(0)==on.sentTime&&on.controller.view().doneByEnabled,"confirmed active edit heard once with the new start");
        // Each step of a failed enable.
        for(int fail=0;fail<3;fail++){Rig r=new Rig();heard.clear();r.controller.setDoneEnabled(true,r.results::add,heard::add);
            for(int step=0;step<=fail;step++)r.reply(step!=fail);
            check(heard.isEmpty()&&!r.controller.view().doneByEnabled&&r.results.get(0)!=null,"unconfirmed step "+fail+" is not heard");}
        // A limit changed while the switch was being confirmed keeps the time but drops the plan: no claim.
        Rig limit=new Rig();heard.clear();limit.controller.setDoneEnabled(true,limit.results::add,heard::add);limit.reply(true);limit.reply(true);limit.c.target=80d;limit.reply(true);
        check(heard.isEmpty()&&limit.results.get(0)!=null,"changed limit after ACK is not heard");
        // A dropped connection before the switch confirmed.
        Rig dropped=new Rig();heard.clear();dropped.controller.setDoneEnabled(true,dropped.results::add,heard::add);dropped.reply(true);dropped.reply(true);dropped.c.generation++;dropped.controller.observe();
        check(heard.isEmpty()&&!dropped.controller.view().doneByEnabled,"interrupted enable is not heard");
        // Not enough time to wait: Volt turns the schedule off and charges now, so no start was saved.
        Rig now=new Rig();now.c.now=time("2026-10-20T13:50:00Z");heard.clear();now.controller.setDoneEnabled(true,now.results::add,heard::add);now.reply(true);
        check(heard.isEmpty()&&now.results.get(0)!=null&&!now.controller.view().doneByEnabled,"charging now is never heard");
        // A saved preference while off and an edit while offline do not reach the scooter.
        Rig pref=new Rig();heard.clear();pref.controller.setDoneTime(7,30,pref.results::add,heard::add);check(heard.isEmpty()&&pref.results.get(0)==null&&pref.writes.isEmpty(),"preference edit is not heard");
        // Measuring arms through the listener-free call, which has nothing to hear it.
        Rig measured=new Rig();heard.clear();measured.controller.setDoneEnabled(true,measured.results::add);measured.reply(true);measured.reply(true);measured.reply(true);
        check(heard.isEmpty()&&measured.controller.view().doneByEnabled,"no listener, no message");
        startPassedInFlight();manualConfirmation();reassuranceNeedsLiveOn();reassuranceRetires();
    }
    /** A start that passes while the ON confirmation is in flight proves nothing about the scooter having taken it in time. */
    private static void startPassedInFlight(){
        List<Long> heard=new ArrayList<>();
        Rig late=new Rig();late.controller.setDoneEnabled(true,late.results::add,heard::add);late.reply(true);late.c.wireStart=late.sentTime/1000+late.sentOffset;late.reply(true);
        check(late.writes.get(2).equals("on"),"the start was still ahead when ON was sent");
        late.c.now=late.sentTime+2_000;late.c.enabled=1;late.reply(true);
        check(heard.isEmpty(),"a start that passed during the ON confirmation is not reassured");
        check(late.results.get(0)==null&&late.controller.view().doneByEnabled&&late.controller.view().startWallMs==late.sentTime,"the confirmed plan stays tracked so a missed start can still be caught");
        Rig exact=new Rig();exact.controller.setDoneEnabled(true,exact.results::add,heard::add);exact.reply(true);exact.c.wireStart=exact.sentTime/1000+exact.sentOffset;exact.reply(true);
        exact.c.now=exact.sentTime;exact.c.enabled=1;exact.reply(true);
        check(heard.isEmpty(),"a confirmation landing on the start instant is not reassured");
        Rig ontime=new Rig();ontime.controller.setDoneEnabled(true,ontime.results::add,heard::add);ontime.reply(true);ontime.c.wireStart=ontime.sentTime/1000+ontime.sentOffset;ontime.reply(true);
        ontime.c.now=ontime.sentTime-1_000;ontime.c.enabled=1;ontime.reply(true);
        check(heard.size()==1&&heard.get(0)==ontime.sentTime,"a confirmation arriving in time is reassured");
    }
    private static void confirmWrites(Rig r){r.reply(true);r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);r.c.enabled=1;r.reply(true);}
    /** Manual Scheduled charging hears the same confirmed start, never for a test, turning off, or a start that passed in flight. */
    private static void manualConfirmation(){
        List<Long> heard=new ArrayList<>();long first=time("2026-10-20T09:00:00Z");long second=time("2026-10-20T10:00:00Z");
        Rig r=new Rig();r.c.wireStart=first/1000-6*3600;r.controller.setManualEnabled(true,r.results::add,heard::add);
        check(heard.isEmpty(),"manual enable waits for confirmation");
        for(int fail=0;fail<3;fail++){Rig f=new Rig();f.c.wireStart=first/1000-6*3600;List<Long> none=new ArrayList<>();f.controller.setManualEnabled(true,f.results::add,none::add);
            for(int step=0;step<=fail;step++)f.reply(step!=fail);
            check(none.isEmpty()&&f.results.get(0)!=null,"unconfirmed manual step "+fail+" is not heard");}
        confirmWrites(r);
        check(heard.size()==1&&heard.get(0)==r.sentTime&&r.controller.view().manualEnabled&&r.results.get(0)==null,"confirmed manual enable heard with the saved start");
        heard.clear();r.controller.setManualStart(second,r.results::add,heard::add);r.reply(true);check(heard.isEmpty(),"manual edit waits for the time confirmation");r.c.wireStart=second/1000-6*3600;r.reply(true);
        check(heard.size()==1&&heard.get(0)==second,"confirmed manual edit heard with its start");
        heard.clear();r.controller.setManualEnabled(false,r.results::add,heard::add);r.c.enabled=0;r.reply(true);
        check(heard.isEmpty()&&!r.controller.view().manualEnabled,"turning manual off is never heard");
        Rig off=new Rig();off.c.enabled=0;off.controller.setManualStart(second,off.results::add,heard::add);off.reply(true);off.c.wireStart=second/1000-6*3600;off.reply(true);
        check(heard.isEmpty()&&!off.controller.view().manualEnabled&&off.results.get(0)==null,"a time edit while off is not heard: the scooter will not act on it");
        Rig late=new Rig();late.c.wireStart=first/1000-6*3600;late.controller.setManualEnabled(true,late.results::add,heard::add);late.reply(true);late.c.wireStart=late.sentTime/1000+late.sentOffset;late.reply(true);
        late.c.now=late.sentTime+2_000;late.c.enabled=1;late.reply(true);
        check(heard.isEmpty()&&late.controller.view().manualEnabled,"a manual start that passed during the ON confirmation is not heard");
        Rig test=new Rig();test.c.wireStart=first/1000-6*3600;check(test.controller.startTest(x->{})==null,"test starts");test.reply(true);test.c.wireStart=test.sentTime/1000+test.sentOffset;test.reply(true);test.c.enabled=1;test.reply(true);
        check(heard.isEmpty()&&test.controller.testing(),"Test scheduled charging has no listener to hear");
    }
    /** A rig with confirmed manual Scheduled charging on, so the remembered plan says on whatever the scooter reads next. */
    private static Rig manualOn(List<Long> heard,long first){Rig r=new Rig();r.c.wireStart=first/1000-6*3600;r.controller.setManualEnabled(true,r.results::add,heard::add);confirmWrites(r);check(heard.size()==1&&r.controller.view().manualEnabled,"manual armed");heard.clear();return r;}
    /** A time edit writes no ON, so only a live reading of the switch can back the message; a reliable OFF retires one already out. */
    private static void reassuranceNeedsLiveOn(){
        List<Long> heard=new ArrayList<>();long first=time("2026-10-20T09:00:00Z");long second=time("2026-10-20T10:00:00Z");long secondWire=second/1000-6*3600;
        Rig before=manualOn(heard,first);before.c.enabled=0;check(before.controller.view().manualEnabled,"the remembered plan still says on over a zero");
        before.controller.setManualStart(second,before.results::add,heard::add);before.reply(true);before.c.wireStart=secondWire;before.reply(true);
        check(heard.isEmpty()&&before.results.get(1)==null,"OFF before the edit: saved, but no reassurance");
        check(!before.controller.startStillPlanned("owner-A",second),"OFF before the edit: nothing claims the new start");
        Rig during=manualOn(heard,first);during.controller.setManualStart(second,during.results::add,heard::add);during.reply(true);during.c.wireStart=secondWire;during.c.enabled=0;during.reply(true);
        check(heard.isEmpty()&&!during.controller.startStillPlanned("owner-A",second),"OFF while the time was confirming: no reassurance");
        Rig on=manualOn(heard,first);on.controller.setManualStart(second,on.results::add,heard::add);on.reply(true);on.c.wireStart=secondWire;on.reply(true);
        check(heard.size()==1&&heard.get(0)==second&&on.controller.startStillPlanned("owner-A",second),"confirmed ON: the new start is heard and holds");heard.clear();
        Rig unknown=manualOn(heard,first);unknown.controller.setManualStart(second,unknown.results::add,heard::add);unknown.reply(true);unknown.c.wireStart=secondWire;unknown.c.enabled=null;unknown.reply(true);
        check(heard.isEmpty(),"no reading of the switch: silence");
        Rig odd=manualOn(heard,first);odd.controller.setManualStart(second,odd.results::add,heard::add);odd.reply(true);odd.c.wireStart=secondWire;odd.c.enabled=2;odd.reply(true);
        check(heard.isEmpty(),"an out-of-range switch reading: silence");
        Rig unchanged=manualOn(heard,first);int writes=unchanged.writes.size();unchanged.c.enabled=0;unchanged.controller.setManualStart(second,unchanged.results::add,heard::add);unchanged.reply(true);unchanged.c.wireStart=secondWire;unchanged.reply(true);
        check(unchanged.writes.subList(writes,unchanged.writes.size()).equals(java.util.Arrays.asList("clock","time")),"Volt never turns the schedule on for the rider");
        // A claim already out: retired by a reliable OFF, kept through what a reboot can look like.
        Rig posted=manualOn(heard,first);long start=first;posted.c.enabled=0;
        check(!posted.controller.startStillPlanned("owner-A",start),"a reliable OFF beside the saved time retires a posted claim");
        check(posted.controller.startStillPlanned("owner-B",start+1),"another scooter's OFF says nothing about this claim");
        Rig zero=manualOn(heard,first);zero.c.enabled=0;zero.c.wireStart=0L;check(zero.controller.startStillPlanned("owner-A",first),"reboot zeros are ambiguous: the claim stands");
        Rig absent=manualOn(heard,first);absent.c.enabled=0;absent.c.wireStart=null;check(absent.controller.startStillPlanned("owner-A",first),"a zero with no time reading is ambiguous: the claim stands");
        Rig blank=manualOn(heard,first);blank.c.enabled=null;check(blank.controller.startStillPlanned("owner-A",first),"no reading of the switch: the claim stands");
        heard.clear();Rig done=new Rig();done.controller.setDoneEnabled(true,done.results::add,heard::add);confirmWrites(done);start=heard.get(0);heard.clear();done.c.enabled=0;
        check(!done.controller.startStillPlanned("owner-A",start),"a reliable OFF retires a Charge done by claim too");
        // Charge done by rewrites ON with each edit, so that confirmation is the evidence.
        Rig edited=new Rig();edited.controller.setDoneEnabled(true,edited.results::add,heard::add);confirmWrites(edited);heard.clear();edited.c.enabled=null;edited.controller.setDoneTime(9,0,edited.results::add,heard::add);edited.reply(true);edited.c.wireStart=edited.sentTime/1000+edited.sentOffset;edited.reply(true);edited.reply(true);
        check(heard.size()==1,"a confirmed ON write is its own evidence");heard.clear();
        Rig contradicted=new Rig();contradicted.controller.setDoneEnabled(true,contradicted.results::add,heard::add);contradicted.reply(true);contradicted.c.wireStart=contradicted.sentTime/1000+contradicted.sentOffset;contradicted.reply(true);contradicted.c.enabled=0;contradicted.reply(true);
        check(heard.isEmpty()&&contradicted.results.get(0)==null,"a reliable OFF read right after the ON is not reassured over");
    }
    /** Whether a "safe to disconnect" notice still speaks for its plan, over every path that ends or replaces that plan. */
    private static void reassuranceRetires(){
        List<Long> heard=new ArrayList<>();
        Rig off=new Rig();off.controller.setDoneEnabled(true,off.results::add,heard::add);confirmWrites(off);long start=heard.get(0);
        check(off.controller.startStillPlanned("owner-A",start),"the notice holds while its plan stands");
        check(off.controller.startStillPlanned("owner-B",start+1),"another scooter's view cannot retire this notice");
        off.c.ready=false;check(off.controller.startStillPlanned("owner-A",start+1),"unknown while disconnected: the claim stands");off.c.ready=true;
        off.controller.setDoneEnabled(false,off.results::add);check(off.controller.startStillPlanned("owner-A",start),"OFF not confirmed yet");
        off.c.enabled=0;off.reply(true);check(!off.controller.startStillPlanned("owner-A",start),"a confirmed OFF retires the notice");
        Rig failedOff=new Rig();failedOff.arm();long kept=failedOff.sentTime;failedOff.controller.setDoneEnabled(false,failedOff.results::add);failedOff.reply(false);
        check(failedOff.controller.startStillPlanned("owner-A",kept),"a failed OFF leaves the plan and the notice");
        heard.clear();Rig edit=new Rig();edit.controller.setDoneEnabled(true,edit.results::add,heard::add);confirmWrites(edit);start=heard.get(0);
        edit.controller.setDoneTime(9,0,edit.results::add,heard::add);
        check(edit.controller.startStillPlanned("owner-A",start),"an edit in flight has changed nothing yet");
        confirmWrites(edit);long moved=heard.get(heard.size()-1);
        check(moved!=start&&!edit.controller.startStillPlanned("owner-A",start)&&edit.controller.startStillPlanned("owner-A",moved),"a confirmed edit retires the old start and holds the new one");
        heard.clear();Rig broken=new Rig();broken.controller.setDoneEnabled(true,broken.results::add,heard::add);confirmWrites(broken);start=heard.get(0);
        broken.controller.setDoneTime(9,0,broken.results::add,heard::add);broken.reply(true);broken.c.wireStart=broken.sentTime/1000+broken.sentOffset;broken.reply(true);broken.reply(false);
        check(!broken.controller.startStillPlanned("owner-A",start),"a time already rewritten retires the old claim even when the switch fails");
        heard.clear();Rig outside=new Rig();outside.controller.setDoneEnabled(true,outside.results::add,heard::add);confirmWrites(outside);start=heard.get(0);
        outside.c.wireStart=outside.sentTime/1000+outside.sentOffset+3600;check(!outside.controller.startStillPlanned("owner-A",start),"a time changed on the scooter retires the notice");
        heard.clear();Rig manual=new Rig();long first=time("2026-10-20T09:00:00Z");long second=time("2026-10-20T10:00:00Z");manual.c.wireStart=first/1000-6*3600;manual.controller.setManualEnabled(true,manual.results::add,heard::add);confirmWrites(manual);
        start=heard.get(0);check(manual.controller.startStillPlanned("owner-A",start),"the manual notice holds");
        manual.controller.setManualStart(second,manual.results::add,heard::add);manual.reply(true);manual.c.wireStart=second/1000-6*3600;manual.reply(true);
        check(!manual.controller.startStillPlanned("owner-A",start)&&manual.controller.startStillPlanned("owner-A",second),"a manual edit retires the old start");
        manual.controller.setManualEnabled(false,manual.results::add);manual.c.enabled=0;manual.reply(true);check(!manual.controller.startStillPlanned("owner-A",second),"turning manual off retires its notice");
    }
    private static void planner(){
        TimeZone boise=TimeZone.getTimeZone("America/Boise");long now=time("2026-10-20T05:00:00Z");
        ChargeScheduleController.Plan plan=ChargeScheduleController.plan(now,8*60,90,boise,false);
        check(plan.deadlineWallMs==time("2026-10-20T14:00:00Z"),"11 PM -> next 8 AM");
        check(plan.startWallMs==time("2026-10-20T12:10:00Z"),"90 minutes plus 20 minute buffer starts at 6:10");
        check(!plan.startNow&&plan.offsetSeconds==-6*3600,"fixed clock offset");
        ChargeScheduleController.Plan whole=ChargeScheduleController.plan(now,8*60,90,boise,true);
        check(whole.startWallMs==time("2026-10-20T12:00:00Z"),"whole-hour models round earlier");
        check(ChargeScheduleController.plan(time("2026-10-20T13:00:00Z"),8*60,90,boise,false).startNow,"insufficient time charges now");
        check(ChargeScheduleController.plan(now,8*60,90.01,boise,false).startWallMs==time("2026-10-20T12:09:00Z"),"fractional minutes round earlier");
        check(!ChargeScheduleController.plan(time("2026-10-20T12:09:00Z"),8*60,90,boise,false).startNow,"one minute before buffered start remains scheduled");
        check(ChargeScheduleController.plan(time("2026-10-20T12:10:00Z"),8*60,90,boise,false).startNow,"exact buffered start charges now");
        check(ChargeScheduleController.plan(time("2026-10-20T12:15:00Z"),8*60,90,boise,false).startNow,"inside added ten-minute reserve charges now rather than waiting");
        check(ChargeScheduleController.nextOccurrence(time("2026-10-20T14:00:00Z"),8*60,boise)==time("2026-10-21T14:00:00Z"),"exact same minute chooses tomorrow");
        ChargeScheduleController.Plan spring=ChargeScheduleController.plan(time("2027-03-14T06:00:00Z"),8*60,90,boise,false);
        check(spring.deadlineWallMs==time("2027-03-14T14:00:00Z"),"DST next day uses chosen localdeadline");
        check(spring.startWallMs==time("2027-03-14T12:10:00Z")&&spring.offsetSeconds==-7*3600,"DST keeps fixed scooter-clock offset, correct elapsed duration");
        check(ChargeScheduleController.nextOccurrence(time("2027-03-14T10:00:00Z"),150,boise)==time("2027-03-15T08:30:00Z"),"past spring gap does not shift tomorrow's chosen time");
        check(ChargeScheduleController.nextOccurrence(time("2027-03-14T08:00:00Z"),150,boise)==time("2027-03-14T09:30:00Z"),"today's nonexistent time advances through the gap");
        check(ChargeScheduleController.nextOccurrence(time("2026-11-01T07:00:00Z"),90,boise)==time("2026-11-01T07:30:00Z"),"fall overlap selects first future occurrence");
        check(ChargeScheduleController.nextOccurrence(time("2026-11-01T08:00:00Z"),90,boise)==time("2026-11-01T08:30:00Z"),"fall overlap retains second occurrence after first has passed");
        ChargeScheduleController.Plan fall=ChargeScheduleController.plan(time("2026-11-01T05:00:00Z"),8*60,90,boise,false);
        check(fall.deadlineWallMs==time("2026-11-01T15:00:00Z"),"fall deadline uses next local8AM");
        ChargeScheduleController.Plan halfDst=ChargeScheduleController.plan(time("2026-10-03T12:00:00Z"),8*60,90,TimeZone.getTimeZone("Australia/Lord_Howe"),true);
        check((halfDst.startWallMs/1000+halfDst.offsetSeconds)%3600==0,"half-hour DST respects the wire whole-hour domain");
        check(halfDst.deadlineWallMs-halfDst.startWallMs>=110*60_000,"rounding preserves the 90 minute estimate plus 20 minute reserve");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,3000}){
            boolean rejected=false;try{ChargeScheduleController.plan(now,480,bad,boise,false);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject bad duration");
        }
    }
    private static void transactions(){
        Rig r=new Rig();r.arm();check(r.controller.view().doneByEnabled&&!r.controller.view().manualEnabled,"done-by is distinct from manual");
        check(r.controller.view().startWallMs==r.sentTime,"display actual UTC start");check(!r.saved.isEmpty(),"confirmed intent persisted");
        r.controller.setManualEnabled(true,r.results::add);check(r.results.get(1).contains("Disable Charge done by"),"manual mutually exclusive");
        r.controller.setDoneEnabled(false,r.results::add);check(r.writes.get(3).equals("off"),"paused state can disable");r.reply(true);check(!r.controller.view().doneByEnabled,"disabled afterconfirmation");
        for(int failure=0;failure<3;failure++){
            Rig f=new Rig();f.enable();for(int step=0;step<failure;step++)f.reply(true);f.reply(false);
            check(!f.controller.view().doneByEnabled,"failure doesn't claim doneby");check(!f.controller.busy(),"failure releasestransaction");check(f.writes.size()==failure+1,"failure stops writes");check(f.results.size()==1&&f.results.get(0)!=null,"failure reportedonce");
        }
        Rig cross=new Rig();cross.enable();cross.c.scope="owner-B";cross.reply(true);check(cross.writes.size()==1,"profile switch aborts continuation");check(cross.results.get(0).contains("Connection changed"),"profile switch reported");
        Rig reconnect=new Rig();reconnect.enable();reconnect.c.generation++;reconnect.reply(true);check(reconnect.writes.size()==1,"new session aborts continuation");
        Rig zoneChange=new Rig();zoneChange.enable();zoneChange.c.zone=TimeZone.getTimeZone("UTC");zoneChange.reply(true);check(zoneChange.writes.size()==1,"timezonechangeabortspartialprogramming");
        Rig lostCallback=new Rig();lostCallback.enable();lostCallback.c.ready=false;lostCallback.controller.observe();
        check(!lostCallback.controller.busy()&&lostCallback.results.size()==1,"disconnect observation releases even a dropped BLE callback");
        lostCallback.reply(false);check(lostCallback.results.size()==1,"late failure callback is ignored after connection observation");
        Rig cancel=new Rig();cancel.enable();cancel.controller.cancel();cancel.reply(true);check(cancel.writes.size()==1&&cancel.results.isEmpty(),"maintenance cancel no late callback/write");
        Rig now=new Rig();now.c.now=time("2026-10-20T13:00:00Z");now.enable();check(now.writes.equals(java.util.Arrays.asList("off")),"too late sends only disable");now.reply(true);check(now.results.get(0).startsWith("Charging now"),"charge-now explained");check(!now.controller.view().doneByEnabled,"no tomorrow schedule claimed");
        Rig elapsed=new Rig();elapsed.enable();elapsed.reply(true);elapsed.c.now=elapsed.sentTime+1;elapsed.reply(true);
        check(elapsed.writes.get(2).equals("off"),"start passed during confirmation disables instead of arming expiredtimestamp");
        elapsed.reply(true);check(!elapsed.controller.view().doneByEnabled&&elapsed.results.get(0).startsWith("Charging now"),"late transaction reports chargingnow");
        Rig changedCap=new Rig();changedCap.enable();changedCap.reply(true);changedCap.c.target=85d;changedCap.reply(true);
        check(changedCap.writes.size()==2&&!changedCap.controller.view().doneByEnabled&&changedCap.results.get(0).startsWith("Charge limit changed"),"ceiling change before enable aborts stale estimate");
        Rig finalCap=new Rig();finalCap.enable();finalCap.reply(true);finalCap.reply(true);finalCap.c.target=85d;finalCap.reply(true);
        check(!finalCap.controller.view().doneByEnabled&&finalCap.controller.view().manualEnabled,"ceiling change after enable preserves actual schedule without false deadline");
        Rig cap=new Rig();cap.c.target=85d;cap.c.estimateMinutes=20d;cap.arm();check(cap.sentTime==time("2026-10-20T13:20:00Z"),"uses ETA to actual 85 percent cap plus 20 minute reserve");
        Rig manual=new Rig();manual.controller.setManualStart(time("2026-10-20T12:00:00Z"),manual.results::add);manual.reply(true);manual.reply(true);
        check(manual.writes.size()==2&&!manual.controller.view().manualEnabled,"time edit does not enable");
        manual.controller.setManualEnabled(true,manual.results::add);manual.reply(true);manual.reply(true);manual.reply(true);check(manual.controller.view().manualEnabled,"manual active persisted");
        manual.c.enabled=0;manual.recreate();check(manual.controller.view().manualEnabled,"manual reboot zero doesn't hideownedon");
        manual.controller.setManualEnabled(false,manual.results::add);check(manual.writes.get(manual.writes.size()-1).equals("off"),"disable sends even when wirealreadyzero");manual.reply(true);check(!manual.controller.view().manualEnabled,"manual disable clearsownedstate");
    }
    private static void reconciliation(){
        Rig r=new Rig();r.arm();int commands=r.writes.size();r.c.ready=false;r.advance(3600_000);r.controller.observe();check(r.controller.view().doneByEnabled,"disconnectretain");
        r.c.ready=true;r.c.generation++;r.c.enabled=0;r.c.wireStart=0L;r.recreate();check(r.controller.view().doneByEnabled,"missing time/rebootzero retained");check(r.writes.size()==commands,"reconnectneverrearms");
        r.c.charging=1;r.advance(1);r.controller.observe();check(r.controller.view().doneByEnabled,"one early sample insufficient");r.advance(4000);r.controller.observe();check(r.controller.view().doneByEnabled,"earlycharging mustpersist");r.advance(1000);r.controller.observe();check(!r.controller.view().doneByEnabled,"sustainedearlychargingretires");
        Rig settle=new Rig();settle.arm();settle.c.charging=1;settle.advance(2000);settle.controller.observe();settle.advance(10_000);settle.controller.observe();check(settle.controller.view().doneByEnabled,"commandsettlinggrace");
        Rig ended=new Rig();ended.arm();ended.c.soc=100d;ended.controller.observe();check(!ended.controller.view().doneByEnabled&&!ended.controller.view().manualEnabled,"completed targetclearsboth despitewire1");
        Rig capped=new Rig();capped.c.target=85d;capped.arm();capped.c.soc=85d;capped.controller.observe();check(!capped.controller.view().doneByEnabled,"lowercaptargetretires");
        Rig past=new Rig();past.arm();past.c.now=past.sentTime+120_000;past.c.charging=0;past.controller.observe();check(!past.controller.view().doneByEnabled,"nonchargingafterstartretires");
        Rig started=new Rig();started.arm();started.c.now=started.sentTime+120_000;started.c.charging=1;started.controller.observe();check(started.controller.view().doneByEnabled,"chargingatscheduledstartkeepsdeadlineuntilcomplete");
        Rig external=new Rig();external.arm();external.c.wireStart+=3600;external.controller.observe();check(!external.controller.view().doneByEnabled&&external.controller.view().manualEnabled,"positive changedtimefollowsdevice");
        Rig stale=new Rig();stale.arm();stale.c.charging=null;stale.c.soc=null;stale.c.ready=false;stale.advance(2*24*3600_000L);stale.controller.observe();check(stale.controller.view().doneByEnabled,"old cachedvaluesneverretire");
        Rig newCap=new Rig();newCap.c.target=85d;newCap.arm();newCap.c.target=100d;newCap.c.soc=85d;newCap.controller.observe();check(!newCap.controller.view().doneByEnabled&&newCap.controller.view().manualEnabled,"chargeceilingeditpreservesstartbutretiresobsoleteestimate");
        Rig dst=new Rig();dst.c.now=time("2026-11-01T05:00:00Z");dst.arm();long changedStart=time("2026-11-01T16:00:00Z");
        dst.c.wireStart=changedStart/1000+dst.sentOffset;dst.controller.observe();check(dst.controller.view().startWallMs==changedStart,"changed wire time keeps original RTC offset across DST");
        Rig unownedDst=new Rig();unownedDst.c.now=time("2026-11-01T05:00:00Z");unownedDst.c.wireStart=changedStart/1000-6*3600;
        check(unownedDst.controller.view().startWallMs==changedStart,"unowned readback uses current RTC offset not pseudo UTC offset");
        Rig unknown=new Rig();unknown.c.enabled=1;unknown.c.wireStart=time("2026-10-20T12:00:00Z")/1000-6*3600;check(unknown.controller.view().manualEnabled,"unownedwireonstilldisplayed");check(unknown.saved.isEmpty(),"readbackaloneisnotownedintent");
    }
    private static void gating(){
        Rig r=new Rig();r.c.charging=0;r.enable();check(r.results.get(0).equals("Connect a charger first")&&r.writes.isEmpty(),"unpluggedtapmessage");
        r.controller.setDoneTime(7,30,r.results::add);check(r.results.get(1)==null&&r.controller.view().doneByMinuteOfDay==450&&r.writes.isEmpty(),"unplugged time preference edit allowed without commands");
        r.c.charging=1;r.c.estimateMinutes=null;r.enable();check(r.results.get(2).equals("Wait for a charging time estimate")&&r.writes.isEmpty(),"waitforETA");
        r.c.target=null;r.c.estimateMinutes=90d;r.enable();check(r.writes.isEmpty(),"unknowncapblocksestimate");
        r.c.target=100d;r.c.toggleReason="Stop the scooter first";r.enable();check(r.results.get(4).equals("Stop the scooter first"),"mutationgate surfaced");
        Rig ready=new Rig();ready.c.ready=false;ready.enable();check(ready.results.get(0).equals("Connect a charger first"),"disconnectednoenable");
        Rig editing=new Rig();editing.controller.setDoneTime(7,30,editing.results::add);check(editing.controller.view().doneByMinuteOfDay==450&&editing.writes.isEmpty(),"preferredtimeeditdoesnotarm");
        editing.enable();editing.controller.setManualStart(editing.c.now+3600_000,editing.results::add);check(editing.results.get(1).contains("Wait"),"busyblocksconcurrentrequest");
        Rig noTime=new Rig();noTime.c.timeReason="Time setting unavailable";
        check(noTime.controller.view().manualBlockedReason==null&&noTime.controller.view().manualTimeBlockedReason.equals("Time setting unavailable"),"time gate independent from switch gate");
        noTime.controller.setManualStart(noTime.c.now+3600_000,noTime.results::add);check(noTime.writes.isEmpty()&&noTime.results.get(0).equals("Time setting unavailable"),"time refusal before clock write");
        Rig invalid=new Rig();invalid.controller.setDoneTime(30,0,invalid.results::add);check(invalid.results.get(0).contains("valid"),"invalidpickerrejected");
    }
    private static void startupEstimateGuard(){
        String reason="Wait for charger detection or select a charger";
        Rig pending=new Rig();pending.c.estimateUnavailableReason=reason;
        check(reason.equals(pending.controller.view().doneByBlockedReason),"Startup estimate reason is visible despite a usable raw-rate ETA");
        pending.enable();
        check(pending.writes.isEmpty()&&pending.results.size()==1&&reason.equals(pending.results.get(0)),"Unknown automatic family cannot write a done-by schedule");
        pending.controller.setDoneTime(9,15,pending.results::add);
        check(pending.results.get(1)==null&&pending.controller.view().doneByMinuteOfDay==555&&pending.writes.isEmpty(),"Startup guard still permits choosing a finish-time preference");
        check(pending.controller.view().manualBlockedReason==null&&pending.controller.view().manualTimeBlockedReason==null,"Startup guard does not block manual controls");
        pending.c.ready=false;pending.controller.observe();pending.c.ready=true;pending.c.generation++;pending.recreate();pending.controller.observe();
        check(pending.writes.isEmpty(),"Blocked startup and reconnect never arm a schedule automatically");
        pending.c.estimateUnavailableReason=null;pending.controller.observe();
        check(pending.writes.isEmpty(),"Detection becoming ready does not retry a refused schedule");
        pending.results.clear();pending.arm();
        check(pending.controller.view().doneByEnabled,"Explicit activation proceeds once the charger family is available");

        Rig manual=new Rig();manual.c.estimateUnavailableReason=reason;
        manual.controller.setManualStart(manual.c.now+3600_000,manual.results::add);manual.reply(true);manual.reply(true);
        manual.controller.setManualEnabled(true,manual.results::add);manual.reply(true);manual.reply(true);manual.reply(true);
        check(manual.controller.view().manualEnabled,"Manual start and enable remain usable during charger identification");
        manual.controller.setManualEnabled(false,manual.results::add);manual.reply(true);
        check(!manual.controller.view().manualEnabled,"Manual cancellation remains usable during charger identification");

        Rig active=new Rig();active.arm();long deadline=active.controller.view().doneByWallMs;
        active.c.charging=1;active.c.estimateUnavailableReason=reason;
        active.controller.setDoneTime(9,0,active.results::add);
        check(active.writes.size()==3&&reason.equals(active.results.get(1))&&active.controller.view().doneByWallMs==deadline,
                "Active charging update refuses an unidentified fresh estimate without changing the existing plan");
        active.controller.setDoneEnabled(false,active.results::add);
        check(active.writes.size()==4&&active.writes.get(3).equals("off"),"Estimate guard does not prevent done-by cancellation");
        active.reply(true);check(!active.controller.view().doneByEnabled,"Done-by cancellation completes during identification");

        Rig paused=new Rig();paused.arm();paused.c.estimateUnavailableReason=reason;paused.c.estimateMinutes=null;
        paused.controller.setDoneTime(9,30,paused.results::add);paused.reply(true);
        check(paused.sentTime==time("2026-10-20T13:40:00Z"),"Paused explicit edit still reuses the verified saved duration");
        paused.c.wireStart=paused.sentTime/1000+paused.sentOffset;paused.reply(true);paused.reply(true);
        check(paused.results.get(1)==null&&paused.controller.view().doneByEnabled,"Paused edit completes without requiring charger identification");
    }
    private static void persistence(){
        Rig r=new Rig();r.arm();String encoded=r.saved.get("owner-A");ChargeScheduleController.State decoded=ChargeScheduleController.decode(encoded);
        check(decoded.mode==2&&decoded.minute==480&&decoded.target==100&&decoded.start==r.sentTime,"persistallownedfields");
        // A schedule saved by the ten-minute-buffer release is already on the scooter.
        // Recreating the controller must preserve that start until an explicit edit.
        decoded.start+=10*60_000L;decoded.wire+=600;decoded.calculatedWire+=600;
        r.saved.put("owner-A",ChargeScheduleController.encode(decoded));r.c.wireStart=decoded.wire;
        int writes=r.writes.size();r.recreate();r.controller.observe();
        check(r.controller.view().doneByEnabled&&r.controller.view().startWallMs==decoded.start,"existing ten-minute-buffer schedule survives upgrade without moving its start");
        check(r.writes.size()==writes,"upgrade does not reprogram a saved schedule");
        Rig cautious=new Rig();cautious.c.estimateMinutes=130d;cautious.arm();
        long oldStart=cautious.sentTime;int oldWrites=cautious.writes.size();
        cautious.c.estimateMinutes=90d;cautious.recreate();cautious.controller.observe();
        check(cautious.controller.view().startWallMs==oldStart&&cautious.writes.size()==oldWrites,
                "A previously programmed cautious plan is not silently rescheduled by an estimator upgrade");
        for(String bad:new String[]{"", "2|2|480|0|0|0|0|100|90|false", "1|2|480|0|0|0|0|100|90|false", "1|2|480|1|2|3|4|NaN|90|false", "1|2|1500|1|2|3|4|100|90|false", "garbage"})
            check(ChargeScheduleController.decode(bad).mode==0,"malformedrecordcannotarm");
        r.c.scope="owner-B";check(!r.controller.view().doneByEnabled,"profilesisolated");r.c.scope="simulation:owner-A";check(!r.controller.view().doneByEnabled,"simulationisolated");
    }
    private static void preferences(){
        Rig offline=new Rig();offline.c.ready=false;offline.c.charging=null;offline.c.timeReason="Unsupported model";offline.c.toggleReason="Connect first";
        check(offline.controller.view().timeBlockedReason==null,"local preference ignores transport/capability mutation gates");
        offline.controller.setDoneTime(9,45,offline.results::add);offline.recreate();
        check(offline.results.get(0)==null&&offline.controller.view().doneByMinuteOfDay==585&&offline.writes.isEmpty(),"offline preference survives recreation without BLE");
        offline.c.scope="owner-B";check(offline.controller.view().doneByMinuteOfDay==480,"offline preference isolated per scooter");
        offline.controller.setDoneTime(10,15,offline.results::add);offline.c.scope="owner-A";
        check(offline.controller.view().doneByMinuteOfDay==585,"other scooter preference cannot change original");
        offline.c.scope="";offline.controller.setDoneTime(9,0,offline.results::add);
        check(offline.results.get(2).equals("Choose a scooter first")&&!offline.saved.containsKey(""),"missing profile cannot store global preference");
        Rig active=new Rig();active.arm();long start=active.controller.view().startWallMs,deadline=active.controller.view().doneByWallMs;int commands=active.writes.size();
        active.c.ready=false;
        active.controller.setDoneTime(9,30,active.results::add);
        check(active.results.get(1)==null&&active.controller.view().doneByMinuteOfDay==570,"disconnected active plan allows preferred deadline edit");
        check(active.writes.size()==commands&&active.controller.view().startWallMs==start&&active.controller.view().doneByWallMs==deadline,"offline active preference edit does not reprogram or mislabel existing plan");
        active.c.ready=false;active.controller.setDoneTime(10,30,active.results::add);active.recreate();
        check(active.controller.view().doneByEnabled&&active.controller.view().doneByWallMs==deadline&&active.controller.view().doneByMinuteOfDay==630,"active disconnected preference preserves plan through restart");
        active.c.ready=true;active.controller.setDoneEnabled(false,active.results::add);active.c.enabled=0;active.reply(true);
        active.c.charging=1;active.enable();active.reply(true);active.c.wireStart=active.sentTime/1000+active.sentOffset;active.reply(true);active.c.enabled=1;active.c.charging=0;active.reply(true);
        check(active.controller.view().doneByWallMs==time("2026-10-20T16:30:00Z"),"next enable uses edited preference");
        Rig captured=new Rig();long manual=time("2026-10-20T11:00:00Z");captured.c.wireStart=manual/1000-6*3600;
        check(captured.controller.view().manualStartWallMs==manual,"unowned native start is available as a manual preference");
        captured.arm();check(captured.controller.view().manualStartWallMs==manual&&captured.controller.view().startWallMs!=manual,"first done-by overwrite captures preexisting native time");
        check(captured.controller.view().manualTimeBlockedReason.equals("Disable Charge done by first"),"manual time stays blocked while done-by active");
        Rig retryManual=new Rig();retryManual.c.wireStart=manual/1000-6*3600;retryManual.controller.setManualEnabled(true,retryManual.results::add);
        retryManual.reply(true);retryManual.reply(true);retryManual.reply(false);
        check(retryManual.controller.view().manualStartWallMs==manual&&!retryManual.controller.view().manualEnabled,"failed manual enable preserves chosen native preference for retry");
        retryManual.controller.setManualEnabled(true,retryManual.results::add);check(retryManual.writes.get(3).equals("clock"),"manual enable retry doesn't require choosing the same time again");
        Rig busy=new Rig();busy.enable();busy.controller.setDoneTime(9,0,busy.results::add);
        check(busy.results.get(0).contains("Wait")&&busy.controller.view().doneByMinuteOfDay==480,"in-flight activation cannot race local preference save");
        String v1="1|2|480|"+start+"|"+(start/1000-6*3600)+"|"+deadline+"|"+time("2026-10-20T05:00:00Z")+"|100.0|90.0|false";
        Rig legacy=new Rig();legacy.saved.put("owner-A",v1);legacy.recreate();legacy.c.charging=0;
        check(legacy.controller.view().doneByEnabled&&legacy.controller.view().manualStartWallMs==0,"v1 active done-by cannot fabricate lost manual preference");
        legacy.controller.setDoneEnabled(false,legacy.results::add);legacy.reply(true);
        check(legacy.writes.equals(java.util.Arrays.asList("off"))&&legacy.controller.view().manualStartWallMs==0,"v1 cancellation without manual preference only disables");
        String manualV1="1|1|480|"+manual+"|"+(manual/1000-6*3600)+"|"+(manual+86400000L)+"|"+time("2026-10-20T05:00:00Z")+"|100.0|0.0|false";
        check(ChargeScheduleController.decode(manualV1).manualStart==manual,"v1 genuine manual start migrates");
        check(ChargeScheduleController.decode(manualV1.replace("|0.0|false","|90.0|false")).manualStart==0,"v1 downgraded calculated schedule not mistaken for prior manual preference");
        ChargeScheduleController.State migrated=ChargeScheduleController.decode(manualV1);
        check(ChargeScheduleController.encode(migrated).startsWith("5|")&&ChargeScheduleController.decode(ChargeScheduleController.encode(migrated)).manualStart==manual,"new schema roundtrips migrated preference");
        check(ChargeScheduleController.decode(ChargeScheduleController.encode(migrated).replace("|"+manual,"|-1")).mode==0,"invalid v3 timestamp rejected");
        String v2=legacy(ChargeScheduleController.encode(migrated),11,"2");
        check(ChargeScheduleController.decode(v2).manualStart==manual&&ChargeScheduleController.decode(v2).calculatedWire==0,"v2 manual preference remains readable");
    }
    private static Rig armedWithManual(){
        Rig r=new Rig();long manual=time("2026-10-20T11:00:00Z");
        r.controller.setManualStart(manual,r.results::add);r.reply(true);r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);
        check(r.controller.view().manualStartWallMs==manual,"confirmed manual edit saves separate preference");r.writes.clear();r.results.clear();r.arm();return r;
    }
    private static void restoration(){
        Rig success=armedWithManual();long preferred=success.controller.view().manualStartWallMs;int before=success.writes.size();
        success.controller.setDoneEnabled(false,success.results::add);check(success.writes.get(before).equals("off"),"restore starts with OFF");
        success.c.enabled=0;success.reply(true);
        check(!success.controller.view().doneByEnabled&&!success.controller.view().manualEnabled&&success.controller.busy(),"confirmed OFF reflected before restoration completes");
        check(success.writes.get(before+1).equals("clock"),"clock restored only after OFF acknowledgement");success.reply(true);
        check(success.writes.get(before+2).equals("time")&&success.sentTime==preferred,"restores user's manual time instead of calculated start");
        success.c.wireStart=success.sentTime/1000+success.sentOffset;success.reply(true);
        check(success.results.get(1)==null&&!success.controller.busy()&&!success.controller.view().manualEnabled,"restoration succeeds without enabling manual schedule");
        check(success.controller.view().startWallMs==preferred&&success.controller.view().manualStartWallMs==preferred,"both actual and preferred time match after restore");
        check(success.writes.subList(before,success.writes.size()).equals(java.util.Arrays.asList("off","clock","time")),"restoration never emits ON");
        success.recreate();check(success.controller.view().manualStartWallMs==preferred&&!success.controller.view().manualEnabled,"restored preference/off survive recreation");
        Rig stale=armedWithManual();stale.c.now=time("2026-10-20T11:30:00Z");stale.controller.setDoneEnabled(false,stale.results::add);stale.c.enabled=0;stale.reply(true);stale.reply(true);
        check(stale.sentTime==time("2026-10-21T11:00:00Z"),"stale manual time advances to next chosen local occurrence");stale.c.wireStart=stale.sentTime/1000+stale.sentOffset;stale.reply(true);
        check(stale.controller.view().manualStartWallMs==stale.sentTime,"rolled manual time persisted");
        for(int failure=0;failure<3;failure++){
            Rig failed=armedWithManual();failed.controller.setDoneEnabled(false,failed.results::add);
            for(int step=0;step<failure;step++){if(step==0)failed.c.enabled=0;failed.reply(true);}failed.reply(false);
            check(failed.writes.size()==4+failure&&!failed.controller.busy(),"restoration failure halts subsequent commands");
            check(failed.results.size()==2&&failed.results.get(1)!=null,"restoration failure reported exactly once");
            check(failure==0?failed.controller.view().doneByEnabled:!failed.controller.view().doneByEnabled&&!failed.controller.view().manualEnabled,"confirmed OFF survives partial restoration failure");
            if(failure>0)check(failed.results.get(1).startsWith("Charge done by is off;"),"partial failure explicitly distinguishes cancellation from restoration");
            int writes=failed.writes.size();failed.recreate();failed.controller.observe();check(failed.writes.size()==writes,"failed restore never retries automatically");
            check(failed.controller.view().manualStartWallMs==time("2026-10-20T11:00:00Z"),"failure cannot destroy prior manual preference");
        }
        Rig switched=armedWithManual();switched.controller.setDoneEnabled(false,switched.results::add);switched.c.enabled=0;switched.reply(true);switched.c.scope="owner-B";switched.reply(true);
        check(switched.writes.size()==5&&switched.results.get(1).contains("Connection changed"),"profile switch after OFF prevents time restore on other scooter");
        switched.c.wireStart=null;check(switched.controller.view().manualStartWallMs==0,"other profile receives no manual preference");switched.c.scope="owner-A";switched.recreate();
        check(!switched.controller.view().doneByEnabled&&!switched.controller.view().manualEnabled,"original profile retains acknowledged OFF after switched context");
        Rig reconnect=armedWithManual();reconnect.controller.setDoneEnabled(false,reconnect.results::add);reconnect.c.enabled=0;reconnect.reply(true);reconnect.c.generation++;reconnect.reply(true);
        check(reconnect.writes.size()==5&&reconnect.results.get(1).contains("Connection changed"),"new session prevents pending time restoration");
        Rig dropped=armedWithManual();dropped.controller.setDoneEnabled(false,dropped.results::add);dropped.c.enabled=0;dropped.reply(true);dropped.c.ready=false;dropped.controller.observe();dropped.reply(false);
        check(dropped.results.size()==2&&!dropped.controller.busy()&&!dropped.controller.view().doneByEnabled,"lost restoration callback/disconnect completes only once and preserves OFF");
        Rig cancel=armedWithManual();cancel.controller.setDoneEnabled(false,cancel.results::add);cancel.c.enabled=0;cancel.reply(true);cancel.controller.cancel();cancel.reply(true);
        check(cancel.writes.size()==5&&cancel.results.size()==1&&!cancel.controller.busy()&&!cancel.controller.view().doneByEnabled,"maintenance cancellation cannot restore or rearm later");
        Rig unsupported=armedWithManual();unsupported.c.timeReason="Time setting unavailable";unsupported.controller.setDoneEnabled(false,unsupported.results::add);unsupported.c.enabled=0;unsupported.reply(true);
        check(unsupported.writes.size()==4&&!unsupported.controller.view().doneByEnabled&&unsupported.results.get(1).startsWith("Charge done by is off;"),"time gate still permits cancellation and honestly reports missing restore");
        Rig priorOn=armedWithManual();priorOn.controller.setDoneEnabled(false,priorOn.results::add);priorOn.c.enabled=0;priorOn.reply(true);priorOn.reply(true);priorOn.c.wireStart=priorOn.sentTime/1000+priorOn.sentOffset;priorOn.reply(true);
        priorOn.controller.setManualEnabled(true,priorOn.results::add);priorOn.reply(true);priorOn.reply(true);priorOn.c.enabled=1;priorOn.reply(true);
        check(priorOn.controller.view().manualEnabled,"manual schedule initially active");priorOn.writes.clear();priorOn.results.clear();priorOn.c.charging=1;priorOn.arm();
        priorOn.controller.setDoneEnabled(false,priorOn.results::add);priorOn.c.enabled=0;priorOn.reply(true);priorOn.reply(true);priorOn.c.wireStart=priorOn.sentTime/1000+priorOn.sentOffset;priorOn.reply(true);
        check(!priorOn.controller.view().manualEnabled&&priorOn.writes.equals(java.util.Arrays.asList("clock","time","on","off","clock","time")),"restoring previously active manual time still never re-enables schedule");
    }
    private static Rig withManualOnly(){
        Rig r=new Rig();long manual=time("2026-10-20T11:00:00Z");r.controller.setManualStart(manual,r.results::add);r.reply(true);
        r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);r.writes.clear();r.results.clear();return r;
    }
    private static void externalManualEdits(){
        Rig external=withManualOnly();long edited=time("2026-10-20T12:00:00Z");external.c.wireStart=edited/1000+external.sentOffset;external.controller.observe();
        check(external.controller.view().startWallMs==edited&&external.controller.view().manualStartWallMs==edited,"positive native edit updates genuine manual preference");
        external.recreate();check(external.controller.view().manualStartWallMs==edited,"external manual edit persists");
        external.controller.setManualEnabled(true,external.results::add);external.reply(true);
        check(external.sentTime==edited,"manual enable preserves external choice rather than overwriting with old time");external.reply(true);external.reply(true);
        Rig externalDone=withManualOnly();externalDone.c.wireStart=edited/1000+externalDone.sentOffset;externalDone.controller.observe();externalDone.arm();
        check(externalDone.controller.view().manualStartWallMs==edited,"done-by retains most recent external manual choice");
        externalDone.controller.setDoneEnabled(false,externalDone.results::add);externalDone.c.enabled=0;externalDone.reply(true);externalDone.reply(true);
        check(externalDone.sentTime==edited,"done-by cancellation restores external manual choice");externalDone.c.wireStart=externalDone.sentTime/1000+externalDone.sentOffset;externalDone.reply(true);
        long later=edited+3600_000;externalDone.c.wireStart=later/1000+externalDone.sentOffset;externalDone.controller.observe();
        check(externalDone.controller.view().manualStartWallMs==later,"successful restoration returns to ordinary external manual reconciliation");
        Rig late=withManualOnly();long preferred=late.controller.view().manualStartWallMs;late.enable();late.reply(true);long calculated=late.sentTime,wire=late.sentTime/1000+late.sentOffset;
        check(ChargeScheduleController.decode(late.saved.get("owner-A")).calculatedWire==wire,"attempted calculated write is journaled before its confirmation");late.reply(false);late.recreate();late.c.wireStart=wire;late.controller.observe();
        check(late.controller.view().startWallMs==calculated&&late.controller.view().manualStartWallMs==preferred,"late readback after failed calculated ACK and restart does not become manual preference");
        Rig dropped=withManualOnly();preferred=dropped.controller.view().manualStartWallMs;dropped.enable();dropped.reply(true);wire=dropped.sentTime/1000+dropped.sentOffset;
        dropped.c.ready=false;dropped.controller.observe();dropped.recreate();dropped.c.ready=true;dropped.c.generation++;dropped.c.wireStart=wire;dropped.controller.observe();dropped.reply(true);
        check(dropped.controller.view().manualStartWallMs==preferred,"disconnect/restart during calculated write cannot corrupt manual preference from delayed readback");
        Rig active=armedWithManual();preferred=active.controller.view().manualStartWallMs;active.c.wireStart+=1800;active.controller.observe();
        check(active.controller.view().manualStartWallMs==preferred,"changed native time during owned done-by never replaces separate manual preference");
        Rig collision=new Rig();long sameCalculated=time("2026-10-20T12:10:00Z");collision.controller.setManualStart(sameCalculated,collision.results::add);collision.reply(true);collision.c.wireStart=collision.sentTime/1000+collision.sentOffset;collision.reply(true);
        collision.writes.clear();collision.results.clear();collision.arm();collision.c.soc=100d;collision.controller.observe();collision.c.wireStart+=1800;collision.controller.observe();
        check(collision.controller.view().manualStartWallMs==sameCalculated,"retired calculated start matching manual by coincidence does not establish manual context");
        Rig clockFailure=withManualOnly();clockFailure.enable();clockFailure.reply(false);clockFailure.c.wireStart=edited/1000+clockFailure.sentOffset;clockFailure.controller.observe();
        check(clockFailure.controller.view().manualStartWallMs==edited,"clock failure before calculated write does not suppress genuine external manual edits");
        Rig empty=new Rig();empty.enable();empty.reply(true);long firstCalculated=empty.sentTime,firstWire=empty.sentTime/1000+empty.sentOffset;empty.reply(false);
        empty.c.wireStart=firstWire;empty.recreate();
        for(int observation=0;observation<3;observation++){empty.controller.observe();check(empty.controller.view().startWallMs==firstCalculated&&empty.controller.view().manualStartWallMs==0,"empty preference never infers failed calculated readback as manual time");}
        empty.controller.setManualEnabled(true,empty.results::add);
        check(empty.writes.size()==2&&empty.results.get(1).equals("Choose Charge start first"),"unknown manual time remains unset after failed calculated write");
        empty.controller.setDoneTime(9,0,empty.results::add);empty.enable();empty.reply(true);long secondWire=empty.sentTime/1000+empty.sentOffset;empty.reply(false);
        check(secondWire!=firstWire&&empty.controller.view().manualStartWallMs==0,"earlier calculated readback is not inferred after another failed attempt replaces the marker");
        empty.recreate();empty.c.wireStart=secondWire;
        check(empty.controller.view().manualStartWallMs==0,"latest calculated readback also remains unknown after repeated attempts and restart");
        empty.enable();empty.reply(true);empty.c.wireStart=empty.sentTime/1000+empty.sentOffset;empty.reply(true);empty.c.enabled=1;empty.c.charging=0;empty.reply(true);
        check(empty.controller.view().doneByEnabled&&empty.controller.view().manualStartWallMs==0,"later successful done-by activation cannot persist a synthetic manual preference");
        int beforeOff=empty.writes.size();empty.controller.setDoneEnabled(false,empty.results::add);empty.c.enabled=0;empty.reply(true);
        check(empty.writes.size()==beforeOff+1&&empty.writes.get(beforeOff).equals("off"),"cancelling after unknown manual history sends OFF without restoring a calculated time");
        Rig emptyDropped=new Rig();emptyDropped.enable();emptyDropped.reply(true);long droppedWire=emptyDropped.sentTime/1000+emptyDropped.sentOffset;emptyDropped.c.ready=false;emptyDropped.controller.observe();emptyDropped.recreate();
        emptyDropped.c.ready=true;emptyDropped.c.generation++;emptyDropped.c.wireStart=droppedWire;emptyDropped.controller.observe();emptyDropped.reply(true);
        check(emptyDropped.controller.view().manualStartWallMs==0,"disconnect and lost callback with no prior manual preference cannot seed it from a calculated readback");
        String[] invalid=late.saved.get("owner-A").split("\\|",-1);invalid[11]="-1";
        check(ChargeScheduleController.decode(String.join("|",invalid)).mode==0,"invalid calculated wire marker rejected");
    }
    private static void connectedPlanEdits(){
        Rig paused=armedWithManual();long manual=paused.controller.view().manualStartWallMs;long oldDeadline=paused.controller.view().doneByWallMs;
        paused.controller.setDoneTime(9,30,paused.results::add);
        check(paused.writes.get(3).equals("clock")&&paused.controller.view().doneByMinuteOfDay==480,"active connected edit starts confirmation without prematurely saving new deadline");
        paused.reply(true);check(paused.sentTime==time("2026-10-20T13:40:00Z"),"paused plan reuses verified 90 minute estimate plus buffer");
        paused.c.wireStart=paused.sentTime/1000+paused.sentOffset;paused.reply(true);paused.reply(true);
        check(paused.results.get(1)==null&&paused.controller.view().doneByMinuteOfDay==570&&paused.controller.view().doneByWallMs==time("2026-10-20T15:30:00Z"),"connected active edit applies and displays confirmed new deadline");
        check(paused.controller.view().manualStartWallMs==manual,"active update preserves separate manual preference");
        paused.recreate();paused.controller.setDoneTime(10,0,paused.results::add);paused.reply(true);
        check(paused.sentTime==time("2026-10-20T14:10:00Z"),"captured ETA and SOC survive restart for explicit paused edit");paused.c.wireStart=paused.sentTime/1000+paused.sentOffset;paused.reply(true);paused.reply(true);
        Rig live=armedWithManual();live.c.charging=1;live.c.soc=80d;live.c.estimateMinutes=60d;live.controller.setDoneTime(10,0,live.results::add);live.reply(true);
        check(live.sentTime==time("2026-10-20T14:40:00Z"),"active charging uses fresh live estimate rather than older capture");live.c.wireStart=live.sentTime/1000+live.sentOffset;live.reply(true);live.reply(true);
        check(ChargeScheduleController.decode(live.saved.get("owner-A")).capturedSoc==80,"fresh update captures current SOC for next paused edit");
        Rig offline=armedWithManual();offline.c.ready=false;offline.controller.setDoneTime(11,0,offline.results::add);int commands=offline.writes.size();offline.c.ready=true;offline.c.generation++;offline.controller.observe();
        check(offline.writes.size()==commands&&offline.controller.view().doneByWallMs==oldDeadline&&offline.controller.view().doneByMinuteOfDay==660,"reconnect does not auto-apply offline preference to active plan");
        for(int unavailable=0;unavailable<4;unavailable++){
            Rig missing=armedWithManual();if(unavailable==0)missing.c.soc=null;if(unavailable==1)missing.c.soc=78d;if(unavailable==2)missing.c.charging=null;if(unavailable==3)missing.c.now=missing.sentTime+1000;
            missing.controller.setDoneTime(9,0,missing.results::add);
            check(missing.writes.size()==3&&missing.results.get(1).contains("fresh charging estimate"),"unverified paused state refuses captured ETA reuse");
            check(missing.controller.view().doneByMinuteOfDay==480&&missing.controller.view().doneByWallMs==oldDeadline,"refused update preserves original deadline and preference");
        }
        Rig legacy=armedWithManual();String old=legacy(legacy.saved.get("owner-A"),11,"2");
        legacy.saved.put("owner-A",old);legacy.recreate();legacy.controller.setDoneTime(9,0,legacy.results::add);
        check(legacy.writes.size()==3&&legacy.results.get(1).contains("fresh charging estimate"),"legacy active plan cannot invent missing captured SOC");
        for(int failure=0;failure<3;failure++){
            Rig failed=armedWithManual();failed.controller.setDoneTime(9,0,failed.results::add);for(int step=0;step<failure;step++){if(step==1)failed.c.wireStart=failed.sentTime/1000+failed.sentOffset;failed.reply(true);}failed.reply(false);
            check(failed.results.size()==2&&failed.results.get(1)!=null&&!failed.controller.busy(),"active update failure completes once");
            check(failed.controller.view().doneByMinuteOfDay==480&&failed.controller.view().manualStartWallMs==manual,"failed active update never saves unconfirmed new preference or overwrites manual preference");
            if(failure<2)check(failed.controller.view().doneByEnabled&&failed.controller.view().doneByWallMs==oldDeadline,"pre-time-confirmation failure retains old plan");
            else check(!failed.controller.view().doneByEnabled&&failed.results.get(1).contains("not confirmed"),"partial native time change is not falsely called an armed finish deadline");
        }
        for(int changed=0;changed<3;changed++){
            Rig race=armedWithManual();race.controller.setDoneTime(9,0,race.results::add);
            for(int step=0;step<changed;step++){if(step==1)race.c.wireStart=race.sentTime/1000+race.sentOffset;race.reply(true);}race.c.soc=80d;
            if(changed==1)race.c.wireStart=race.sentTime/1000+race.sentOffset;race.reply(true);
            check(race.results.size()==2&&race.results.get(1)!=null&&!race.controller.busy(),"SOC change during paused transaction prevents stale finish claim");
            check(race.controller.view().doneByMinuteOfDay==480&&race.controller.view().manualStartWallMs==manual,"SOC race preserves user preferences");
        }
        Rig late=armedWithManual();late.c.now=time("2026-10-20T11:00:00Z");late.controller.setDoneTime(6,0,late.results::add);
        check(late.writes.get(3).equals("off"),"updated deadline with insufficient time starts charging now");late.c.enabled=0;late.reply(true);
        check(!late.controller.view().doneByEnabled&&late.controller.view().doneByMinuteOfDay==360&&late.results.get(1).startsWith("Charging now"),"charge-now update saves new preference only after OFF confirmed");
        String[] bad=paused.saved.get("owner-A").split("\\|",-1);bad[12]="NaN";
        check(ChargeScheduleController.decode(String.join("|",bad)).mode==0,"invalid captured SOC rejected");
    }
    /** Scheduled charging armed by Volt for 11:00Z (5 AM Boise) at 41%, the scooter reporting it on and paused. */
    private static Rig manualAt41(){
        Rig r=withManualOnly();r.c.soc=41d;r.c.charging=0;r.controller.setManualEnabled(true,r.results::add);r.reply(true);r.reply(true);r.c.enabled=1;r.reply(true);
        check(r.results.get(0)==null&&r.controller.view().manualEnabled&&r.controller.view().missed==null,"manual schedule armed without an alert");return r;
    }
    private static void pastStart(Rig r,Double soc){r.c.now=time("2026-10-20T11:02:00Z");r.c.sampleMs+=6*3600_000L;r.c.charging=0;r.c.soc=soc;r.controller.observe();}
    private static void missedStarts(){
        Rig missed=manualAt41();int commands=missed.writes.size();pastStart(missed,41d);
        ChargeScheduleController.View view=missed.controller.view();
        check(view.missed!=null&&!view.missed.doneBy&&!view.missed.shown&&view.missed.startWallMs==time("2026-10-20T11:00:00Z")&&view.missed.soc==41&&view.missed.armedSoc==41,
                "unchanged battery after the start is a missed start with its time and both levels");
        check(!view.manualEnabled&&missed.c.enabled==1,"a missed start clears the switch despite the scooter's own readback still saying on");
        check(missed.writes.size()==commands,"detecting a missed start never sends a command");
        missed.recreate();check(missed.controller.view().missed!=null,"the alert survives a restart before anyone saw it");
        ChargeScheduleController.Missed taken=missed.controller.takeUnshownMissed();
        check(taken!=null&&taken.startWallMs==time("2026-10-20T11:00:00Z")&&missed.controller.takeUnshownMissed()==null,"the background notice is taken once");
        missed.recreate();missed.controller.observe();check(missed.controller.takeUnshownMissed()==null&&missed.controller.view().missed.shown,"a restart neither repeats the notice nor loses the unanswered alert");
        missed.controller.acknowledgeMissed();missed.recreate();check(missed.controller.view().missed==null,"acknowledging clears the alert for good");
        ChargeScheduleController.Diagnostics d=missed.controller.diagnostics();
        check(d.mode==0&&d.outcome==ChargeScheduleController.Outcome.MISSED&&d.outcomeMode==1&&d.outcomeSoc==41&&d.armedSoc==41&&d.outcomeEnabled==1
                &&missed.c.wireStart.equals(d.outcomeWireStart)&&d.clockOffsetSeconds==-6*3600&&d.target==100&&d.readEnabled==1&&d.alert==0,"diagnostics keep the missed intention's numbers");

        // Council review of 4e7c8802 (finding 1, neighbouring fields): a schedule switch from a status frame older than the
        // readings' freshness rule is unknown in the diagnostics, at the outcome and in the readback; behaviour is unchanged.
        Rig stale=manualAt41();stale.c.statusStale=true;int staleCommands=stale.writes.size();pastStart(stale,41d);
        ChargeScheduleController.Diagnostics sd=stale.controller.diagnostics();
        check(stale.controller.view().missed!=null&&stale.writes.size()==staleCommands&&sd.outcome==ChargeScheduleController.Outcome.MISSED,"a stale status frame finds the same miss, sending nothing");
        check(sd.outcomeEnabled==null&&sd.readEnabled==null,"a stale status frame leaves the switch unknown at the outcome and in the readback: "+sd.outcomeEnabled+" "+sd.readEnabled);
        check(sd.outcomeSoc==41&&sd.generation==1,"the rest of the outcome is kept, on generation 1: "+sd.outcomeSoc+" "+sd.generation);
        check(ChargeScheduleController.diagnostics(stale.saved.get("owner-A")).generation==-1,"a decoded record was taken on no connection");

        Rig drift=manualAt41();pastStart(drift,42d);check(drift.controller.view().missed!=null,"one point of reading noise is still a missed start");
        Rig drained=manualAt41();pastStart(drained,40d);check(drained.controller.view().missed!=null&&drained.controller.view().missed.soc==40,"standby drain is still a missed start");
        Rig charged=manualAt41();pastStart(charged,60d);
        check(charged.controller.view().missed==null&&!charged.controller.view().manualEnabled&&charged.controller.diagnostics().outcome==ChargeScheduleController.Outcome.START_PASSED,
                "a battery that rose charged, then stopped (unplugged or done): retired quietly");
        Rig twoPoints=manualAt41();pastStart(twoPoints,43d);check(twoPoints.controller.view().missed==null,"two points is a charge, not reading noise");
        Rig unknown=manualAt41();pastStart(unknown,null);
        check(unknown.controller.view().missed==null&&!unknown.controller.view().manualEnabled,"unknown battery retires quietly rather than guessing");
        Rig unarmed=withManualOnly();unarmed.c.soc=null;unarmed.c.charging=0;unarmed.controller.setManualEnabled(true,unarmed.results::add);unarmed.reply(true);unarmed.reply(true);unarmed.c.enabled=1;unarmed.reply(true);
        pastStart(unarmed,41d);check(unarmed.controller.view().missed==null,"no battery level at arming gives no baseline: quiet");
        Rig waiting=manualAt41();waiting.c.now=time("2026-10-20T11:01:00Z");waiting.c.soc=41d;waiting.controller.observe();
        check(waiting.controller.view().missed==null&&waiting.controller.view().manualEnabled,"inside the start grace nothing is decided");
        Rig charging=manualAt41();charging.c.now=time("2026-10-20T11:02:00Z");charging.c.charging=1;charging.controller.observe();
        check(charging.controller.view().missed==null&&charging.controller.view().manualEnabled,"charging at the start is the schedule working");

        Rig rearm=manualAt41();pastStart(rearm,41d);rearm.c.charging=1;rearm.controller.setManualEnabled(true,rearm.results::add);rearm.reply(true);
        check(rearm.sentTime==time("2026-10-21T11:00:00Z"),"re-arming uses the next occurrence");rearm.c.wireStart=rearm.sentTime/1000+rearm.sentOffset;rearm.reply(true);rearm.reply(true);
        check(rearm.controller.view().missed==null&&rearm.controller.view().manualEnabled&&rearm.controller.diagnostics().outcome==ChargeScheduleController.Outcome.NONE,"re-arming clears the alert");
        check(ChargeScheduleController.decode(rearm.saved.get("owner-A")).alert==0,"re-armed record carries no alert");

        Rig off=manualAt41();pastStart(off,41d);commands=off.writes.size();off.controller.turnOffMissed(off.results::add);
        check(off.writes.size()==commands+1&&off.writes.get(commands).equals("off"),"Turn off schedule sends only OFF");
        off.reply(false);check(off.controller.view().missed!=null&&off.results.get(1)!=null,"an unconfirmed OFF keeps the alert");
        off.controller.turnOffMissed(off.results::add);off.c.enabled=0;off.reply(true);
        check(off.results.get(2)==null&&off.controller.view().missed==null&&off.controller.diagnostics().outcome==ChargeScheduleController.Outcome.MISSED,"a confirmed OFF clears the alert and keeps the outcome");
        Rig offline=manualAt41();pastStart(offline,41d);offline.c.ready=false;commands=offline.writes.size();offline.controller.turnOffMissed(offline.results::add);
        check(offline.writes.size()==commands&&offline.results.get(1).equals("Connect the scooter first")&&offline.controller.view().missed!=null,"offline turn-off sends nothing and keeps the alert");

        Rig doneBy=new Rig();doneBy.arm();long start=doneBy.controller.view().startWallMs;doneBy.c.now=doneBy.controller.view().doneByWallMs+180_000;doneBy.c.charging=0;doneBy.controller.observe();
        check(doneBy.controller.view().missed!=null&&doneBy.controller.view().missed.doneBy&&doneBy.controller.view().missed.startWallMs==start&&doneBy.controller.view().missed.armedSoc==79,
                "Charge done by found after its deadline is still a missed start, not just an ended plan");
        check(doneBy.controller.diagnostics().outcomeMode==2&&doneBy.controller.diagnostics().armedSoc==79,"done-by arming records the battery separately");
        Rig doneByCharged=new Rig();doneByCharged.arm();doneByCharged.c.now=doneByCharged.controller.view().doneByWallMs+180_000;doneByCharged.c.charging=0;doneByCharged.c.soc=97d;doneByCharged.controller.observe();
        check(doneByCharged.controller.view().missed==null&&doneByCharged.controller.diagnostics().outcome==ChargeScheduleController.Outcome.ENDED,"done-by that charged and stopped below the limit ends quietly");
        Rig doneByOff=new Rig();doneByOff.arm();doneByOff.c.now=start+180_000;doneByOff.c.charging=0;doneByOff.controller.observe();
        doneByOff.controller.turnOffMissed(doneByOff.results::add);check(doneByOff.writes.get(doneByOff.writes.size()-1).equals("off"),"done-by turn-off starts with OFF");

        Rig changed=manualAt41();long moved=time("2026-10-21T11:00:00Z");changed.c.wireStart=moved/1000-6*3600;pastStart(changed,41d);
        check(changed.controller.view().missed!=null&&changed.controller.view().missed.startWallMs==time("2026-10-20T11:00:00Z"),"a start the scooter moved after missing it is still reported with its original time");
        check(changed.controller.view().manualEnabled&&changed.controller.view().startWallMs==moved,"the moved native schedule is shown as the scooter reports it");
        check(changed.controller.view().missed.armedSoc==41&&changed.controller.view().missed.soc==41&&changed.controller.diagnostics().outcomeArmedSoc==41,
                "the moved start keeps the missed intention's own arming battery");
        changed.recreate();check(changed.controller.view().missed.armedSoc==41,"the missed intention's arming battery survives a restart");
        Rig editedAfter=manualAt41();pastStart(editedAfter,41d);editedAfter.c.soc=60d;editedAfter.c.charging=1;
        editedAfter.controller.setManualStart(time("2026-10-21T11:00:00Z"),editedAfter.results::add);editedAfter.reply(true);
        editedAfter.c.wireStart=editedAfter.sentTime/1000+editedAfter.sentOffset;editedAfter.reply(true);
        check(editedAfter.controller.view().missed!=null&&editedAfter.controller.view().missed.armedSoc==41&&editedAfter.controller.view().missed.soc==41,
                "editing Charge start after an unanswered miss does not rewrite that miss's battery levels");
        Rig started=manualAt41();started.c.now=time("2026-10-20T11:02:00Z");started.c.sampleMs+=6*3600_000L;started.c.charging=1;started.controller.observe();
        started.recreate();started.c.now+=30_000;started.c.sampleMs+=30_000;started.c.charging=0;started.c.soc=41d;started.controller.observe();
        check(started.controller.view().missed==null&&!started.controller.view().manualEnabled&&started.controller.diagnostics().outcome==ChargeScheduleController.Outcome.START_PASSED,
                "a start Volt saw charging, then stopping within the noise tolerance, is not a missed start, even across a restart");
        Rig early=manualAt41();early.c.now=time("2026-10-20T10:59:00Z");early.c.charging=1;early.controller.observe();
        early.c.now=time("2026-10-20T11:02:00Z");early.c.charging=0;early.controller.observe();
        check(early.controller.view().missed!=null,"charging seen only before the start is not evidence that the start fired");
        Rig edited=manualAt41();edited.c.wireStart=moved/1000-6*3600;edited.c.now=time("2026-10-20T08:00:00Z");edited.controller.observe();
        check(edited.controller.view().missed==null&&edited.controller.diagnostics().outcome==ChargeScheduleController.Outcome.CHANGED_ON_SCOOTER,"an edit before the start is a change, not a miss");

        Rig native0=new Rig();native0.c.enabled=1;native0.c.charging=0;native0.c.soc=41d;native0.c.wireStart=time("2026-10-20T11:00:00Z")/1000-6*3600;native0.c.now=time("2026-10-20T12:00:00Z");
        native0.controller.observe();check(native0.controller.view().missed==null&&native0.controller.view().manualEnabled,"a native schedule Volt never armed is never alerted");

        Rig lost=withManualOnly();lost.c.soc=41d;lost.c.charging=0;lost.controller.setManualEnabled(true,lost.results::add);lost.reply(true);lost.reply(true);lost.reply(false);
        check(lost.results.get(0)!=null&&lost.results.get(0).contains("not confirmed")&&!lost.controller.view().manualEnabled,"an unconfirmed ON is reported and not shown as armed");
        lost.c.wireStart=lost.sentTime/1000+lost.sentOffset;lost.c.enabled=1;lost.controller.observe();
        check(lost.controller.view().manualEnabled&&lost.controller.diagnostics().mode==1,"the scooter reading back Volt's start with ON is tracked as Volt's schedule");
        pastStart(lost,41d);check(lost.controller.view().missed!=null&&lost.controller.view().missed.armedSoc==41,"a start missed after a lost ON confirmation is still reported");
        Rig lostLate=withManualOnly();lostLate.c.soc=41d;lostLate.c.charging=0;lostLate.controller.setManualEnabled(true,lostLate.results::add);lostLate.reply(true);lostLate.reply(true);lostLate.reply(false);
        lostLate.c.wireStart=lostLate.sentTime/1000+lostLate.sentOffset;lostLate.c.enabled=1;pastStart(lostLate,41d);
        check(lostLate.controller.view().missed!=null,"first seen after its start (the next morning), a lost-ON schedule that never charged is reported");
        Rig lostOff=withManualOnly();lostOff.c.soc=41d;lostOff.c.charging=0;lostOff.controller.setManualEnabled(true,lostOff.results::add);lostOff.reply(true);lostOff.reply(true);lostOff.reply(false);
        lostOff.c.wireStart=lostOff.sentTime/1000+lostOff.sentOffset;lostOff.c.enabled=0;pastStart(lostOff,41d);
        check(lostOff.controller.view().missed==null&&!lostOff.controller.view().manualEnabled,"a lost ON the scooter did not apply stays untracked and quiet");
        Rig movedOn=manualAt41();movedOn.c.wireStart=moved/1000-6*3600;movedOn.c.now=time("2026-10-20T08:00:00Z");movedOn.controller.observe();pastStart(movedOn,41d);
        movedOn.c.now=moved+180_000;movedOn.controller.observe();
        check(movedOn.controller.view().missed==null,"a start edited on the scooter is never adopted as Volt's");

        Rig v3=manualAt41();String old=legacy(v3.saved.get("owner-A"),13,"3");ChargeScheduleController.State decoded=ChargeScheduleController.decode(old);
        check(decoded.mode==1&&decoded.armedSoc==-1&&decoded.alert==0&&decoded.outcome==0,"version 3 records still decode, with no arming battery level");
        v3.saved.put("owner-A",old);v3.recreate();pastStart(v3,41d);check(v3.controller.view().missed==null&&!v3.controller.view().manualEnabled,"an upgraded schedule retires quietly");
        String encoded=missed.saved.get("owner-A");check(encoded.startsWith("5|")&&encoded.split("\\|",-1).length==30,"version 5 record");
        ChargeScheduleController.State fromV4=ChargeScheduleController.decode(legacy(encoded,24,"4"));
        check(fromV4.outcome==ChargeScheduleController.Outcome.MISSED.ordinal()&&fromV4.outcomeArmedSoc==41&&fromV4.alert==missed.controller.diagnostics().alert&&fromV4.testOutcome==0,
                "a version 4 record from #129 keeps its missed-start evidence and has no test");
        String[] v4bad=legacy(encoded,24,"4").split("\\|",-1);v4bad[23]="maybe";check(ChargeScheduleController.decode(String.join("|",v4bad)).outcome==0,"invalid version 4 field rejected");
        check(ChargeScheduleController.decode(legacy(encoded,30,"4")).outcome==0&&ChargeScheduleController.decode(legacy(encoded,24,"5")).outcome==0,"field counts must match their version");
        String active=encoded.replaceFirst("^5\\|0\\|","5|1|");check(ChargeScheduleController.decode(active).mode==1,"fixture: the record is otherwise valid");
        for(int field:new int[]{13,14,15,16,17,18,19,20,21,22,23}){
            String[] v=active.split("\\|",-1);v[field]=field==13||field==17||field==22?"101":field==23?"maybe":"9999999999999999";
            check(ChargeScheduleController.decode(String.join("|",v)).mode==0,"invalid version 5 field "+field+" rejected");
        }
        String[] alert=active.split("\\|",-1);alert[21]="3";check(ChargeScheduleController.decode(String.join("|",alert)).mode==0,"unknown alert state rejected");
        String[] outcome=active.split("\\|",-1);outcome[14]=Integer.toString(ChargeScheduleController.Outcome.values().length);check(ChargeScheduleController.decode(String.join("|",outcome)).mode==0,"unknown outcome rejected");
        Rig empty=new Rig();empty.c.scope="";check(empty.controller.diagnostics()==null,"no scooter, no schedule record");
    }

    /**
     * The gate's answer while the status is stale: the service fills toggleReason and timeReason from BleController's
     * decisions, which refuse every write then (CapabilityResolver NOT_CONNECTED).
     */
    private static final String GATE="Connect your scooter to change this";
    /**
     * Just after a wake (or the first seconds back in sight): the same live connection (linked), a status not fresh enough
     * to write (not ready), the gate refusing, and readings from before the sleep no longer live, the settings report too.
     * #418's service context.
     */
    private static void woken(Rig r){r.c.ready=false;r.c.linked=true;r.c.settingsLive=false;r.c.toggleReason=GATE;r.c.timeReason=GATE;r.c.charging=null;r.c.soc=null;r.c.target=null;r.c.enabled=null;r.c.wireStart=null;r.c.estimateMinutes=null;}
    /** The fresh status after it: ready again, readings live. */
    private static void fresh(Rig r,Integer enabled,Long wire){r.c.ready=true;r.c.settingsLive=true;r.c.toggleReason=null;r.c.timeReason=null;r.c.charging=1;r.c.soc=79d;r.c.target=100d;r.c.estimateMinutes=90d;r.c.enabled=enabled;r.c.wireStart=wire;}

    /**
     * #418 round 4: every use of the context's connection answer, with linked (the same live connection) and ready (can
     * write now) apart, as just after a wake. Each decision to send, each blocked reason and the send-or-save choice asks
     * ready; whether work in flight carries on, and whether readings are looked at, asks linked (readings carry their
     * own freshness). "One answer" is fde6403f's service, which gave ready = linked(): the gate then refused.
     */
    private static void linkedNotReady(){
        // setDoneTime (the finding): with an active plan, a stale status saves the time for the next plan, as offline.
        Rig active=new Rig();active.arm();long start=active.controller.view().startWallMs,deadline=active.controller.view().doneByWallMs;long wire=active.c.wireStart;int commands=active.writes.size();
        woken(active);
        check(active.controller.view().timeBlockedReason==null,"the picker stays available just after a wake");
        active.controller.setDoneTime(9,30,active.results::add);
        check(active.results.get(1)==null&&active.controller.view().doneByMinuteOfDay==570&&active.writes.size()==commands,"just after a wake an edit to an active Done by is saved, nothing sent "+active.results);
        check(active.controller.view().doneByEnabled&&active.controller.view().startWallMs==start&&active.controller.view().doneByWallMs==deadline,"and the plan on the scooter is left as it is");
        fresh(active,1,wire);active.c.charging=1;
        active.controller.setDoneTime(10,0,active.results::add);
        check(active.writes.size()==commands+1&&active.writes.get(commands).equals("clock"),"on a fresh status the next edit reprograms the scooter");
        Rig one=new Rig();one.arm();int sent=one.writes.size();
        woken(one);one.c.ready=true;// fde6403f: ready = linked(), the gate still refuses
        one.controller.setDoneTime(9,30,one.results::add);
        check(GATE.equals(one.results.get(1))&&one.controller.view().doneByMinuteOfDay==480&&one.writes.size()==sent,"one answer chose a write the gate refused, and the time was lost "+one.results);

        // Blocked reasons and every other send: ready. Nothing is attempted while the status is stale, even with a fresh charging reading.
        Rig blocked=new Rig();woken(blocked);blocked.c.charging=1;blocked.c.soc=79d;blocked.c.target=100d;blocked.c.estimateMinutes=90d;blocked.c.enabled=0;
        ChargeScheduleController.View v=blocked.controller.view();
        check("Connect the scooter first".equals(v.manualBlockedReason)&&"Connect the scooter first".equals(v.manualTimeBlockedReason)
                &&"Connect the scooter first".equals(v.doneByBlockedReason)&&"Connect the scooter first".equals(v.testBlockedReason),"every blocked reason asks ready "+v.manualBlockedReason+" "+v.doneByBlockedReason+" "+v.testBlockedReason);
        check(v.chargerConnected,"while a fresh charging reading on the live connection still shows the charger");
        blocked.controller.setDoneEnabled(true,blocked.results::add);
        blocked.controller.setManualEnabled(true,blocked.results::add);
        blocked.controller.setManualStart(blocked.c.now+3_600_000L,blocked.results::add);
        check(blocked.controller.startTest(tested::add)!=null,"a schedule test does not start");
        check(blocked.writes.isEmpty()&&blocked.results.size()==3&&!blocked.results.contains(null)&&!blocked.controller.busy(),"Done by, Scheduled charging and Charge start send nothing "+blocked.results);
        Rig off=new Rig();off.arm();int before=off.writes.size();woken(off);off.controller.setDoneEnabled(false,off.results::add);
        check(off.writes.size()==before&&"Connect the scooter first".equals(off.results.get(1))&&off.controller.view().doneByEnabled,"turning a plan off sends nothing either, and keeps it");
        Rig missed=manualAt41();pastStart(missed,41d);int was=missed.writes.size();woken(missed);missed.controller.turnOffMissed(missed.results::add);
        check(missed.writes.size()==was&&missed.results.get(missed.results.size()-1)!=null,"a missed start's Turn off sends nothing");

        // Work in flight: linked. A merely stale status neither interrupts it nor stops its next step.
        Rig flight=new Rig();flight.enable();check(flight.writes.equals(java.util.Arrays.asList("clock")),"in flight");
        woken(flight);flight.controller.observe();
        check(flight.controller.busy()&&flight.results.isEmpty(),"a stale status does not interrupt a change in flight");
        fresh(flight,0,null);flight.reply(true);
        check(flight.writes.equals(java.util.Arrays.asList("clock","time")),"its next step goes out on the fresh status");
        flight.c.wireStart=flight.sentTime/1000+flight.sentOffset;flight.reply(true);
        check(flight.writes.size()==3&&flight.writes.get(2).equals("on"),"and the next");
        fresh(flight,1,flight.sentTime/1000+flight.sentOffset);flight.c.charging=0;flight.reply(true);
        check(flight.results.size()==1&&flight.results.get(0)==null&&flight.controller.view().doneByEnabled,"completed across the stale spell");
        // A step confirmed while the readings are stale never arms on them: it stops, with nothing more sent (as before #418).
        Rig blind=new Rig();blind.enable();blind.reply(true);check(blind.writes.equals(java.util.Arrays.asList("clock","time")),"time in flight");
        woken(blind);blind.reply(true);
        check(blind.writes.size()==2&&blind.results.size()==1&&blind.results.get(0)!=null&&!blind.controller.busy(),"a time confirmed on stale readings does not switch on blind "+blind.results);
        Rig lost=new Rig();lost.enable();lost.c.ready=false;lost.c.linked=false;lost.controller.observe();
        check(lost.results.size()==1&&lost.results.get(0)!=null&&!lost.controller.busy(),"a lost connection still interrupts");
        Rig running=testing();long testStart=running.sentTime;woken(running);at(running,testStart-100_000,null);
        check(running.controller.testing()&&tested.isEmpty()&&running.writes.isEmpty(),"a running schedule test is not interrupted by a stale status");

        // Looking at readings: linked, each reading by its own freshness.
        Rig target=new Rig();target.arm();woken(target);target.c.soc=100d;target.c.target=100d;target.c.settingsLive=true;target.controller.observe();
        check(!target.controller.view().doneByEnabled&&"Charge target reached".equals(target.controller.view().status),"a fresh reading on the live connection still retires a plan");
        Rig still=new Rig();still.arm();long planned=still.controller.view().startWallMs;long stillWire=still.c.wireStart;woken(still);
        check(still.controller.startStillPlanned("owner-A",planned),"stale readings: the claim stands");
        still.c.enabled=0;still.c.wireStart=stillWire;still.c.settingsLive=true;
        check(!still.controller.startStillPlanned("owner-A",planned),"a fresh OFF beside the plan's time retires the claim");
        ChargeScheduleController.Diagnostics d=still.controller.diagnostics();
        check(d.readEnabled!=null&&d.readEnabled==0&&d.readWireStart!=null&&d.readWireStart==stillWire,"diagnostics report the fresh readings");
        woken(still);d=still.controller.diagnostics();
        check(d.readEnabled==null&&d.readWireStart==null,"and none from before the wake");
        // ready implies linked: a driver that sets only ready (every older driver) means both.
        Rig old=new Rig();check(!old.c.linked&&old.c.ready,"fixture: an older driver");old.enable();old.controller.observe();
        check(old.controller.busy()&&old.writes.equals(java.util.Arrays.asList("clock")),"ready alone keeps work in flight");
    }

    /**
     * #418 round 5: a live battery level beside a settings report that is not live (just after a wake: the level comes
     * from 0x72 replies and 0x90 reports, the limit only from the 0x70 status). No outcome is judged against the plan's
     * saved target until the settings report is live again. 4164feba retired a plan at 85 as "Charge target reached" on
     * a limit that may have been raised meanwhile, and a retired plan is never taken up again.
     */
    private static void staleSettings(){
        // The finding: a plan at 85, a live level of 85, the settings stale. The plan waits.
        Rig wait=new Rig();wait.c.target=85d;wait.c.estimateMinutes=20d;wait.arm();
        woken(wait);wait.c.charging=1;wait.c.soc=85d;wait.controller.observe();
        check(wait.controller.view().doneByEnabled&&wait.controller.view().status.isEmpty(),"a stale limit never retires the plan (4164feba did) "+wait.controller.view().status);
        // The settings report live and the limit unchanged: retired, as before.
        wait.c.settingsLive=true;wait.c.target=85d;wait.controller.observe();
        check(!wait.controller.view().doneByEnabled&&"Charge target reached".equals(wait.controller.view().status)
                &&wait.controller.diagnostics().outcome==ChargeScheduleController.Outcome.TARGET_REACHED,"retired once the live settings show the limit unchanged");
        // A raised limit arriving live is followed: the start kept, the obsolete Done by dropped, nothing retired.
        Rig raised=new Rig();raised.c.target=85d;raised.c.estimateMinutes=20d;raised.arm();long start=raised.controller.view().startWallMs;
        woken(raised);raised.c.charging=1;raised.c.soc=85d;raised.controller.observe();
        raised.c.settingsLive=true;raised.c.target=100d;raised.controller.observe();
        ChargeScheduleController.View v=raised.controller.view();
        check(!v.doneByEnabled&&v.manualEnabled&&v.startWallMs==start&&"Charge limit changed; update Charge done by".equals(v.status),"a raised limit is followed, not taken as reached "+v.status);
        // A model with no limit reading: target is null even while the settings are live, and the plan's target is 100.
        Rig none=withManualOnly();none.c.target=null;none.controller.setManualEnabled(true,none.results::add);none.reply(true);none.reply(true);none.c.enabled=1;none.reply(true);
        check(none.results.get(0)==null&&none.controller.view().manualEnabled,"armed on a model with no limit reading");
        woken(none);none.c.charging=1;none.c.soc=100d;none.controller.observe();
        check(none.controller.view().manualEnabled,"no limit reading, stale settings: waits");
        none.c.settingsLive=true;none.controller.observe();
        check(!none.controller.view().manualEnabled&&"Charge target reached".equals(none.controller.view().status),"no limit reading, live settings: retired at 100");
        // The same class past the start: missed against passed is not decided on a stale limit. Here it was lowered unseen
        // below the level, so the charger stopped at it: not a missed start.
        Rig low=manualAt41();low.c.now=time("2026-10-20T11:02:00Z");low.c.sampleMs+=6*3600_000L;
        woken(low);low.c.charging=0;low.c.soc=41d;low.controller.observe();
        check(low.controller.view().manualEnabled&&low.controller.view().missed==null,"stale settings: no missed start yet (4164feba raised one)");
        low.c.settingsLive=true;low.c.target=40d;low.c.enabled=1;low.controller.observe();
        check(!low.controller.view().manualEnabled&&low.controller.view().missed==null&&"Charge target reached".equals(low.controller.view().status),"the live limit says it was reached "+low.controller.view().status);
    }

    /**
     * #418 round 6 (finding 3): a charging start seen while the settings are stale is kept. A plan armed at 84% under an
     * 85% limit; the limit raised unseen in a sleep; after the start, 85% and charging arrive before the next status, then
     * charging stops. 702153ba returned before recording the start, and the live pass called the charge a missed start.
     */
    private static void startSeenStale(){
        Rig r=withManualOnly();r.c.soc=84d;r.c.target=85d;r.c.charging=0;r.controller.setManualEnabled(true,r.results::add);r.reply(true);r.reply(true);r.c.enabled=1;r.reply(true);
        check(r.results.get(0)==null&&r.controller.view().manualEnabled,"armed at 84% under an 85% limit");
        long wire=r.c.wireStart;
        woken(r);r.c.now=time("2026-10-20T11:01:00Z");r.c.charging=1;r.c.soc=85d;r.controller.observe();
        check(r.controller.view().manualEnabled&&r.controller.view().status.isEmpty(),"stale settings: nothing decided");
        r.c.now=time("2026-10-20T11:03:00Z");r.c.charging=0;r.controller.observe();
        check(r.controller.view().manualEnabled,"still nothing while they stay stale");
        r.c.ready=true;r.c.settingsLive=true;r.c.target=90d;r.c.enabled=1;r.c.wireStart=wire;r.c.toggleReason=null;r.c.timeReason=null;r.controller.observe();
        ChargeScheduleController.View v=r.controller.view();
        check(v.missed==null&&!v.manualEnabled&&"Scheduled start has passed".equals(v.status),"the start seen on stale settings is kept: not a missed start (702153ba raised one) "+v.status);
    }

    /** A test armed on a scooter with a manual Charge start preference (11:00Z), ON confirmed and still charging. */
    private static final List<ChargeScheduleController.TestResult> tested=new ArrayList<>();
    private static Rig testing(){
        Rig r=withManualOnly();tested.clear();
        check(r.controller.startTest(tested::add)==null,"the test starts");check(r.controller.testing()&&r.controller.view().testPhase==ChargeScheduleTest.Phase.ARMING,"arming");
        check(r.writes.equals(java.util.Arrays.asList("clock")),"the usual transaction: clock first");r.reply(true);
        check(r.writes.get(1).equals("time")&&r.sentTime==time("2026-10-20T05:02:00Z")&&r.sentTime==ChargeScheduleTest.start(r.c.now),"time second: two minutes ahead, a whole minute");
        r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);check(r.writes.get(2).equals("on"),"ON last");r.c.enabled=1;r.reply(true);
        ChargeScheduleController.View v=r.controller.view();
        check(v.testPhase==ChargeScheduleTest.Phase.PAUSING&&v.testStartWallMs==r.sentTime&&tested.isEmpty(),"armed, waiting for the pause");
        check(v.manualEnabled&&v.manualStartWallMs==time("2026-10-20T11:00:00Z"),"tracked as a Volt manual intention, the owner's preference untouched");
        check(v.manualBlockedReason.equals("Wait for the schedule test to finish")&&v.doneByBlockedReason.equals("Wait for the schedule test to finish")&&v.testBlockedReason==null,"other schedule controls wait for the test");
        r.writes.clear();return r;
    }
    private static void at(Rig r,long wall,Integer charging){r.c.sampleMs+=wall-r.c.now;r.c.now=wall;r.c.charging=charging;r.controller.observe();}
    /** OFF, then the 11:00Z preference restored (clock and time), never ON. */
    private static void cleanup(Rig r,String message){
        check(r.writes.equals(java.util.Arrays.asList("off")),message+": the verdict sends OFF first");r.c.enabled=0;r.reply(true);
        check(r.writes.equals(java.util.Arrays.asList("off","clock")),message+": then restores the clock");r.reply(true);
        check(r.writes.get(2).equals("time")&&r.sentTime==time("2026-10-20T11:00:00Z"),message+": and the owner's Charge start");r.c.wireStart=r.sentTime/1000+r.sentOffset;r.reply(true);
        ChargeScheduleController.View v=r.controller.view();
        check(r.writes.size()==3&&!r.controller.testing()&&!r.controller.busy()&&!v.manualEnabled&&!v.doneByEnabled&&v.manualStartWallMs==time("2026-10-20T11:00:00Z"),message+": schedule off, preference kept, test over");
        check(tested.size()==1&&tested.get(0).scheduleOff&&tested.get(0).cleanup==null,message+": one clean result");
    }
    private static void scheduleTests(){
        long start=time("2026-10-20T05:02:00Z");
        Rig offFails=testing();at(offFails,start-110_000,0);at(offFails,start+6_000,1);check(offFails.writes.equals(java.util.Arrays.asList("off")),"verdict sends OFF");offFails.reply(false);
        offFails.recreate();at(offFails,start+130_000,0);offFails.c.soc=79d;offFails.controller.observe();
        check(offFails.controller.view().missed==null,"a resume the test saw is never later called a missed start, even after a failed OFF and a restart");
        Rig lostOff=testing();at(lostOff,start-110_000,0);at(lostOff,start+6_000,1);lostOff.c.generation++;lostOff.controller.observe();lostOff.c.generation--;
        lostOff.recreate();at(lostOff,start+130_000,0);
        check(lostOff.controller.view().missed==null,"nor after the link drops while OFF is pending");
        Rig earlyResume=testing();at(earlyResume,start-110_000,0);at(earlyResume,start-20_000,1);earlyResume.reply(false);earlyResume.recreate();at(earlyResume,start+130_000,0);
        check(earlyResume.controller.view().missed==null,"a resume inside the early pass window counts as the start too");
        Rig pass=testing();at(pass,start-110_000,1);at(pass,start-100_000,0);check(pass.controller.view().testPhase==ChargeScheduleTest.Phase.WAITING&&pass.writes.isEmpty(),"paused: waiting for the start");
        at(pass,start-40_000,0);check(pass.controller.view().manualEnabled&&pass.writes.isEmpty(),"a long pause before the start is not a missed start or anything else");
        at(pass,start+6_000,1);cleanup(pass,"pass");
        ChargeScheduleController.TestResult result=tested.get(0);
        check(result.outcome==ChargeScheduleTest.Outcome.PASSED&&result.offsetSeconds==6&&result.paused&&result.startWallMs==start&&result.failure==null,"pass with its measured offset");
        ChargeScheduleController.Diagnostics d=pass.controller.diagnostics();
        check(d.testOutcome==ChargeScheduleTest.Outcome.PASSED&&d.testOffsetSeconds==6&&d.testPaused&&d.testScheduleOff==1&&d.testWallMs==time("2026-10-20T05:00:00Z")&&d.testStartWallMs==start,"diagnostics keep the last test");
        check(d.outcome==ChargeScheduleController.Outcome.TURNED_OFF&&d.alert==0,"the test's intention ended turned off, without an alert");
        pass.recreate();check(pass.controller.view().lastTest==ChargeScheduleTest.Outcome.PASSED&&pass.controller.view().lastTestOffsetSeconds==6,"the last test survives a restart");
        at(pass,start+600_000,0);check(pass.controller.view().missed==null&&pass.writes.size()==3,"nothing follows a finished test");

        Rig early=testing();at(early,start-110_000,0);at(early,start-95_000,1);cleanup(early,"early");
        check(tested.get(0).outcome==ChargeScheduleTest.Outcome.RESUMED_EARLY&&tested.get(0).offsetSeconds==-95,"an early resume reports how early");
        Rig noPause=testing();at(noPause,time("2026-10-20T05:00:30Z"),1);check(noPause.writes.isEmpty(),"charging inside the pause window");
        at(noPause,time("2026-10-20T05:01:00Z"),1);cleanup(noPause,"no pause");
        check(tested.get(0).outcome==ChargeScheduleTest.Outcome.NO_PAUSE&&!tested.get(0).paused&&tested.get(0).offsetSeconds==null,"never paused");

        Rig noResume=testing();at(noResume,start-100_000,0);for(long t=start-80_000;t<start+300_000;t+=20_000)at(noResume,t,0);
        check(noResume.writes.isEmpty()&&noResume.controller.view().missed==null,"through the grace period a running test raises no missed-start alert");
        at(noResume,start+300_000,0);cleanup(noResume,"no resume");check(tested.get(0).outcome==ChargeScheduleTest.Outcome.NO_RESUME&&tested.get(0).paused,"paused, never resumed");
        check(noResume.controller.view().missed==null,"OFF confirmed: no missed-start alert follows");

        Rig offFailed=testing();at(offFailed,start-100_000,0);at(offFailed,start+300_000,0);check(offFailed.writes.equals(java.util.Arrays.asList("off")),"OFF sent");offFailed.reply(false);
        result=tested.get(0);check(result.outcome==ChargeScheduleTest.Outcome.NO_RESUME&&!result.scheduleOff&&result.cleanup.startsWith("Schedule cancellation was not confirmed")&&!offFailed.controller.testing(),"an unconfirmed OFF is reported plainly");
        check(offFailed.writes.size()==1&&offFailed.controller.diagnostics().testScheduleOff==0,"nothing restored without OFF");
        at(offFailed,start+310_000,0);ChargeScheduleController.Missed missed=offFailed.controller.view().missed;
        check(missed!=null&&missed.test&&missed.startWallMs==start,"the tracked intention then raises the missed-start alert");
        offFailed.controller.turnOffMissed(offFailed.results::add);check(offFailed.writes.get(1).equals("off"),"Turn off schedule sends OFF");offFailed.c.enabled=0;offFailed.reply(true);offFailed.reply(true);
        check(offFailed.writes.get(3).equals("time")&&offFailed.sentTime==time("2026-10-20T11:00:00Z"),"turning off a test's missed start restores the Charge start too");

        Rig cancel=testing();at(cancel,start-100_000,0);cancel.controller.cancelTest();cleanup(cancel,"cancel");check(tested.get(0).outcome==ChargeScheduleTest.Outcome.CANCELLED,"cancelled");
        Rig cancelArming=withManualOnly();tested.clear();cancelArming.controller.startTest(tested::add);cancelArming.controller.cancelTest();cancelArming.reply(true);cancelArming.reply(true);cancelArming.c.enabled=1;cancelArming.reply(true);
        cancelArming.writes.subList(0,3).clear();cleanup(cancelArming,"cancel while arming");check(tested.get(0).outcome==ChargeScheduleTest.Outcome.CANCELLED,"a cancel while arming ends once ON is confirmed");

        Rig dropped=testing();at(dropped,start-100_000,0);dropped.c.generation++;at(dropped,start-90_000,0);
        result=tested.get(0);check(result.outcome==ChargeScheduleTest.Outcome.INTERRUPTED&&!result.scheduleOff&&result.cleanup==null&&dropped.writes.isEmpty()&&!dropped.controller.testing(),"a new session interrupts without sending anything");
        check(dropped.controller.view().manualEnabled&&dropped.controller.diagnostics().testScheduleOff==null,"the interrupted test stays a tracked intention");
        at(dropped,start+130_000,0);check(dropped.controller.view().missed!=null&&dropped.controller.view().missed.test&&dropped.writes.isEmpty(),"on reconnect its missed start is alerted, with no command sent");
        Rig away=testing();away.c.ready=false;at(away,start-100_000,null);check(tested.get(0).outcome==ChargeScheduleTest.Outcome.INTERRUPTED&&away.writes.isEmpty(),"a disconnect interrupts");
        Rig closed=testing();closed.controller.cancel();check(tested.get(0).outcome==ChargeScheduleTest.Outcome.INTERRUPTED&&closed.writes.isEmpty()&&!closed.controller.testing(),"Volt closing interrupts");
        Rig armingDrop=withManualOnly();tested.clear();armingDrop.controller.startTest(tested::add);armingDrop.c.generation++;armingDrop.reply(true);
        check(tested.size()==1&&tested.get(0).outcome==ChargeScheduleTest.Outcome.INTERRUPTED&&armingDrop.writes.size()==1,"a new session while arming stops it there");
        Rig running=testing();running.saved.put("owner-A",running.saved.get("owner-A"));String record=ChargeScheduleController.encode(ChargeScheduleController.decode(running.saved.get("owner-A")));
        check(ChargeScheduleController.decode(running.saved.get("owner-A")).testOutcome==ChargeScheduleTest.Outcome.INTERRUPTED.ordinal(),"a running test found on load was cut off by the process ending");
        check(record.split("\\|",-1).length==30,"version 5 record carries the test");

        Rig clockFailed=withManualOnly();tested.clear();clockFailed.controller.startTest(tested::add);clockFailed.reply(false);
        check(tested.get(0).outcome==ChargeScheduleTest.Outcome.NOT_STARTED&&tested.get(0).failure.startsWith("Could not set the scooter clock")&&clockFailed.writes.size()==1,"a refused clock changed nothing, so nothing is undone");
        Rig timeFailed=withManualOnly();tested.clear();timeFailed.controller.startTest(tested::add);timeFailed.reply(true);timeFailed.reply(false);timeFailed.writes.subList(0,2).clear();cleanup(timeFailed,"unconfirmed time");
        check(tested.get(0).outcome==ChargeScheduleTest.Outcome.NOT_STARTED&&tested.get(0).failure!=null,"an unconfirmed time is undone and reported");
        Rig onFailed=withManualOnly();tested.clear();onFailed.controller.startTest(tested::add);onFailed.reply(true);onFailed.reply(true);onFailed.reply(false);onFailed.writes.subList(0,3).clear();cleanup(onFailed,"unconfirmed ON");

        Rig nativeTime=new Rig();long nativeStart=time("2026-10-20T13:00:00Z");nativeTime.c.wireStart=nativeStart/1000-6*3600;tested.clear();
        nativeTime.controller.startTest(tested::add);nativeTime.reply(true);nativeTime.reply(true);nativeTime.c.enabled=1;nativeTime.reply(true);nativeTime.writes.clear();
        at(nativeTime,start-100_000,0);at(nativeTime,start,1);nativeTime.reply(true);nativeTime.reply(true);
        check(nativeTime.sentTime==nativeStart&&nativeTime.controller.view().manualStartWallMs==nativeStart,"a native time set elsewhere is restored as the preference");nativeTime.reply(true);
        Rig none=new Rig();tested.clear();none.controller.startTest(tested::add);none.reply(true);none.reply(true);none.c.enabled=1;none.reply(true);none.writes.clear();
        at(none,start-100_000,0);at(none,start,1);none.reply(true);
        check(none.writes.equals(java.util.Arrays.asList("off"))&&tested.get(0).scheduleOff&&none.controller.view().manualStartWallMs==0,"without a preference only OFF is sent and none is invented");

        Rig gate=new Rig();gate.c.wholeHours=true;check("Not available on this model".equals(gate.controller.startTest(tested::add))&&gate.writes.isEmpty(),"other models schedule whole hours only");
        gate.c.wholeHours=false;gate.c.charging=0;check("Connect a charger first".equals(gate.controller.view().testBlockedReason),"needs charging now");
        gate.c.charging=null;check("Connect a charger first".equals(gate.controller.view().testBlockedReason),"needs a fresh charging reading");
        gate.c.charging=1;gate.c.soc=100d;check("Battery is already at its charge limit".equals(gate.controller.view().testBlockedReason),"needs room below the limit");
        gate.c.soc=null;check("Wait for fresh charging readings".equals(gate.controller.view().testBlockedReason),"needs a fresh battery level");
        gate.c.soc=79d;gate.c.ready=false;check("Connect the scooter first".equals(gate.controller.view().testBlockedReason),"needs the scooter");
        gate.c.ready=true;gate.c.enabled=1;check("Turn off Scheduled charging first".equals(gate.controller.view().testBlockedReason),"not over a schedule that is on");
        gate.c.enabled=0;gate.c.timeReason="Charge start isn't available";check("Charge start isn't available".equals(gate.controller.view().testBlockedReason),"needs the charge start control");
        gate.c.timeReason=null;check(gate.controller.view().testBlockedReason==null,"ready to test");
        gate.arm();gate.c.charging=1;check("Turn off Charge done by first".equals(gate.controller.view().testBlockedReason),"not over Charge done by");
        Rig busy=new Rig();busy.enable();check("Wait for the current change to finish".equals(busy.controller.startTest(tested::add)),"not during another change");
        Rig twice=testing();check("Wait for the schedule test to finish".equals(twice.controller.startTest(tested::add))&&twice.writes.isEmpty(),"one test at a time");
        Rig limit=testing();at(limit,start-100_000,0);limit.c.soc=100d;at(limit,start-90_000,0);check(limit.writes.equals(java.util.Arrays.asList("off")),"a charge limit reached mid-test still ends with OFF");

        String[] v=pass.saved.get("owner-A").split("\\|",-1);
        for(int field:new int[]{24,25,26,27,28,29}){String[] bad=v.clone();bad[field]=field==27?"99":field==28?"2":field==29?"maybe":"-1";
            check(ChargeScheduleController.decode(String.join("|",bad)).testWall==0,"invalid test field "+field+" rejected");}
    }
    /** Report diagnostics' journal: what it hears, the result ids and interruption reasons; it never changes what is sent. */
    private static final class Heard implements ChargeScheduleController.Journal {
        final List<String> ops=new ArrayList<>();final List<ChargeScheduleController.Diagnostics> saved=new ArrayList<>();boolean fail;
        public void saved(String scope,ChargeScheduleController.Diagnostics d){if(fail)throw new IllegalStateException("broken journal");saved.add(d);}
        public void operation(String scope,ChargeScheduleController.Op op,ChargeScheduleController.Result result,ChargeScheduleController.Interruption why,long ms,long testWall,ChargeScheduleTest.Outcome t){
            if(fail)throw new IllegalStateException("broken journal");ops.add(op+" "+result+(why==null?"":" "+why)+(t==null?"":" "+t)+(testWall>0?" test":""));}
        String last(){return ops.isEmpty()?"":ops.get(ops.size()-1);}
    }
    private static void journal(){
        // A confirmed done-by: the record is heard, the operation ends OK.
        Rig r=new Rig();Heard h=new Heard();r.controller.journal(h);r.arm();
        check(h.last().equals("DONE_BY_ON OK")&&!h.saved.isEmpty()&&h.saved.get(h.saved.size()-1).mode==2,"an armed plan: its record and its OK");
        // Blocked while a change is in flight; then the link changes under the pending change.
        r.controller.setManualEnabled(true,r.results::add);check(h.last().equals("MANUAL_ON BLOCKED_DISABLE_DONE_BY"),"a blocked control records its reason id");
        r.controller.setDoneTime(9,0,r.results::add);r.c.generation++;r.controller.observe();
        check(h.last().equals("DONE_BY_TIME INTERRUPTED GENERATION_CHANGED"),"an interrupted change says the connection changed");
        Rig zone=new Rig();Heard hz=new Heard();zone.controller.journal(hz);zone.enable();zone.c.zone=TimeZone.getTimeZone("Europe/Lisbon");zone.reply(true);
        check(hz.last().equals("DONE_BY_ON INTERRUPTED TIME_ZONE_CHANGED"),"or that the time zone changed");
        Rig lost=new Rig();Heard hl=new Heard();lost.controller.journal(hl);lost.enable();lost.c.ready=false;lost.controller.observe();
        check(hl.last().equals("DONE_BY_ON INTERRUPTED LINK_NOT_READY"),"or that the link stopped being ready");
        Rig failed=new Rig();Heard hf=new Heard();failed.controller.journal(hf);failed.enable();failed.reply(false);
        check(hf.last().equals("DONE_BY_ON CLOCK_FAILED"),"a failed step names itself");
        // The service ending under a pending change: recorded as interrupted, why named.
        Rig ended=new Rig();Heard he=new Heard();ended.controller.journal(he);ended.enable();ended.controller.cancel(ChargeScheduleController.Interruption.SERVICE_ENDED);
        check(he.last().equals("DONE_BY_ON INTERRUPTED SERVICE_ENDED")&&ended.results.isEmpty(),"cancel records the pending change, still without answering it");
        // A schedule test: start, a stale reading cuts it off, then the phone clock.
        tested.clear();Rig t=withManualOnly();Heard ht=new Heard();t.controller.journal(ht);long start=time("2026-10-20T05:02:00Z");
        check(t.controller.startTest(tested::add)==null,"test starts");t.reply(true);t.c.wireStart=t.sentTime/1000+t.sentOffset;t.reply(true);t.c.enabled=1;t.reply(true);
        check(ht.ops.contains("TEST_START OK test"),"the test's arming, tied to the test");
        at(t,start-110_000,0);t.c.now=start-60_000;t.c.charging=null;t.controller.observe();
        check(ht.last().equals("TEST_END INTERRUPTED READINGS_STALE INTERRUPTED test"),"stale readings: why the test was cut off");
        tested.clear();Rig clock=withManualOnly();Heard hc=new Heard();clock.controller.journal(hc);
        clock.controller.startTest(tested::add);clock.reply(true);clock.c.wireStart=clock.sentTime/1000+clock.sentOffset;clock.reply(true);clock.c.enabled=1;clock.reply(true);
        clock.c.mono=1_000_000;at(clock,start-110_000,0);clock.c.mono=1_001_000;at(clock,start-100_000,0);
        check(hc.last().equals("TEST_END INTERRUPTED PHONE_CLOCK_CHANGED INTERRUPTED test"),"a phone clock change, not the scooter");
        tested.clear();Rig busy=withManualOnly();Heard hb=new Heard();busy.controller.journal(hb);busy.controller.startTest(tested::add);
        check(busy.controller.startTest(tested::add)!=null&&hb.last().equals("TEST_START BLOCKED_WAIT_TEST test"),"a second test waits");
        // A passed test: its verdict, then the cleanup's own operation.
        tested.clear();Rig pass=withManualOnly();Heard hp=new Heard();pass.controller.journal(hp);pass.controller.startTest(tested::add);pass.reply(true);
        pass.c.wireStart=pass.sentTime/1000+pass.sentOffset;pass.reply(true);pass.c.enabled=1;pass.reply(true);pass.writes.clear();
        at(pass,start-110_000,0);at(pass,start+6_000,1);cleanup(pass,"journalled pass");
        check(hp.ops.contains("TEST_END OK PASSED test")&&hp.last().equals("TEST_CLEANUP OK test"),"a verdict and its cleanup");
        // Council review of 6c968d5c (finding 4): the pause is saved when the test sees it, so a test the process end cuts off still says it paused.
        tested.clear();Rig pausing=withManualOnly();Heard hpz=new Heard();pausing.controller.journal(hpz);pausing.controller.startTest(tested::add);pausing.reply(true);
        pausing.c.wireStart=pausing.sentTime/1000+pausing.sentOffset;pausing.reply(true);pausing.c.enabled=1;pausing.reply(true);pausing.writes.clear();
        at(pausing,start-110_000,1);check(!hpz.saved.get(hpz.saved.size()-1).testPaused,"charging: no pause yet");int saves=hpz.saved.size();
        at(pausing,start-100_000,0);ChargeScheduleController.Diagnostics live=pausing.controller.diagnostics();
        check(hpz.saved.size()==saves+1&&hpz.saved.get(saves).testPaused&&live.testOutcome==ChargeScheduleTest.Outcome.RUNNING&&live.testPaused&&pausing.writes.isEmpty(),
                "the pause is saved once, as seen, the test still running, nothing sent");
        at(pausing,start-90_000,0);check(hpz.saved.size()==saves+1,"and not saved again while it stays paused");
        try{
            java.io.File dir=java.nio.file.Files.createTempDirectory("charge-evidence-pause").toFile();String scope="0b0c5e9e-2d1f-4c3a-9a51-7f5b2e8d1c00";
            ChargeEvidence before=new ChargeEvidence(dir,java.util.ArrayList::new,()->start-100_000,()->1_000_000L);before.saved(scope,live);before.flush().get(10,java.util.concurrent.TimeUnit.SECONDS);
            ChargeEvidence after=new ChargeEvidence(dir,java.util.ArrayList::new,()->start,()->2_000_000L);after.prefetch(scope);after.flush().get(10,java.util.concurrent.TimeUnit.SECONDS);
            long[] row=after.snapshot(scope).book.tests.get(0);
            check(row[ChargeEvidence.X_OUTCOME]==ChargeScheduleTest.Outcome.INTERRUPTED.ordinal()&&row[ChargeEvidence.X_PAUSED]==1
                    &&row[ChargeEvidence.X_WHY]==ChargeScheduleController.Interruption.PROCESS_ENDED.ordinal(),"cut off by the process end: the evidence row is interrupted, paused");
        }catch(Exception e){throw new AssertionError(e);}
        // The journal never changes behaviour: the same steps with a journal that throws send exactly the same writes.
        Rig plain=new Rig();plain.arm();plain.controller.setDoneTime(9,0,plain.results::add);plain.reply(true);
        Rig broken=new Rig();Heard hx=new Heard();hx.fail=true;broken.controller.journal(hx);broken.arm();broken.controller.setDoneTime(9,0,broken.results::add);broken.reply(true);
        check(plain.writes.equals(broken.writes)&&plain.results.equals(broken.results)&&plain.saved.equals(broken.saved),"a failing journal changes nothing");
        // Reconciliation still never sends commands, journal or not.
        Rig quiet=new Rig();Heard hq=new Heard();quiet.controller.journal(hq);quiet.arm();quiet.writes.clear();
        for(int i=0;i<40;i++){quiet.advance(600_000);quiet.c.charging=i%2;quiet.c.enabled=i%3==0?0:1;quiet.c.wireStart=i%5==0?quiet.c.wireStart+60:quiet.c.wireStart;quiet.controller.observe();quiet.controller.view();}
        check(quiet.writes.isEmpty(),"observing with a journal sends nothing");
    }
}
