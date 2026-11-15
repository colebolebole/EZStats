package com.ezstats.control;

import java.util.UUID;

/** Refuses every change: for an EZvac that can't be controlled, or a server that switched control off. */
public final class ReadOnlyControl implements DeviceControl {
    private final String reason;
    private final Result refused;

    /** @param reason why, as a sentence: "Changing devices is switched off on this server." */
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

    @Override
    public Result setPowered(UUID actor, String key, boolean powered) {
        return refused;
    }

    @Override
    public Result setSetpoint(UUID actor, String key, int setpoint) {
        return refused;
    }

    @Override
    public Result setFridgeSetpoint(UUID actor, String key, int setpointF) {
        return refused;
    }

    @Override
    public Result setFan(UUID actor, String key, String fan) {
        return refused;
    }

    @Override
    public Result startDefrost(UUID actor, String key) {
        return refused;
    }

    @Override
    public Result setAutoDefrost(UUID actor, String key, boolean on) {
        return refused;
    }

    @Override
    public Result setEquipmentEnabled(UUID actor, String key, boolean enabled) {
        return refused;
    }
}
