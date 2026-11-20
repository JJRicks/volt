package com.jjricks.volt;

import java.nio.file.Files;
import java.util.function.DoubleUnaryOperator;

/**
 * stitched-estimate-v1: partial recordings join, and a percent one recording covers counts provisionally. Every
 * recording here is synthesized from two charger models: a stock-like one (about 100 W, a long taper from 85%) and a
 * high-speed-like one (about 450 W, a taper from 80%). No real recording is used.
 */
public final class StitchedChargeEstimateTest {
    private static int checks;
    private static final long WALL=1_800_000_000_000L, MONO=1_000_000L;
    private static final int OTHER_MODEL=9999;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double expected,Double actual,double tolerance,String message){
        check(actual!=null&&Math.abs(expected-actual)<=tolerance,message+": expected "+expected+", got "+actual);
    }

    /** A charger model: amps and pack volts by battery level, and the pack's rated Ah. */
    private static final class Model {
        final double ratedAh;final DoubleUnaryOperator amps,volts;
        Model(double ratedAh,DoubleUnaryOperator amps,DoubleUnaryOperator volts){this.ratedAh=ratedAh;this.amps=amps;this.volts=volts;}
        /** Minutes the percent from level to level + 1 takes at this model's current. */
        double minutes(int level){return ratedAh*.01/amps.applyAsDouble(level)*60;}
        double watts(double level){return amps.applyAsDouble(level)*volts.applyAsDouble(level);}
    }
    /** About 100 W in bulk (2.4 A near 41 V, 2.5 minutes a percent), then a long taper from 85%. */
    private static final Model STOCK=new Model(10,soc->soc<85?2.4:2.4*Math.max(.18,1-(soc-85)/18),soc->38+4*Math.min(soc,90)/100);
    /** About 450 W in bulk (10.7 A near 42 V, 1.1 minutes a percent), then a taper from 80%. */
    private static final Model FAST=new Model(20,soc->soc<80?10.7:10.7*Math.max(.12,1-(soc-80)/22),soc->38.5+4.5*Math.min(soc,85)/100);

    private static ChargeSessionStore store()throws Exception{return new ChargeSessionStore(Files.createTempDirectory("stitched-charge-test-").toFile());}
    private static ChargeSessionStore.Value value(double v,long mono){return new ChargeSessionStore.Value(v,mono,0x72);}

    /**
     * One recording from start to end, each percent taking the model's minutes times slower, sampled every ten seconds.
     * complete: it ends with the scooter's own 100% (stop COMPLETE); otherwise it stops at end (STOPPED).
     */
    private static void record(ChargeSessionStore store,ChargeSessionStore.Charger charger,Model model,int start,int end,double slower)throws Exception{
        store.start("one",charger,WALL,MONO);
        long t=0;
        for(int level=start;level<end;level++){
            long span=(long)(model.minutes(level)*slower*60000);
            for(long at=0;at<span;at+=10000,t+=10000)sample(store,model,t,level,false);
        }
        boolean complete=end==100;
        if(complete)sample(store,model,t,100,true);
        else for(int i=0;i<3;i++,t+=10000)sample(store,model,t,end,false);
        store.stop(complete?ChargeSessionStore.Stop.COMPLETE:ChargeSessionStore.Stop.STOPPED);
    }
    private static void sample(ChargeSessionStore store,Model model,long t,int level,boolean completion)throws Exception{
        long mono=MONO+t;double amps=model.amps.applyAsDouble(Math.min(level,99)),volts=model.volts.applyAsDouble(level);
        ChargeSessionStore.Frame frame=new ChargeSessionStore.Frame(WALL+t,mono,value(completion?0:1,mono),value(level,mono),value(volts,mono),value(-amps,mono),value(23,mono));
        check(completion?store.sampleCompletion(frame):store.sample(frame),"Synthesized sample saved at "+level+"%");
    }
    private static ChargeSessionStore.Curve curve(ChargeSessionStore store,ChargeSessionStore.Charger charger)throws Exception{
        return store.snapshot().curves.get(ChargeSessionStore.key("one",charger));
    }
    /** The live reading at level on the model's charger, scaled by power (1: the recorded power). */
    private static ChargingEstimate.Result baseline(Model model,double level,Double target,double power){
        double volts=model.volts.applyAsDouble(level),amps=model.amps.applyAsDouble(level)*power;
        return ChargingEstimate.calculate(new ChargingEstimate.Value(1d,true,100),new ChargingEstimate.Value(volts,true,100),
                new ChargingEstimate.Value(-amps,true,100),new ChargingEstimate.Value(level,true,100),
                new ChargingEstimate.Value(model.ratedAh,true,100),new ChargingEstimate.Value(target,true,100));
    }
    private static ChargingEstimate.Result estimate(ChargeSessionStore.Curve curve,Model model,double level,Double target,double power,int product){
        return ChargingEstimate.forScooter(baseline(model,level,target,power),new ChargingEstimate.Value(level,true,100),
                new ChargingEstimate.Value(model.ratedAh,true,100),product,curve.charger,curve);
    }
    /** As ScooterService passes it for a high-speed charge it is tracking: the 12-argument forScooter with its taper state. */
    private static ChargingEstimate.Result withTaper(ChargeSessionStore.Curve curve,double level,double power,FastChargeTaper taper,long now){
        return ChargingEstimate.forScooter(baseline(FAST,level,100d,power),new ChargingEstimate.Value(level,true,100),
                new ChargingEstimate.Value(FAST.ratedAh,true,100),OTHER_MODEL,curve.charger,curve,null,"one:test",now,now,null,taper);
    }
    private static ChargingEstimate.Result estimate(ChargeSessionStore.Curve curve,Model model,double level,Double target){
        return estimate(curve,model,level,target,1,OTHER_MODEL);
    }
    /** Minutes from from to to as recorded: each percent lasts whole ten-second samples. */
    private static double minutes(Model model,int from,int to){
        double sum=0;for(int level=from;level<to;level++)sum+=Math.ceil((long)(model.minutes(level)*60000)/10000d)/6;return sum;
    }

    /** The owner's example: one recording 17–50%, one complete 45–100%. */
    private static void ownersExample(ChargeSessionStore.Charger charger,Model model)throws Exception{
        ChargeSessionStore store=store();
        record(store,charger,model,17,50,1);
        record(store,charger,model,45,100,1);
        ChargeSessionStore.Curve curve=curve(store,charger);
        String name=charger.name();
        check(curve.qualifiedSessions==2,name+": both recordings qualify");
        // The finish recording starts at its first full percent, 46%: 30–46% from the partial one, then the finish's own time.
        double own=minutes(model,46,100);
        ChargingEstimate.Result at30=estimate(curve,model,30,100d);
        near(minutes(model,30,46)+own,at30.remainingMinutesToFull,.5,name+": 30% joins 30–46% to the finish recording's own time");
        check(at30.learnedToFull&&at30.provisional&&at30.learnedSessions==2,name+": the join is provisional and uses both recordings: "+at30.status);
        check(at30.status.startsWith("Provisional estimate from 2 past ")&&at30.status.contains("up to 46%, then a completed charge's own time from there")
                &&at30.status.contains("Charge done by plans with the longer"),name+": the status says what was joined: "+at30.status);
        check("From 2 past charges".equals(at30.label),name+": the existing past-charges label: "+at30.label);
        check(at30.remainingMinutesToLimit.equals(at30.remainingMinutesToFull),name+": a 100% limit is the same time");
        // Before stitched-estimate-v1 there was no learned estimate at 30%.
        ChargingEstimate.Result before=ChargingEstimate.withObservedCurve(baseline(model,30,100d,1),new ChargingEstimate.Value(30d,true,100),curve,false);
        check(!before.learnedToFull,name+": without provisional evidence 30% had none");
        // 47% and 80% lie inside the finish recording: its own matched finish, as before (not provisional).
        for(int level:new int[]{47,80}){
            ChargingEstimate.Result inside=estimate(curve,model,level,100d);
            check(inside.learnedToFull&&!inside.provisional&&inside.status.contains("completed"),name+": "+level+"% uses the finish recording: "+inside.status);
            near(minutes(model,level,100),inside.remainingMinutesToFull,model.minutes(level)+.5,name+": "+level+"% finish time");
        }
        // The limit target: 30–46% joined to the finish recording's own time from 46% to 80%.
        ChargingEstimate.Result limit=estimate(curve,model,30,80d);
        near(minutes(model,30,46)+minutes(model,46,80),limit.remainingMinutesToLimit,.5,name+": the time to an 80% limit joins too");
        check(limit.learnedToLimit&&limit.provisional,name+": provisional time to the limit");
        // A power mismatch at the current level: the provisional guard is the one recording's power there.
        ChargingEstimate.Result mismatch=estimate(curve,model,30,100d,2,OTHER_MODEL);
        check(!mismatch.learnedToFull&&!mismatch.provisional,name+": a power mismatch falls back: "+mismatch.status);
        check(ChargingEstimate.withObservedCurve(baseline(model,30,100d,2),new ChargingEstimate.Value(30d,true,100),curve,true).status.contains("Live power differs"),
                name+": and says why");
        near(baseline(model,30,100d,2).remainingMinutesToFull,mismatch.remainingMinutesToFull,1e-9,name+": to the current rate");
    }

    public static void main(String[] args)throws Exception{
        ownersExample(ChargeSessionStore.Charger.STOCK,STOCK);
        ownersExample(ChargeSessionStore.Charger.HIGH_SPEED,FAST);

        // Council review of #562, finding 1: a provisional finish is never held through a power dropout. High-speed
        // charger, another model, the 17–50% + 45–100% recordings: at 30% with a 100% limit the joined time shows; when
        // live power drops to 0.6x within the hold (FastChargeTaper.HOLD_MS), the estimate falls back to the current rate,
        // so Charge done by plans at least that, and nothing claims completed charges.
        ChargeSessionStore held=store();
        record(held,ChargeSessionStore.Charger.HIGH_SPEED,FAST,17,50,1);record(held,ChargeSessionStore.Charger.HIGH_SPEED,FAST,45,100,1);
        ChargeSessionStore.Curve heldCurve=curve(held,ChargeSessionStore.Charger.HIGH_SPEED);
        FastChargeTaper taper=new FastChargeTaper();
        long now=5_000_000L;
        taper.observe("one",true,FAST.volts.applyAsDouble(30),FAST.amps.applyAsDouble(30),now);
        check(taper.tracking("one"),"Fixture: the taper state is tracking this charge");
        ChargingEstimate.Result joined=withTaper(heldCurve,30,1,taper,now);
        check(joined.learnedToFull&&joined.provisional,"At the recorded power the joined estimate shows: "+joined.status);
        for(long later:new long[]{10_000L,60_000L,FastChargeTaper.HOLD_MS-10_000L}){
            taper.observe("one",true,FAST.volts.applyAsDouble(30),FAST.amps.applyAsDouble(30)*.6,now+later);
            ChargingEstimate.Result dropped=withTaper(heldCurve,30,.6,taper,now+later);
            double rate=baseline(FAST,30,100d,.6).remainingMinutesToFull;
            check(dropped.planningMinutesToLimit!=null&&dropped.planningMinutesToLimit>=rate-1e-9&&dropped.planningMinutesToFull>=rate-1e-9,
                    "After "+later+" ms at 0.6x power, Charge done by plans at least the current rate: "+dropped.planningMinutesToLimit+" vs "+rate);
            check(!dropped.status.contains("completed")&&!dropped.learnedToFull,"Nothing claims completed charges: "+dropped.status);
        }

        // A single recording only: its percents count provisionally to a covered target, never past its end.
        ChargeSessionStore single=store();record(single,ChargeSessionStore.Charger.STOCK,STOCK,20,95,1);
        ChargeSessionStore.Curve one=curve(single,ChargeSessionStore.Charger.STOCK);
        check(one.estimate(30,90)==null,"The robust estimate still needs two recordings");
        ChargingEstimate.Result toLimit=estimate(one,STOCK,30,90d);
        near(minutes(STOCK,30,90),toLimit.remainingMinutesToLimit,.5,"One recording's per-percent times to a 90% limit");
        check(toLimit.learnedToLimit&&!toLimit.learnedToFull&&toLimit.provisional&&toLimit.learnedSessions==1,"Provisional, from one charge: "+toLimit.status);
        check(toLimit.status.startsWith("Provisional time to the limit from 1 past stock charge: its per-percent times."),toLimit.status);
        ChargingEstimate.Result toFull=estimate(one,STOCK,30,100d);
        check(!toFull.learnedToFull&&!toFull.learnedToLimit,"Nothing past the recording's 95%: the current rate");

        // Charge done by plans with the longer of the provisional time and the current rate; the display keeps the provisional time.
        ChargingEstimate.Result slowLive=estimate(one,STOCK,30,90d,.7,OTHER_MODEL);
        double rate=baseline(STOCK,30,90d,.7).remainingMinutesToLimit;
        check(slowLive.provisional&&rate>slowLive.remainingMinutesToLimit,"Fixture: a slower live current gives a longer current-rate time");
        near(rate,slowLive.planningMinutesToLimit,1e-9,"Planning takes the longer current-rate time");
        near(minutes(STOCK,30,90),slowLive.remainingMinutesToLimit,.5,"The shown time stays the provisional one");
        ChargingEstimate.Result tapered=estimate(one,STOCK,80,90d);
        check(tapered.provisional&&tapered.remainingMinutesToLimit>baseline(STOCK,80,90d,1).remainingMinutesToLimit,"Fixture: the recorded taper is longer than the current rate");
        near(tapered.remainingMinutesToLimit,tapered.planningMinutesToLimit,1e-9,"Planning takes the longer provisional time");
        // The same in withObservedCurve directly, and for the time to 100% (owner's example above).
        ChargingEstimate.Result direct=ChargingEstimate.withObservedCurve(baseline(STOCK,30,90d,.7),new ChargingEstimate.Value(30d,true,100),one,true);
        near(rate,direct.planningMinutesToLimit,1e-9,"withObservedCurve plans with the longer time too");
        check(!ChargingEstimate.withObservedCurve(baseline(STOCK,30,90d,1),new ChargingEstimate.Value(30d,true,100),one).learnedToLimit,
                "Its public form keeps today's evidence only");

        // Two agreeing recordings: today's robust average, not provisional. Two strongly disagreeing: none, as today.
        ChargeSessionStore agree=store();record(agree,ChargeSessionStore.Charger.STOCK,STOCK,17,50,1);record(agree,ChargeSessionStore.Charger.STOCK,STOCK,17,50,1.2);
        ChargingEstimate.Result robust=estimate(curve(agree,ChargeSessionStore.Charger.STOCK),STOCK,30,45d);
        check(robust.learnedToLimit&&!robust.provisional&&robust.status.startsWith("Personal estimate from 2 qualified charges."),"Two agreeing: the robust average: "+robust.status);
        near(minutes(STOCK,30,45)*1.1,robust.remainingMinutesToLimit,.5,"Their average");
        ChargeSessionStore disagree=store();record(disagree,ChargeSessionStore.Charger.STOCK,STOCK,17,50,1);record(disagree,ChargeSessionStore.Charger.STOCK,STOCK,17,50,3);
        ChargeSessionStore.Curve disagreeing=curve(disagree,ChargeSessionStore.Charger.STOCK);
        check(disagreeing.estimateProvisional(30,45)==null,"Two strongly disagreeing recordings stay unusable, even provisionally");
        check(!estimate(disagreeing,STOCK,30,45d).learnedToLimit,"No learned time across the disagreement");
        // ... unless a finish recording covers them: from where it starts, its own matched finish.
        record(disagree,ChargeSessionStore.Charger.STOCK,STOCK,35,100,1);
        ChargeSessionStore.Curve covered=curve(disagree,ChargeSessionStore.Charger.STOCK);
        check(!estimate(covered,STOCK,30,100d).learnedToFull,"Below the finish recording, the disagreeing percents still block a join");
        check(estimate(covered,STOCK,40,100d).learnedToFull,"Inside the finish recording, its own finish");

        // After a second agreeing recording the robust path takes over for every percent it covers.
        ChargeSessionStore second=store();record(second,ChargeSessionStore.Charger.STOCK,STOCK,20,95,1);
        check(estimate(curve(second,ChargeSessionStore.Charger.STOCK),STOCK,30,90d).provisional,"One recording: provisional");
        record(second,ChargeSessionStore.Charger.STOCK,STOCK,20,95,1.1);
        ChargingEstimate.Result twice=estimate(curve(second,ChargeSessionStore.Charger.STOCK),STOCK,30,90d);
        check(twice.learnedToLimit&&!twice.provisional&&twice.learnedSessions==2,"Two agreeing: robust: "+twice.status);
        near(twice.remainingMinutesToLimit,twice.planningMinutesToLimit,1e-9,"Robust evidence plans with its own time, as before");

        // A gap in coverage: no estimate across it, joined or not.
        ChargeSessionStore gap=store();record(gap,ChargeSessionStore.Charger.STOCK,STOCK,17,40,1);record(gap,ChargeSessionStore.Charger.STOCK,STOCK,50,70,1);
        ChargeSessionStore.Curve gapped=curve(gap,ChargeSessionStore.Charger.STOCK);
        check(gapped.estimateProvisional(30,65)==null&&!estimate(gapped,STOCK,30,65d).learnedToLimit,"No estimate across a gap");
        check(gapped.estimateProvisional(30,38)!=null&&gapped.estimateProvisional(55,65)!=null,"Either side of the gap still counts");
        record(gap,ChargeSessionStore.Charger.STOCK,STOCK,55,100,1);
        check(!estimate(curve(gap,ChargeSessionStore.Charger.STOCK),STOCK,30,100d).learnedToFull,"A finish recording beyond the gap does not join across it");
        near(minutes(STOCK,51,56)+minutes(STOCK,56,100),estimate(curve(gap,ChargeSessionStore.Charger.STOCK),STOCK,51,100d).remainingMinutesToFull,.5,
                "Above the gap, the 50–70% recording joins the finish recording");

        // Two finish recordings starting at different levels: each joined time counts, combined like matched finishes.
        ChargeSessionStore finishes=store();record(finishes,ChargeSessionStore.Charger.STOCK,STOCK,17,50,1);
        record(finishes,ChargeSessionStore.Charger.STOCK,STOCK,40,100,1);record(finishes,ChargeSessionStore.Charger.STOCK,STOCK,45,100,1);
        ChargeSessionStore.Duration both=curve(finishes,ChargeSessionStore.Charger.STOCK).estimateJoined(30,100,true);
        check(both!=null&&both.sessions==3&&both.joinedAt==41,"Both finish recordings join, from the lowest start: "+(both==null?null:both.joinedAt));
        near(minutes(STOCK,30,100),both.minutes,.5,"Both joined times agree");

        // The UT5 MAX keeps its precedence: no provisional evidence where Volt bundles a reference for the pair.
        int ut5=Ut5MaxStockChargeReference.PRODUCT_ID;
        check(!ChargingEstimate.provisionalAllowed(ut5,ChargeSessionStore.Charger.STOCK)&&!ChargingEstimate.provisionalAllowed(ut5,ChargeSessionStore.Charger.HIGH_SPEED)
                &&ChargingEstimate.provisionalAllowed(OTHER_MODEL,ChargeSessionStore.Charger.STOCK),"Only the UT5 MAX's pairs are excluded");
        check(!estimate(one,STOCK,30,90d,1,ut5).provisional,"A UT5 MAX on the stock charger gets no provisional estimate");

        // One partial recording on another model, with the charger Automatic picks from it (LearnedChargerMatch, as
        // ScooterService.detectedCharger: null where nothing matches): at a level it covers and whose power matches, never
        // the mismatch label: a provisional time where it reaches the target, otherwise the plain current rate, as where it
        // does not cover the level at all and Automatic has no charger.
        for(ChargeSessionStore.Charger charger:ChargeSessionStore.Charger.values()){
            Model model=charger==ChargeSessionStore.Charger.STOCK?STOCK:FAST;
            ChargeSessionStore partial=store();record(partial,charger,model,17,50,1);
            ChargeSessionStore.Curve lone=curve(partial,charger);
            check(lone.qualifiedSessions==1&&lone.powerMatch(30,model.watts(30))==ChargeSessionStore.Curve.PowerMatch.UNKNOWN
                    &&lone.powerMatch(30,model.watts(30),true)==ChargeSessionStore.Curve.PowerMatch.MATCH,charger+": one recording's power guards only provisional estimates");
            for(int level=10;level<=95;level++){
                LearnedChargerMatch match=new LearnedChargerMatch();
                ChargeSessionStore.Charger auto=null;
                for(int i=0;i<3;i++){long t=1000+i*2000L;
                    auto=match.observe("one",true,(double)level,model.volts.applyAsDouble(level),-model.amps.applyAsDouble(level),t,t,
                            charger==ChargeSessionStore.Charger.STOCK?lone:null,charger==ChargeSessionStore.Charger.STOCK?null:lone);}
                check(auto==(level>=18&&level<=49?charger:null),charger+" at "+level+"%: Automatic picks the recorded charger exactly where it saved power: "+auto);
              for(Double target:new Double[]{45d,80d,100d,null}){
                ChargingEstimate.Result shown=ChargingEstimate.forScooter(baseline(model,level,target,1d),new ChargingEstimate.Value((double)level,true,100),
                        new ChargingEstimate.Value(model.ratedAh,true,100),OTHER_MODEL,auto,auto==null?null:lone);
                String where=charger+" at "+level+"% to "+target+": ";
                check(!"Charger mismatch · rate estimate".equals(shown.label),where+"no mismatch label: "+shown.label);
                boolean provisional=target!=null&&target==45&&level>=18&&level<45;
                check(shown.learnedToLimit==provisional&&!shown.learnedToFull,where+"a provisional time exactly where the recording reaches the target: "+shown.status);
                check(provisional?"From 1 past charge".equals(shown.label):"Current rate".equals(shown.label),where+shown.label);
              }
            }
        }

        // The curve's coverage per percent (Curve.summary), for a chart of the saved curve.
        ChargeSessionStore mixed=store();record(mixed,ChargeSessionStore.Charger.STOCK,STOCK,17,50,1);record(mixed,ChargeSessionStore.Charger.STOCK,STOCK,30,50,1.1);
        record(mixed,ChargeSessionStore.Charger.STOCK,STOCK,38,50,3);record(mixed,ChargeSessionStore.Charger.STOCK,STOCK,45,100,1);
        ChargeSessionStore.Summary summary=curve(mixed,ChargeSessionStore.Charger.STOCK).summary();
        check(summary.charger==ChargeSessionStore.Charger.STOCK&&summary.qualifiedSessions==4&&summary.bins.size()==100,"Curve-level facts");
        check(summary.finishStarts.size()==1&&summary.finishStarts.get(0)==46,"Where the finish recording starts: "+summary.finishStarts);
        ChargeSessionStore.Summary.Bin none=summary.bins.get(10),provisional=summary.bins.get(20),robustBin=summary.bins.get(35),finish=summary.bins.get(60);
        check(none.coverage==ChargeSessionStore.Summary.Coverage.NONE&&none.sessions==0&&none.minutes==null&&none.watts==null&&!none.finishCovered,"An uncovered percent");
        check(provisional.coverage==ChargeSessionStore.Summary.Coverage.PROVISIONAL&&provisional.sessions==1&&!provisional.finishCovered,"One recording: provisional");
        near(STOCK.minutes(20),provisional.minutes,.2,"Its minutes");near(STOCK.watts(20),provisional.watts,1,"Its power");
        check(robustBin.coverage==ChargeSessionStore.Summary.Coverage.ROBUST&&robustBin.sessions==2,"Two agreeing recordings: robust");
        near(STOCK.minutes(35)*1.05,robustBin.minutes,.2,"Their average");
        // 39–45%: three recordings, one three times slower: the two that agree outvote it; 46–49% add the finish's.
        check(summary.bins.get(42).coverage==ChargeSessionStore.Summary.Coverage.ROBUST&&summary.bins.get(42).sessions==2,"A divergent third does not count");
        check(finish.coverage==ChargeSessionStore.Summary.Coverage.PROVISIONAL&&finish.finishCovered&&summary.bins.get(46).finishCovered&&!summary.bins.get(45).finishCovered,"Finish coverage from 46%");
        ChargeSessionStore pair=store();record(pair,ChargeSessionStore.Charger.STOCK,STOCK,17,50,1);record(pair,ChargeSessionStore.Charger.STOCK,STOCK,17,50,3);
        ChargeSessionStore.Summary.Bin split=curve(pair,ChargeSessionStore.Charger.STOCK).summary().bins.get(30);
        check(split.coverage==ChargeSessionStore.Summary.Coverage.DISAGREEING&&split.sessions==2&&split.minutes==null,"Two strongly disagreeing: no minutes");
        boolean immutable=false;try{summary.bins.clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"The summary is read-only");
        System.out.println("Stitched charge estimate tests passed: "+checks+" checks");
    }
}
