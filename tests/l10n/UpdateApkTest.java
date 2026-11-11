package com.jjricks.volt;

/**
 * Download in the update dialog: the one APK address Volt hands the browser, taken from GitHub's reply only when it
 * lists exactly that file, and the fallback to the release page otherwise. Synthesized replies; never touches the network.
 */
public final class UpdateApkTest {
    private static int assertions;
    private static final String PAGE = "https://github.com/JJRicks/volt/releases/tag/v1.13.1";
    private static final String APK = "https://github.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.apk";

    public static void main(String[] args) throws Exception {
        UpdateCheck.fetcher = (url, agent) -> { throw new AssertionError("Tests must not fetch " + url); };
        expected(); downloads(); replies(); fallbacks(); realisticReply();
        System.out.println("UpdateApk tests passed: " + assertions + " assertions");
    }

    private static String asset(String name, String url, String state) {
        return "{\"url\":\"https://api.github.com/repos/JJRicks/volt/releases/assets/1\",\"id\":1,\"name\":" + BackupJson.quote(name)
                + ",\"label\":\"\",\"uploader\":{\"login\":\"someone\",\"id\":2,\"type\":\"User\",\"site_admin\":false},"
                + "\"content_type\":\"application/vnd.android.package-archive\",\"state\":" + BackupJson.quote(state)
                + ",\"size\":4799436,\"download_count\":0,\"created_at\":\"2026-11-11T00:00:00Z\",\"updated_at\":\"2026-11-11T00:00:00Z\","
                + "\"browser_download_url\":" + BackupJson.quote(url) + "}";
    }
    private static String assetsOf(String... assets) { return "[" + String.join(",", assets) + "]"; }
    private static String reply(String tag, String assets, String body) {
        return "{\"url\":\"https://api.github.com/repos/JJRicks/volt/releases/1\",\"html_url\":\"https://github.com/JJRicks/volt/releases/tag/" + tag + "\","
                + "\"id\":1,\"author\":{\"login\":\"someone\",\"id\":2,\"site_admin\":false},\"tag_name\":" + BackupJson.quote(tag)
                + ",\"name\":\"Volt\",\"draft\":false,\"prerelease\":false,\"created_at\":\"2026-11-11T00:00:00Z\""
                + (assets == null ? "" : ",\"assets\":" + assets) + ",\"body\":" + BackupJson.quote(body) + "}";
    }
    private static String standard(String tag) {
        String version = tag.startsWith("v") ? tag.substring(1) : tag, base = "https://github.com/JJRicks/volt/releases/download/" + tag + "/";
        return assetsOf(asset("SHA256SUMS", base + "SHA256SUMS", "uploaded"), asset("volt-" + version + "-source.zip", base + "volt-" + version + "-source.zip", "uploaded"),
                asset("volt-" + version + ".apk", base + "volt-" + version + ".apk", "uploaded"));
    }

    private static void expected() {
        check(APK.equals(UpdateCheck.expectedApk("v1.13.1")), "v1.13.1 names volt-1.13.1.apk under its own tag");
        check("https://github.com/JJRicks/volt/releases/download/1.13.1/volt-1.13.1.apk".equals(UpdateCheck.expectedApk("1.13.1")), "A tag without v keeps its name");
        for (String tag : new String[]{null, "", "v", "v1..3", "..", "v1.13.1/../../evil", "v1.13.1?x", "v1.13.1#x", "v1 13", "v1.13.1%2F", "v1/2",
                "v1.13.1١", "vé1", "v1.13.1@evil.example", "v1:2", "v1\\2"})
            check(UpdateCheck.expectedApk(tag) == null, "Refused tag: " + tag);
        StringBuilder longTag = new StringBuilder("v1"); while (longTag.length() < 120) longTag.append(".0");
        check(UpdateCheck.expectedApk(longTag.toString()) == null, "A tag that makes the address too long");
    }

