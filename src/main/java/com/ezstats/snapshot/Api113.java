package com.ezstats.snapshot;

import com.github.blade.hvac.api.ClimateUnitView;
import com.github.blade.hvac.api.EquipmentView;
import com.github.blade.hvac.api.ThermostatView;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * What EZvac API 1.13 (BETA 1.0 Pilot 8) added to the views: whether a device's chunk is loaded, and how long
 * a thermostat has been in its mode. Kept in a class of its own so that {@link DeviceJson} only touches these
 * methods when the running EZvac has them: on an older one they don't exist, and calling them would fail.
 */
final class Api113 {
    private Api113() {}

    static void thermostat(JsonObject out, ThermostatView view) {
        out.addProperty("loaded", view.loaded());
        // When it went into its mode, not for how long: that number would change every second and make the
        // page redraw. The page works the duration out from this and the server's clock.
        view.modeSince().ifPresent(since -> out.addProperty("modeSince", since.toEpochMilli()));
        view.lastRun().ifPresent(run -> {
            JsonObject last = new JsonObject();
            last.addProperty("mode", run.mode().name().toLowerCase(Locale.ROOT));
            last.addProperty("startedAt", run.started().toEpochMilli());
            last.addProperty("endedAt", run.ended().toEpochMilli());
            out.add("lastRun", last);
        });
    }

    static void equipment(JsonObject out, EquipmentView view) {
        out.addProperty("loaded", view.loaded());
    }

    static void unit(JsonObject out, ClimateUnitView view) {
        out.addProperty("loaded", view.loaded());
    }
}
