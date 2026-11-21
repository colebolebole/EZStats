package com.ezstats.control;

import java.util.UUID;

/**
 * How the website changes a device. {@link EzvacDeviceControl} does it through EZvac's control API
 * (1.12, EZvac BETA 1.0 Pilot 7); {@link ReadOnlyControl} refuses, for an EZvac that can't be
 * controlled or a server that switched control off.
 *
 * <p>Calls come from a web server thread and may wait for EZvac, which makes the change on the
 * server's main thread. The web layer has already checked that {@code actor} owns the device, named
 * by {@code key}. A device that can't do what is asked (a humidifier has no fan) answers
 * {@code NOT_SUPPORTED}, which comes back here as a failure with EZvac's message.
 */
public interface DeviceControl {

    /** Whether this control can change anything. The dashboard shows its buttons only when it can. */
    boolean writable();

    /** When not {@link #writable}, why, as a sentence the website and commands show. */
    default String readOnlyReason() {
        return "";
    }

    // ---- Thermostats ----

    /** A thermostat's target, in degrees Fahrenheit, which is what EZvac's API speaks. */
    Result setTarget(UUID actor, String key, double targetF);

    /** Switches a thermostat, and so its whole system, on or off. */
    Result setEnabled(UUID actor, String key, boolean enabled);

    /** The profile of a thermostat's system: {@code eco}, {@code normal} or {@code turbo}. */
    Result setProfile(UUID actor, String key, String profile);

    /** A car's climate control mode: {@code auto}, {@code cool} or {@code heat}. Only cars have one. */
    default Result setMode(UUID actor, String key, String mode) {
        return Result.failure(400, "That device has no mode to set.");
    }

    // ---- Dehumidifiers, humidifiers, air purifiers, fridges and freezers ----

    Result setPowered(UUID actor, String key, boolean powered);

    /** A dehumidifier's or humidifier's % RH (to its nearest preset), or a fridge's or freezer's degrees F. */
    Result setSetpoint(UUID actor, String key, int setpoint);

    /** A refrigerator's fridge compartment, in degrees F (its freezer is {@link #setSetpoint}). */
    Result setFridgeSetpoint(UUID actor, String key, int setpointF);

    /** An air purifier's fan: {@code low}, {@code medium}, {@code high} or {@code auto}. */
    Result setFan(UUID actor, String key, String fan);

    Result startDefrost(UUID actor, String key);

    Result setAutoDefrost(UUID actor, String key, boolean on);

    // ---- Equipment ----

    /** Switches a heat pump, AC, furnace, boiler or pool heat pump on or off. */
    Result setEquipmentEnabled(UUID actor, String key, boolean enabled);

    /**
     * What happened. {@code status} is the HTTP status the website answers with; {@code adjusted} means
     * it was done but not exactly as asked (a target kept within the thermostat's range), and
     * {@code message} then says to what.
     */
    record Result(boolean ok, int status, String message, boolean adjusted) {
        public static Result success(String message) {
            return new Result(true, 200, message, false);
        }

        public static Result adjusted(String message) {
            return new Result(true, 200, message, true);
        }

        public static Result failure(int status, String message) {
            return new Result(false, status, message, false);
        }
    }
}
