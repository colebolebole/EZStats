package com.ezstats.snapshot;

import com.github.blade.hvac.api.AirQuality;
import com.github.blade.hvac.api.ClimateReading;
import com.github.blade.hvac.api.ClimateUnitView;
import com.github.blade.hvac.api.ColdStorage;
import com.github.blade.hvac.api.EquipmentView;
import com.github.blade.hvac.api.EzvacApi;
import com.github.blade.hvac.api.HvacDevice;
import com.github.blade.hvac.api.InstrumentView;
import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.ThermostatView;
import com.github.blade.hvac.api.VentView;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;
import java.util.Optional;

/**
 * An EZvac device as the JSON the dashboard draws, and as words for a player. Every device has a
 * {@code kind} (thermostat, equipment, unit, vent or instrument) and a {@code type} within it
 * (heat_pump, dehumidifier, thermometer...). An EZvac that adds a kind this doesn't know is shown as
 * {@code other} rather than failing.
 */
public final class DeviceJson {
    private DeviceJson() {}

    /** The device as JSON. Read on the main thread: it asks EZvac for the system and the spot's reading. */
    public static JsonObject of(EzvacApi api, World world, HvacDevice device) {
        JsonObject out = new JsonObject();
        Position position = device.position();
        out.addProperty("key", SnapshotService.key(position));
        out.addProperty("world", world.getName());
        out.addProperty("x", position.x());
        out.addProperty("y", position.y());
        out.addProperty("z", position.z());

        if (device instanceof ThermostatView view) {
            thermostat(out, view);
        } else if (device instanceof EquipmentView view) {
            equipment(out, api, world, view);
        } else if (device instanceof ClimateUnitView view) {
            unit(out, api, world, view);
        } else if (device instanceof VentView view) {
            out.addProperty("kind", "vent");
            out.addProperty("type", view.medium().name().toLowerCase(Locale.ROOT));
            out.addProperty("label", "");
            out.addProperty("system", systemName(api, world, view.system()));
        } else if (device instanceof InstrumentView view) {
            instrument(out, api, world, view);
        } else {
            out.addProperty("kind", "other");
            out.addProperty("type", "other");
            out.addProperty("label", "");
        }
        return out;
    }

    private static void thermostat(JsonObject out, ThermostatView view) {
        out.addProperty("kind", "thermostat");
        out.addProperty("type", view.pool() ? "pool" : "room");
        out.addProperty("label", view.id());
        number(out, "roomF", view.roomF());
        number(out, "targetF", view.targetF());
        out.addProperty("mode", view.mode().name().toLowerCase(Locale.ROOT));
        out.addProperty("enabled", view.enabled());
        out.addProperty("pool", view.pool());
        out.addProperty("mini", view.mini());
        number(out, "humidity", view.humidity());
        out.addProperty("runningUnits", view.runningUnits());
        out.addProperty("totalUnits", view.totalUnits());
        out.addProperty("unitsOff", view.unitsOff());
        out.addProperty("profile", view.profile().name().toLowerCase(Locale.ROOT));
        out.addProperty("blocked", view.blocked());
        JsonArray attention = new JsonArray();
        for (ThermostatView.Attention item : view.attention()) attention.add(item.name().toLowerCase(Locale.ROOT));
        out.add("attention", attention);
    }

    private static void equipment(JsonObject out, EzvacApi api, World world, EquipmentView view) {
        out.addProperty("kind", "equipment");
        out.addProperty("type", view.kind().name().toLowerCase(Locale.ROOT));
        out.addProperty("label", "");
        out.addProperty("enabled", view.enabled());
        out.addProperty("running", view.running());
        out.addProperty("mode", view.mode().name().toLowerCase(Locale.ROOT));
        number(out, "rpm", view.rpm());
        out.addProperty("tier", view.tier());
        out.addProperty("system", systemName(api, world, view.system()));
    }

    /**
     * A purifier's view is empty until the purifier has measured, so read the air at its spot instead,
     * as an air monitor does (and never load a chunk to do it).
     */
    private static Optional<AirQuality> airAt(EzvacApi api, World world, Position position) {
        if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return Optional.empty();
        Location location = position.toLocation();
        return location == null ? Optional.empty() : api.readingAt(location).airQuality();
    }

    private static void unit(JsonObject out, EzvacApi api, World world, ClimateUnitView view) {
        out.addProperty("kind", "unit");
        out.addProperty("type", view.kind().name().toLowerCase(Locale.ROOT));
        out.addProperty("label", view.label());
        number(out, "humidity", view.humidity());
        out.addProperty("setpoint", view.setpoint());
        out.addProperty("powered", view.powered());
        out.addProperty("state", view.state().name().toLowerCase(Locale.ROOT));
        number(out, "tank", view.tank());
        if (view.filter().isPresent()) number(out, "filter", view.filter().getAsDouble());
        Optional<AirQuality> air = view.airQuality();
        if (air.isEmpty() && view.kind() == ClimateUnitView.Kind.AIR_PURIFIER) air = airAt(api, world, view.position());
        air.ifPresent(a -> out.add("air", air(a)));
        view.fanMode().ifPresent(fan -> out.addProperty("fanMode", fan.name().toLowerCase(Locale.ROOT)));
        view.fanSpeed().ifPresent(fan -> out.addProperty("fanSpeed", fan.name().toLowerCase(Locale.ROOT)));
        if (view.insideF().isPresent()) number(out, "insideF", view.insideF().getAsDouble());
        view.cold().ifPresent(cold -> out.add("cold", cold(cold)));
    }

