package com.jjricks.volt;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The generated catalog and its runtime: every T accessor renders its English with sentinel arguments,
 * M builds the matching Msg, sources fall back to English, the l10n_tag parse is defensive, ladder groups
 * are translated whole, and locales_config.xml lists exactly the shipped languages.
 */
public final class CatalogCheckTest {
    private static int checks;
    private static final String VOLT_NS = "https://volt.jjricks.com/ns/l10n";
    private static final Set<String> NOT_ACCESSORS = new HashSet<>(Arrays.asList("render", "renderPlural", "key", "language"));

    public static void main(String[] args) throws Exception {
        try {
            accessors();
            messages();
            sources();
            languages();
            File res = new File(L10nFiles.root(), "res");
            ladders(res);
            localesConfig(res);
        } finally {
            T.install(null);
        }
        System.out.println("CatalogCheckTest: " + checks + " checks passed (" + T.size() + " catalog ids)");
    }

    /** One accessor per id, named camelCase(key), whose output is the English rendered with its arguments. */
    private static void accessors() throws Exception {
        check(T.size() > 0 && T.source() == EnglishText.INSTANCE, "the catalog is not empty and English is the default source");
        Map<String, Method> byName = publicAccessors(T.class, String.class);
        check(byName.size() == T.size(), "T has one accessor per id: " + byName.size() + " vs " + T.size());
        for (int id = 0; id < T.size(); id++) {
            String key = T.key(id);
            check(key != null && key.matches("[a-z][a-z0-9]*(_[a-z0-9]+)*"), "id " + id + " has a resource key");
            check(T.class.getField(key.toUpperCase(Locale.ROOT)).getInt(null) == id, key + " constant is its id");
            Method accessor = byName.get(camel(key));
            check(accessor != null, key + " has accessor " + camel(key));
            Class<?>[] types = accessor.getParameterTypes();
            boolean plural = T.isPlural(id);
            check(plural == (types.length > 0 && types[0] == long.class && EnglishText.INSTANCE.string(id) == null), key + " plural flag matches");
            for (long quantity : plural ? new long[]{0, 1, 2, 21} : new long[]{0}) {
                Object[] call = sentinels(types, plural, quantity);
                String rendered = (String) accessor.invoke(null, call);
                Object[] values = plural ? Arrays.copyOfRange(call, 1, call.length) : call;
                String raw = plural ? EnglishText.INSTANCE.plural(id, quantity) : EnglishText.INSTANCE.string(id);
                check(raw != null, key + " has English");
                String expected = T.isFormatted(id) ? String.format(Locale.ROOT, raw, values) : raw;
                check(rendered.equals(expected), key + " renders its English: " + rendered);
                check(T.isFormatted(id) == raw.contains("%") || !T.isFormatted(id), key + " is formatted only when its text has %");
                check(!Pattern.compile("%(\\d+\\$)?[sdf]").matcher(rendered).find(), key + " leaves no placeholder unfilled");
                if (!plural || quantity != 1) {
                    for (Object value : values)
                        if (value instanceof CharSequence) check(rendered.contains((CharSequence) value), key + " shows every text argument");
                }
            }
        }
    }

