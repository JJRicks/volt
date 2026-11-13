package com.jjricks.volt;

import java.util.Arrays;

/** UT5 MAX keeps today's read arrays; other layers probe once and stop asking for silent or rejected optional reads. */
public final class ReadPolicyTest {
    private static int assertions;
    public static void main(String[] args) {
        ut5Unchanged(); adaptive(); silence(); rejection(); core(); layerSwitch(); neverPin(); quietRequests();
        System.out.println("ReadPolicy tests passed: "+assertions+" assertions");
    }

    private static void ut5Unchanged() {
        for(ReadPolicy p:new ReadPolicy[]{ReadPolicy.forLayer(ModelRegistry.Layer.UT5_MAX),ReadPolicy.legacy()}){
            check(Arrays.equals(p.afterAuth(),new int[]{0x70,0x71,0x72,0x73,0x74,0x75,0x76,0x79,0x7b}),"UT5 bundle is today's constant");
            check(Arrays.equals(p.afterAuth(),ScooterCommands.diagnosticReads()),"UT5 bundle equals diagnosticReads()");
            check(Arrays.equals(p.periodicBattery(),new int[]{0x72,0x79}),"UT5 battery poll is today's pair");
            // Silence and rejection never change the UT5 arrays.
            for(int cmd:new int[]{0x71,0x72,0x75,0x76,0x79,0x7b}){p.requested(cmd,0);p.tick(3000);p.requested(cmd,4000);p.tick(7000);p.responded(cmd,5,4,8000);}
            check(Arrays.equals(p.afterAuth(),ScooterCommands.diagnosticReads())&&Arrays.equals(p.periodicBattery(),new int[]{0x72,0x79}),"UT5 arrays survive silence and rejects");
            for(int cmd:ScooterCommands.diagnosticReads())check(p.allowed(cmd),"UT5 allows "+Integer.toHexString(cmd));
        }
    }
    private static void adaptive() {
        for(ModelRegistry.Layer layer:new ModelRegistry.Layer[]{ModelRegistry.Layer.OEM_GROUP,ModelRegistry.Layer.SHARED}){
            ReadPolicy p=ReadPolicy.forLayer(layer);
            check(Arrays.equals(p.afterAuth(),new int[]{0x74,0x70,0x73,0x72,0x76,0x7b,0x71,0x75,0x79}),"First bundle: core, optional, then probes");
            check(Arrays.equals(p.afterAuth(),new int[]{0x74,0x70,0x73,0x72,0x76,0x7b}),"Probes are sent once per session");
            check(Arrays.equals(p.periodicBattery(),new int[]{0x72}),"No 0x79 until it answers");
            p.responded(0x79,0,4,100);
            check(Arrays.equals(p.periodicBattery(),new int[]{0x72,0x79}),"0x79 polled once it has answered");
        }
    }
    private static void silence() {
        ReadPolicy p=ReadPolicy.forLayer(ModelRegistry.Layer.SHARED);
        p.requested(0x72,0);p.tick(2499);check(p.allowed(0x72),"Not silent before 2.5 s");
        p.tick(2500);check(p.allowed(0x72),"One silence does not suppress");
        p.requested(0x72,4000);p.requested(0x72,4500);p.tick(6499);check(p.allowed(0x72),"A repeated request keeps its first time");
        p.tick(6500);check(!p.allowed(0x72)&&p.suppressed().contains(0x72),"Two silences suppress");
        // A retry that goes out before any tick still counts the first request's silence.
        ReadPolicy m=ReadPolicy.forLayer(ModelRegistry.Layer.SHARED);
        m.requested(0x72,0);m.tick(2000);m.requested(0x72,2500);m.tick(4000);check(m.allowed(0x72),"One silence so far");
        m.tick(5000);check(!m.allowed(0x72)&&m.suppressed().contains(0x72),"A retry before a tick still counts both silences");
        // The compatibility check's silent results carry into normal polling; core reads never stop.
        ReadPolicy c=ReadPolicy.forLayer(ModelRegistry.Layer.SHARED);
        c.unanswered(0x76);c.unanswered(0x79);c.unanswered(0x70);
        check(!c.allowed(0x76)&&!c.allowed(0x79)&&c.allowed(0x70),"Check silences suppress optional reads and probes only");
        ReadPolicy u=ReadPolicy.forLayer(ModelRegistry.Layer.UT5_MAX);u.unanswered(0x76);
        check(u.allowed(0x76)&&java.util.Arrays.equals(u.afterAuth(),ScooterCommands.diagnosticReads()),"UT5 MAX arrays unchanged by check silences");
        // A status-0 reply with no data is an acknowledgement, not an answer.
        ReadPolicy e=ReadPolicy.forLayer(ModelRegistry.Layer.SHARED);
        e.requested(0x79,0);e.responded(0x79,0,0,10);
        check(!e.answered(0x79)&&!contains(e.periodicBattery(),0x79),"An empty ACK does not start the 0x79 poll");
        e.responded(0x79,0,2,20);check(e.answered(0x79)&&contains(e.periodicBattery(),0x79),"A reply with data does");
        check(p.periodicBattery().length==0,"Suppressed battery read leaves the poll");
        p.responded(0x72,0,4,7000);check(!p.allowed(0x72),"Suppression is session-scoped even if a late reply arrives");
        ReadPolicy q=ReadPolicy.forLayer(ModelRegistry.Layer.SHARED);
        q.requested(0x76,0);q.tick(3000);q.requested(0x76,4000);q.responded(0x76,0,4,4100);q.requested(0x76,8000);q.tick(11000);
        check(q.allowed(0x76),"An answer resets the silence count");
        q.afterAuth();q.requested(0x71,0);q.tick(2500);q.tick(9000);
        check(!Arrays.toString(q.afterAuth()).contains(Integer.toString(0x71)),"Probe never repeats");
    }
    private static void rejection() {
        ReadPolicy p=ReadPolicy.forLayer(ModelRegistry.Layer.OEM_GROUP);
        p.requested(0x7b,0);p.responded(0x7b,3,4,10);
        check(!p.allowed(0x7b)&&!contains(p.afterAuth(),0x7b),"A rejected optional read is suppressed");
        p.responded(0x79,0,4,10);p.responded(0x79,1,4,20);
        check(!contains(p.periodicBattery(),0x79),"A rejected probe leaves the battery poll");
    }
    private static void core() {
        ReadPolicy p=ReadPolicy.forLayer(ModelRegistry.Layer.SHARED);
        for(int cmd:new int[]{0x70,0x73,0x74}){for(int i=0;i<5;i++){p.requested(cmd,i*3000L);p.tick(i*3000L+2600);}p.responded(cmd,7,4,99999);}
        for(int cmd:new int[]{0x70,0x73,0x74})check(p.allowed(cmd)&&!p.suppressed().contains(cmd),"Core never suppressed "+cmd);
        check(contains(p.afterAuth(),0x70)&&contains(p.afterAuth(),0x73)&&contains(p.afterAuth(),0x74),"Core always in the bundle");
    }
    private static void layerSwitch() {
        ReadPolicy p=ReadPolicy.legacy();
        int[] first=p.afterAuth();for(int cmd:first)p.requested(cmd,0);
        p.layer(ModelRegistry.Layer.SHARED,true);
        check(!p.isLegacy()&&Arrays.equals(p.afterAuth(),new int[]{0x74,0x70,0x73,0x72,0x76,0x7b}),"Probes already sent before identification are not repeated");
        p.layer(ModelRegistry.Layer.SHARED,false);check(p.isLegacy(),"No identity keeps today's arrays");
        p.layer(ModelRegistry.Layer.UT5_MAX,true);check(p.isLegacy(),"UT5 MAX keeps today's arrays");
    }
    private static void neverPin() {
        for(ModelRegistry.Layer layer:ModelRegistry.Layer.values()){
            ReadPolicy p=ReadPolicy.forLayer(layer);
            for(int i=0;i<3;i++){check(!contains(p.afterAuth(),0x7a)&&!contains(p.periodicBattery(),0x7a),"0x7A never in "+layer);}
            check(!p.allowed(0x7a)&&!p.allowed(0x63)&&!p.allowed(0x30)&&!p.allowed(0x51),"Non-read opcodes never allowed");
            for(int cmd:p.afterAuth())check(ReadPolicy.cls(cmd)!=null&&ReadPolicy.cls(cmd)!=ReadPolicy.Cls.PASSIVE,"Only known reads");
        }
        check(ReadPolicy.cls(0x7a)==null,"0x7A has no read class");
    }
    /**
     * #418: the connected notice's background read (noticeBattery, sent as a quiet request) can never switch a read off for
     * the rest of the connection. A quiet silence is never counted; a nonzero status still rejects; in sight every silence
     * is counted exactly as before, even with a quiet request outstanding when it is made.
     */
    private static void quietRequests() {
        for(ModelRegistry.Layer layer:new ModelRegistry.Layer[]{ModelRegistry.Layer.OEM_GROUP,ModelRegistry.Layer.SHARED,ModelRegistry.Layer.UT5_MAX,null}){
            ReadPolicy p=layer==null?ReadPolicy.legacy():ReadPolicy.forLayer(layer);
            check(p.noticeBattery().length==0,"Nothing for the notice before 0x72 has answered ("+layer+")");
            p.requested(0x72,0);p.responded(0x72,0,36,300);p.requested(0x79,0);p.responded(0x79,0,2,300);
            check(Arrays.equals(p.noticeBattery(),p.periodicBattery())&&Arrays.equals(p.noticeBattery(),new int[]{0x72,0x79}),"The notice reads what periodicBattery would, the direction with the level ("+layer+")");
            for(int i=0;i<20;i++){long t=1000+i*15000L;p.requested(0x72,t,true);p.tick(t+3000);}
            check(p.suppressed().isEmpty()&&p.allowed(0x72)&&Arrays.equals(p.noticeBattery(),new int[]{0x72,0x79}),"Quiet silences are never counted ("+layer+")");
            // An identification that follows (no identity yet is legacy) finds nothing switched off by them.
            p.layer(ModelRegistry.Layer.SHARED,true);check(p.allowed(0x72)&&p.suppressed().isEmpty(),"and a later switch to an adaptive layer finds 0x72 allowed ("+layer+")");
        }
        // Round 6: 0x79 goes with 0x72 only where periodicBattery sends it: on an adaptive layer once it has answered, on
        // the UT5 MAX layer (and with no identity yet) always; never without 0x72, whose readings it signs.
        ReadPolicy adaptive=answered72();check(Arrays.equals(adaptive.noticeBattery(),new int[]{0x72}),"Adaptive, 0x79 not answered: 0x72 alone");
        ReadPolicy ut5=ReadPolicy.forLayer(ModelRegistry.Layer.UT5_MAX);ut5.requested(0x72,0);ut5.responded(0x72,0,36,300);
        check(Arrays.equals(ut5.noticeBattery(),new int[]{0x72,0x79}),"UT5 MAX: both, as periodicBattery");
        ReadPolicy signOnly=ReadPolicy.forLayer(ModelRegistry.Layer.OEM_GROUP);signOnly.requested(0x79,0);signOnly.responded(0x79,0,2,300);
        check(signOnly.noticeBattery().length==0&&Arrays.equals(signOnly.periodicBattery(),new int[]{0x72,0x79}),"No 0x72 answer yet: nothing in the background");
        // In sight, the same requests and ticks suppress at the same moment whether or not quiet requests went before them.
        for(long quietAt=-3000;quietAt<=4000;quietAt+=250){
            ReadPolicy plain=answered72(),mixed=answered72();
            for(long t=6_000;t<=20_000;t+=250){
                if(t==10_000+quietAt)mixed.requested(0x72,t,true);
                if(t>=10_000&&t%2000==0){plain.requested(0x72,t);mixed.requested(0x72,t);}
                plain.tick(t);mixed.tick(t);
                check(plain.allowed(0x72)==mixed.allowed(0x72),"In sight, counted as before (quiet at "+quietAt+", t "+t+")");
            }
            check(!plain.allowed(0x72)&&!mixed.allowed(0x72),"Two in-sight silences still suppress (quiet at "+quietAt+")");
        }
        // A quiet request joining an in-sight one outstanding leaves it counted.
        ReadPolicy joined=answered72();joined.requested(0x72,0);joined.requested(0x72,1000,true);joined.tick(2600);joined.requested(0x72,3000);joined.tick(5600);
        check(!joined.allowed(0x72),"The in-sight request's silence still counts");
        // A nonzero status to a quiet request is the scooter's own answer: rejected, as before.
        ReadPolicy rejected=answered72();rejected.requested(0x72,0,true);rejected.responded(0x72,3,0,100);
        check(!rejected.allowed(0x72)&&rejected.noticeBattery().length==0&&rejected.periodicBattery().length==0,"A rejection still suppresses, and the notice reads nothing more");
        // An empty ACK ends a quiet wait without an answer; a later in-sight silence counts from its own request.
        ReadPolicy ack=answered72();ack.requested(0x72,0,true);ack.responded(0x72,0,0,100);ack.tick(5000);
        check(ack.allowed(0x72)&&ack.suppressed().isEmpty(),"An acknowledgement is not a silence");
    }
    private static ReadPolicy answered72(){ReadPolicy p=ReadPolicy.forLayer(ModelRegistry.Layer.OEM_GROUP);p.requested(0x72,-9000);p.responded(0x72,0,36,-8700);return p;}
    private static boolean contains(int[] values,int v){for(int x:values)if(x==v)return true;return false;}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
