package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B19 (MainActivity's text), committed before the batch changes anything.
 *
 * MainActivity needs Android, so a plain JVM test cannot build it; G2 (LedgerTest) proves each new
 * template renders the original concatenation, and G7's census covers the screens. What this pins is
 * the English the activity joins, chooses and formats today:
 *   - Base: copies of the base's concatenations and choices, so B19's own suite (MainTextTest) can check
 *     the catalog against them for every branch;
 *   - the format sites the batch moves onto Fmt, byte for byte against Fmt.decimal and Fmt.integer.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR.
 */
public final class MainActivityTextTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"), new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                formats(phone);
                joins(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("MainActivityTextTest: " + checks + " checks passed (MainActivity joins, choices, sizes "
                + "and counts under 5 phone locales)");
    }

    private static void formats(Locale phone) {
        for (int bytes : new int[]{1024, 1025, 1536, 2048, 10240, 52429, 102400, 1048576, 5242880})
            equal(Base.size(bytes), Fmt.decimal(Locale.ROOT, bytes / 1024.0, 1) + " KB", phone, "size " + bytes);
        equal("1023 bytes", Base.size(1023), phone, "under one KB");
        equal("1.0 KB", Base.size(1024), phone, "one KB");
        for (int n : new int[]{0, 1, 42, 59, 100, 1000}) {
            equal(Base.minutes(n), "Measuring · " + Fmt.integer(n) + " min", phone, "measuring " + n);
            equal(Base.below(n), "Below " + Fmt.integer(n) + "%", phone, "below " + n);
        }
    }

    private static void joins(Locale phone) {
        equal("Last known lock state: locked. Reconnect to verify.", Base.lockDescription("Locked"), phone, "locked");
        equal("Last known lock state: unlocked. Reconnect to verify.", Base.lockDescription("Unlocked"), phone, "unlocked");
        equal("Lock state unknown", Base.lockDescription(null), phone, "unknown");
        equal("Connecting to UT5. Lock state unknown", Base.connectingTo("UT5", "Lock state unknown"), phone, "connecting to");
        equal("Last known: locked", Base.lastKnown("Locked", true), phone, "last known, connecting");
        equal("Last known", Base.lastKnown("Unlocked", false), phone, "last known");
        equal("", Base.lastKnown(null, true), phone, "nothing known");
        equal("Connected · UT5", Base.connectionShown(true, false, "UT5", 0, null), phone, "connected");
        equal("Connected", Base.connectionShown(true, false, null, 5, "now"), phone, "connected, no profile");
        equal("Disconnected · last seen 5 min ago", Base.connectionShown(false, false, null, 5, "5 min ago"), phone, "last seen");
        equal("Disconnected", Base.connectionShown(false, false, null, 0, null), phone, "never");
        equal("Connecting", Base.connectionShown(false, true, null, 5, "x"), phone, "connecting");
        equal("Disconnected, last connected Nov 3. Connection details", Base.connectionDescription(false, false, 5, "Nov 3"), phone, "details");
        equal("Connected. Connection details", Base.connectionDescription(true, false, 5, "Nov 3"), phone, "details, ready");
        equal("Connecting. Connection details", Base.connectionDescription(false, true, 5, "Nov 3"), phone, "details, connecting");
        equal("Disconnected. Connection details", Base.connectionDescription(false, false, 0, "Nov 3"), phone, "details, never");
        equal("Electronic lock state unavailable", Base.lockSwitch(null), phone, "switch unknown");
        equal("Electronic lock, locked. Double-tap or slide to unlock", Base.lockSwitch(true), phone, "switch locked");
        equal("Electronic lock, unlocked. Double-tap or slide to lock", Base.lockSwitch(false), phone, "switch unlocked");
        equal("State, Connected", Base.line("State", "Connected"), phone, "sheet line");
        equal("Searching…", Base.scanStatus(true, 3), phone, "searching");
        equal("No scooter found. Is it on?", Base.scanStatus(false, 0), phone, "none");
        equal("1 scooter found", Base.scanStatus(false, 1), phone, "one");
        equal("2 scooters found", Base.scanStatus(false, 2), phone, "two");
        equal("12 scooters found", Base.scanStatus(false, 12), phone, "twelve");
        equal("UT5, Good signal, address ending A1:B2", Base.scanOption("UT5", "Good signal", false, false, "A1:B2"), phone, "unsaved");
        equal("UT5, Good signal, your scooter, address ending A1:B2", Base.scanOption("UT5", "Good signal", true, true, "A1:B2"), phone, "yours");
        equal("UT5, Good signal, saved scooter in My scooters, address ending A1:B2", Base.scanOption("UT5", "Good signal", true, false, "A1:B2"), phone, "saved");
        equal("Unfinished try · Boost +2", Base.banner(true, "Boost", 3), phone, "try banner");
        equal("Unfinished test · Boost", Base.banner(false, "Boost", 1), phone, "test banner");
        equal("Trying boost…", Base.trying("Boost"), phone, "trying");
        equal("Review unfinished try", Base.review(true), phone, "review try");
        equal("Review unfinished test", Base.review(false), phone, "review test");
        equal("Sent 9:30 AM", Base.sent("9:30 AM"), phone, "clock value");
        equal("Scooter clock, sending", Base.clock(true, false, "9:30", null), phone, "clock sending");
        equal("Scooter clock, sent 9:30, not verified", Base.clock(false, true, "9:30", null), phone, "clock sent");
        equal("Scooter clock, Rejected", Base.clock(false, false, "9:30", "Rejected"), phone, "clock chip");
        equal("Scooter clock", Base.clock(false, false, "9:30", null), phone, "clock quiet");
        equal("Auto · Stock", Base.auto("Stock"), phone, "charger");
        equal("Low battery alert, Off", Base.alertRow(true, "Off", null), phone, "low row");
        equal("Charge alert, 80%, Watching this charge", Base.alertRow(false, "80%", "Watching this charge"), phone, "charge row");
        equal("Volt didn't see this charge begin, so it would plan for a fresh charger, which usually finishes early. "
                + "Watching the charger for about 10 minutes lets it plan closer to your time, still with the 20-minute buffer. "
                + "Volt stays connected and shows a notification until the schedule is set.", Base.measure(true), phone, "measure, background");
        equal("Volt didn't see this charge begin, so it would plan for a fresh charger, which usually finishes early. "
                + "Watching the charger for about 10 minutes lets it plan closer to your time, still with the 20-minute buffer. "
                + "Volt stays connected, even in the background, and shows a notification until the schedule is set.", Base.measure(false), phone, "measure, foreground");
        equal("Measuring · Charge done by is set within 7 min", Base.measuringToast(7), phone, "measuring toast");
        equal("Charge done by is set automatically within 7 min. Schedule now plans with what Volt knows so far. Stop measuring leaves Charge done by off.",
                Base.measuringBody(7), phone, "measuring body");
        equal("Charging should resume at 9:30 PM. Cancel test turns the schedule off and restores your Charge start.", Base.testBody("9:30 PM"), phone, "test body");
        equal("Test scheduled charging", Base.testRow("", null), phone, "test row quiet");
        equal("Test scheduled charging, Passed, Connect a charger first", Base.testRow("Passed", "Connect a charger first"), phone, "test row");
        equal("Charge done by time, 9:30, Blocked", Base.doneRow(true, "9:30", "Blocked"), phone, "done by time");
        equal("Charge done by, On", Base.doneRow(false, "On", null), phone, "done by");
        equal("Range estimate, Volt (learned), opens choices", Base.range("Volt (learned)"), phone, "range");
        equal("Boost, On, confirming", Base.row("Boost", "On", true, "Rejected", true, "Note"), phone, "row pending");
        equal("Boost, On, rejected", Base.row("Boost", "On", false, "Rejected", true, "Note"), phone, "row chip");
        equal("Boost, On, stop to change", Base.row("Boost", "On", false, null, true, "Note"), phone, "row parked");
        equal("Boost, On, a note", Base.row("Boost", "On", false, null, false, "A Note"), phone, "row note");
        equal("Boost, On", Base.row("Boost", "On", false, null, false, null), phone, "row quiet");
        equal("Start, On, Blocked, Note", Base.schedule("Start", "On", "Blocked", "Note"), phone, "schedule all");
        equal("Start, On, Blocked", Base.schedule("Start", "On", "Blocked", "Blocked"), phone, "schedule same");
        equal("Start, On", Base.schedule("Start", "On", null, null), phone, "schedule quiet");
        equal("Sport · Note", Base.joinNote("Sport", "Note"), phone, "join");
        equal("Note", Base.joinNote("", "Note"), phone, "join, no text");
        equal("Note", Base.joinNote(null, "Note"), phone, "join, null text");
        equal("Sport", Base.joinNote("Sport", null), phone, "join, no note");
        equal("25 kilometres per hour", Base.spoken(25, "km"), phone, "spoken km");
        equal("16 miles per hour, 25 kilometres per hour", Base.spoken(25, "mi"), phone, "spoken mi");
        equal("mph · 25 km/h", Base.dualUnit(25), phone, "dual unit");
        equal("Top speed, Sport mode maximum, not reported, last seen setting", Base.topSpeed(null, "mi", false), phone, "top unknown");
        equal("Top speed, Sport mode maximum, 25 kilometres per hour", Base.topSpeed(25, "km", true), phone, "top km");
        equal("Set top speed to 25 kilometres per hour", Base.setTop(25, "km"), phone, "set top");
        equal("Volt hasn't seen a Model X before. A 30-second check reads the scooter without changing anything. Then your email app opens a short report you can read before you send it.",
                Base.invite("X"), phone, "invite");
        equal("To help@x", Base.to("help@x"), phone, "address");
        equal("volt.txt · 3 bytes. This is exactly what Volt attaches.", Base.attaches("volt.txt", "3 bytes"), phone, "preview");
        equal("About 10 mi at 80%", Base.learned("10 mi", 80, null), phone, "learned");
        equal("About 10 mi at 80% · likely 8–12 mi", Base.learned("10 mi", 80, "8–12 mi"), phone, "learned, likely");
        equal("10 km", Base.span(10, 10, "km"), phone, "span one");
        equal("8–12 mi", Base.span(8, 12, "mi"), phone, "span two");
        equal("6 mi / 10 km", Base.whole("dual", "6 mi", "10 km"), phone, "whole dual");
        equal("Replaces UT5’s dashboard and all 3 slots.", Base.replaces("UT5"), phone, "replaces");
        equal("Volt pauses until you reopen it.", Base.pause(false), phone, "backup pause");
        equal("Choose a backup, review its contents, then confirm. Nothing changes before you approve. Volt pauses until you reopen it.", Base.pause(true), phone, "restore pause");
        equal("12 h", Base.shortHours("12 hours"), phone, "lock_expiry's short label");
        equal("1 hour", Base.shortHours("1 hour"), phone, "lock_expiry's short label leaves one hour");
        equal("2 settings", Base.results(2), phone, "two results");
        equal("1 setting", Base.results(1), phone, "one result");
        equal("0 settings", Base.results(0), phone, "no results");
    }

    /** Copies of the base's expressions (MainActivity at b07031014, by location in comments). */
    static final class Base {
        static String size(int bytes) {return bytes<1024?bytes+" bytes":String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);} // :2780
        static String minutes(int n) {return "Measuring · "+n+" min";} // :2178
        static String below(int n) {return "Below "+n+"%";} // :1844
        static String lockDescription(String known) { // :908, :912
            String unknown="Lock state unknown";
            return known==null?unknown:"Last known lock state: "+known.toLowerCase(java.util.Locale.ROOT)+". Reconnect to verify.";
        }
        static String connectingTo(String scooterName, String description) {return "Connecting to "+scooterName+". "+description;} // :913
        static String lastKnown(String known, boolean connecting) {return known==null?"":connecting?"Last known: "+known.toLowerCase(java.util.Locale.ROOT):"Last known";} // :917
        static String connectionShown(boolean ready, boolean connecting, String name, long last, String recent) { // :924-925
            String state=ready?"Connected":connecting?"Connecting":"Disconnected";
            return state+(ready&&name!=null?" · "+name:"")+(!ready&&!connecting&&last>0?" · last seen "+recent:"");
        }
        static String connectionDescription(boolean ready, boolean connecting, long last, String when) { // :927
            String state=ready?"Connected":connecting?"Connecting":"Disconnected";
            return state+(!ready&&!connecting&&last>0?", last connected "+when:"")+". Connection details";
        }
        static String lockSwitch(Boolean locked) {return locked==null?"Electronic lock state unavailable":"Electronic lock, "+(locked?"locked":"unlocked")+". Double-tap or slide to "+(locked?"unlock":"lock");} // :1014
        static String line(String label, String value) {return label+", "+value;} // :747
        static String scanStatus(boolean scanning, int size) {return scanning?"Searching…":size==0?"No scooter found. Is it on?":size==1?"1 scooter found":size+" scooters found";} // :1199
        static String scanOption(String name, String strength, boolean saved, boolean match, String suffix) {return name+", "+strength+(!saved?"":match?", your scooter":", saved scooter in My scooters")+", address ending "+suffix;} // :1238
        static String banner(boolean isTry, String label, int size) {return (isTry?"Unfinished try · ":"Unfinished test · ")+label+(size>1?" +"+(size-1):"");} // :1527
        static String trying(String label) {return "Trying "+label.toLowerCase(Locale.ROOT)+"…";} // :1530
        static String review(boolean isTry) {return "Review unfinished "+(isTry?"try":"test");} // :1532
        static String sent(String time) {return "Sent "+time;} // :1547
        static String clock(boolean pending, boolean accepted, String time, String chip) {return "Scooter clock"+(pending?", sending":accepted?", sent "+time+", not verified":chip!=null?", "+chip:"");} // :1549
        static String auto(String family) {return "Auto · "+family;} // :1558
        static String alertRow(boolean low, String value, String sub) {return (low?"Low battery alert":"Charge alert")+", "+value+(sub==null?"":", "+sub);} // :1849
        static String measure(boolean background) { // :2024
            return "Volt didn't see this charge begin, so it would plan for a fresh charger, which usually finishes early. "
                    +"Watching the charger for about 10 minutes lets it plan closer to your time, still with the 20-minute buffer. "
                    +"Volt stays connected"+(background?"":", even in the background,")+" and shows a notification until the schedule is set.";
        }
        static String measuringToast(int n) {return "Measuring · Charge done by is set within "+n+" min";} // :2030
        static String measuringBody(int n) {return "Charge done by is set automatically within "+n+" min. "+"Schedule now plans with what Volt knows so far. Stop measuring leaves Charge done by off.";} // :2041
        static String testBody(String t) {return "Charging should resume at "+t+". "+"Cancel test turns the schedule off and restores your Charge start.";} // :2084
        static String testRow(String value, String blocked) {return "Test scheduled charging"+(value.isEmpty()?"":", "+value)+(blocked==null?"":", "+blocked);} // :2123
        static String doneRow(boolean time, String value, String blocked) {return (time?"Charge done by time":"Charge done by")+", "+value+(blocked==null?"":", "+blocked);} // :2182
        static String range(String source) {return "Range estimate, "+source+", opens choices";} // :2244
        static String row(String label, String shown, boolean pending, String chip, boolean parked, String note) {return label+", "+shown+(pending?", confirming":chip!=null?", "+chip.toLowerCase(Locale.ROOT):parked?", stop to change":note!=null?", "+note.toLowerCase(Locale.ROOT):"");} // :2282
        static String schedule(String label, String shown, String blocked, String note) {return label+", "+shown+(blocked==null?"":", "+blocked)+(note==null||note.equals(blocked)?"":", "+note);} // :2299
        static String joinNote(String text, String note) {return note==null?text:text==null||text.isEmpty()?note:text+" · "+note;} // :2335
        static String number(int kmh, String units) {return "km".equals(units)?Integer.toString(kmh):Long.toString(Math.round(kmh/1.609344));} // :2427
        static String spoken(int kmh, String units) {return "km".equals(units)?kmh+" kilometres per hour":number(kmh,units)+" miles per hour, "+kmh+" kilometres per hour";} // :2429
        static String dualUnit(int actual) {return "mph · "+actual+" km/h";} // :2444
        static String topSpeed(Integer actual, String units, boolean ready) {return "Top speed, Sport mode maximum, "+(actual==null?"not reported":spoken(actual,units))+(ready?"":", last seen setting");} // :2445
        static String setTop(int kmh, String units) {return "Set top speed to "+spoken(kmh,units);} // :2450
        static String invite(String model) {return "Volt hasn't seen a Model "+model+" before. A 30-second check reads the scooter without changing anything. Then your email app opens a short report you can read before you send it.";} // :2524
        static String to(String address) {return "To "+address;} // :2709
        static String attaches(String name, String size) {return name+" · "+size+". This is exactly what Volt attaches.";} // :2781
        static String learned(String km, long percent, String likely) {return "About "+km+" at "+percent+"%"+(likely==null?"":" · likely "+likely);} // :3013
        static String span(long a, long b, String unit) {return (a==b?Long.toString(a):a+"–"+b)+" "+unit;} // :3022
        static String whole(String units, String mi, String km) {return units.equals("km")?km:units.equals("dual")?mi+" / "+km:mi;} // :3018
        static String replaces(String name) {return "Replaces "+name+"’s dashboard and all 3 slots.";} // :3284
        static String pause(boolean restore) {return (restore?"Choose a backup, review its contents, then confirm. Nothing changes before you approve. ":"")+"Volt pauses until you reopen it.";} // :3308
        static String shortHours(String label) {return label.replace(" hours"," h");} // :1762
        static String results(int count) {return count+(count==1?" setting":" settings");} // :results line
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(what + " under " + phone + ": expected [" + expected + "] got [" + actual + "]");
    }
}