    /** M mirrors T: the same accessors, returning a Msg with the same id and arguments that renders the same text. */
    private static void messages() throws Exception {
        Map<String, Method> t = publicAccessors(T.class, String.class), m = publicAccessors(M.class, Msg.class);
        check(t.keySet().equals(m.keySet()), "M has exactly T's accessors");
        for (int id = 0; id < T.size(); id++) {
            Method tm = t.get(camel(T.key(id))), mm = m.get(camel(T.key(id)));
            check(Arrays.equals(tm.getParameterTypes(), mm.getParameterTypes()), T.key(id) + ": M and T take the same parameters");
            boolean plural = T.isPlural(id);
            Object[] call = sentinels(tm.getParameterTypes(), plural, 2);
            Msg message = (Msg) mm.invoke(null, call);
            check(message.id() == id && message.isPlural() == plural && message.render().equals(tm.invoke(null, call)), T.key(id) + ": M renders as T");
            check(message.toString().equals(message.render()) && message.length() == message.render().length(), T.key(id) + ": a Msg reads as its text");
            check(message.equals(mm.invoke(null, call)) && message.hashCode() == mm.invoke(null, call).hashCode(), T.key(id) + ": equal arguments make equal messages");
            check(message.english().equals(message.render()), T.key(id) + ": English renders the same while English is installed");
        }
        // A Msg argument renders inside another message, at display time, in the then-current language.
        int quantity = T.FMT_QUANTITY;
        Msg inner = Msg.of(T.COMMON_ON), outer = Msg.of(quantity, inner, Msg.verbatim("km"));
        check(outer.render().equals("On km"), "nested messages render: " + outer.render());
        T.install(echo());
        check(outer.render().equals("‹fmt_quantity›") && outer.english().equals("On km"), "a later language change reaches stored messages; english() stays English");
        T.install(null);
        UserFacingException error = new UserFacingException(Msg.of(T.COMMON_TRY_AGAIN));
        check("Try again".equals(error.getMessage()) && error.msg().id() == T.COMMON_TRY_AGAIN, "UserFacingException keeps English in getMessage()");
        T.install(echo());
        check("Try again".equals(new UserFacingException(Msg.of(T.COMMON_TRY_AGAIN)).getMessage()), "getMessage() stays English under another source");
        T.install(null);
        check(!Msg.of(T.COMMON_ON).equals(Msg.of(T.COMMON_OFF)) && Msg.verbatim("Ollie").render().equals("Ollie"), "messages compare by id; verbatim text shows as it is");
        // Appending a Msg to a StringBuilder reads it one character at a time: it renders once, not per character.
        final int[] renders = {0};
        T.Source counting = new T.Source() {
            public String string(int id) { renders[0]++; return EnglishText.INSTANCE.string(id); }
            public String plural(int id, long q) { renders[0]++; return EnglishText.INSTANCE.plural(id, q); }
            public String language() { return "en"; }
        };
        T.install(counting);
        Msg wait = Msg.of(T.COMMON_TRY_AGAIN);
        String appended = new StringBuilder().append(wait).toString();
        check(appended.equals("Try again") && renders[0] == 1, "StringBuilder.append(Msg) renders once: " + renders[0] + " renders");
        T.install(echo());
        check(new StringBuilder().append(wait).toString().equals("‹common_try_again›") && wait.length() == "‹common_try_again›".length(),
                "a language change still reaches a Msg read as a CharSequence");
        T.install(null);
        rejects(() -> Msg.of(-1), "a negative id is refused");
        rejects(() -> Msg.of(T.size()), "an id past the catalog is refused");
    }

    /** Installed sources: a missing id falls back to English; null restores English; % text always formats. */
    private static void sources() {
        T.install(echo());
        check(T.commonCancel().equals("‹common_cancel›"), "an installed source is read");
        check(T.language().equals("xx"), "the source's language is reported");
        T.install(new T.Source() {
            public String string(int id) { return null; }
            public String plural(int id, long quantity) { return null; }
            public String language() { return "xx"; }
        });
        check(T.commonCancel().equals("Cancel") && T.fmtPercent("75").equals("75%"), "a source without an id falls back to English");
        T.install(null);
        check(T.source() == EnglishText.INSTANCE && T.language().equals("en"), "null restores English");
        check(T.fmtPercent("75").equals("75%"), "%% renders as one percent sign");
        rejects(() -> T.renderPlural(T.COMMON_CANCEL, 1), "a string id is not a plural");
    }

    /** AppLanguages.resolve: only an exact shipped tag counts; pseudo-locales' mangled tags read as English. */
    private static void languages() {
        check(AppLanguages.size() >= 1 && AppLanguages.tag(0).equals("en") && AppLanguages.shipped(0), "English is first and ships");
        for (String tag : new String[]{null, "", "EN", "en-US", "en-XA", "[éñ one two]", "ar-XB", "‮neb‬", "xx"})
            check(AppLanguages.resolve(tag).equals("en"), "resolve(" + tag + ") is English");
        check(AppLanguages.resolve("en").equals("en"), "resolve(en) is English");
        for (int i = 0; i < AppLanguages.size(); i++)
            check(AppLanguages.resolve(AppLanguages.tag(i)).equals(AppLanguages.shipped(i) ? AppLanguages.tag(i) : "en"), AppLanguages.tag(i) + " resolves only when it ships");
    }

    /** A translation that has any rung of a ladder group (full, _short, _compact) must have every rung. */
    private static void ladders(File res) throws Exception {
        Map<String, Element> english = strings(new File(res, "values"));
        Map<String, List<String>> groups = new HashMap<>();
        for (Map.Entry<String, Element> e : english.entrySet()) {
            String ladder = e.getValue().getAttributeNS(VOLT_NS, "ladder");
            if (!ladder.equals("short") && !ladder.equals("compact")) continue;
            String full = e.getKey().substring(0, e.getKey().length() - ladder.length() - 1);
            check(english.containsKey(full), e.getKey() + " has its full key " + full);
            groups.computeIfAbsent(full, k -> new ArrayList<>(Arrays.asList(k))).add(e.getKey());
        }
        for (File folder : languageFolders(res)) {
            Set<String> translated = strings(folder).keySet();
            for (List<String> group : groups.values()) {
                int present = 0;
                for (String key : group) if (translated.contains(key)) present++;
                check(present == 0 || present == group.size(), folder.getName() + " translates all of " + group + " or none");
            }
            for (String key : translated) check(english.containsKey(key) || key.equals("l10n_tag"), folder.getName() + "/" + key + " exists in English");
        }
    }

