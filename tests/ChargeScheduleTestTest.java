package com.jjricks.volt;

import java.time.Instant;

public final class ChargeScheduleTestTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static final long NOW=Instant.parse("2026-10-20T05:00:00Z").toEpochMilli(),START=NOW+120_000;
    private static final Integer ON=1,OFF=0;
    /** Armed five seconds after the button, charging until the pause. */
    private static ChargeScheduleTest armed(){ChargeScheduleTest t=new ChargeScheduleTest(NOW);t.armed(NOW+5000);return t;}
    private static ChargeScheduleTest paused(){ChargeScheduleTest t=armed();check(t.observe(NOW+10_000,ON,50d,100d)==ChargeScheduleTest.Outcome.NONE,"still charging just after ON");
        check(t.observe(NOW+20_000,OFF,50d,100d)==ChargeScheduleTest.Outcome.NONE&&t.paused()&&t.phase()==ChargeScheduleTest.Phase.WAITING,"a 0 after ON is the pause");return t;}
    public static void main(String[] args){
        check(ChargeScheduleTest.start(NOW)==START,"a whole minute two minutes ahead stays");
        check(ChargeScheduleTest.start(NOW+1)==START+60_000,"a second past rounds up to the next whole minute");
        check(ChargeScheduleTest.start(NOW+59_999)==START+60_000,"never less than two minutes of lead");
        ChargeScheduleTest arming=new ChargeScheduleTest(NOW);
        check(arming.phase()==ChargeScheduleTest.Phase.ARMING&&arming.observe(NOW+300_000,ON,50d,100d)==ChargeScheduleTest.Outcome.NONE,"nothing is judged before ON is confirmed");
        check(armed().phase()==ChargeScheduleTest.Phase.PAUSING,"armed, waiting for the pause");

        ChargeScheduleTest pass=paused();check(pass.observe(START-1000,OFF,50d,100d)==ChargeScheduleTest.Outcome.NONE,"paused before the start");
        check(pass.observe(START+7_400,ON,50d,100d)==ChargeScheduleTest.Outcome.PASSED&&pass.offsetSeconds()==7,"resume 7 s after the start passes, offset rounded");
        check(pass.observe(START+600_000,OFF,50d,100d)==ChargeScheduleTest.Outcome.PASSED,"a verdict is final");
        check(paused().observe(START-30_000,ON,50d,100d)==ChargeScheduleTest.Outcome.PASSED,"30 s early is inside the pass window");
        ChargeScheduleTest late=paused();check(late.observe(START+90_000,ON,50d,100d)==ChargeScheduleTest.Outcome.PASSED,"90 s late is inside the pass window");
        ChargeScheduleTest behind=paused();check(behind.observe(START+180_000,ON,50d,100d)==ChargeScheduleTest.Outcome.RESUMED_LATE&&behind.offsetSeconds()==180,"a resume after 90 s is late, with its offset");
        ChargeScheduleTest early=paused();check(early.observe(START-31_000,ON,50d,100d)==ChargeScheduleTest.Outcome.RESUMED_EARLY&&early.offsetSeconds()==-31,"a resume before start - 30 s is early, negative offset");

        ChargeScheduleTest noPause=armed();check(noPause.observe(NOW+64_999,ON,50d,100d)==ChargeScheduleTest.Outcome.NONE,"charging inside the 60 s window is not yet a verdict");
        check(noPause.observe(NOW+65_000,ON,50d,100d)==ChargeScheduleTest.Outcome.NO_PAUSE&&!noPause.paused()&&noPause.offsetSeconds()==null,"charging 60 s after ON: never paused");
        ChargeScheduleTest latePause=armed();latePause.observe(NOW+30_000,null,50d,100d);
        check(latePause.observe(NOW+70_000,OFF,50d,100d)==ChargeScheduleTest.Outcome.NONE&&latePause.paused(),"a first reading after the window that is 0 still shows the pause");

        ChargeScheduleTest stuck=paused();for(long t=NOW+30_000;t<START+300_000;t+=20_000)check(stuck.observe(t,OFF,50d,100d)==ChargeScheduleTest.Outcome.NONE,"still paused, still waiting");
        check(stuck.observe(START+300_000,OFF,50d,100d)==ChargeScheduleTest.Outcome.NO_RESUME&&stuck.paused()&&stuck.offsetSeconds()==null,"still paused 5 min after the start: no resume");

        ChargeScheduleTest stale=paused();check(stale.observe(NOW+50_000,null,50d,100d)==ChargeScheduleTest.Outcome.NONE,"a short gap in readings is tolerated");
        check(stale.observe(NOW+50_001,null,null,null)==ChargeScheduleTest.Outcome.INTERRUPTED,"no fresh charging reading for 30 s ends without a verdict");
        check(paused().observe(START,OFF,100d,100d)==ChargeScheduleTest.Outcome.LIMIT_REACHED,"at the charge limit the charger stops by itself: no verdict on the clock");
        check(paused().observe(NOW-1,OFF,50d,100d)==ChargeScheduleTest.Outcome.INTERRUPTED,"a phone clock set backwards ends without a verdict");
        ChargeScheduleTest cancel=paused();check(cancel.stop(ChargeScheduleTest.Outcome.CANCELLED)==ChargeScheduleTest.Outcome.CANCELLED&&cancel.observe(START,ON,50d,100d)==ChargeScheduleTest.Outcome.CANCELLED,"cancel is final");
        ChargeScheduleTest done=paused();done.observe(START,ON,50d,100d);check(done.stop(ChargeScheduleTest.Outcome.INTERRUPTED)==ChargeScheduleTest.Outcome.PASSED,"a verdict already in survives a later stop");
        ChargeScheduleTest ending=paused();ending.observe(START,ON,50d,100d);ending.ending();check(ending.phase()==ChargeScheduleTest.Phase.ENDING,"cleanup phase");
        System.out.println("ChargeScheduleTestTest: "+checks+" assertions passed");
    }
}
