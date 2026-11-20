package com.jjricks.volt;

/** RouteTileBudget: RouteMapView's tile cache follows its view's visible tiles, x 1.5, within a cap (#560 review r2, F01). */
public final class RouteTileBudgetTest {
    private static int checks;
    private static final long BIG_HEAP = 512L * 1024 * 1024;

    public static void main(String[] args) {
        // A phone's ride map, 1080 x 2400 px at 2.625x: tiles drawn at least 128 dp (336 px) a side.
        double phoneTile = 256 * 2.625 / 2;
        check(RouteTileBudget.visibleTiles(1080, 2400, phoneTile) == 5 * 9, "a phone screen shows at most 5 x 9 tiles");
        check(RouteTileBudget.bytes(1080, 2400, phoneTile, BIG_HEAP) == RouteTileBudget.FLOOR_BYTES, "a phone keeps the 20 MB it had (68 tiles fit in 80)");
        // A large tablet full screen, 2560 x 1600 px at 2x: 256 px tiles at least, 11 x 8 visible: more than the old 80.
        double tabletTile = 256 * 2.0 / 2;
        int visible = RouteTileBudget.visibleTiles(2560, 1600, tabletTile);
        check(visible == 11 * 8 && visible > RouteTileBudget.FLOOR_BYTES / RouteTileBudget.TILE_BYTES, "a tablet shows " + visible + " tiles, more than 80");
        int tablet = RouteTileBudget.bytes(2560, 1600, tabletTile, BIG_HEAP);
        check(tablet == 132 * RouteTileBudget.TILE_BYTES, "the tablet's cache holds its visible tiles x 1.5 (" + tablet / RouteTileBudget.TILE_BYTES + " tiles)");
        // Never past the cap, nor past a small heap's share, nor below the old size.
        check(RouteTileBudget.bytes(8000, 8000, 64, BIG_HEAP) == RouteTileBudget.CAP_BYTES, "a huge view stops at the cap");
        check(RouteTileBudget.bytes(2560, 1600, tabletTile, 24L * 1024 * 1024) == 24 * 1024 * 1024, "a small heap's share caps it");
        check(RouteTileBudget.bytes(2560, 1600, tabletTile, 8L * 1024 * 1024) == RouteTileBudget.FLOOR_BYTES, "never below the 20 MB it always had");
        check(RouteTileBudget.bytes(0, 0, tabletTile, BIG_HEAP) == RouteTileBudget.FLOOR_BYTES && RouteTileBudget.visibleTiles(100, 100, 0) == 0,
                "no size yet: the old 20 MB");
        System.out.println("RouteTileBudget: " + checks + " checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
