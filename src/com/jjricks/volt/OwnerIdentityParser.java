package com.jjricks.volt;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports only explicit local setup text or checksum-verified OEM owner-auth frames. */
public final class OwnerIdentityParser {
    private static final int MAX_INPUT = 1048576;
    private static final Pattern ID_FIELD = Pattern.compile(
            "(?im)(?<![\\w])(?:\"owner_identity\"|owner_identity|owner identity|\"identity\"|identity)\\s*:\\s*\"?([0-9a-f]{12})(?=\"?(?:\\s*(?:[,}\\r\\n]|$)))");
    private static final Pattern ID_KEY = Pattern.compile(
            "(?i)(?<![\\w])(?:\"owner_identity\"|owner_identity|owner identity|\"identity\"|identity)\\s*:");
    private static final Pattern ROLE_FIELD = Pattern.compile(
            "(?im)(?<![\\w])(?:\"role\"|role)\\s*:\\s*\"?([01])(?=\"?(?:\\s*(?:[,}\\r\\n]|$)))");
    private static final Pattern ROLE_KEY = Pattern.compile("(?i)(?<![\\w])(?:\"role\"|role)\\s*:");
    private static final Pattern SHARED_FIELD = Pattern.compile(
            "(?im)^\\s*Shared-user option\\s*:\\s*(ON|OFF)\\s*$");
    private static final String AUTH_MARKER = "Send authData:";
    /*
     * Why parse refuses a file: each is the English getMessage() of the IllegalArgumentException it throws.
     * They stay English here (the Shizuku shell process runs this parser, and logs and tests read them);
     * screens show their catalog text through SetupException.shown.
     */
    static final String EMPTY_OR_TOO_LARGE = "Setup file is empty or too large.";
    static final String NEED_HEX = "Need 12 hex characters";
    static final String NO_IDENTITY = "No owner identity in file";
    static final String UNSUPPORTED_ROLE = "Unsupported owner role";
    static final String CONFLICTING =
            "This file contains conflicting owner identities or roles. Capture one NAVEE account connection or import one private setup backup.";

    /** A file (or a capture) holding more than one identity or role; getMessage() is {@link #CONFLICTING}. */
    public static final class ConflictingIdentityException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        ConflictingIdentityException(String message) { super(message); }
    }

    public static final class Result {
        public final String identity;
        public final int role;
        Result(String identity, int role) { this.identity = identity; this.role = role; }
    }

    public static Result parse(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > MAX_INPUT)
            throw new IllegalArgumentException(EMPTY_OR_TOO_LARGE);
        String trimmed = input.trim();
        if (trimmed.matches("(?i)[0-9a-f]{12}"))
            return new Result(trimmed.toUpperCase(Locale.ROOT), 0);
        String written = written(trimmed);
        if (written != null) return new Result(written, 0);

        Matcher ids = ID_FIELD.matcher(input);
        String explicitId = null;
        int identityFields = 0;
        while (ids.find()) {
            identityFields++;
            String value = ids.group(1).toUpperCase(Locale.ROOT);
            if (explicitId != null && !explicitId.equals(value)) ambiguous();
            explicitId = value;
        }
        if (count(ID_KEY, input) != identityFields)
            throw new IllegalArgumentException(NEED_HEX);
        if (explicitId != null) return new Result(explicitId, explicitRole(input));

        Result found = null;
        for (String line : input.split("\\r?\\n")) {
            int at = line.indexOf(AUTH_MARKER);
            if (at < 0) continue;
            String candidate = line.substring(at + AUTH_MARKER.length()).trim();
            if (candidate.startsWith("[")) {
                int end = candidate.indexOf(']');
                if (end < 0) continue;
                candidate = candidate.substring(1, end).trim();
            }
            // Never manufacture a packet by stripping arbitrary log text into hex.
            if (!candidate.matches("(?i)[0-9a-f]{2}(?:[\\s:-]*[0-9a-f]{2}){16}")) continue;
            byte[] p;
            try { p = Protocol.fromHex(candidate); } catch (RuntimeException invalid) { continue; }
            if (p.length != 17 || (p[0] & 255) != 0x55 || (p[1] & 255) != 0xaa
                    || p[2] != 0 || (p[3] & 255) != 0x30 || p[4] != 9
                    || (p[5] & 255) > 4 || (p[6] & 255) > 1 || p[13] != 0
                    || (p[15] & 255) != 0xfe || (p[16] & 255) != 0xfd) continue;
            int sum = 0;
            for (int i = 0; i < 14; i++) sum += p[i] & 255;
            if ((sum & 255) != (p[14] & 255)) continue;
            Result captured = new Result(Protocol.hex(Arrays.copyOfRange(p, 7, 13))
                    .toUpperCase(Locale.ROOT), p[6] & 255);
            if (found != null && (!found.identity.equals(captured.identity) || found.role != captured.role))
                ambiguous();
            found = captured;
        }
        if (found == null) throw new IllegalArgumentException(NO_IDENTITY);
        return found;
    }

    /**
     * A key as a rider writes it down from setup's "Write down your owner key" (MadeKeyPrompt): six hex byte pairs
     * with one separator between each, the same one throughout: a space, a colon or a dash ("88 6B 33 AF 01 C2",
     * "88:6b:33:af:01:c2", "88-6B-33-AF-01-C2"). The whole trimmed input, nothing around it: like the 12-digit form,
     * never a key picked out of longer text. Upper-case, or null.
     */
    private static String written(String trimmed) {
        if (trimmed.length() != 17) return null;
        char separator = trimmed.charAt(2);
        // Space, colon, dash (as code points: the language gates keep new char literals out of src).
        if (separator != 0x20 && separator != 0x3a && separator != 0x2d) return null;
        StringBuilder key = new StringBuilder(12);
        for (int i = 0; i < 17; i++) {
            char c = trimmed.charAt(i);
            if (i % 3 == 2) { if (c != separator) return null; continue; }
            if (c >= 0x80 || Character.digit(c, 16) < 0) return null;
            key.append(c);
        }
        return key.toString().toUpperCase(Locale.ROOT);
    }

    private static int explicitRole(String input) {
        int role = -1;
        Matcher roles = ROLE_FIELD.matcher(input);
        int roleFields = 0;
        while (roles.find()) {
            roleFields++;
            int value = Integer.parseInt(roles.group(1));
            if (role != -1 && role != value) ambiguous();
            role = value;
        }
        if (count(ROLE_KEY, input) != roleFields)
            throw new IllegalArgumentException(UNSUPPORTED_ROLE);
        Matcher shared = SHARED_FIELD.matcher(input);
        while (shared.find()) {
            int value = "ON".equalsIgnoreCase(shared.group(1)) ? 1 : 0;
            if (role != -1 && role != value) ambiguous();
            role = value;
        }
        return role == -1 ? 0 : role;
    }

    private static int count(Pattern pattern, String input) {
        int result = 0;
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) result++;
        return result;
    }

    private static void ambiguous() {
        throw new ConflictingIdentityException(
                CONFLICTING);
    }
    private OwnerIdentityParser() { }
}