    private static void downloads() {
        check(UpdateCheck.safeDownload(APK) && UpdateCheck.safeDownload("https://github.com/JJRicks/volt/releases/download/v2.0.0/volt-2.0.0.apk"), "Volt's APK addresses");
        for (String url : new String[]{null, "", "https://github.com/JJRicks/volt/releases/download/v1.13.1/SHA256SUMS",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1-source.zip",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.0.apk",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/volt.apk",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/Volt-1.13.1.apk",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.APK",
                "https://github.com/JJRicks/Volt/releases/download/v1.13.1/volt-1.13.1.apk",
                "https://GitHub.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.apk",
                "http://github.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.apk",
                "https://github.com.evil.example/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.apk",
                "https://github.com/JJRicks/volt-fake/releases/download/v1.13.1/volt-1.13.1.apk",
                "https://github.com/Other/volt/releases/download/v1.13.1/volt-1.13.1.apk",
                "https://objects.githubusercontent.com/github-production-release-asset/volt-1.13.1.apk",
                APK + "?x=1", APK + "#x", APK + "/", " " + APK, APK + " ",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/../v1.13.1/volt-1.13.1.apk",
                "https://github.com/JJRicks/volt/releases/download/../download/v1.13.1/volt-1.13.1.apk",
                "https://github.com/JJRicks/volt/releases/download/v1..13/volt-1..13.apk",
                "https://github.com/JJRicks/volt/releases/download//volt-.apk",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.apk/volt-1.13.1.apk",
                "https://github.com/JJRicks/volt/releases/download/v1.13.1%2Fx/volt-1.13.1%2Fx.apk",
                "https://github.com/JJRicks/volt/releases/latest/download/volt-1.13.1.apk",
                "https://github.com/JJRicks/volt/releases/tag/v1.13.1", PAGE})
            check(!UpdateCheck.safeDownload(url), "Refused download: " + url);
        StringBuilder longTag = new StringBuilder("v1"); while (longTag.length() < 120) longTag.append(".0");
        String tag = longTag.toString();
        check(!UpdateCheck.safeDownload("https://github.com/JJRicks/volt/releases/download/" + tag + "/volt-" + tag.substring(1) + ".apk"), "An over-long address");
        // The release page rule is unchanged: an APK is never a page, and a page never a download.
        check(!UpdateCheck.safePage(APK) && UpdateCheck.safePage(PAGE), "safePage still refuses downloads");
    }

    private static void replies() {
        UpdateCheck.Result r = UpdateCheck.evaluate("1.13.0", reply("v1.13.1", standard("v1.13.1"), "Notes"));
        check(r.outcome == UpdateCheck.Outcome.AVAILABLE && r.url.equals(PAGE) && r.download.equals(APK) && r.direct() && r.target().equals(APK),
                "A newer release with its APK: Download opens the APK, never SHA256SUMS or the source zip");
        UpdateCheck.Result order = UpdateCheck.evaluate("1.13.0", reply("v1.13.1", assetsOf(asset("volt-1.13.1.apk", APK, "uploaded"),
                asset("SHA256SUMS", "https://github.com/JJRicks/volt/releases/download/v1.13.1/SHA256SUMS", "uploaded")), ""));
        check(APK.equals(order.download), "The APK is found wherever it sits in the list");
        UpdateCheck.Result bare = UpdateCheck.evaluate("1.12.1", reply("1.13.1", assetsOf(asset("volt-1.13.1.apk",
                "https://github.com/JJRicks/volt/releases/download/1.13.1/volt-1.13.1.apk", "uploaded")), ""));
        check("https://github.com/JJRicks/volt/releases/download/1.13.1/volt-1.13.1.apk".equals(bare.download), "A tag without v");
        UpdateCheck.Result current = UpdateCheck.evaluate("1.13.1", reply("v1.13.1", standard("v1.13.1"), ""));
        check(current.outcome == UpdateCheck.Outcome.CURRENT, "The installed release is still current; Download is not offered there");
        check(UpdateCheck.Result.unreadable().download.isEmpty() && !UpdateCheck.Result.unreadable().direct(), "An unreadable reply has no download");
    }