    /** res/xml/locales_config.xml lists exactly the shipped languages, and only they have resource folders. */
    private static void localesConfig(File res) throws Exception {
        Set<String> shipped = new TreeSet<>(), folders = new TreeSet<>();
        Map<String, String> folderOf = new HashMap<>();
        for (int i = 0; i < AppLanguages.size(); i++) {
            if (AppLanguages.shipped(i)) shipped.add(AppLanguages.tag(i));
            folderOf.put("values-" + AppLanguages.tag(i), AppLanguages.tag(i));
        }
        for (String line : L10nFiles.read(new File(L10nFiles.root(), "l10n/lang/en.tsv")).split("\n"))
            if (line.startsWith("folder\t")) check(line.equals("folder\tvalues"), "English is res/values");
        Set<String> listed = new TreeSet<>();
        Element root = parse(new File(res, "xml/locales_config.xml")).getDocumentElement();
        check(root.getTagName().equals("locale-config"), "locales_config.xml is a locale-config");
        NodeList locales = root.getElementsByTagName("locale");
        for (int i = 0; i < locales.getLength(); i++)
            listed.add(((Element) locales.item(i)).getAttributeNS("http://schemas.android.com/apk/res/android", "name"));
        check(listed.equals(shipped), "locales_config.xml lists the shipped languages " + shipped + ", not " + listed);
        for (File folder : languageFolders(res)) folders.add(folder.getName());
        for (String folder : folders) {
            String tag = languageOfFolder(folder);
            check(tag != null && shipped.contains(tag) && !tag.equals("en"), folder + " belongs to a shipped, non-English language");
        }
        for (String tag : shipped) if (!tag.equals("en")) check(folders.stream().anyMatch(f -> tag.equals(languageOfFolder(f))), tag + " ships with a resource folder");
        String manifest = L10nFiles.read(new File(L10nFiles.root(), "AndroidManifest.xml"));
        check(manifest.contains("android:localeConfig") == (shipped.size() > 1),
                "the manifest names localeConfig only once a language besides English ships");
    }

    private static String languageOfFolder(String folder) {
        for (int i = 0; i < AppLanguages.size(); i++)
            if (folder.equals(AppLanguages.FOLDERS[i])) return AppLanguages.tag(i);
        return null;
    }

    /** res/values-* folders with any strings*.xml file. */
    private static List<File> languageFolders(File res) {
        List<File> result = new ArrayList<>();
        File[] folders = res.listFiles();
        if (folders == null) return result;
        Arrays.sort(folders);
        for (File folder : folders) {
            String[] files = folder.list((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
            if (folder.getName().startsWith("values-") && files != null && files.length > 0) result.add(folder);
        }
        return result;
    }

    private static Map<String, Element> strings(File folder) throws Exception {
        Map<String, Element> result = new HashMap<>();
        File[] files = folder.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files == null) return result;
        for (File file : files) {
            NodeList nodes = parse(file).getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element && (((Element) node).getTagName().equals("string") || ((Element) node).getTagName().equals("plurals")))
                    result.put(((Element) node).getAttribute("name"), (Element) node);
            }
        }
        return result;
    }

    private static org.w3c.dom.Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file);
    }

    static Map<String, Method> publicAccessors(Class<?> type, Class<?> returns) {
        Map<String, Method> result = new HashMap<>();
        for (Method method : type.getDeclaredMethods()) {
            int mod = method.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && method.getReturnType() == returns && !NOT_ACCESSORS.contains(method.getName()))
                result.put(method.getName(), method);
        }
        return result;
    }

    /** Sentinel arguments by type: text \u0001A, \u0001B, ...; whole numbers 1234567; decimals 12.5. */
    static Object[] sentinels(Class<?>[] types, boolean plural, long quantity) {
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (plural && i == 0) values[i] = quantity;
            else if (types[i] == CharSequence.class) values[i] = "\u0001" + (char) ('A' + i);
            else if (types[i] == long.class) values[i] = 1234567L;
            else if (types[i] == double.class) values[i] = 12.5;
            else throw new IllegalStateException("unexpected parameter type " + types[i]);
        }
        return values;
    }

    static String camel(String key) {
        Matcher m = Pattern.compile("_([a-z0-9])").matcher(key);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, m.group(1).toUpperCase(Locale.ROOT));
        m.appendTail(out);
        return out.toString();
    }

    /** A source that answers every id with its key in ‹›, as a language that is not English would. */
    static T.Source echo() {
        return new T.Source() {
            public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›"; }
            public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "›" : null; }
            public String language() { return "xx"; }
        };
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
