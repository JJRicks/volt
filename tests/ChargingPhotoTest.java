package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ChargingPhoto: the owner's photo rule for Clean Home's charging view (#383, 6849341616), over every bundled photo.
 * Every photo ScooterPhotos bundles (read from its source, the first argument) declares its kind and bounds; the
 * scooter spans the stage ChargingFit gives it (8 dp under the line to 8 dp above the track); three-quarter photos keep
 * the picture's bottom right corner on the content edge, side views put the scooter's own right edge there; and the
 * scooter never reaches the percentage, at 344, 412 and 448 dp wide, 1x and 1.4x text.
 *
 * <p>The stage comes from ChargingFit with the same estimates as ChargingFitTest (the line at 12.5 sp, the percentage
 * at ChargingFit.PERCENT_SP, a tile at 10 + label + 2 + value + 10 dp, 1.17 times each text size), and the window
 * height from the phone each width stands for (344x882, 412x915, 448x998 dp). The percentage's width is estimated for
 * Saira bold (digits 0.6 em, the % at 0.4 size 0.8 em), for "78%" and the widest, "100%".
 */
public final class ChargingPhotoTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        declared(args[0]);
        kinds();
        for (int[] window : new int[][]{{344, 882}, {412, 915}, {448, 998}})
            for (float text : new float[]{1f, 1.4f}) placed(window[0], window[1], text);
        rule();
        System.out.println("ChargingPhoto: " + checks + " checks passed");
    }

    /** Every bundled photo has a kind and bounds here, and nothing is declared for a photo that is not bundled. */
    private static void declared(String scooterPhotos) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(scooterPhotos)), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("case \"([a-z0-9_]+)\": return R\\.drawable\\.").matcher(source);
        Set<String> bundled = new LinkedHashSet<>();
        while (m.find()) bundled.add(m.group(1));
        require(bundled.size() >= 7, "ScooterPhotos lists its photos: " + bundled);
        for (String key : bundled) {
            ChargingPhoto.Photo photo = ChargingPhoto.of(key);
            require(photo != null, "the bundled photo " + key + " declares its kind and bounds in ChargingPhoto");
            require(photo.kind != null, key + " has a kind");
            require(photo.left >= 0 && photo.top >= 0 && photo.right <= 1 && photo.bottom <= 1
                    && photo.left < photo.right && photo.top < photo.bottom, key + ": bounds inside the picture");
            require(photo.height() > .5f && photo.width() > .4f, key + ": the scooter fills most of its picture");
        }
        for (String key : ChargingPhoto.all().keySet()) require(bundled.contains(key), "ChargingPhoto declares only bundled photos: " + key);
        require(ChargingPhoto.of("nonesuch") == null && ChargingPhoto.of(null) == null, "no declaration, no photo");
    }

    /** The owner's list: four three-quarter photos, three long side views. */
    private static void kinds() {
        for (String key : new String[]{"ut5max", "ut5ultrax", "st3pro", "n65i"})
            require(ChargingPhoto.of(key).kind == ChargingPhoto.Kind.THREE_QUARTER, key + " is a three-quarter photo");
        for (String key : new String[]{"gt3max", "gt5max", "xt5ultra"})
            require(ChargingPhoto.of(key).kind == ChargingPhoto.Kind.SIDE, key + " is a side view");
    }

    private static float percentWidth(String shown, float text) {
        int digits = shown.length() - 1;
        return (ChargingFit.PERCENT_SP * digits * .6f + ChargingFit.PERCENT_SP * .4f * .8f) * text;
    }

    /** Every photo in the stage a phone gives it. */
    private static void placed(int width, int height, float text) {
        String at = width + "x" + height + " at " + text + "x";
        int inner = TopArea.heightDp(height, WideLayout.wide(width, text), text) - 24;
        int across = Math.min(width - 36, 560) - 28;
        int line = Math.round(12.5f * text * 1.17f), percent = Math.round(ChargingFit.PERCENT_SP * text * 1.17f);
        float tile = 20 + 12 * text * 1.17f + 2 + Math.max(ChargingFit.VALUE_MIN_DP * text, ChargingFit.VALUE_SP * text * 1.17f);
        int readings = Math.round(across >= ChargingFit.PAIR_DP ? tile : 2 * tile + 8);
        ChargingFit.Plan plan = ChargingFit.plan(inner, line, percent, readings, 1f);
        if (text == 1) require(plan.photo, at + ": room for the photo: " + plan);
        if (!plan.photo) return;
        int top = ChargingFit.PHOTO_PAD_DP, bottom = plan.stageHeight - ChargingFit.TRACK_DP - ChargingFit.PHOTO_PAD_DP;
        for (String shown : new String[]{"78%", "100%"}) {
            int clear = Math.round(percentWidth(shown, text)) + 12;
            for (ChargingPhoto.Photo photo : ChargingPhoto.all().values()) {
                ChargingPhoto.Place place = ChargingPhoto.place(photo, across, top, bottom, clear, ChargingFit.PHOTO_MIN_DP);
                String name = at + ", " + shown + ", " + photo.key;
                if (place == null) { require(text > 1 || shown.equals("100%"), name + ": shows at 1x beside 78%"); continue; }
                System.out.println("  " + name + ": " + place);
                require(place.scooterLeft >= clear - 1, name + ": clear of the percentage: " + place);
                require(Math.abs(place.scooterBottom - bottom) < 1, name + ": the scooter stands 8 dp above the bar");
                require(place.scooterTop >= top - 1, name + ": never above 8 dp under the line: " + place);
                require(place.scooterHeight() >= ChargingFit.PHOTO_MIN_DP - 1, name + ": at least its least height");
                if (photo.kind == ChargingPhoto.Kind.SIDE)
                    require(Math.abs(place.scooterRight - across) <= 1, name + ": a side view's scooter ends on the content edge: " + place);
                else require(Math.abs(place.left + place.size - across) <= 1, name + ": a three-quarter picture's corner on the content edge: " + place);
                // Unless it had to get smaller to keep clear, the scooter spans the whole stage.
                boolean full = Math.abs(place.scooterTop - top) <= 1;
                if (!full) require(place.scooterLeft <= clear + 1, name + ": smaller only to keep clear of the percentage: " + place);
                if (shown.equals("78%") && text == 1) require(full, name + ": at 1x beside 78% the scooter spans the stage: " + place);
            }
        }
        if (text == 1) {
            ChargingPhoto.Place ut5 = ChargingPhoto.place(ChargingPhoto.of("ut5max"), across, top, bottom,
                    Math.round(percentWidth("78%", 1)) + 12, ChargingFit.PHOTO_MIN_DP);
            float[] owner = width == 344 ? new float[]{125, 167} : width == 412 ? new float[]{134, 179} : new float[]{158, 211};
            require(Math.abs(ut5.scooterWidth() - owner[0]) <= 12 && Math.abs(ut5.scooterHeight() - owner[1]) <= 15,
                    at + ": the UT5 MAX is about the owner's " + owner[0] + "x" + owner[1] + " dp: " + ut5);
        }
    }

    /** The rule on its own: the anchors, getting smaller to keep clear, and no photo below its least height. */
    private static void rule() {
        ChargingPhoto.Photo square = new ChargingPhoto.Photo("t", ChargingPhoto.Kind.THREE_QUARTER, .1f, .1f, .9f, .9f);
        ChargingPhoto.Place p = ChargingPhoto.place(square, 300, 10, 170, 0, 72);
        require(p.size == 200 && p.left == 100 && p.top == -10, "a three-quarter picture: its corner on the edge, scaled from there: " + p);
        require(Math.abs(p.scooterHeight() - 160) < 1 && Math.abs(p.scooterRight - 280) < 1, "its scooter spans the stage: " + p);
        ChargingPhoto.Photo long_ = new ChargingPhoto.Photo("s", ChargingPhoto.Kind.SIDE, .1f, .1f, .9f, .9f);
        p = ChargingPhoto.place(long_, 300, 10, 170, 0, 72);
        require(Math.abs(p.scooterRight - 300) < 1 && p.left + p.size > 300, "a side view: the scooter's own right edge on the content edge: " + p);
        p = ChargingPhoto.place(square, 300, 10, 170, 200, 72);
        require(p != null && p.scooterLeft >= 199 && Math.abs(p.scooterBottom - 170) < 1 && p.scooterHeight() < 160,
                "beside a wide percentage it gets smaller from the same corner: " + p);
        require(ChargingPhoto.place(square, 300, 10, 170, 250, 72) == null, "no room for its least height beside the percentage: none");
        require(ChargingPhoto.place(square, 300, 10, 60, 0, 72) == null, "a stage too short: none");
        require(ChargingPhoto.place(null, 300, 10, 170, 0, 72) == null, "no declaration: none");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
