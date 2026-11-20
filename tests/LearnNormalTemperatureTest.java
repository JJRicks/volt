package com.jjricks.volt;

import java.nio.file.Files;
import java.util.function.DoubleUnaryOperator;

/**
 * normal-temps-v1 (owner, Nov 19 2026): on every model, a fast charger's percents recorded with the battery outside
 * 20–38 C (68–100 F) teach no learned curve; the stock charger always learns; no temperature excludes nothing; the
 * recording itself stays whole. Also the held finish after #562's review (round 2): a completed charge's time to 100%
 * is still held through a high-speed power dropout when only the time to the limit is provisional. Every recording
 * here is synthesized.
 */
public final class LearnNormalTemperatureTest {
    private static int checks;
    private static final long WALL=1_800_000_000_000L, MONO=1_000_000L;
    private static final int OTHER_MODEL=9999;
    private static final double RATED_AH=20;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}

    /** About 450 W in bulk (10.7 A near 42 V, 1.1 minutes a percent), then a taper from 80%. */
    private static double amps(double soc){return soc<80?10.7:10.7*Math.max(.12,1-(soc-80)/22);}
    private static double volts(double soc){return 38.5+4.5*Math.min(soc,85)/100;}
    private static double minutes(int level){return RATED_AH*.01/amps(level)*60;}

    private static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("learn-temps-test-").toFile());}
    private static ChargeSessionStore.Value value(Double v,long mono){return new ChargeSessionStore.Value(v,mono,0x72);}

    /** One recording from start to end; tempC gives the battery temperature by level (null: none reported). Returns its samples. */
    private static int record(ChargeSessionStore store,ChargeSessionStore.Charger charger,int start,int end,DoubleUnaryOperator tempC)throws Exception{
        store.start("one",charger,WALL,MONO);
        long t=0;int saved=0;
        for(int level=start;level<end;level++){
            long span=(long)(minutes(level)*60000);
            for(long at=0;at<span;at+=10000,t+=10000)saved+=sample(store,t,level,false,tempC);
        }
        if(end==100)saved+=sample(store,t,100,true,tempC);
        else for(int i=0;i<3;i++,t+=10000)saved+=sample(store,t,end,false,tempC);
        store.stop(end==100?ChargeSessionStore.Stop.COMPLETE:ChargeSessionStore.Stop.STOPPED);
        return saved;
    }
    private static int sample(ChargeSessionStore store,long t,int level,boolean completion,DoubleUnaryOperator tempC)throws Exception{
        long mono=MONO+t;
        Double temp=tempC==null?null:Double.valueOf(tempC.applyAsDouble(level));
        ChargeSessionStore.Frame frame=new ChargeSessionStore.Frame(WALL+t,mono,value(completion?0d:1d,mono),value((double)level,mono),
                value(volts(level),mono),value(-amps(Math.min(level,99)),mono),temp==null?null:value(temp,mono));
        boolean ok=completion?store.sampleCompletion(frame):store.sample(frame);
        check(ok,"Synthesized sample saved at "+level+"%");
        return 1;
    }
    private static ChargeSessionStore.Curve curve(ChargeSessionStore store,ChargeSessionStore.Charger charger)throws Exception{
        return store.snapshot().curves.get(ChargeSessionStore.key("one",charger));
    }
    private static ChargeSessionStore.Summary.Coverage coverage(ChargeSessionStore.Curve curve,int percent){return curve.summary().bins.get(percent).coverage;}
    private static ChargingEstimate.Result baseline(double level,Double target,double power){
        return ChargingEstimate.calculate(new ChargingEstimate.Value(1d,true,100),new ChargingEstimate.Value(volts(level),true,100),
                new ChargingEstimate.Value(-amps(level)*power,true,100),new ChargingEstimate.Value(level,true,100),
                new ChargingEstimate.Value(RATED_AH,true,100),new ChargingEstimate.Value(target,true,100));
    }
    private static ChargingEstimate.Result withTaper(ChargeSessionStore.Curve curve,double level,Double target,double power,FastChargeTaper taper,long now){
        return ChargingEstimate.forScooter(baseline(level,target,power),new ChargingEstimate.Value(level,true,100),
                new ChargingEstimate.Value(RATED_AH,true,100),OTHER_MODEL,curve.charger,curve,null,"one:test",now,now,null,taper);
    }

    public static void main(String[] args)throws Exception{
        ChargeSessionStore.Charger fast=ChargeSessionStore.Charger.HIGH_SPEED,stock=ChargeSessionStore.Charger.STOCK;
        // The window, inclusive, and who it applies to.
        check(ChargeSessionStore.learnsAtTemperature(fast,20d)&&ChargeSessionStore.learnsAtTemperature(fast,38d)
                &&!ChargeSessionStore.learnsAtTemperature(fast,19.9)&&!ChargeSessionStore.learnsAtTemperature(fast,38.1),"20–38 C inclusive on the fast charger");
        check(ChargeSessionStore.learnsAtTemperature(fast,null)&&ChargeSessionStore.learnsAtTemperature(fast,Double.NaN),"No temperature excludes nothing");
        check(ChargeSessionStore.learnsAtTemperature(stock,-5d)&&ChargeSessionStore.learnsAtTemperature(stock,45d),"The stock charger always learns");

        // A cold start on the fast charger: 15 C below 30%, 25 C from there. The cold percents teach nothing; the file stays whole.
        DoubleUnaryOperator coldStart=level->level<30?15:25;
        ChargeSessionStore cold=store();int saved=record(cold,fast,17,60,coldStart);
        ChargeSessionStore.Curve coldCurve=curve(cold,fast);
        check(coldCurve!=null&&coldCurve.qualifiedSessions==1,"The recording still qualifies");
        for(int percent=18;percent<30;percent++){
            ChargeSessionStore.Summary.Bin bin=coldCurve.summary().bins.get(percent);
            check(bin.coverage==ChargeSessionStore.Summary.Coverage.NONE&&bin.minutes==null&&bin.watts==null,"Cold "+percent+"% teaches no minutes or power");
        }
        // 29% ends on the first 30% reading, the first one at 25 C: it stays out. 30% onwards learns.
        for(int percent=30;percent<60;percent++)check(coverage(coldCurve,percent)==ChargeSessionStore.Summary.Coverage.PROVISIONAL,"Normal "+percent+"% learns");
        check(cold.recordings("one").get(0).points.size()==saved,"Every sample stays in the recording: "+saved);
        check(cold.recordings("one").get(0).points.get(0).temperature==15,"Including the cold ones, as recorded");
        ChargingEstimate.Result coldEstimate=ChargingEstimate.forScooter(baseline(20,40d,1),new ChargingEstimate.Value(20d,true,100),
                new ChargingEstimate.Value(RATED_AH,true,100),OTHER_MODEL,fast,coldCurve);
        check(!coldEstimate.learnedToLimit&&!coldEstimate.provisional,"No learned time across the cold percents: "+coldEstimate.status);
        ChargingEstimate.Result warmEstimate=ChargingEstimate.forScooter(baseline(35,55d,1),new ChargingEstimate.Value(35d,true,100),
                new ChargingEstimate.Value(RATED_AH,true,100),OTHER_MODEL,fast,coldCurve);
        check(warmEstimate.learnedToLimit,"The normal percents still give a time: "+warmEstimate.status);

        // The same recording on the stock charger, and one with no temperature at all: everything learns.
        ChargeSessionStore stockCold=store();record(stockCold,stock,17,60,coldStart);
        ChargeSessionStore none=store();record(none,fast,17,60,null);
        for(int percent=18;percent<60;percent++){
            check(coverage(curve(stockCold,stock),percent)==ChargeSessionStore.Summary.Coverage.PROVISIONAL,"Stock learns at "+percent+"%");
            check(coverage(curve(none,fast),percent)==ChargeSessionStore.Summary.Coverage.PROVISIONAL,"No temperature learns at "+percent+"%");
        }

        // A fast recording hot throughout still qualifies (it keeps its place in the folder) but teaches nothing.
        ChargeSessionStore hot=store();record(hot,fast,17,60,level->45);
        ChargeSessionStore.Curve hotCurve=curve(hot,fast);
        check(hotCurve!=null&&hotCurve.qualifiedSessions==1,"A hot recording still qualifies");
        boolean any=false;for(ChargeSessionStore.Summary.Bin bin:hotCurve.summary().bins)any|=bin.coverage!=ChargeSessionStore.Summary.Coverage.NONE;
        check(!any,"... and teaches no percent");

        // A complete fast charge hot over 50–60%: its finish starts after the hot readings; the percents around them teach nothing.
        ChargeSessionStore warmFinish=store();record(warmFinish,fast,45,100,level->level>=50&&level<=60?40:30);
        ChargeSessionStore.Curve finish=curve(warmFinish,fast);
        check(finish.summary().finishStarts.size()==1&&finish.summary().finishStarts.get(0)==62,"The finish starts at the first full percent after the hot readings: "+finish.summary().finishStarts);
        for(int percent=49;percent<=60;percent++)check(coverage(finish,percent)==ChargeSessionStore.Summary.Coverage.NONE,"Hot "+percent+"% teaches nothing");
        check(coverage(finish,48)==ChargeSessionStore.Summary.Coverage.PROVISIONAL&&coverage(finish,61)==ChargeSessionStore.Summary.Coverage.PROVISIONAL,"Either side learns");
        check(!finish.summary().bins.get(55).finishCovered&&finish.summary().bins.get(62).finishCovered,"Finish coverage only after the hot readings");
        check(finish.estimateFinishing(55,volts(55)*amps(55),volts(55))==null&&finish.estimateFinishing(70,volts(70)*amps(70),volts(70))!=null,
                "No matched finish inside the hot stretch, one after it");
        // Hot at the very end: no finish at all, though the recording qualifies.
        ChargeSessionStore hotEnd=store();record(hotEnd,fast,45,100,level->level>=95?40:30);
        check(curve(hotEnd,fast).summary().finishStarts.isEmpty()&&curve(hotEnd,fast).qualifiedSessions==1,"A finish hot at its end teaches no finish");
        // On the stock charger the same heat changes nothing.
        ChargeSessionStore stockFinish=store();record(stockFinish,stock,45,100,level->level>=50&&level<=60?40:30);
        check(curve(stockFinish,stock).summary().finishStarts.get(0)==46,"A stock finish keeps its whole range");

        // Two agreeing fast recordings, one cold below 40%: the robust average covers only where both learned.
        ChargeSessionStore pair=store();record(pair,fast,17,60,level->25);record(pair,fast,17,60,level->level<40?10:25);
        ChargeSessionStore.Curve pairCurve=curve(pair,fast);
        check(coverage(pairCurve,30)==ChargeSessionStore.Summary.Coverage.PROVISIONAL&&coverage(pairCurve,45)==ChargeSessionStore.Summary.Coverage.ROBUST,
                "The cold recording's percents don't count toward the average");
        check(pairCurve.estimate(30,50)==null&&pairCurve.estimate(41,50)!=null,"The robust estimate needs two normal recordings per percent");

        // #562 r2: a completed charge's finish is held through a power dropout even when only the time to the limit is
        // provisional. One complete 45–100% fast charge: at 60% with an 80% limit, the finish is matched (completed) and
        // the limit provisional; power then drops to 0.6x within the hold and the held finish keeps counting down.
        ChargeSessionStore heldStore=store();record(heldStore,fast,45,100,level->30);
        ChargeSessionStore.Curve heldCurve=curve(heldStore,fast);
        FastChargeTaper taper=new FastChargeTaper();long now=5_000_000L;
        taper.observe("one",true,volts(60),amps(60),now);
        ChargingEstimate.Result matched=withTaper(heldCurve,60,80d,1,taper,now);
        check(matched.learnedToFull&&matched.learnedToLimit&&matched.provisional,"Fixture: matched finish, provisional limit: "+matched.status);
        check(Math.abs(matched.planningMinutesToFull-matched.remainingMinutesToFull)<1e-9,"A completed charge's time to 100% plans as itself");
        for(long later:new long[]{10_000L,FastChargeTaper.HOLD_MS-10_000L}){
            taper.observe("one",true,volts(60),amps(60)*.6,now+later);
            ChargingEstimate.Result dropped=withTaper(heldCurve,60,80d,.6,taper,now+later);
            check(dropped.learnedToFull&&dropped.status.startsWith("Holding the personal finish estimate from 1 completed high-speed charge"),
                    "After "+later+" ms at 0.6x power the completed finish is held: "+dropped.status);
            check(Math.abs(dropped.remainingMinutesToFull-(matched.remainingMinutesToFull-later/60000d))<1e-6,"... and counts down");
        }
        // The same at 100% (no limit below it): nothing provisional, held as before.
        FastChargeTaper full=new FastChargeTaper();full.observe("one",true,volts(60),amps(60),now);
        check(!withTaper(heldCurve,60,100d,1,full,now).provisional,"Fixture: a 100% limit is the matched finish");
        full.observe("one",true,volts(60),amps(60)*.6,now+10_000);
        check(withTaper(heldCurve,60,100d,.6,full,now+10_000).learnedToFull,"Held at a 100% limit too");
        System.out.println("Learn-normal-temperature tests passed: "+checks+" checks");
    }
}
