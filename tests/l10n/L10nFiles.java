package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers for the tests/l10n suites: repository files, TSV, a small JSON reader and Java source scanning. */
final class L10nFiles {
    private L10nFiles() {}

    /** The repository root: tests/run.sh runs these suites from it. */
    static File root() {
        File here = new File("").getAbsoluteFile();
        if (!new File(here, "src/com/jjricks/volt").isDirectory()) throw new IllegalStateException("run from the repository root: " + here);
        return here;
    }

    static String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("cannot read " + file, error); }
    }

    static byte[] bytes(File file) {
        try { return Files.readAllBytes(file.toPath()); }
        catch (IOException error) { throw new IllegalStateException("cannot read " + file, error); }
    }

    /** The .java files of src/com/jjricks/volt by file name, sorted. */
    static Map<String, String> appSources() {
        File dir = new File(root(), "src/com/jjricks/volt");
        String[] names = dir.list((d, n) -> n.endsWith(".java"));
        if (names == null || names.length < 40) throw new IllegalStateException("application sources missing in " + dir);
        Arrays.sort(names);
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) result.put(name, read(new File(dir, name)));
        return result;
    }

    /** Rows of a TSV with a header (blank and # lines skipped): each row maps header name to raw field. */
    static List<Map<String, String>> tsv(String text, String... header) {
        List<Map<String, String>> rows = new ArrayList<>();
        String[] names = null;
        int number = 0;
        for (String line : text.split("\n", -1)) {
            number++;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (names == null) {
                names = fields;
                if (header.length > 0 && !Arrays.equals(names, header))
                    throw new IllegalArgumentException("line " + number + ": expected header " + String.join(",", header));
                continue;
            }
            if (fields.length != names.length) throw new IllegalArgumentException("line " + number + ": expected " + names.length + " fields");
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < names.length; i++) row.put(names[i], fields[i]);
            row.put("#line", Integer.toString(number));
            rows.add(row);
        }
        return rows;
    }

    /** tools/l10n/l10nlib.unescape_field: backslash escapes for tab, line feed, carriage return, backslash, \\uXXXX. */
    static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { out.append(c); continue; }
            char e = value.charAt(++i);
            if (e == 't') out.append('\t');
            else if (e == 'n') out.append('\n');
            else if (e == 'r') out.append('\r');
            else if (e == '\\') out.append('\\');
            else if (e == 'u' && i + 4 < value.length()) { out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16)); i += 4; }
            else throw new IllegalArgumentException("unknown escape \\" + e);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // JSON: objects become LinkedHashMap, arrays ArrayList, numbers Long or Double, plus String, Boolean, null.

    static Object json(String text) {
        int[] at = {0};
        Object value = value(text, at);
        skip(text, at);
        if (at[0] != text.length()) throw new IllegalArgumentException("trailing JSON at " + at[0]);
        return value;
    }

    private static Object value(String s, int[] at) {
        skip(s, at);
        if (at[0] >= s.length()) throw new IllegalArgumentException("JSON ends early");
        char c = s.charAt(at[0]);
        if (c == '{') {
            Map<String, Object> map = new LinkedHashMap<>();
            at[0]++; skip(s, at);
            if (s.charAt(at[0]) == '}') { at[0]++; return map; }
            while (true) {
                skip(s, at);
                Object key = value(s, at);
                if (!(key instanceof String)) throw new IllegalArgumentException("JSON key at " + at[0]);
                skip(s, at); expect(s, at, ':');
                map.put((String) key, value(s, at));
                skip(s, at);
                if (s.charAt(at[0]) == ',') { at[0]++; continue; }
                expect(s, at, '}');
                return map;
            }
        }
        if (c == '[') {
            List<Object> list = new ArrayList<>();
            at[0]++; skip(s, at);
            if (s.charAt(at[0]) == ']') { at[0]++; return list; }
            while (true) {
                list.add(value(s, at));
                skip(s, at);
                if (s.charAt(at[0]) == ',') { at[0]++; continue; }
                expect(s, at, ']');
                return list;
            }
        }
        if (c == '"') {
            StringBuilder out = new StringBuilder();
            at[0]++;
            while (true) {
                char d = s.charAt(at[0]++);
                if (d == '"') return out.toString();
                if (d != '\\') { out.append(d); continue; }
                char e = s.charAt(at[0]++);
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u': out.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16)); at[0] += 4; break;
                    default: throw new IllegalArgumentException("JSON escape \\" + e);
                }
            }
        }
        if (s.startsWith("true", at[0])) { at[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", at[0])) { at[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", at[0])) { at[0] += 4; return null; }
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        String number = s.substring(start, at[0]);
        if (number.isEmpty()) throw new IllegalArgumentException("JSON value at " + start);
        return number.matches("-?\\d+") ? (Object) Long.valueOf(number) : (Object) Double.valueOf(number);
    }

    private static void skip(String s, int[] at) { while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++; }

    private static void expect(String s, int[] at, char c) {
        if (at[0] >= s.length() || s.charAt(at[0]) != c) throw new IllegalArgumentException("JSON expected " + c + " at " + at[0]);
        at[0]++;
    }

    // ------------------------------------------------------------------------------------------------
    // Java source scanning

    /**
     * The source with comments removed and every string, text block and char literal's contents blanked
     * (the quotes stay), so code patterns never match inside text. Line breaks are kept.
     */
    static String code(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0, n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) if (source.charAt(k) == '\n') out.append('\n');
                out.append(' ');
                i = end;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append("\"\"");
                for (int k = i; k < end; k++) if (source.charAt(k) == '\n') out.append('\n');
                i = end;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && source.charAt(i) != c) {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                out.append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The index just after the parenthesis that closes the one at open, or -1. */
    static int closing(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i + 1;
        }
        return -1;
    }

    static int line(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
