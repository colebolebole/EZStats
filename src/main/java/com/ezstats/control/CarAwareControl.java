package com.ezstats.control;

import com.ezstats.car.CarClimates;

import java.util.UUID;

/**
 * Sends changes of a car (a key that starts with {@code car:}) to MTVehicles, and everything else to the control behind it.
 * Whether the website can change anything at all ({@link #writable}) is up to that control: {@code control.enabled} and a
 * new enough EZvac switch cars off too.
 */
public final class CarAwareControl implements DeviceControl {
    private static final Result NOT_FOR_CARS = Result.failure(400, "A car's climate control can't do that.");

    private final DeviceControl inner;
    private final CarClimates cars;

    public CarAwareControl(DeviceControl inner, CarClimates cars) {
        this.inner = inner;
        this.cars = cars;
    }

    @Override
    public boolean writable() {
        return inner.writable();
    }

    @Override
    public String readOnlyReason() {
        return inner.readOnlyReason();
    }

    @Override
    public Result setTarget(UUID actor, String key, double targetF) {
        return CarClimates.isCarKey(key) ? cars.control(actor, key, "target", targetF) : inner.setTarget(actor, key, targetF);
    }

    @Override
    public Result setEnabled(UUID actor, String key, boolean enabled) {
        return CarClimates.isCarKey(key) ? cars.control(actor, key, "enabled", enabled) : inner.setEnabled(actor, key, enabled);
    }

    @Override
    public Result setProfile(UUID actor, String key, String profile) {
        return CarClimates.isCarKey(key) ? Result.failure(400, "A car has no profile: set its mode.") : inner.setProfile(actor, key, profile);
    }

    @Override
    public Result setMode(UUID actor, String key, String mode) {
        return CarClimates.isCarKey(key) ? cars.control(actor, key, "mode", mode) : inner.setMode(actor, key, mode);
    }

    @Override
    public Result setPowered(UUID actor, String key, boolean powered) {
        return CarClimates.isCarKey(key) ? NOT_FOR_CARS : inner.setPowered(actor, key, powered);
    }

    @Override
    public Result setSetpoint(UUID actor, String key, int setpoint) {
        return CarClimates.isCarKey(key) ? NOT_FOR_CARS : inner.setSetpoint(actor, key, setpoint);
    }

    @Override
    public Result setFridgeSetpoint(UUID actor, String key, int setpointF) {
        return CarClimates.isCarKey(key) ? NOT_FOR_CARS : inner.setFridgeSetpoint(actor, key, setpointF);
    }

    /** A car's fan is {@code auto}, {@code 1}, {@code 2} or {@code 3}. */
    @Override
    public Result setFan(UUID actor, String key, String fan) {
        return CarClimates.isCarKey(key) ? cars.control(actor, key, "fan", fan) : inner.setFan(actor, key, fan);
    }

    @Override
    public Result startDefrost(UUID actor, String key) {
        return CarClimates.isCarKey(key) ? NOT_FOR_CARS : inner.startDefrost(actor, key);
    }

    @Override
    public Result setAutoDefrost(UUID actor, String key, boolean on) {
        return CarClimates.isCarKey(key) ? NOT_FOR_CARS : inner.setAutoDefrost(actor, key, on);
    }

    @Override
    public Result setEquipmentEnabled(UUID actor, String key, boolean enabled) {
        return CarClimates.isCarKey(key) ? NOT_FOR_CARS : inner.setEquipmentEnabled(actor, key, enabled);
    }
}
