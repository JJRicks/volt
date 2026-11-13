package com.jjricks.volt;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which read opcodes BleController sends, per layer and per session. The UT5 MAX layer (and a
 * scooter with no identity evidence yet) keeps today's bundle and battery poll exactly. Other
 * layers send the core reads, the optional reads while they keep answering, and each probe once;
 * an optional or probe read that is rejected, or silent twice, is not requested again this
 * session; the silence of a quiet request (the notice's background read) never counts. Core reads are never suppressed:
 * their silence keeps the "Signal lost" path.
 * Replies are always accepted whenever they arrive. 0x7A (PIN) is never part of any policy.
 */
final class ReadPolicy {
    enum Cls { CORE, OPTIONAL, PROBE, PASSIVE }
    /** A request with no reply after this long counts as one silence. */
    static final long SILENCE_MS=2500;
    static final int SILENCES_TO_SUPPRESS=2;
    private static final int[] CORE={0x74,0x70,0x73},OPTIONAL={0x72,0x76,0x7b},PROBES={0x71,0x75,0x79};
    private static final int[] LEGACY_BATTERY={0x72,0x79};

    private boolean legacy;
    private final Map<Integer,Long> outstanding=new HashMap<>();
    private final Map<Integer,Integer> silences=new HashMap<>();
    private final Set<Integer> answered=new HashSet<>(),suppressed=new TreeSet<>(),probed=new HashSet<>();
    /** Reads outstanding only as quiet requests (requested): their silence is never counted. */
    private final Set<Integer> quiet=new HashSet<>();

    private ReadPolicy(boolean legacy){this.legacy=legacy;}
    /** UT5_MAX keeps today's arrays; every other layer is adaptive. */
    static ReadPolicy forLayer(ModelRegistry.Layer layer){return new ReadPolicy(layer==ModelRegistry.Layer.UT5_MAX);}
    /** Today's bundle and poll, for a scooter whose model is not known yet (no serial or advertised ID). */
    static ReadPolicy legacy(){return new ReadPolicy(true);}
    /** Switches the layer in place; this session's observations (answers, silences, probes) carry over. */
    void layer(ModelRegistry.Layer layer,boolean identified){legacy=!identified||layer==ModelRegistry.Layer.UT5_MAX;}
    boolean isLegacy(){return legacy;}

    /** null for an opcode that is not a known read. */
    static Cls cls(int cmd){
        for(int c:CORE)if(c==cmd)return Cls.CORE;
        for(int c:OPTIONAL)if(c==cmd)return Cls.OPTIONAL;
        for(int c:PROBES)if(c==cmd)return Cls.PROBE;
        return cmd==0x90||cmd==0x91||cmd==0x92?Cls.PASSIVE:null;
    }

    /** UT5_MAX: exactly ScooterCommands.diagnosticReads(). Others: core, allowed optional reads, then each probe once. */
    int[] afterAuth(){
        if(legacy)return ScooterCommands.diagnosticReads();
        int[] out=new int[CORE.length+OPTIONAL.length+PROBES.length];int n=0;
        for(int c:CORE)out[n++]=c;
        for(int c:OPTIONAL)if(allowed(c))out[n++]=c;
        for(int c:PROBES)if(allowed(c)&&!probed.contains(c)){out[n++]=c;probed.add(c);}
        int[] trimmed=new int[n];System.arraycopy(out,0,trimmed,0,n);return trimmed;
    }
    /** UT5_MAX: {0x72, 0x79} as today. Others: 0x72 while allowed, plus 0x79 only once it has answered this session. */
    int[] periodicBattery(){
        if(legacy)return LEGACY_BATTERY.clone();
        boolean battery=allowed(0x72),sign=answered.contains(0x79)&&allowed(0x79);
        return battery&&sign?new int[]{0x72,0x79}:battery?new int[]{0x72}:sign?new int[]{0x79}:new int[0];
    }
    /**
     * The reads that keep the connected notice's level current out of sight, with no battery watch and no recording (#418):
     * exactly periodicBattery's, so the direction read 0x79 goes with 0x72 wherever it would (the UT5 MAX layer, or once it
     * has answered this session) and the current and temperature each 0x72 reply brings are signed with a direction read
     * beside them. Only once 0x72 has answered this connection: a model that ignores it is never asked in the background,
     * where its silence is not counted. Sent as quiet requests.
     */
    int[] noticeBattery(){return allowed(0x72)&&answered.contains(0x72)?periodicBattery():new int[0];}

    /**
     * A 7-byte read went out. A request that is already outstanding keeps its first time, unless
     * it has been unanswered for SILENCE_MS: that one counts as a silence and this is a new request.
     */
    void requested(int cmd,long nowMs){requested(cmd,nowMs,false);}
    /**
     * quietly: a read sent out of sight only to keep the notice current (noticeBattery). Its silence is never counted, so
     * a background read can never switch a read off for the rest of the connection; a reply with a nonzero status is the
     * scooter's own answer and still rejects it (responded). A request that is not quiet, finding only a quiet one
     * outstanding, starts its own wait, so in sight every silence is counted exactly as before.
     */
    void requested(int cmd,long nowMs,boolean quietly){
        if(cls(cmd)==null||cls(cmd)==Cls.PASSIVE)return;
        tick(nowMs);
        if(cls(cmd)==Cls.PROBE)probed.add(cmd);
        if(!quietly&&quiet.remove(cmd))outstanding.remove(cmd);
        if(!outstanding.containsKey(cmd)){outstanding.put(cmd,nowMs);if(quietly)quiet.add(cmd);}
    }
    /**
     * Any reply, solicited or not; dataLength is the length after the status byte. A nonzero status
     * rejects an optional read or probe for this session. A status-0 reply with no data is only an
     * acknowledgement: it ends the wait but does not count as an answer.
     */
    void responded(int cmd,int status,int dataLength,long nowMs){
        Cls c=cls(cmd);if(c==null||c==Cls.PASSIVE)return;
        outstanding.remove(cmd);quiet.remove(cmd);
        if(status!=0){if(c!=Cls.CORE)suppressed.add(cmd);return;}
        if(dataLength<=0)return;
        answered.add(cmd);silences.remove(cmd);
    }
    /** Counts each request that has been unanswered for SILENCE_MS as one silence. */
    void tick(long nowMs){
        for(Integer cmd:new HashSet<>(outstanding.keySet())){
            long since=outstanding.get(cmd);
            if(nowMs-since<SILENCE_MS)continue;
            outstanding.remove(cmd);
            if(quiet.remove(cmd)||cls(cmd)==Cls.CORE)continue;
            int n=silences.containsKey(cmd)?silences.get(cmd)+1:1;silences.put(cmd,n);
            if(n>=SILENCES_TO_SUPPRESS)suppressed.add(cmd);
        }
    }
    /** The compatibility check had no reply on any attempt: an optional read or probe stops for this session. */
    void unanswered(int cmd){Cls c=cls(cmd);if(c==Cls.OPTIONAL||c==Cls.PROBE)suppressed.add(cmd);}
    /** Whether this read may be requested now. UT5_MAX: every read in today's arrays. 0x7A: never. */
    boolean allowed(int cmd){
        Cls c=cls(cmd);if(c==null||c==Cls.PASSIVE)return false;
        if(legacy||c==Cls.CORE)return true;
        return !suppressed.contains(cmd);
    }
    /** Reads this session stopped requesting (adaptive layers only take effect). */
    Set<Integer> suppressed(){return Collections.unmodifiableSet(new TreeSet<>(suppressed));}
    boolean answered(int cmd){return answered.contains(cmd);}
}
