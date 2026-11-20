package com.jjricks.volt;

/**
 * What the charging pill at the top of Home's charging view says and how it looks (Widgets' takeover and Clean's
 * charging view, DashboardScreen.ChargingHero). Pure Java, so tests/l10n/ChargingPillStatusTest checks it on the JVM.
 *
 * <p>The owner's pick of the concepts (Nov 19 2026, concept B: colour only when slowed):
 * <ul>
 *   <li>Charging, and Charging to the limit, keep today's red pill and bolt (Ui.accent's tint, Ui.accentOnTint).</li>
 *   <li>Slowed with a hot battery: red-orange, with the thermometer.</li>
 *   <li>Slowed with a cold battery: blue, with a snowflake.</li>
 *   <li>Slowed for no named reason (the temperature in between or unknown): amber, with a gauge whose needle is low.</li>
 * </ul>
 * The slowed state is ChargeSlowdown's (ScooterService.chargeSlowdown): power is the signal, the battery temperature
 * only names the cause. Every pill's words and icon share one ink, 4.5:1 or more on the tint it draws over the page.
 *
 * <p>The words follow the temperature unit setting, in whole degrees, and step down a narrow ladder where the full
 * text does not fit on one line: "Charging slowed · battery hot (113°)", "Slowed · battery hot 113°", "Battery hot ·
 * 113°" (plain: "Charging slowed", "Slowed"). TalkBack reads the full text.
 */
final class ChargingPillStatus {
    private ChargingPillStatus() {}

    /** The pill's five states. */
    enum Kind {
        CHARGING, LIMIT, HOT, COLD, SLOWED;

        /** Charging is slowed (one of the three slowed states). */
        boolean slowed() { return this == HOT || this == COLD || this == SLOWED; }
    }

    /** The icons' ids in Ui.drawSymbol: the bolt and the thermometer were there; the snowflake and gauge are the pill's. */
    static final String GLYPH_BOLT = "bolt";
    static final String GLYPH_HOT = "temperature";
    static final String GLYPH_COLD = "snowflake";
    static final String GLYPH_SLOWED = "slowed";

    /** The tint's alpha over the page: today's pill (dark 34, light 24), for every state. */
    static final int ALPHA_DARK = 34, ALPHA_LIGHT = 24;

    /**
     * The slowed states' colours: the base drawn at the tint's alpha, and the ink of the words and icon. Dark keeps
     * the theme's own colours (Ui.orange and Ui.blue pass as ink on their tints); light deepens each ink to reach 4.5:1
     * on its pale tint (the theme colours as ink were 3.6 to 4.2:1).
     */
    static final int HOT_BASE_DARK = 0xFFF0603A, HOT_INK_DARK = 0xFFFF9473, HOT_BASE_LIGHT = 0xFFD2461C, HOT_INK_LIGHT = 0xFFAE3A12;
    static final int COLD_BASE_DARK = 0xFF8FC2FF, COLD_INK_DARK = 0xFF8FC2FF, COLD_BASE_LIGHT = 0xFF2F6DB5, COLD_INK_LIGHT = 0xFF245C9C;
    static final int SLOWED_BASE_DARK = 0xFFE8A23A, SLOWED_INK_DARK = 0xFFE8A23A, SLOWED_BASE_LIGHT = 0xFFA8650F, SLOWED_INK_LIGHT = 0xFF8A5208;

    /** The state: slowed (with its cause) wins over the limit, as the slowdown is what the rider should look at. */
    static Kind kind(boolean hasLimit, boolean slowed, ChargeSlowdown.Cause cause) {
        if (slowed) return cause == ChargeSlowdown.Cause.HOT ? Kind.HOT : cause == ChargeSlowdown.Cause.COLD ? Kind.COLD : Kind.SLOWED;
        return hasLimit ? Kind.LIMIT : Kind.CHARGING;
    }

    static String glyph(Kind kind) {
        if (kind == Kind.HOT) return GLYPH_HOT;
        if (kind == Kind.COLD) return GLYPH_COLD;
        if (kind == Kind.SLOWED) return GLYPH_SLOWED;
        return GLYPH_BOLT;
    }