    private static JsonObject cold(ColdStorage cold) {
        JsonObject out = new JsonObject();
        out.addProperty("doorOpen", cold.doorOpen());
        if (cold.frost().isPresent()) number(out, "frost", cold.frost().getAsDouble());
        out.addProperty("defrosting", cold.defrosting());
        out.addProperty("autoDefrost", cold.autoDefrost());
        if (cold.fridgeInsideF().isPresent()) number(out, "fridgeInsideF", cold.fridgeInsideF().getAsDouble());
        if (cold.fridgeSetpointF().isPresent()) out.addProperty("fridgeSetpointF", cold.fridgeSetpointF().getAsInt());
        if (cold.chillerF().isPresent()) number(out, "chillerF", cold.chillerF().getAsDouble());
        out.addProperty("barrels", cold.barrels());
        return out;
    }

    private static void instrument(JsonObject out, EzvacApi api, World world, InstrumentView view) {
        out.addProperty("kind", "instrument");
        out.addProperty("type", view.kind().name().toLowerCase(Locale.ROOT));
        out.addProperty("label", view.label());
        view.system().ifPresent(system -> out.addProperty("system", systemName(api, world, system)));
        out.addProperty("links", view.links().size());

        // An instrument's view doesn't hold its reading: read the spot, but never load a chunk to do it.
        Position position = view.position();
        if (world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            Location location = position.toLocation();
            if (location != null) reading(out, api.readingAt(location), view.kind());
        }
    }

    /** What a thermometer, hygrometer, sensor or air monitor shows, by what it measures. */
    private static void reading(JsonObject out, ClimateReading reading, InstrumentView.Kind kind) {
        JsonObject r = new JsonObject();
        switch (kind) {
            case THERMOMETER, TEMPERATURE_SENSOR -> number(r, "tempF", reading.temperatureF());
            case HYGROMETER, HUMIDITY_SENSOR -> number(r, "humidity", reading.humidity());
            case THERMO_HYGROMETER, TEMPERATURE_HUMIDITY_SENSOR -> {
                number(r, "tempF", reading.temperatureF());
                number(r, "humidity", reading.humidity());
            }
            case WEATHER_SENSOR -> {
                number(r, "tempF", reading.outdoorF());
                r.addProperty("weather", reading.weather());
            }
            case AIR_MONITOR, AIR_SENSOR -> reading.airQuality().ifPresent(air -> r.add("air", air(air)));
            case WIND_SENSOR -> {
                number(r, "windMs", reading.wind().speedMs());
                r.addProperty("windLevel", reading.wind().level().name().toLowerCase(Locale.ROOT));
                if (reading.wind().hasDirection()) r.addProperty("windFrom", reading.wind().compass());
            }
            default -> { /* a hub, RPM monitor or settings panel shows no reading of its own */ }
        }
        if (r.size() > 0) out.add("reading", r);
    }

    private static JsonObject air(AirQuality air) {
        JsonObject out = new JsonObject();
        out.addProperty("aqi", air.aqi());
        out.addProperty("band", air.band().name().toLowerCase(Locale.ROOT));
        return out;
    }

    /** The label of a system's thermostat, for a device that belongs to a system. */
    private static String systemName(EzvacApi api, World world, String system) {
        Optional<ThermostatView> thermostat = api.thermostatOf(world, system);
        return thermostat.map(ThermostatView::id).orElse("");
    }

    /** NaN and infinity are not JSON: the field is left out, and the page shows a dash. */
    private static void number(JsonObject object, String name, double value) {
        if (Double.isFinite(value)) object.addProperty(name, value);
    }

    // --- words for a player -----------------------------------------------------------------

    /** A short name for a device in chat: {@code thermostat 'Home'}, {@code heat pump}, {@code dehumidifier 'Cellar'}. */
    public static String describe(HvacDevice device) {
        if (device instanceof ThermostatView view) return "thermostat '" + view.id() + "'";
        if (device instanceof EquipmentView view) return words(view.kind().name());
        if (device instanceof ClimateUnitView view) return words(view.kind().name()) + " '" + view.label() + "'";
        if (device instanceof VentView view) return words(view.medium().name()) + " vent";
        if (device instanceof InstrumentView view) return words(view.kind().name()) + " '" + view.label() + "'";
        return "device";
    }

    private static String words(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
