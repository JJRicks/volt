package com.jjricks.volt;

import java.util.Arrays;

/**
 * Release this scooter (OwnerRelease): the 0x50 frame, the gate (owner, ready, parked, real scooter, idle), the command
 * log's handling and which outcomes remove the profile. Synthesized fixtures only; nothing here talks to a scooter.
 */
public final class OwnerReleaseTest {
    private static int checks;
    private static final long SEEN = 100000, NOW = SEEN + 1000;

    public static void main(String[] args) {
        frame();
        gate();
        origins();
        transmit();
        commandLog();
        outcomes();
        System.out.println("OwnerReleaseTest: " + checks + " checks");
    }

    /** 55 AA 00 50 01 01, the additive checksum of every byte before it, FE FD: the official app's unbind frame. */
    private static void frame() {
        byte[] f = Protocol.ownerRelease();
        int sum = 0;
        for (int i = 0; i < 6; i++) sum += f[i] & 255;
        byte[] expected = {0x55, (byte) 0xaa, 0x00, 0x50, 0x01, 0x01, (byte) (sum & 255), (byte) 0xfe, (byte) 0xfd};
        check(Arrays.equals(f, expected), "release frame bytes " + hex(f));
        check((f[6] & 255) == 0x51, "checksum 0x55+0xAA+0x00+0x50+0x01+0x01 = 0x151, low byte 51");
        Protocol.Frame parsed = Protocol.parse(f);
        check(parsed.cmd == OwnerRelease.CMD && parsed.cmd == 0x50, "parses back as command 0x50");
        check(f != Protocol.ownerRelease() && Arrays.equals(f, Protocol.ownerRelease()), "a fresh array each time, same bytes");
        // The generic builder still refuses 0x50, so only the named builder can make it.
        boolean refused = false;
        try { Protocol.frame(0x50, new byte[] {1}); } catch (IllegalArgumentException expectedRefusal) { refused = true; }
        check(refused, "Protocol.frame keeps refusing 0x50");
        check(ScooterCommands.sensitiveCommand(0x50), "0x50 stays out of the event log and raw-frame views");
    }

    private static StatusFields status() {
        byte[] d = CapabilityResolverTest.full(); d[2] = 0; d[35] = 0;
        return CapabilityResolverTest.status(d, SEEN);
    }
    private static CapabilityResolver.Runtime parked() {
        CapabilityResolver.Runtime rt = CapabilityResolverTest.parked(); rt.nowMs = NOW; return rt;
    }
    private static MutationGate.Request release() {
        return new MutationGate.Request(MutationGate.Kind.RELEASE, OwnerRelease.KEY, 1, MutationGate.Origin.USER);
    }
    private static CapabilityResolver.Reason reason(DeviceDescriptor d, CapabilityResolver.Runtime rt) {
        CapabilityResolver.Decision decision = MutationGate.check(release(), d, status(), rt);
        check(decision.feature.equals(OwnerRelease.KEY), "the decision names the release");
        check(decision.available() == (decision.reason == CapabilityResolver.Reason.NONE), "available() matches its reason");
        return decision.reason;
    }

    /** Admitted only for the owner, on a ready real link, parked, with fresh status and nothing else running. */
    private static void gate() {
        DeviceDescriptor[] supported = {ModelRegistryTest.serial("Q2538ZZ9US7777777"), ModelRegistryTest.both("Q2443ZZ9US7777777", "24431"),
                ModelRegistryTest.advertised("39991"), ModelRegistryTest.serial("Q2443ZZ9US7777777")};
        for (DeviceDescriptor d : supported) {
            check(reason(d, parked()) == CapabilityResolver.Reason.NONE, "owner, ready, parked: admitted " + d);
            CapabilityResolver.Runtime rt = parked(); rt.owner = false;
            check(reason(d, rt) == CapabilityResolver.Reason.OWNER_ONLY, "a shared user can't release " + d);
            rt = parked(); rt.ready = false;
            check(reason(d, rt) == CapabilityResolver.Reason.NOT_CONNECTED, "needs a ready link " + d);
            rt = parked(); rt.stationary = false;
            check(reason(d, rt) == CapabilityResolver.Reason.MOVING, "refused while moving " + d);
            rt = parked(); rt.stationary = false; rt.motionSeen = false;
            check(reason(d, rt) == CapabilityResolver.Reason.NO_MOTION_REPORT, "refused before any motion report " + d);
            rt = parked(); rt.simulation = true;
            check(reason(d, rt) == CapabilityResolver.Reason.NEEDS_REAL_SCOOTER, "never in simulation " + d);
            rt = parked(); rt.busy = true;
            check(reason(d, rt) == CapabilityResolver.Reason.BUSY, "waits for a change in flight " + d);
            rt = parked(); rt.recording = true;
            check(reason(d, rt) == CapabilityResolver.Reason.RECORDING, "refused while recording " + d);
            rt = parked(); rt.discovery = true;
            check(reason(d, rt) == CapabilityResolver.Reason.DISCOVERY_ACTIVE, "refused during a compatibility check " + d);
            rt = parked(); rt.test = true;
            check(reason(d, rt) == CapabilityResolver.Reason.TEST_ACTIVE, "refused during a guided test " + d);
            rt = parked(); rt.nowMs = SEEN + 7001;
            check(reason(d, rt) == CapabilityResolver.Reason.STATUS_STALE, "cached status never authorises it " + d);
            rt = parked(); rt.warningActive = true; rt.bonded = false; rt.pinEnabledFresh = false;
            check(reason(d, rt) == CapabilityResolver.Reason.NONE, "a warning, bond or PIN state doesn't matter " + d);
        }
        check(reason(ModelRegistryTest.advertised("24351"), parked()) == CapabilityResolver.Reason.PRODUCT_UNSUPPORTED, "unsupported product");
        check(reason(ModelRegistryTest.advertised("27361"), parked()) == CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED, "unsupported transport");
        CapabilityResolver.Runtime sim = new CapabilityResolver.Runtime(); sim.simulation = true; sim.ready = true; sim.nowMs = NOW;
        check(!MutationGate.check(release(), DeviceDescriptor.simulated("", 0), StatusFields.NONE, sim).available(), "the UT5 MAX simulator can't release");
        check(MutationGate.check(release(), DeviceDescriptor.simulated("", 0), StatusFields.NONE, sim).reason == CapabilityResolver.Reason.NEEDS_REAL_SCOOTER, "it says why");
        check(MutationGate.check(release(), ModelRegistryTest.serial("Q2538ZZ9US7777777"), status(), null).reason == CapabilityResolver.Reason.NOT_CONNECTED, "no runtime: not connected");
    }