    private static void fallbacks() {
        String other = "https://github.com/JJRicks/volt/releases/download/v1.13.1/";
        String[] noApk = {
                null,                                   // no assets key at all
                "[]",                                   // nothing uploaded yet
                "\"volt-1.13.1.apk\"", "{}", "null", "7", "[null,1,\"x\",[]]",
                assetsOf(asset("volt-1.13.1.apk", APK, "starter")),          // still uploading
                assetsOf(asset("volt-1.13.1.apk", APK, "")),
                "[{\"name\":\"volt-1.13.1.apk\",\"browser_download_url\":\"" + APK + "\"}]", // no state
                "[{\"name\":\"volt-1.13.1.apk\",\"state\":\"uploaded\",\"browser_download_url\":7}]",
                "[{\"name\":\"volt-1.13.1.apk\",\"state\":\"uploaded\",\"browser_download_url\":null}]",
                assetsOf(asset("volt.apk", other + "volt.apk", "uploaded")),                              // renamed file
                assetsOf(asset("volt-1.13.1-release.apk", other + "volt-1.13.1-release.apk", "uploaded")),
                assetsOf(asset("volt-1.13.0.apk", "https://github.com/JJRicks/volt/releases/download/v1.13.0/volt-1.13.0.apk", "uploaded")),
                assetsOf(asset("SHA256SUMS", other + "SHA256SUMS", "uploaded"), asset("volt-1.13.1-source.zip", other + "volt-1.13.1-source.zip", "uploaded")),
                assetsOf(asset("volt-1.13.1.apk", "https://evil.example/JJRicks/volt/releases/download/v1.13.1/volt-1.13.1.apk", "uploaded")),
                assetsOf(asset("volt-1.13.1.apk", APK + "?x", "uploaded"), asset("volt-1.13.1.apk", APK + "#x", "uploaded"),
                        asset("volt-1.13.1.apk", APK.toUpperCase(java.util.Locale.ROOT), "uploaded"), asset("volt-1.13.1.apk", " " + APK, "uploaded")),
                assetsOf(asset("volt-1.13.1.apk", "https://github.com/JJRicks/volt/releases/download/v1.13.1/../v1.13.1/volt-1.13.1.apk", "uploaded")),
        };
        for (String assets : noApk) {
            UpdateCheck.Result r = UpdateCheck.evaluate("1.13.0", reply("v1.13.1", assets, "Notes"));
            check(r.outcome == UpdateCheck.Outcome.AVAILABLE && r.download.isEmpty() && !r.direct() && r.target().equals(PAGE) && UpdateCheck.safePage(r.target()),
                    "No acceptable APK falls back to the release page: " + assets);
        }
        // A tag Volt would refuse for the download still reads as before when its page is safe.
        UpdateCheck.Result odd = UpdateCheck.evaluate("1.13.0", "{\"html_url\":\"https://github.com/JJRicks/volt/releases/latest\",\"tag_name\":\"v1.13.1é\","
                + "\"draft\":false,\"prerelease\":false,\"assets\":[{\"state\":\"uploaded\",\"browser_download_url\":\"" + APK + "\"}],\"body\":\"\"}");
        check(odd.outcome == UpdateCheck.Outcome.AVAILABLE && !odd.direct() && odd.target().equals("https://github.com/JJRicks/volt/releases/latest"),
                "A tag outside the APK rule keeps the page");
        // An asset for another tag never matches this one's expected address.
        UpdateCheck.Result mismatch = UpdateCheck.evaluate("1.13.0", reply("v1.13.1", standard("v1.13.2"), ""));
        check(!mismatch.direct() && mismatch.target().equals(PAGE), "Another release's APK is never offered");
    }

    /** GitHub's real reply carries every asset with its uploader: well inside the size cap and BackupJson's depth of 8. */
    private static void realisticReply() {
        StringBuilder notes = new StringBuilder();
        while (notes.length() < 12000) notes.append("- **Riding mode** shows a longer line of release notes, as a real release might.\n");
        String json = reply("v1.13.1", standard("v1.13.1"), notes.toString());
        check(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < UpdateCheck.MAX_BYTES / 8, "A long reply with three assets is far below the 256 KB cap");
        UpdateCheck.Result r = UpdateCheck.evaluate("1.13.0", json);
        check(r.outcome == UpdateCheck.Outcome.AVAILABLE && r.download.equals(APK), "A long reply with assets still reads (assets reach depth 4 of 8)");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 40; i++) many.append(asset("extra-" + i + ".bin", "https://github.com/JJRicks/volt/releases/download/v1.13.1/extra-" + i + ".bin", "uploaded")).append(',');
        many.append(asset("volt-1.13.1.apk", APK, "uploaded")).append(']');
        String crowded = reply("v1.13.1", many.toString(), notes.toString());
        check(crowded.length() < UpdateCheck.MAX_BYTES && APK.equals(UpdateCheck.evaluate("1.13.0", crowded).download), "Forty more assets still fit and still match");
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
