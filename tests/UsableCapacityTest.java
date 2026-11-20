package com.jjricks.volt;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * usable-capacity-v1: a scooter's usable charge per gauge percent, learned from its own recordings below 80%, shown in
 * place of the rated capacity there; Charge done by keeps the rated time. Every recording here is synthesized.
 */
public final class UsableCapacityTest {
    private static int checks;
    private static final long WALL=1_800_000_000_000L, MONO=1_000_000L;
    private static final int OTHER_MODEL=9999;
    private static final double RATED=12.75;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double expected,Double actual,double tolerance,String message){
        check(actual!=null&&Math.abs(expected-actual)<=tolerance,message+": expected "+expected+", got "+actual);
    }
    private static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("usable-capacity-test-").toFile());}
    private static ChargeSessionStore.Value value(double v,long mono){return new ChargeSessionStore.Value(v,mono,0x72);}

    /**
     * One recording from start to end at a constant current that takes 5.5 minutes a percent, the gauge holding
     * ahPer100 ampere-hours per 100 points; tempC the battery temperature throughout.
     */
    private static void record(ChargeSessionStore store,ChargeSessionStore.Charger charger,int start,int end,double ahPer100,double tempC)throws Exception{
        double amps=amps(ahPer100);
        store.start("one",charger,WALL,MONO);
        long t=0;
        for(int level=start;level<end;level++)for(int i=0;i<33;i++,t+=10000)sample(store,t,level,amps,tempC);
        for(int i=0;i<3;i++,t+=10000)sample(store,t,end,amps,tempC);
        store.stop(ChargeSessionStore.Stop.STOPPED);
    }
    /** The current that charges ahPer100 / 100 ampere-hours in 5.5 minutes. */
    private static double amps(double ahPer100){return ahPer100/100/(5.5/60);}
    private static void sample(ChargeSessionStore store,long t,int level,double amps,double tempC)throws Exception{
        long mono=MONO+t;
        check(store.sample(new ChargeSessionStore.Frame(WALL+t,mono,value(1,mono),value(level,mono),value(47+level/20d,mono),value(-amps,mono),value(tempC,mono))),
                "Synthesized sample saved at "+level+"%");
    }
    private static ChargingEstimate.Result baseline(double soc,Double target,double amps){
        return ChargingEstimate.calculate(new ChargingEstimate.Value(1d,true,100),new ChargingEstimate.Value(47+soc/20,true,100),
                new ChargingEstimate.Value(-amps,true,100),new ChargingEstimate.Value(soc,true,100),new ChargingEstimate.Value(RATED,true,100),
                new ChargingEstimate.Value(target,true,100));
    }
    private static ChargingEstimate.Value live(double v){return new ChargingEstimate.Value(v,true,100);}

    /**
     * Review of #567, round 1: the learned capacity must never reach Charge done by, whatever path set the estimate. The
     * whole forScooter pipeline over many synthesized states (another model and the UT5 MAX, both chargers, no history,
     * a robust curve, a provisional partial one, a complete finish, a held high-speed finish after a power drop, its
     * own taper, references and the pack model, limits below and at 100%, none), with the learned capacity on (forced to
     * 0.85 x rated, inside the band) and off: the planning minutes are identical everywhere, the shown times are never
     * longer, and a shown time changes only where it is the rated current-rate time itself.
     */
    private static void planningNeverChanges(ChargeSessionStore robust,ChargeSessionStore partial)throws Exception{
        ChargeSessionStore finish=store();
        {
            double amps=amps(11.2)*6;
            finish.start("one",ChargeSessionStore.Charger.HIGH_SPEED,WALL,MONO);long t=0;
            for(int level=45;level<100;level++)for(int i=0;i<33;i++,t+=10000)sample(finish,t,level,level<85?amps:amps*Math.max(.15,1-(level-85)/15d),25);
            long mono=MONO+t;
            check(finish.sampleCompletion(new ChargeSessionStore.Frame(WALL+t,mono,value(0,mono),value(100,mono),value(52,mono),value(-.2,mono),value(25,mono))),"Fixture: complete finish");
            finish.stop(ChargeSessionStore.Stop.COMPLETE);
        }
        ChargeSessionStore[] stores={null,robust,partial,finish};
        int[] products={OTHER_MODEL,Ut5MaxStockChargeReference.PRODUCT_ID};
        int states=0,changed=0,held=0;
        for(int product:products)for(ChargeSessionStore.Charger charger:ChargeSessionStore.Charger.values())for(ChargeSessionStore source:stores){
            ChargeSessionStore.Curve curve=null;
            if(source!=null)for(ChargeSessionStore.Curve c:source.snapshot().curves.values())if(c.charger==charger)curve=c;
            if(source!=null&&curve==null)continue;
            double rated=product==OTHER_MODEL?RATED:15.3;
            UsableCapacity.Learned forced=new UsableCapacity.Learned(rated*.85,3,false);
            for(double soc:new double[]{20,35,50,65,79,85})for(Double target:new Double[]{null,60d,80d,95d,100d})for(double factor:new double[]{.6,1,1.6}){
                double amps=amps(11.2)*factor*(charger==ChargeSessionStore.Charger.HIGH_SPEED?6:1);
                for(int pass=0;pass<2;pass++){
                    // pass 1: a high-speed charge whose power drops to 0.6x within the hold after a matched reading.
                    FastChargeTaper taper=null;long now=5_000_000L;double live=amps;
                    if(pass==1){
                        if(charger!=ChargeSessionStore.Charger.HIGH_SPEED)continue;
                        taper=new FastChargeTaper();taper.observe("one",true,47+soc/20,amps,now);
                        ChargingEstimate.forScooter(ChargingEstimate.calculate(live(1),live(47+soc/20),live(-amps),live(soc),live(rated),new ChargingEstimate.Value(target,true,100)),
                                live(soc),live(rated),product,charger,curve,null,"one:test",now,now,null,taper,null);
                        now+=60_000L;live=amps*.6;taper.observe("one",true,47+soc/20,live,now);
                    }
                    ChargingEstimate.Result base=ChargingEstimate.calculate(live(1),live(47+soc/20),live(-live),live(soc),live(rated),new ChargingEstimate.Value(target,true,100));
                    ChargingEstimate.Result off=ChargingEstimate.forScooter(base,live(soc),live(rated),product,charger,curve,new ChargeReferenceGate(),"one:test",now,now,null,taper,null);
                    ChargingEstimate.Result on=ChargingEstimate.withUsableCapacity(off,base,live(soc),live(rated),forced);
                    String where=product+" "+charger+" "+(curve==null?"none":curve.qualifiedSessions)+" at "+soc+"% to "+target+" x"+factor+" pass "+pass+": ";
                    check(java.util.Objects.equals(off.planningMinutesToLimit,on.planningMinutesToLimit)&&java.util.Objects.equals(off.planningMinutesToFull,on.planningMinutesToFull),
                            where+"Charge done by's minutes are identical: "+off.planningMinutesToLimit+"/"+off.planningMinutesToFull+" vs "+on.planningMinutesToLimit+"/"+on.planningMinutesToFull);
                    check(off.learnedToFull==on.learnedToFull&&off.learnedToLimit==on.learnedToLimit&&off.provisional==on.provisional&&off.referenceToFull==on.referenceToFull
                            &&off.stockPackModel==on.stockPackModel&&off.label==null==(on.label==null)&&(off.label==null||off.label.equals(on.label)),where+"Nothing else changes");
                    check(off.remainingMinutesToFull==null?on.remainingMinutesToFull==null:on.remainingMinutesToFull<=off.remainingMinutesToFull+1e-9,where+"Never longer to 100%");
                    check(off.remainingMinutesToLimit==null?on.remainingMinutesToLimit==null:on.remainingMinutesToLimit<=off.remainingMinutesToLimit+1e-9,where+"Never longer to the limit");
                    if(!java.util.Objects.equals(off.remainingMinutesToFull,on.remainingMinutesToFull))
                        check(java.util.Objects.equals(off.remainingMinutesToFull,base.remainingMinutesToFull),where+"Only the rated current-rate time to 100% changes");
                    if(!java.util.Objects.equals(off.remainingMinutesToLimit,on.remainingMinutesToLimit))
                        check(java.util.Objects.equals(off.remainingMinutesToLimit,base.remainingMinutesToLimit),where+"Only the rated current-rate time to the limit changes");
                    if(on!=off)changed++;
                    if(off.status!=null&&off.status.startsWith("Holding the personal finish"))held++;
                    states++;
                }
            }
        }
        check(states>500&&changed>50&&held>0,"The sweep covers many states, changes some shown times and reaches the held finish: "+states+" states, "+changed+" changed, "+held+" held");
        // The review's own case: another model's held high-speed finish with a limit below 100% (withHeldPersonal keeps the
        // limit at the current rate): shown at the learned capacity, planned exactly as without it.
        ChargeSessionStore.Curve fast=finish.snapshot().curves.get(ChargeSessionStore.key("one",ChargeSessionStore.Charger.HIGH_SPEED));
        double amps=amps(11.2)*6;FastChargeTaper taper=new FastChargeTaper();long now=5_000_000L;
        taper.observe("one",true,47+60/20d,amps,now);
        ChargingEstimate.Result first=ChargingEstimate.forScooter(ChargingEstimate.calculate(live(1),live(50),live(-amps),live(60),live(RATED),new ChargingEstimate.Value(70d,true,100)),
                live(60),live(RATED),OTHER_MODEL,ChargeSessionStore.Charger.HIGH_SPEED,fast,null,"one:test",now,now,null,taper,null);
        check(first.learnedToFull,"Fixture: the finish is matched: "+first.status);
        now+=60_000L;taper.observe("one",true,50d,amps*.6,now);
        ChargingEstimate.Result base=ChargingEstimate.calculate(live(1),live(50),live(-amps*.6),live(60),live(RATED),new ChargingEstimate.Value(70d,true,100));
        ChargingEstimate.Result off=ChargingEstimate.forScooter(base,live(60),live(RATED),OTHER_MODEL,ChargeSessionStore.Charger.HIGH_SPEED,fast,null,"one:test",now,now,null,taper,null);
        ChargingEstimate.Result on=ChargingEstimate.withUsableCapacity(off,base,live(60),live(RATED),new UsableCapacity.Learned(11.2,3,false));
        check(off.status.startsWith("Holding the personal finish")&&off.remainingMinutesToLimit.equals(base.remainingMinutesToLimit),"Fixture: held finish, limit at the current rate: "+off.status);
        check(on.remainingMinutesToLimit<off.remainingMinutesToLimit&&on.planningMinutesToLimit.equals(off.planningMinutesToLimit)&&on.planningMinutesToLimit.equals(base.remainingMinutesToLimit),
                "The limit shows the learned capacity; Charge done by plans today's rated time");
    }
    private static double rated(double from,double to,double amps){return RATED*(to-from)/100/amps*60;}

    public static void main(String[] args)throws Exception{
        // combine: too few levels, one recording, agreement, disagreement, an outlier.
        check(UsableCapacity.combine(Collections.emptyList())==null,"No recordings");
        check(UsableCapacity.combine(Collections.singletonList(new double[]{1.0,9}))==null,"Nine levels are too few");
        UsableCapacity.Learned one=UsableCapacity.combine(Collections.singletonList(new double[]{1.12,10}));
        check(one!=null&&one.provisional&&one.recordings==1&&Math.abs(one.ahPer100-11.2)<1e-9,"One recording: provisional");
        UsableCapacity.Learned two=UsableCapacity.combine(Arrays.asList(new double[]{1.10,10},new double[]{1.14,10}));
        check(two!=null&&!two.provisional&&two.recordings==2&&Math.abs(two.ahPer100-11.2)<1e-9,"Two within 5% of their median agree");
        check(UsableCapacity.combine(Arrays.asList(new double[]{1.10,10},new double[]{1.25,10}))==null,"Two that disagree give none");
        check(UsableCapacity.combine(Arrays.asList(new double[]{1.12,10},new double[]{1.02,10}))==null,"Two 9% apart give none");
        check(UsableCapacity.combine(Arrays.asList(new double[]{1.10,10},new double[]{1.155,10}))!=null,"Two 5% apart agree");
        UsableCapacity.Learned outvoted=UsableCapacity.combine(Arrays.asList(new double[]{1.10,10},new double[]{1.13,10},new double[]{1.50,10}));
        check(outvoted!=null&&outvoted.recordings==2&&Math.abs(outvoted.ahPer100-11.15)<1e-9,"A third that disagrees is outvoted");

        // usable: clamp edges and the dead band.
        near(RATED*.8,UsableCapacity.usable(new UsableCapacity.Learned(9,3,false),RATED),1e-9,"Clamped to 0.8 x rated");
        near(RATED*.8,UsableCapacity.usable(new UsableCapacity.Learned(RATED*.8,3,false),RATED),1e-9,"The lower edge itself");
        near(11.2,UsableCapacity.usable(new UsableCapacity.Learned(11.2,3,false),RATED),1e-9,"Inside the band: the learned value");
        check(UsableCapacity.usable(new UsableCapacity.Learned(RATED*.97,3,false),RATED)==null,"At 0.97 x rated: the rated capacity");
        check(UsableCapacity.usable(new UsableCapacity.Learned(RATED*1.1,3,false),RATED)==null,"Above rated: the rated capacity");
        check(UsableCapacity.usable(null,RATED)==null&&UsableCapacity.usable(new UsableCapacity.Learned(11.2,3,false),0)==null,"Nothing to use");

        // A scooter whose gauge holds 11.2 Ah (rated 12.75), three recordings from 30% to 80%: learned, robust.
        ChargeSessionStore store=store();
        for(int i=0;i<3;i++)record(store,ChargeSessionStore.Charger.STOCK,30,80,11.2,25);
        ChargeSessionStore.Snapshot snapshot=store.snapshot();
        UsableCapacity.Learned learned=snapshot.usableCapacity.get("one");
        check(learned!=null&&!learned.provisional&&learned.recordings==3,"Three recordings agree");
        near(11.2,learned.ahPer100,.05,"Learned usable capacity");
        double amps=amps(11.2);

        // The current rate below 80% at the learned capacity; above it rated; Charge done by plans the rated time.
        ChargingEstimate.Result before=baseline(40,80d,amps);
        ChargingEstimate.Result after=ChargingEstimate.withUsableCapacity(before,before,live(40),live(RATED),learned);
        near(40*5.5,after.remainingMinutesToLimit,1,"40% to 80% at 5.5 minutes a percent");
        near(rated(40,80,amps),before.remainingMinutesToLimit,1e-9,"Fixture: the rated time is about 14% longer");
        near(40*5.5+rated(80,100,amps),after.remainingMinutesToFull,1,"To 100%: learned to 80%, rated above");
        near(before.remainingMinutesToLimit,after.planningMinutesToLimit,1e-9,"Charge done by plans the rated time to the limit");
        near(before.remainingMinutesToFull,after.planningMinutesToFull,1e-9,"... and to 100%");
        check(after.planningMinutesToLimit>=after.remainingMinutesToLimit,"Never earlier than today's plan");
        check(after.label.equals(before.label)&&after.status.startsWith(before.status)
                &&after.status.contains("Below 80% the current rate uses the charge this battery's gauge took per percent in 3 past charges (11.2 Ah per 100%)")
                &&after.status.contains("Charge done by plans with the rated capacity."),"Same label; the status says what was used: "+after.status);
        ChargeSessionStore.Estimate saved=ChargingEstimate.recordingSnapshot(after);
        check(saved.fullSource==ChargeSessionStore.Source.CURRENT_RATE&&saved.learnedSessions==0,"Recorded as the current rate");
        // No limit (a charge alert or a scooter without one): the time to 100% only.
        ChargingEstimate.Result noLimit=ChargingEstimate.withUsableCapacity(baseline(40,null,amps),baseline(40,null,amps),live(40),live(RATED),learned);
        check(noLimit.remainingMinutesToLimit==null,"No limit stays none");
        near(40*5.5+rated(80,100,amps),noLimit.remainingMinutesToFull,1,"Time to 100% with no limit");
        // At or above 80%: nothing changes (the taper's cushion).
        for(double level:new double[]{80,85,99}){
            ChargingEstimate.Result high=baseline(level,100d,amps);
            check(ChargingEstimate.withUsableCapacity(high,high,live(level),live(RATED),learned)==high,"Unchanged at "+level+"%");
        }
        // Low current, no learned value, or one near the rating: unchanged.
        ChargingEstimate.Result low=baseline(40,80d,.01);
        check(ChargingEstimate.withUsableCapacity(low,low,live(40),live(RATED),learned)==low,"Low current: unchanged");
        check(ChargingEstimate.withUsableCapacity(before,before,live(40),live(RATED),null)==before,"No learned value: unchanged");
        check(ChargingEstimate.withUsableCapacity(before,before,live(40),live(RATED),new UsableCapacity.Learned(RATED*.98,3,false))==before,"A gauge holding its rating: unchanged");

        // Through the whole estimate (another model, stock charger, its learned curve): the curve covers 31–80%, so the
        // time to the 80% limit is the curve's; 100% falls back to the current rate, now at the learned capacity to 80%.
        ChargeSessionStore.Curve curve=snapshot.curves.get(ChargeSessionStore.key("one",ChargeSessionStore.Charger.STOCK));
        ChargingEstimate.Result today=ChargingEstimate.forScooter(before,live(40),live(RATED),OTHER_MODEL,ChargeSessionStore.Charger.STOCK,curve);
        ChargingEstimate.Result shown=ChargingEstimate.withUsableCapacity(today,before,live(40),live(RATED),learned);
        check(shown.learnedToLimit&&today.learnedToLimit,"A learned curve covers the limit");
        near(today.remainingMinutesToLimit,shown.remainingMinutesToLimit,1e-9,"No effect where a learned curve covers the range");
        near(today.planningMinutesToLimit,shown.planningMinutesToLimit,1e-9,"... nor on Charge done by");
        near(after.remainingMinutesToFull,shown.remainingMinutesToFull,1e-9,"The uncovered time to 100% uses the learned capacity");
        near(before.remainingMinutesToFull,shown.planningMinutesToFull,1e-9,"... and plans the rated time");
        // Charge done by plans with the learned curve's time to the limit here: the status no longer says it plans with the
        // rated capacity (#567 review, F01), and still says what the shown time to 100% used.
        check(shown.status.contains("(11.2 Ah per 100%).")&&!shown.status.contains("Charge done by plans with the rated capacity."),
                "A learned limit: the status doesn't name the rated capacity as the plan: "+shown.status);
        // A provisional fill-in (stitched estimate) plans with the longer of it and the rated time, never the learned one.
        ChargeSessionStore partial=store();record(partial,ChargeSessionStore.Charger.STOCK,30,60,11.2,25);
        UsableCapacity.Learned single=partial.snapshot().usableCapacity.get("one");
        check(single!=null&&single.provisional&&single.recordings==1,"One recording: provisional");
        ChargingEstimate.Result fillBase=baseline(40,55d,amps);
        ChargingEstimate.Result fill=ChargingEstimate.withUsableCapacity(ChargingEstimate.forScooter(fillBase,
                live(40),live(RATED),OTHER_MODEL,ChargeSessionStore.Charger.STOCK,partial.snapshot().curves.get(ChargeSessionStore.key("one",ChargeSessionStore.Charger.STOCK))),
                fillBase,live(40),live(RATED),single);
        check(fill.provisional&&fill.planningMinutesToLimit>=rated(40,55,amps)-1e-9,"A provisional fill-in plans no earlier than the rated time: "+fill.planningMinutesToLimit);
        check(!fill.learnedToFull&&fill.planningMinutesToFull>=rated(40,100,amps)-1e-9&&fill.remainingMinutesToFull<fill.planningMinutesToFull,
                "Its uncovered time to 100% shows the learned capacity and plans the rated one");
        boolean ratedFill=fill.planningMinutesToLimit.equals(fillBase.remainingMinutesToLimit)&&fill.planningMinutesToFull.equals(fillBase.remainingMinutesToFull);
        check(fill.status.contains("Charge done by plans with the rated capacity.")==ratedFill,
                "A provisional fill-in names the rated capacity as the plan only where it plans with the rated time ("+fill.planningMinutesToLimit+"): "+fill.status);

        // Disagreeing recordings teach nothing; nor do recordings with fewer than ten counted levels.
        ChargeSessionStore split=store();record(split,ChargeSessionStore.Charger.STOCK,30,80,11.2,25);record(split,ChargeSessionStore.Charger.STOCK,30,80,10.2,25);
        check(split.snapshot().usableCapacity.get("one")==null,"Two recordings 9% apart teach nothing");
        // A fast charge with the battery hot: its steps don't count (as for the learned curve); the stock charger's do.
        ChargeSessionStore hot=store();record(hot,ChargeSessionStore.Charger.HIGH_SPEED,30,80,11.2,42);
        check(hot.snapshot().usableCapacity.get("one")==null,"A hot fast charge teaches no capacity");
        ChargeSessionStore hotStock=store();record(hotStock,ChargeSessionStore.Charger.STOCK,30,80,11.2,42);
        check(hotStock.snapshot().usableCapacity.get("one")!=null,"A hot stock charge does");
        // Steps at and above 80% never count: a recording from 75% to 95% has five usable levels at most.
        ChargeSessionStore top=store();record(top,ChargeSessionStore.Charger.STOCK,75,95,11.2,25);
        check(top.snapshot().usableCapacity.get("one")==null,"Above 80% teaches nothing");
        // A gauge that holds its rating: learned, and the estimate stays exactly the rated one.
        ChargeSessionStore full=store();for(int i=0;i<2;i++)record(full,ChargeSessionStore.Charger.STOCK,30,80,RATED,25);
        UsableCapacity.Learned whole=full.snapshot().usableCapacity.get("one");
        near(RATED,whole.ahPer100,.06,"A full gauge learns its rating");
        ChargingEstimate.Result ratedBaseline=baseline(40,80d,amps(RATED));
        check(ChargingEstimate.withUsableCapacity(ratedBaseline,ratedBaseline,live(40),live(RATED),whole)==ratedBaseline,"... and changes nothing");
        planningNeverChanges(store,partial);
        System.out.println("Usable capacity tests passed: "+checks+" checks");
    }
}
