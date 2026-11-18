package com.jjricks.volt;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import java.util.HashMap;
import java.util.Map;

/** Bundled model photos, decoded once per model and sample size. Sample sizes assume a 2048 px source; a smaller one is sampled less. */
final class ScooterPhotos {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private ScooterPhotos() {}

    /** This model's photo, or null when it has none or cannot be decoded. */
    static synchronized Bitmap get(Resources resources, DeviceDescriptor d, int sampleSize) {
        String key = ModelRegistry.photo(d);
        int id = resource(key);
        if (id == 0) return null;
        Bitmap cached = CACHE.get(id + "@" + sampleSize); // by file: keys sharing a photo share its bitmap
        if (cached != null) return cached;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(resources, id, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = bounds.outWidth > 0 ? Math.max(1, sampleSize * bounds.outWidth / 2048) : sampleSize;
            Bitmap decoded = BitmapFactory.decodeResource(resources, id, options);
            if (decoded != null) CACHE.put(id + "@" + sampleSize, decoded);
            return decoded;
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    /**
     * Each bundled photo by photo key, including any waiting for their Model ID (ModelRegistryTest checks every key has one
     * here). Models whose catalog pictures are the same image share its file; each keeps its own key, so a charging picture
     * keyed to one of them (ChargePicture) stays with that model.
     */
    private static int resource(String key) {
        switch (key) {
            case "ut5max": return R.drawable.ut5max;
            // The catalog pictures the UT5 Ultra with the NT3 Max's image; it shows its own family's UT5 Ultra X photo instead.
            case "ut5ultrax": case "ut5ultra": return R.drawable.ut5ultrax;
            case "e20lite": return R.drawable.e20lite;
            case "e25go": return R.drawable.e25go;
            case "e60pro": case "e45pro": return R.drawable.e60pro;
            case "easyride20": return R.drawable.easyride20;
            case "easyride25pro": return R.drawable.easyride25pro;
            case "g5": case "g5max": case "g5pro": return R.drawable.g5;
            case "gt3max": case "gt3": case "gt3pro": return R.drawable.gt3max;
            case "gt5max": return R.drawable.gt5max;
            case "gt5pro": return R.drawable.gt5pro;
            case "k100": return R.drawable.k100;
            case "k100max": return R.drawable.k100max;
            case "k100pro": return R.drawable.k100pro;
            case "n65i": case "v60ipro": return R.drawable.n65i;
            case "n65iii": return R.drawable.n65iii;
            case "nt3max": case "s2_26142": case "s60": return R.drawable.nt3max;
            case "nt5max": return R.drawable.nt5max;
            case "nt5maxplus": return R.drawable.nt5maxplus;
            case "nt5ultra": return R.drawable.nt5ultra;
            case "nt5ultrax": return R.drawable.nt5ultrax;
            case "s2": return R.drawable.s2;
            case "s40": return R.drawable.s40;
            case "st3pro": case "st3": return R.drawable.st3pro;
            case "st5max": return R.drawable.st5max;
            case "st5pro": return R.drawable.st5pro;
            case "ut3max": return R.drawable.ut3max;
            case "ut3pro": return R.drawable.ut3pro;
            case "v25iproii": return R.drawable.v25iproii;
            case "v25proseries": return R.drawable.v25proseries;
            case "v25series": return R.drawable.v25series;
            case "v3pro": return R.drawable.v3pro;
            case "v40iproii": return R.drawable.v40iproii;
            case "v40series": return R.drawable.v40series;
            case "v45i": return R.drawable.v45i;
            case "v50ipro": return R.drawable.v50ipro;
            case "v50iproii": return R.drawable.v50iproii;
            case "xt5max": return R.drawable.xt5max;
            case "xt5pro": return R.drawable.xt5pro;
            case "xt5ultra": return R.drawable.xt5ultra;
            default: return 0;
        }
    }

    /** Shows the model's photo in view, replacing another model's; false (view untouched) when it has none. */
    static boolean show(ImageView view, DeviceDescriptor d, int sampleSize) {
        Bitmap photo = get(view.getResources(), d, sampleSize);
        if (photo == null) return false;
        if (!(view.getDrawable() instanceof BitmapDrawable) || ((BitmapDrawable) view.getDrawable()).getBitmap() != photo)
            view.setImageBitmap(photo);
        String model = ModelRegistry.modelName(d);
        if (view.getImportantForAccessibility() != ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO)
            view.setContentDescription(model.isEmpty() ? T.uiPhotoScooter() : T.uiPhotoModel(model));
        return true;
    }
}
