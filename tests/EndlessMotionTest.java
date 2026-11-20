package com.jjricks.volt;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * EndlessMotion: the emulator validations' hold on the never-ending animations is off by default, so the app runs them
 * exactly as the system's animation setting says; only reflection (the validations' base class) turns it on, no app
 * source writes it, and every validation extends that base class. Arguments: the app's source folder (default src) and
 * the validations' folder (default android-tests).
 */
public final class EndlessMotionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // Off by default: the answer is the system setting's, both ways.
        require(!EndlessMotion.held(), "The hold is on by default");
        require(EndlessMotion.allowed(true), "With animations on, an endless animation may not run");
        require(!EndlessMotion.allowed(false), "With animations off, an endless animation may run");

        // The field: private, static, volatile (written from the test thread, read on the main thread), not final.
        Field held = EndlessMotion.class.getDeclaredField("held");
        int modifiers = held.getModifiers();
        require(held.getType() == boolean.class && Modifier.isPrivate(modifiers) && Modifier.isStatic(modifiers)
                && Modifier.isVolatile(modifiers) && !Modifier.isFinal(modifiers), "The hold is not a private static volatile boolean");
        // No way in from the app: no method that changes it (every method is a read).
        for (Method method : EndlessMotion.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            require(method.getReturnType() == boolean.class, "EndlessMotion." + method.getName() + " is not a read");
        }

        // The validations' way in (reflection), and back.
        held.setAccessible(true);
        try {
            held.setBoolean(null, true);
            require(EndlessMotion.held() && !EndlessMotion.allowed(true) && !EndlessMotion.allowed(false), "The hold did not hold");
        } finally {
            held.setBoolean(null, false);
        }
        require(!EndlessMotion.held() && EndlessMotion.allowed(true), "The hold did not let go");

        sources(Paths.get(args.length > 0 ? args[0] : "src"));
        validations(Paths.get(args.length > 1 ? args[1] : "android-tests"));
        System.out.println("EndlessMotion: " + checks + " checks passed");
    }

    /** No app source assigns the hold or names it for reflection; EndlessMotion only declares it. */
    private static void sources(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        require(files.size() > 50, "App sources not found under " + root.toAbsolutePath());
        Pattern write = Pattern.compile("\\bheld\\s*=[^=]");
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            if (name.equals("EndlessMotion.java")) {
                Matcher m = write.matcher(text);
                int writes = 0;
                while (m.find()) writes++;
                require(writes == 0, "EndlessMotion assigns its hold");
                require(text.contains("private static volatile boolean held;"), "The hold's declaration changed (it must start false)");
            } else {
                require(!text.contains("EndlessMotion.held ="), name + " assigns the hold");
                require(!(text.contains("EndlessMotion") && text.contains("\"held\"")), name + " reaches the hold by reflection");
            }
        }
    }

    /**
     * Every emulator validation extends ValidationInstrumentation, so a new one gets the bounded idle wait and the hold
     * too; only the base class extends Instrumentation itself.
     */
    private static void validations(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(root)) {
            files = list.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        Pattern plain = Pattern.compile("\\bextends\\s+(android\\.app\\.)?Instrumentation\\b");
        int based = 0;
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            if (name.equals("ValidationInstrumentation.java")) {
                require(text.contains("public abstract class ValidationInstrumentation extends Instrumentation"), "The base class changed");
                continue;
            }
            require(!plain.matcher(text).find(), name + " extends Instrumentation directly: extend ValidationInstrumentation");
            if (text.contains("extends ValidationInstrumentation")) based++;
        }
        require(based > 50, "Validations not found under " + root.toAbsolutePath());
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
