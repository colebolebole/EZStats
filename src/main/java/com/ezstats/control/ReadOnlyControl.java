package com.ezstats.control;

import java.util.UUID;

/** Refuses every change: for an EZvac that can't be controlled, or a server that switched control off. */
public final class ReadOnlyControl implements ThermostatControl {
    private final String reason;
    private final Result refused;

    /** @param reason why, as a sentence: "Changing thermostats is switched off on this server." */
    public ReadOnlyControl(String reason) {
        this.reason = reason;
        this.refused = Result.failure(501, reason);
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public String readOnlyReason() {
        return reason;
    }

    @Override
    public Result setTarget(UUID actor, String key, double targetF) {
        return refused;
    }

    @Override
    public Result setEnabled(UUID actor, String key, boolean enabled) {
        return refused;
    }

    @Override
    public Result setProfile(UUID actor, String key, String profile) {
        return refused;
    }
}
