package com.jjricks.volt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The last-crash record (CrashRecord): classes and Volt's own frames only, never a message or another package's
 * frame; bounded; a damaged or foreign file reads as none; the handler records and then hands the crash on.
 */
public final class CrashRecordTest {
    private static int checks;
    /** A message stuffed with what a message could carry. Not one word of it may reach the record. */
    static final String MESSAGE = "failed for " + CommandLogTest.SERIAL + " at " + CommandLogTest.MAC + " key " + CommandLogTest.OWNER_KEY
            + " pin " + CommandLogTest.PIN + " " + CommandLogTest.NAME + " joel.rider@example.com 43.6150123,-116.2023456 /storage/emulated/0/Download/Joel";

    public static void main(String[] args) throws Exception {
        encodeAndDecode();
        bounds();
        damaged();
        fileAndHandler();
        System.out.println("CrashRecord tests passed: " + checks + " assertions");
    }

    /** A crash with a cause: both classes, Volt's frames as Class.method:line, others only counted. */
    static Throwable sample() {
        IllegalStateException cause = new IllegalStateException(MESSAGE);
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.jjricks.volt.RideStore", "sample", "RideStore.java", 531),
                new StackTraceElement("java.io.FileOutputStream", "write", "FileOutputStream.java", 10)});
        RuntimeException top = new RuntimeException(MESSAGE, cause);
        top.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
                new StackTraceElement("com.jjricks.volt.ScooterService$3", "run", "ScooterService.java", 1204),
                new StackTraceElement("com.jjricks.volt.MainActivity", "lambda$onCreate$7", "MainActivity.java", -2),
                new StackTraceElement("com.jjricks.voltage.Other", "x", "Other.java", 1)});
        return top;
    }

    private static void encodeAndDecode() {
        String text = CrashRecord.encode(sample(), "1.13.1", 1_792_026_000_000L);
        for (String word : MESSAGE.split("[ ,]")) if (word.length() >= 5) require(!text.contains(word), "no word of the message in the file: " + word);
        require(!text.contains(Integer.toString(CommandLogTest.PIN)), "nor the PIN");
        require(!text.contains("android.os") && !text.contains("java.io") && !text.contains("voltage"), "no other package's frame");
        CrashRecord r = CrashRecord.decode(text);
        require(r != null && r.wallMs == 1_792_026_000_000L && r.appVersion.equals("1.13.1") && r.chain.size() == 2, "it reads back");
        require(r.chain.get(0).exception.equals("java.lang.RuntimeException") && r.chain.get(1).exception.equals("java.lang.IllegalStateException"), "classes in order");
        require(r.chain.get(0).frames.equals(Arrays.asList("ScooterService$3.run:1204", "MainActivity.lambda$onCreate$7:-1")) && r.chain.get(0).otherFrames == 2,
                "Volt's frames kept, a native line as -1, the others counted");
        require(r.chain.get(1).frames.equals(Arrays.asList("RideStore.sample:531")) && r.chain.get(1).otherFrames == 1, "the cause's frames");
        String json = new DiagnosticsBundle("1.13.1", 1, false).crash(r).json(null);
        require(json.contains("\"last_crash\": {") && json.contains("\"exception\": \"java.lang.IllegalStateException\"") && json.contains("\"RideStore.sample:531\"")
                && json.contains("\"other_frames\": 2"), "the bundle's section");
        for (String word : MESSAGE.split("[ ,]")) if (word.length() >= 5) require(!json.contains(word), "no word of the message in the diagnostics file: " + word);
        require(CrashRecord.decode(CrashRecord.encode(new Error(), "not a version", -5)).appVersion.equals("unknown"), "an odd version reads as unknown");
    }

    private static void bounds() {
        Throwable deep = new RuntimeException("x");
        StackTraceElement[] many = new StackTraceElement[200];
        for (int i = 0; i < many.length; i++) many[i] = new StackTraceElement("com.jjricks.volt.Deep", "m" + i, "Deep.java", i);
        Throwable t = deep;
        for (int i = 0; i < 10; i++) { t.setStackTrace(many); Throwable next = new RuntimeException("y"); t.initCause(next); t = next; }
        CrashRecord r = CrashRecord.decode(CrashRecord.encode(deep, "1.0", 1));
        require(r.chain.size() == CrashRecord.MAX_CAUSES + 1, "causes are capped");
        require(r.chain.get(0).frames.size() == CrashRecord.MAX_FRAMES && r.chain.get(0).otherFrames == 200 - CrashRecord.MAX_FRAMES, "frames are capped and the rest counted");
        int kept = 0; for (CrashRecord.Thrown x : r.chain) kept += x.frames.size();
        require(kept == CrashRecord.MAX_TOTAL_FRAMES && r.chain.get(2).frames.isEmpty() && r.chain.get(2).otherFrames == 200, "frames in all are capped; later causes keep their counts");
        require(CrashRecord.encode(deep, "1.0", 1).length() < CrashRecord.MAX_FILE_BYTES, "a worst case fits the file cap");
        StringBuilder longName = new StringBuilder("com.jjricks.volt.A"); for (int i = 0; i < 70; i++) longName.append('b');
        require(CrashRecord.frame(longName.toString(), "run", 1) == null, "an over-long frame is dropped");
        // A throwable that is its own cause (or a loop) ends.
        Throwable loop = new Exception() { @Override public synchronized Throwable getCause() { return this; } };
        require(CrashRecord.decode(CrashRecord.encode(loop, "1.0", 1)).chain.size() == 1, "a self cause ends the chain");
        require(CrashRecord.frame("java.lang.Thread", "run", 1) == null && CrashRecord.frame("com.jjricks.volt.a.B", "c", 1) == null
                && "A.b:1".equals(CrashRecord.frame("com.jjricks.volt.A", "b", 1)), "only top-level Volt classes' frames");
    }

    private static void damaged() {
        require(CrashRecord.decode(null) == null && CrashRecord.decode("") == null && CrashRecord.decode("volt-crash-v0\nexception=x\n") == null, "another format reads as none");
        require(CrashRecord.decode(CrashRecord.FORMAT + "\nwall=1\n") == null, "nothing thrown reads as none");
        CrashRecord r = CrashRecord.decode(CrashRecord.FORMAT + "\nwall=oops\nversion=1.0\nexception=" + MESSAGE + "\nat=" + CommandLogTest.SERIAL + "\nat=A.b:3\nother=-9\n");
        require(r != null && r.wallMs == 0 && r.chain.get(0).exception.equals("unknown") && r.chain.get(0).frames.equals(Arrays.asList("A.b:3")) && r.chain.get(0).otherFrames == 0,
                "lines that fail their pattern are dropped");
    }

    private static void fileAndHandler() throws IOException {
        File dir = Files.createTempDirectory("crash").toFile();
        File file = new File(dir, "last-crash");
        require(CrashRecord.read(file) == null, "no file, no crash");
        require(CrashRecord.write(file, sample(), "1.13.1", 5), "written");
        require(CrashRecord.read(file).chain.size() == 2 && !new File(dir, "last-crash.tmp").exists(), "read back; no temporary left");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[CrashRecord.MAX_FILE_BYTES + 10]); }
        require(CrashRecord.read(file) == null, "an oversized file reads as none");
        File folder = new File(dir, "folder"); require(folder.mkdir(), "fixture folder");
        require(CrashRecord.read(folder) == null && !CrashRecord.write(new File(new File(dir, "missing"), "x"), sample(), "1.0", 1), "unreadable and unwritable: no throw");
        // The handler writes the record, then hands the crash to the handler before it.
        AtomicReference<Throwable> handed = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, thrown) -> handed.set(thrown));
        File installed = new File(dir, "installed");
        CrashRecord.install(installed, "1.13.1");
        CrashRecord.install(new File(dir, "second"), "1.13.1");
        Throwable crash = sample();
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), crash);
        require(handed.get() == crash, "the earlier handler still gets the crash");
        require(CrashRecord.read(installed) != null && !new File(dir, "second").exists(), "recorded once, by the first install");
        String text = new String(Files.readAllBytes(installed.toPath()), StandardCharsets.UTF_8);
        require(!text.contains(CommandLogTest.SERIAL) && text.startsWith(CrashRecord.FORMAT + "\n"), "the file holds no message");
    }

    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
