package com.ezstats.control;

import java.util.UUID;

/**
 * How the website changes a thermostat.
 *
 * <p>EZvac's public API (through 1.11) only reads: it has no call to set a target or switch a
 * system on or off. EZStats therefore ships {@link ReadOnlyControl}. When EZvac adds write methods,
 * implement this interface against them and pass it to the plugin in place of {@code ReadOnlyControl};
 * the web layer and the dashboard already speak it.
 *
 * <p>Implementations are called from a web server thread. Anything that touches Bukkit or EZvac must
 * be handed to the main thread (for example with {@code Bukkit.getScheduler().callSyncMethod}).
 */
public interface ThermostatControl {

    /** Whether this control can change anything. The dashboard shows its buttons only when it can. */
    boolean writable();

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

    /** What happened. {@code status} is the HTTP status the website answers with. */
    record Result(boolean ok, int status, String message) {
        public static Result success() {
            return new Result(true, 200, "ok");
        }

        public static Result failure(int status, String message) {
            return new Result(false, status, message);
        }
    }
}
