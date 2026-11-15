package com.ezstats.control;

import com.github.blade.hvac.api.EzvacApi;
import org.bukkit.plugin.Plugin;

/**
 * Picks how the website changes thermostats. Kept apart from the plugin class so the control API's
 * types, which an older EZvac doesn't have, are only touched when the API can be controlled.
 */
public final class Controls {
    private Controls() {}

    /**
     * @param enabled      the server's {@code control.enabled} setting
     * @param apiSupports  whether EZvac's API is 1.12 (BETA 1.0 Pilot 7) or newer
     */
    public static ThermostatControl create(Plugin plugin, EzvacApi api, boolean enabled, boolean apiSupports) {
        if (!enabled) {
            return new ReadOnlyControl("Changing thermostats from the website is switched off on this server.");
        }
        if (!apiSupports) {
            return new ReadOnlyControl("This server's EZvac is too old for the website to change thermostats: "
                    + "it needs BETA 1.0 Pilot 7 or newer.");
        }
        try {
            return new EzvacThermostatControl(api.control(plugin));
        } catch (UnsupportedOperationException | LinkageError e) {
            return new ReadOnlyControl("EZvac didn't give EZStats a controller, so the website can't change "
                    + "thermostats.");
        }
    }
}
