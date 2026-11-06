package com.jjricks.volt;

/**
 * Version 19's kick-start tries outside the official low-start region (owner decision, November 2026), used by
 * ModelRegistry.experimentalRule. A try offers Standing start, 1, 2 and 3 km/h, so Set back to 3 km/h stays inside it;
 * 4 and 5 km/h (offered in Scooter settings) show as they are. Kick-start is the speed at which the motor starts
 * helping, not a speed limit.
 *
 * They are held here, not in ModelRegistry, because the B4 controls characterization
 * (tests/l10n/characterization/ControlsEnglishTest) sweeps every variant ModelRegistry and its nested classes hold,
 * and must keep passing on the code it was pinned against. tests/l10n/KickStartTryEnglishTest pins these two the same
 * way: id, labels, decision labels, cautions and encode refusals.
 */
final class KickStartTry {
    /** An OEM-group model outside the low-start region (group M included). */
    static final FeatureVariant OEM=ModelRegistry.startSpeed("0_3",ModelRegistry.range(0,3))
        .withShown(ModelRegistry.range(4,5),ModelRegistry.kmhLabels(ModelRegistry.range(4,5),false)).lab();
    /** A UT5 MAX held to 3–5 km/h by its serial region. */
    static final FeatureVariant UT5=ModelRegistry.Ut5.VARIANTS.get("start_speed")
        .narrowed("start_speed.ut5_0_3",ModelRegistry.range(0,3),ModelRegistry.kmhLabels(ModelRegistry.range(0,3),false))
        .withShown(ModelRegistry.range(4,5),ModelRegistry.kmhLabels(ModelRegistry.range(4,5),false)).lab();

    private KickStartTry() {}
}
