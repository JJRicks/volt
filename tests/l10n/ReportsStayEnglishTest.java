package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * G9, reports stay English: every report Volt sends or saves is built with a text source installed that
 * fails on any use, and must come out exactly as it does with the built-in English. The compatibility
 * report, connection report, issue report, report invite, owner notes and diagnostics JSON never read
 * the catalog, so a translated app still sends English reports. tests/l10n/golden.sh separately keeps
 * the diagnostics JSON, ride CSV and GPX byte-identical with tests/l10n/golden/.
 */
public final class ReportsStayEnglishTest {
    private static int checks;

    public static void main(String[] args) {
        List<String> english = reports();
        T.install(new T.Source() {
            public String string(int id) { throw new AssertionError("a report read catalog text " + T.key(id)); }
            public String plural(int id, long quantity) { throw new AssertionError("a report read catalog plural " + T.key(id)); }
            public String language() { throw new AssertionError("a report asked for the language"); }
        });
        List<String> guarded;
        try {
            guarded = reports();
        } finally {
            T.install(null);
        }
        check(guarded.size() == english.size(), "the same reports were built");
        for (int i = 0; i < english.size(); i++) check(guarded.get(i).equals(english.get(i)), "report " + i + " is identical without the catalog");
        System.out.println("ReportsStayEnglishTest: " + checks + " checks passed (" + english.size() + " report texts built with a throwing text source)");
    }

    private static List<String> reports() {
        List<String> out = new ArrayList<>();
        // Diagnostics: the same fixture as the golden diagnostics.json.
        out.add(GoldenReports.diagnostics().json(Collections.<String>emptyList()));
        // Compatibility report, its preview summary and the invite email around it.
        DeviceDescriptor d = ModelRegistryTest.advertised("39991");
        DiscoveryCoordinator discovery = new DiscoveryCoordinator(1_000_000L, false);
        discovery.interrupt(1_000_010L);
        CompatibilityReport report = new CompatibilityReport.Builder("1.11.1", new Random(7)).device(d, ModelRegistry.resolve(d), "challenge_free")
                .reads(discovery.reads(), discovery.passive(), discovery.startMs()).fields(StatusFields.NONE)
                .decisions(Collections.<CapabilityResolver.Decision>emptyList()).tests(null).build();
        out.add(report.json());
        out.add(report.summary());
        DriveModeSurvey modes = new DriveModeSurvey();
        modes.observe(1); modes.name(DriveModeSurvey.Label.ECO);
        NaveeModels.Choice model = NaveeModels.Choice.typedName("Synthetic 9");
        out.add(ReportInvite.subject("39991"));
        out.add(ReportInvite.body(report));
        out.add(ReportInvite.body(report, model, modes, "39991"));
        // Owner notes appended to an email.
        out.add(OwnerNotes.json("39991", model, modes));
        out.add(OwnerNotes.appendTo("Body", "39991", model, modes));
        out.add(String.valueOf(OwnerNotes.firstLine(model)));
        // Connection report.
        List<ConnectionEventLog.Record> records = Arrays.asList(
                new ConnectionEventLog.Record(1792026000000L, 1000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, true, false, ConnectionEventLog.Phase.CONNECTING, -1, -1),
                new ConnectionEventLog.Record(1792026002000L, 3000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 8, true, false, ConnectionEventLog.Phase.CONNECTING, -1, -1));
        String connection = ConnectionReport.json("1.11.1", 36, "Synthetic Phone", "22131", "standard", 247, records, new ArrayList<String>());
        out.add(connection);
        out.add(ConnectionReport.subject("22131"));
        out.add(ConnectionReport.body(connection));
        out.add(ConnectionReport.body(connection, model, "22131"));
        // Issue report.
        out.add(IssueReport.subject("1.11.1"));
        out.add(IssueReport.body("1.11.1", "16", 36, "Synthetic Phone", "UT5 MAX", true));
        return out;
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
