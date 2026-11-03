package com.jjricks.volt;

/**
 * Internal unit codes (plan section 4.4). Code that decides by unit compares these, never display
 * symbols; the symbol a person reads comes from res/values/strings_units.xml, in the current language.
 * In English every symbol equals the unit text Volt shows today (FmtLegacyTest checks each one).
 *
 * The unit strings already stored as data (Telemetry units, chart series units, journals) stay as they
 * are: each batch that owns such data moves its comparisons to these codes.
 */
public enum UnitCode {
    KMH(T.UNITS_KMH, "km/h"),
    MPH(T.UNITS_MPH, "mph"),
    KM(T.UNITS_KM, "km"),
    MI(T.UNITS_MI, "mi"),
    VOLT(T.UNITS_VOLT, "V"),
    AMP(T.UNITS_AMP, "A"),
    WATT(T.UNITS_WATT, "W"),
    WATT_HOUR(T.UNITS_WATT_HOUR, "Wh"),
    AMP_HOUR(T.UNITS_AMP_HOUR, "Ah"),
    CELSIUS(T.UNITS_CELSIUS, "°C"),
    FAHRENHEIT(T.UNITS_FAHRENHEIT, "°F"),
    MINUTE(T.UNITS_MINUTE, "min");

    /** The catalog id of the symbol (an id, rule R5: rendered only when shown). */
    final int symbolId;

    /** The unit as data spells it (Telemetry and chart series units): never shown through here. */
    final String code;

    UnitCode(int symbolId, String code) { this.symbolId = symbolId; this.code = code; }

    /** True when a data unit string is this unit. */
    boolean matches(String unit) { return code.equals(unit); }

    /** The symbol in the current language. */
    public String symbol() { return T.render(symbolId); }
}
