package com.jjricks.volt;

public final class ChargeRecoveryPolicyTest {
    private static int checks;
    private static final long START = 100_000, LOST = START + 60_000;
    private static final String PROFILE = "one", SERIAL = "X2538SYNTHETIC0001";
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static ChargeRecoveryPolicy policy() { return new ChargeRecoveryPolicy(PROFILE, SERIAL, START, 7); }
    private static ChargeSessionStore.Value v(double value, long now) { return new ChargeSessionStore.Value(value, now, 0x72); }
    private static ChargeSessionStore.Frame frame(long now, double state, double soc) {
        return new ChargeSessionStore.Frame(1_800_000_000_000L + now, now, v(state, now), v(soc, now), v(52, now), v(-2, now), null);
    }
    private static ChargeRecoveryPolicy.Result evaluate(ChargeRecoveryPolicy p, long now, boolean connected, boolean ready, boolean retry, ChargeSessionStore.Frame frame) {
        return p.evaluate(now, PROFILE, SERIAL, 7, true, true, connected, ready, retry, connected ? SERIAL : "", frame);
    }
    private static void lose(ChargeRecoveryPolicy p) {
        ChargeRecoveryPolicy.Result r=evaluate(p, LOST, false, false, true, frame(LOST-1000,1,50));
        check(r.beganRecovery && p.recovering() && !r.record && r.stop==null, "Transient loss enters a bounded wait");
    }
    public static void main(String[] args) throws Exception {
        ChargeRecoveryPolicy p=policy();
        check(evaluate(p,START,true,true,false,frame(START,1,50)).record,"Verified initial connection records normally");
        lose(p);
        check(!evaluate(p,LOST+1000,false,false,true,null).beganRecovery,"Repeated callbacks do not restart recovery");
        check(!evaluate(p,LOST+2000,true,false,true,frame(LOST+2000,1,50)).record,"Authentication without ready status cannot resume");
        ChargeSessionStore.Frame cached=frame(LOST,1,50);
        check(!evaluate(p,LOST+3000,true,true,true,cached).record&&p.recovering(),"Cached pre-loss readings cannot resume even if otherwise fresh");
        ChargeSessionStore.Frame mixed=new ChargeSessionStore.Frame(cached.wall,LOST+3000,v(1,LOST+3000),v(50,LOST),v(52,LOST+3000),v(-2,LOST+3000),null);
        check(!evaluate(p,LOST+3000,true,true,true,mixed).record,"Every required field must be observed after loss");
        ChargeRecoveryPolicy.Result resumed=evaluate(p,LOST+4000,true,true,true,frame(LOST+4000,1,51));
        check(resumed.record&&resumed.resumed&&!p.recovering(),"Same authenticated scooter with new paired charging data resumes");
        check(!evaluate(p,LOST+5000,true,true,false,frame(LOST+5000,1,51)).resumed,"Resume emitted once");
        long again=LOST+6000;
        check(evaluate(p,again,false,false,true,null).beganRecovery,"A later interruption gets a new recovery window");
        check(p.remainingMs(again+1)==ChargeRecoveryPolicy.RECOVERY_TIMEOUT_MS-1,"Window starts at the new interruption");

        p=policy();lose(p);
        check(evaluate(p,LOST+299999,true,false,true,null).stop==null,"Partial authentication can wait just under five minutes");
        check(evaluate(p,LOST+300000,true,true,true,frame(LOST+300000,1,55)).stop==ChargeSessionStore.Stop.RECOVERY_TIMEOUT,"Fresh readings at the deadline cannot extend recovery");
        p=policy();lose(p);
        check(evaluate(p,LOST+1000,false,false,false,null).stop==ChargeSessionStore.Stop.CONNECTION_FAILED,"Fatal auth/transport rejection stops rather than retries");
        p=policy();lose(p);
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,8,true,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.STOPPED,"Explicit disconnect wins over retry");
        check(p.evaluate(LOST+1000,"other",SERIAL,7,true,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.PROFILE_CHANGED,"Changing profile ends capture");
        check(p.evaluate(LOST+1000,PROFILE,"OTHER",7,true,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.IDENTITY_CHANGED,"Editing saved serial ends capture");
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,false,true,false,false,true,"",null).stop==ChargeSessionStore.Stop.PERMISSION_LOST,"Permission loss ends capture");
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,false,false,false,true,"",null).stop==ChargeSessionStore.Stop.BLUETOOTH_OFF,"Bluetooth off ends capture");
        check(p.evaluate(LOST+1000,PROFILE,SERIAL,7,true,true,true,true,true,"OTHER",frame(LOST+1000,1,50)).stop==ChargeSessionStore.Stop.IDENTITY_CHANGED,"Wrong authenticated scooter never contributes data");
        p=policy();lose(p);
        resumed=evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,0,75));
        check(resumed.resumed&&resumed.record&&resumed.stop==null&&!p.recovering(),"Charging off below 99% after a gap resumes into a pause, not an immediate end");
        p=policy();lose(p);
        resumed=evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,0,100));
        check(resumed.resumed&&resumed.record,"Observed 100 percent endpoint is retained after gap without filling it");
        p=policy();lose(p);
        resumed=evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,0,99));
        check(resumed.resumed&&resumed.record,"Fresh 99 percent cutoff goes through existing final-packet grace window");
        p=policy();lose(p);
        check(evaluate(p,LOST+1000,true,true,true,frame(LOST+1000,2,75)).stop==ChargeSessionStore.Stop.CHARGING_ENDED,"Only charging=off resumes into a pause; another state ends the capture");
        chargerPauseAcrossLoss();
        p=policy();
        check(evaluate(p,START+ChargeSessionStore.MAX_SESSION_MS-1,false,false,true,null).beganRecovery,"Loss near session limit starts recovery");
        check(evaluate(p,START+ChargeSessionStore.MAX_SESSION_MS,false,false,true,null).stop==ChargeSessionStore.Stop.LIMIT,"Total eighteen-hour limit also applies while disconnected");
        p=policy();check(evaluate(p,START-1,true,true,false,frame(START-1,1,50)).stop==ChargeSessionStore.Stop.LIMIT,"Monotonic clock reversal fails closed");
        System.out.println("ChargeRecoveryPolicy: "+checks+" checks passed");
    }

    /**
     * The service's order, with the real store: recovery first, then ChargeSessionStore.chargerStopped for a fresh
     * charging=off below 99%. A Bluetooth loss during a charger pause keeps the pause and its original deadline;
     * a cutout first seen after a loss opens one; identity, explicit disconnect and the recovery timeout still end it.
     */
    private static ChargeSessionStore store() throws Exception {
        ChargeSessionStore store=new ChargeSessionStore(java.nio.file.Files.createTempDirectory("charge-recovery-pause-").toFile());
        store.start(PROFILE,ChargeSessionStore.Charger.STOCK,1_800_000_000_000L+START,START);
        check(store.sample(frame(START,1,80)),"Charging before the cutout samples");
        return store;
    }
    /** What updateChargeCapture does with one fresh frame once recovery admits it. */
    private static ChargeSessionStore.ChargerOff step(ChargeRecoveryPolicy p,ChargeSessionStore store,long now,boolean connected,ChargeSessionStore.Frame frame) throws Exception {
        ChargeRecoveryPolicy.Result r=evaluate(p,now,connected,connected,true,frame);
        if(r.stop!=null){store.stop(r.stop);return null;}
        if(r.beganRecovery)store.markGap();
        if(!r.record||frame==null)return null;
        return frame.state.value==0&&frame.soc.value<99?store.chargerStopped(frame.wall,now):null;
    }
    private static void chargerPauseAcrossLoss() throws Exception {
        long off=START+10_000,lost=START+20_000,back=START+30_000;
        // Codex's case 1: an existing charger pause, a loss, the same scooter back still off at 85%.
        ChargeRecoveryPolicy p=policy();ChargeSessionStore store=store();
        check(step(p,store,off,true,frame(off,0,85))==ChargeSessionStore.ChargerOff.PAUSED,"The cutout opens a charger pause");
        check(step(p,store,lost,false,null)==null&&p.recovering()&&store.chargerPaused(),"A loss keeps the charger pause open while recovering");
        check(step(p,store,back,true,frame(back,0,85))==ChargeSessionStore.ChargerOff.WAITING&&store.active()&&store.chargerPaused(),"Back and still off: the same pause keeps waiting");
        long deadline=off+ChargeSessionStore.CHARGER_PAUSE_MAX_MS;
        check(step(p,store,deadline-1000,true,frame(deadline-1000,0,85))==ChargeSessionStore.ChargerOff.WAITING,"The pause's original deadline still applies");
        check(step(p,store,deadline,true,frame(deadline,0,85))==ChargeSessionStore.ChargerOff.ENDED,"…and ends it 30 minutes after the cutout, not after the reconnect");
        store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        ChargeSessionStore.HistorySession ended=store.recordings(PROFILE).get(0);
        check(ended.stop==ChargeSessionStore.Stop.CHARGING_ENDED&&ended.chargerPauses==1,"One charger pause across the loss, ended as CHARGING_ENDED");

        // Codex's case 2: the cutout is first seen after a loss; charging later resumes into the same recording.
        p=policy();store=store();
        check(step(p,store,lost,false,null)==null&&p.recovering(),"A loss while charging starts recovery");
        check(step(p,store,back,true,frame(back,0,85))==ChargeSessionStore.ChargerOff.PAUSED&&!p.recovering()&&store.chargerPaused(),"An off reading after the loss opens a charger pause");
        long resume=back+5*60_000L;
        store.resume(1_800_000_000_000L+resume,resume);
        check(evaluate(p,resume,true,true,false,frame(resume,1,85)).record&&store.sample(frame(resume,1,85))&&!store.paused()&&store.active(),"Charging again resumes the same recording");
        store.stop(ChargeSessionStore.Stop.STOPPED);
        check(store.recordings(PROFILE).get(0).chargerPauses==1,"The post-loss cutout is recorded as a charger pause");

        // The limits recovery enforces still win over waiting for the charger.
        p=policy();store=store();step(p,store,off,true,frame(off,0,85));step(p,store,lost,false,null);
        check(step(p,store,lost+ChargeRecoveryPolicy.RECOVERY_TIMEOUT_MS,false,null)==null&&!store.active()
                &&store.recordings(PROFILE).get(0).stop==ChargeSessionStore.Stop.RECOVERY_TIMEOUT,"Recovery timeout ends a charger-paused recording");
        p=policy();store=store();step(p,store,off,true,frame(off,0,85));step(p,store,lost,false,null);
        ChargeRecoveryPolicy.Result other=p.evaluate(back,PROFILE,SERIAL,7,true,true,true,true,true,"OTHER",frame(back,0,85));
        check(other.stop==ChargeSessionStore.Stop.IDENTITY_CHANGED,"Another scooter never continues a charger pause");
        check(p.evaluate(back,PROFILE,SERIAL,8,true,true,true,true,true,SERIAL,frame(back,0,85)).stop==ChargeSessionStore.Stop.STOPPED,"An explicit disconnect still ends it");
        p=policy();store=store();step(p,store,off,true,frame(off,0,85));step(p,store,lost,false,null);
        check(step(p,store,back,true,frame(lost-500,0,85))==null&&p.recovering(),"A stale pre-loss frame cannot continue the pause");
    }
}
