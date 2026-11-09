package com.jjricks.volt;

import java.util.function.BooleanSupplier;

/**
 * The condition a queued change was accepted under, until its frame goes out. Once a look finds it closed it stays
 * closed, so a change that outlives the moment it became invalid (motion while a read is in flight) can't be reopened
 * by the condition later coming true again (the scooter stopping before that read ends).
 */
final class LatchedCondition {
    private final BooleanSupplier condition;
    private boolean closed;

    LatchedCondition(BooleanSupplier condition) { this.condition = condition; }

    /** One look: false from the first look that finds the condition closed, and from then on. */
    boolean holds() {
        if (!closed && !condition.getAsBoolean()) closed = true;
        return !closed;
    }
}