    /** Only the owner's own tap, for the release feature with value 1. */
    private static void origins() {
        DeviceDescriptor d = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        for (MutationGate.Origin o : MutationGate.Origin.values()) {
            if (o == MutationGate.Origin.USER) continue;
            CapabilityResolver.Runtime rt = parked(); rt.test = o == MutationGate.Origin.GUIDED_TEST || o == MutationGate.Origin.GUIDED_RESTORE;
            rt.testFeature = OwnerRelease.KEY;
            check(!MutationGate.check(new MutationGate.Request(MutationGate.Kind.RELEASE, OwnerRelease.KEY, 1, o), d, status(), rt).available(), "origin " + o + " refused");
        }
        check(!MutationGate.check(new MutationGate.Request(MutationGate.Kind.RELEASE, OwnerRelease.KEY, 0, MutationGate.Origin.USER), d, status(), parked()).available(), "value 0 refused");
        check(!MutationGate.check(new MutationGate.Request(MutationGate.Kind.RELEASE, "lock", 1, MutationGate.Origin.USER), d, status(), parked()).available(), "another feature refused");
        check(OwnerRelease.offered(0) && !OwnerRelease.offered(1), "offered for owner profiles only");
    }

    /** The radio boundary: the release frame goes out only as the admitted mutation. */
    private static void transmit() {
        byte[] f = Protocol.ownerRelease();
        check(!MutationGate.mayTransmit(f, null), "not admitted: dropped");
        check(MutationGate.mayTransmit(f, Protocol.ownerRelease()), "the admitted release passes");
        check(!MutationGate.mayTransmit(f, Protocol.frame(0x51, new byte[] {1})), "another admitted change doesn't carry it");
    }

    /** Logged by key and outcome only: never a value. */
    private static void commandLog() {
        check(CommandLog.control(OwnerRelease.KEY), "a known command-log key");
        check(!CommandLog.valued(OwnerRelease.KEY) && CommandLog.loggable(OwnerRelease.KEY, 1) == null, "never carries a value");
        CommandLog.Command c = new CommandLog.Command(1L, CommandLog.Kind.SET, MutationGate.Origin.USER, OwnerRelease.KEY, null,
                CommandLog.reply(0), -1, null, null, 120);
        check(c.outcome == CommandLog.Outcome.ACK && c.value == null, "status 0 is an ACK event without a value");
        boolean refused = false;
        try { new CommandLog.Command(1L, CommandLog.Kind.SET, MutationGate.Origin.USER, OwnerRelease.KEY, 1, CommandLog.Outcome.ACK, -1, null, null, 120); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "a value is refused for the release");
    }

    /** Status 0 alone removes the profile; a refusal, silence, a closed link or a cancelled send keep it. */
    private static void outcomes() {
        check(OwnerRelease.reply(0) == OwnerRelease.Outcome.RELEASED, "status 0 released");
        for (int status : new int[] {1, 2, 0x7f, 255}) check(OwnerRelease.reply(status) == OwnerRelease.Outcome.REJECTED, "status " + status + " refused");
        check(OwnerRelease.unanswered(true) == OwnerRelease.Outcome.NOT_CONFIRMED, "sent without a reply: not confirmed");
        check(OwnerRelease.unanswered(false) == OwnerRelease.Outcome.NOT_SENT, "never sent");
        for (OwnerRelease.Outcome o : OwnerRelease.Outcome.values())
            check(OwnerRelease.forgetsProfile(o) == (o == OwnerRelease.Outcome.RELEASED), o + (o == OwnerRelease.Outcome.RELEASED ? " removes" : " keeps") + " the profile");
    }

    private static String hex(byte[] b) {
        StringBuilder out = new StringBuilder();
        for (byte x : b) out.append(String.format(java.util.Locale.ROOT, "%02X ", x & 255));
        return out.toString().trim();
    }
    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
