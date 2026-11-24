package com.jjricks.volt;

/**
 * The words for a scooter switched off but still answering (ScooterPowerState, 2.0.5 item 5). They come with their
 * catalog strings in the next pull request; until then each is null and the screens keep what they showed before:
 * Home says Connected, and a refused change gives the resolver's own reason (CapabilityResolver.OFF_REASON).
 * The split follows #595 and #596: CI's localization gates refuse edits to existing tests in a pull request that adds
 * a catalog string.
 */
final class ScooterOffWords {
    private ScooterOffWords() { }
    /** Home's status line while off ("Scooter off · Turn the scooter on to use it"), or null. */
    static String status() { return null; }
    /** Its short rung, where the full line does not fit ("Scooter off"), or null. */
    static String statusShort() { return null; }
    /** The status line as a screen reader says it, or null. */
    static String spoken() { return null; }
    /** The connection state word ("Scooter off") for the connection line and details, or null. */
    static String state() { return null; }
    /** The line under it in the connection details ("Turn the scooter on to use it"), or null. */
    static String detail() { return null; }
    /**
     * The UT5 MAX's refusal before the first motion report after connecting ("Waiting for the scooter to report its
     * speed"), or null for the resolver's "No motion report yet". Never "Bring the scooter to a stop first": that is
     * for a scooter that is moving.
     */
    static CharSequence waitingForSpeed() { return null; }
}
