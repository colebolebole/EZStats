package com.ezstats.control;

import java.util.UUID;

/** The only control there can be while EZvac's API is read-only: it refuses every change. */
public final class ReadOnlyControl implements ThermostatControl {
    private static final Result REFUSED = Result.failure(501,
            "EZvac's API can't change thermostats yet, so the website is read-only.");

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public Result setTarget(UUID actor, String key, double targetF) {
        return REFUSED;
    }

    @Override
    public Result setEnabled(UUID actor, String key, boolean enabled) {
        return REFUSED;
    }
}