    /** The state keeps today's red pill (its base Ui.accent, its ink Ui.accentOnTint). */
    static boolean accent(Kind kind) { return !kind.slowed(); }

    /** A slowed state's base colour (opaque; drawn at {@link #alpha}); 0 for the normal states, which use the accent. */
    static int base(Kind kind, boolean dark) {
        if (kind == Kind.HOT) return dark ? HOT_BASE_DARK : HOT_BASE_LIGHT;
        if (kind == Kind.COLD) return dark ? COLD_BASE_DARK : COLD_BASE_LIGHT;
        if (kind == Kind.SLOWED) return dark ? SLOWED_BASE_DARK : SLOWED_BASE_LIGHT;
        return 0;
    }

    /** A slowed state's ink for its words and icon; 0 for the normal states, which use Ui.accentOnTint. */
    static int ink(Kind kind, boolean dark) {
        if (kind == Kind.HOT) return dark ? HOT_INK_DARK : HOT_INK_LIGHT;
        if (kind == Kind.COLD) return dark ? COLD_INK_DARK : COLD_INK_LIGHT;
        if (kind == Kind.SLOWED) return dark ? SLOWED_INK_DARK : SLOWED_INK_LIGHT;
        return 0;
    }

    static int alpha(boolean dark) { return dark ? ALPHA_DARK : ALPHA_LIGHT; }

    /** The battery temperature in the unit setting, in whole degrees (never a negative zero). */
    static long degrees(double celsius, boolean fahrenheit) {
        long rounded = Math.round(fahrenheit ? celsius * 1.8 + 32 : celsius);
        return rounded == 0 ? 0 : rounded;
    }

    /**
     * The words, longest first. {@code normal} is the normal states' one text (Widgets' and Clean's own); the slowed
     * states name the cause, with the temperature when one is known.
     */
    static String[] rungs(Kind kind, Double batteryTempC, boolean fahrenheit, String normal) {
        if (!kind.slowed()) return new String[] {normal};
        if (kind == Kind.SLOWED) return new String[] {T.dashboardChargingSlowed(), T.dashboardChargingSlowedShort()};
        boolean hot = kind == Kind.HOT;
        if (batteryTempC == null || Double.isNaN(batteryTempC) || Double.isInfinite(batteryTempC)) {
            return hot ? new String[] {T.dashboardChargingHot(), T.dashboardChargingHotShort(), T.dashboardChargingHotCompact()}
                    : new String[] {T.dashboardChargingCold(), T.dashboardChargingColdShort(), T.dashboardChargingColdCompact()};
        }
        String degrees = Fmt.integer(degrees(batteryTempC, fahrenheit));
        return hot ? new String[] {T.dashboardChargingSlowedHot(degrees), T.dashboardChargingSlowedHotShort(degrees), T.dashboardChargingSlowedHotCompact(degrees)}
                : new String[] {T.dashboardChargingSlowedCold(degrees), T.dashboardChargingSlowedColdShort(degrees), T.dashboardChargingSlowedColdCompact(degrees)};
    }

    /**
     * The narrow ladder: the first (longest) rung whose one line, {@code needed[i]} wide with the pill's padding and
     * icon, fits {@code room}; the last rung when none does (it then wraps between words where its view allows).
     */
    static int pick(float[] needed, float room) {
        for (int i = 0; i < needed.length; i++) if (needed[i] <= room) return i;
        return Math.max(0, needed.length - 1);
    }

    /**
     * The icon's side for words {@code textSizePx} tall: ChargingFit's 14 dp glyph beside its 12.5 sp words, growing
     * with Volt's Text size and Android's font size as the words do (it was a fixed 14 dp).
     */
    static int glyphPx(float textSizePx) {
        return Math.max(1, Math.round(textSizePx * ChargingFit.LINE_GLYPH_DP / ChargingFit.LINE_SP));
    }
}
