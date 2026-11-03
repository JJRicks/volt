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
    KMH(T.UNITS_KMH),
    MPH(T.UNITS_MPH),
    KM(T.UNITS_KM),
    MI(T.UNITS_MI),
    VOLT(T.UNITS_VOLT),
    AMP(T.UNITS_AMP),
    WATT(T.UNITS_WATT),
    WATT_HOUR(T.UNITS_WATT_HOUR),
    AMP_HOUR(T.UNITS_AMP_HOUR),
    CELSIUS(T.UNITS_CELSIUS),
    FAHRENHEIT(T.UNITS_FAHRENHEIT),
    MINUTE(T.UNITS_MINUTE);

    /** The catalog id of the symbol (an id, rule R5: rendered only when shown). */
    final int symbolId;

    UnitCode(int symbolId) { this.symbolId = symbolId; }

    /** The symbol in the current language. */
    public String symbol() { return T.render(symbolId); }
}
