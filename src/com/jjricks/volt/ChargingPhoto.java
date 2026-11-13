package com.jjricks.volt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The scooter photo in Clean Home's charging view, by the owner's rule (#383, 6849341616). Pure Java, so the JVM suite
 * checks it over every bundled photo (tests/ChargingPhotoTest).
 *
 * <ul>
 *   <li>Size: the scooter itself (the photo's opaque pixels, not the square picture) runs from 8 dp under the
 *       "Charging" line to 8 dp above the bar (ChargingFit gives the stage those bounds).</li>
 *   <li>Place, by the kind of photo: a three-quarter photo keeps the picture's bottom right corner on the content edge,
 *       as the smaller photo had it, and scales up from there; a long side view puts the scooter's own right edge on
 *       the content edge, where the bar and the tiles end.</li>
 *   <li>Nothing overlaps: where the scooter at that size would reach the percentage, it gets smaller from the same
 *       corner (its bottom stays on its line), down to {@link ChargingFit#PHOTO_MIN_DP}; below that it does not show.</li>
 * </ul>
 *
 * <p>Each bundled photo declares its kind and its opaque bounds here (fractions of the square picture, measured at
 * alpha above 24 of 255), and a new photo has to be declared too: ChargingPhotoTest fails for a photo in ScooterPhotos
 * that is missing here.
 */
final class ChargingPhoto {
    /** How a photo shows the scooter. */
    enum Kind { THREE_QUARTER, SIDE }

    /** One bundled photo: its kind and where its opaque pixels are in the square picture (0 to 1). */
    static final class Photo {
        final String key;
        final Kind kind;
        final float left, top, right, bottom;

        Photo(String key, Kind kind, float left, float top, float right, float bottom) {
            this.key = key; this.kind = kind; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }

        /** The scooter's height as a share of the picture's side. */
        float height() { return bottom - top; }

        /** The scooter's width as a share of the picture's side. */
        float width() { return right - left; }
    }

    private static final Map<String, Photo> PHOTOS;
    static {
        LinkedHashMap<String, Photo> photos = new LinkedHashMap<>();
        // Three-quarter ("diagonal") photos.
        add(photos, new Photo("ut5max", Kind.THREE_QUARTER, 232 / 2048f, 70 / 2048f, 1678 / 2048f, 1999 / 2048f));
        add(photos, new Photo("ut5ultrax", Kind.THREE_QUARTER, 223 / 1760f, 27 / 1760f, 1526 / 1760f, 1719 / 1760f));
        add(photos, new Photo("st3pro", Kind.THREE_QUARTER, 246 / 1136f, 51 / 1136f, 883 / 1136f, 1085 / 1136f));
        add(photos, new Photo("n65i", Kind.THREE_QUARTER, 474 / 2048f, 57 / 2048f, 1618 / 2048f, 1996 / 2048f));
        // Long side views.
        add(photos, new Photo("gt3max", Kind.SIDE, 118 / 1024f, 51 / 1024f, 898 / 1024f, 910 / 1024f));
        add(photos, new Photo("gt5max", Kind.SIDE, 99 / 1000f, 53 / 1000f, 921 / 1000f, 890 / 1000f));
        add(photos, new Photo("xt5ultra", Kind.SIDE, 49 / 628f, 38 / 628f, 579 / 628f, 591 / 628f));
        PHOTOS = Collections.unmodifiableMap(photos);
    }

    private static void add(Map<String, Photo> photos, Photo photo) { photos.put(photo.key, photo); }

    private ChargingPhoto() {}

    /** Every declared photo, by its ScooterPhotos key. */
    static Map<String, Photo> all() { return PHOTOS; }

    /** A photo's declaration, or null for one that has none (then it does not show in the charging view). */
    static Photo of(String key) { return key == null ? null : PHOTOS.get(key); }

    /** Where the square picture goes in the stage, and where its scooter ends up (pixels, the stage's own coordinates). */
    static final class Place {
        /** The square picture's side and its top left corner (left may run past the stage's right edge for a side view). */
        final int size, left, top;
        /** The scooter's own bounds. */
        final float scooterLeft, scooterTop, scooterRight, scooterBottom;

        Place(int size, int left, int top, float scooterLeft, float scooterTop, float scooterRight, float scooterBottom) {
            this.size = size; this.left = left; this.top = top;
            this.scooterLeft = scooterLeft; this.scooterTop = scooterTop; this.scooterRight = scooterRight; this.scooterBottom = scooterBottom;
        }

        float scooterWidth() { return scooterRight - scooterLeft; }
        float scooterHeight() { return scooterBottom - scooterTop; }

        @Override public String toString() {
            return Math.round(scooterWidth()) + "x" + Math.round(scooterHeight()) + " at " + Math.round(scooterLeft) + ","
                    + Math.round(scooterTop) + " (picture " + size + " at " + left + "," + top + ")";
        }
    }

    /**
     * The photo's place in a stage {@code width} wide whose scooter runs from {@code top} to {@code bottom} (the stage's
     * top plus 8 dp and its track's top less 8 dp), keeping clear of everything left of {@code clear} (the percentage's
     * right edge and a gap). Null when even a scooter {@code minHeight} tall would not fit beside the percentage.
     */
    static Place place(Photo photo, int width, int top, int bottom, int clear, int minHeight) {
        if (photo == null || width <= 0 || photo.height() <= 0 || photo.width() <= 0) return null;
        float height = bottom - top;
        if (height < minHeight || height <= 0) return null;
        // The picture's right edge: on the content edge (three-quarter), or past it by the picture's own margin so the
        // scooter's right edge is on it (side view). Either way it scales from there.
        float side = height / photo.height();
        float room = width - Math.max(0, clear);
        // The scooter's right edge, as a share of the picture side, measured from the picture's right edge.
        float rightInset = photo.kind == Kind.SIDE ? 1 - photo.right : 0;
        // How far the scooter reaches left from the anchor, per unit of picture side.
        float reach = photo.kind == Kind.SIDE ? photo.width() : 1 - photo.left;
        if (side * reach > room) {
            side = room / reach;
            if (side * photo.height() < minHeight) return null;
        }
        float scooterBottom = bottom;
        float pictureTop = scooterBottom - photo.bottom * side;
        float pictureRight = width + rightInset * side;
        float pictureLeft = pictureRight - side;
        int size = Math.round(side);
        return new Place(size, Math.round(pictureLeft), Math.round(pictureTop),
                pictureLeft + photo.left * side, pictureTop + photo.top * side,
                pictureLeft + photo.right * side, scooterBottom);
    }
}
