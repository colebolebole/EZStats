package com.ezstats.control;

import java.util.UUID;

/**
 * How the website changes a thermostat. {@link EzvacThermostatControl} does it through EZvac's
 * control API (1.12, EZvac BETA 1.0 Pilot 7); {@link ReadOnlyControl} refuses, for an EZvac that
 * can't be controlled or a server that switched control off.
 *
 * <p>Calls come from a web server thread and may wait for EZvac, which makes the change on the
 * server's main thread. The web layer has already checked that {@code actor} owns the thermostat.
 */
public interface ThermostatControl {

    /** Whether this control can change anything. The dashboard shows its buttons only when it can. */
    boolean writable();

    /** When not {@link #writable}, why, as a sentence the website and commands show. */
    default String readOnlyReason() {
        return "";
    }

    /**
     * Sets a thermostat's target.
     *
     * @param actor    the linked player asking
     * @param key      the thermostat's key, as the dashboard read it from the snapshot
     * @param targetF  the target in degrees Fahrenheit, which is what EZvac's API speaks
     */
    Result setTarget(UUID actor, String key, double targetF);

    /** Switches a thermostat's system on or off. */
    Result setEnabled(UUID actor, String key, boolean enabled);

    /** Sets the profile of a thermostat's system: {@code eco}, {@code normal} or {@code turbo}. */
    Result setProfile(UUID actor, String key, String profile);

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
