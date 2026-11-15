package com.ezstats.snapshot;

import com.ezstats.control.ThermostatControl;
import com.github.blade.hvac.api.EzvacApi;
import com.github.blade.hvac.api.OutdoorClimate;
import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.ThermostatView;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Reads EZvac on the main thread every few ticks and keeps the result, so web server threads never
 * touch Bukkit or EZvac: EZvac's API is for the main thread only. A web thread asks for the part of
 * the snapshot a player owns with {@link #jsonFor}.
 */
public final class SnapshotService {
    private static final Gson GSON = new Gson();

    /** What one read of EZvac found. The JSON objects are never changed after they are built. */
    private record Snapshot(long generatedAt, String units, boolean writable, String ezvacVersion,
                            String apiVersion, Map<String, JsonObject> worlds, Map<String, JsonObject> thermostats) {}

    private final Plugin plugin;
    private final EzvacApi api;
    private final ThermostatControl control;

    private volatile Snapshot snapshot;
    private BukkitTask task;
    private boolean warned;

    public SnapshotService(Plugin plugin, EzvacApi api, ThermostatControl control) {
        this.plugin = plugin;
        this.api = api;
        this.control = control;
        this.snapshot = new Snapshot(System.currentTimeMillis(), "F", control.writable(), "", "", Map.of(), Map.of());
    }

    public void start(int periodTicks) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, Math.max(5, periodTicks));
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /**
     * The snapshot as JSON, with only the thermostats whose keys are given (and the worlds they are in).
     * Safe from any thread.
     */
    public String jsonFor(Set<String> ownedKeys) {
        Snapshot snap = snapshot;
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", snap.generatedAt());
        root.addProperty("units", snap.units());
        root.addProperty("writable", snap.writable());
        root.addProperty("ezvacVersion", snap.ezvacVersion());
        root.addProperty("apiVersion", snap.apiVersion());

        JsonArray thermostats = new JsonArray();
        Set<String> worldNames = new java.util.LinkedHashSet<>();
        for (String key : ownedKeys) {
            JsonObject thermostat = snap.thermostats().get(key);
            if (thermostat == null) continue;
            thermostats.add(thermostat);
            worldNames.add(thermostat.get("world").getAsString());
        }
        JsonArray worlds = new JsonArray();
        for (String name : worldNames) {
            JsonObject world = snap.worlds().get(name);
            if (world != null) worlds.add(world);
        }
        root.add("worlds", worlds);
        root.add("thermostats", thermostats);
        return GSON.toJson(root);
    }

    private void refresh() {
        try {
            snapshot = build();
            warned = false;
        } catch (RuntimeException e) {
            // Keep serving the last good snapshot; say so once, not twenty times a second.
            if (!warned) plugin.getLogger().log(Level.WARNING, "Could not read EZvac for the website", e);
            warned = true;
        }
    }

    private Snapshot build() {
        Map<String, JsonObject> worlds = new LinkedHashMap<>();
        Map<String, JsonObject> thermostats = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.put(world.getName(), worldJson(world));
            for (ThermostatView view : api.thermostats(world)) {
                thermostats.put(key(view.position()), thermostatJson(world, view));
            }
        }
        return new Snapshot(System.currentTimeMillis(), api.units().name(), control.writable(),
                api.pluginVersion(), api.apiVersion(), worlds, thermostats);
    }

    private JsonObject worldJson(World world) {
        JsonObject out = new JsonObject();
        out.addProperty("name", world.getName());
        api.season(world).ifPresent(season -> {
            JsonObject s = new JsonObject();
            s.addProperty("name", season.season().name().toLowerCase(Locale.ROOT));
            s.addProperty("day", season.dayOfSeason());
            s.addProperty("days", season.daysPerSeason());
            s.addProperty("paused", season.paused());
            out.add("season", s);
        });
        try {
            OutdoorClimate outdoor = api.outdoorAt(world.getSpawnLocation());
            JsonObject o = new JsonObject();
            number(o, "airF", outdoor.airF());
            number(o, "feelsLikeF", outdoor.feelsLikeF());
            number(o, "humidity", outdoor.humidity());
            o.addProperty("weather", outdoor.weather());
            out.add("outdoor", o);
        } catch (RuntimeException ignored) {
            // A world without outdoors (some custom dimensions): leave it out.
        }
        return out;
    }

    private JsonObject thermostatJson(World world, ThermostatView view) {
        Position position = view.position();
        JsonObject out = new JsonObject();
        out.addProperty("key", key(position));
        out.addProperty("label", view.id());
        out.addProperty("world", world.getName());
        out.addProperty("x", position.x());
        out.addProperty("y", position.y());
        out.addProperty("z", position.z());
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
        return out;
    }

    /** A thermostat's key: its world and block. Labels can repeat, so they can't be keys. */
    public static String key(Position position) {
        return position.world() + ":" + position.x() + ":" + position.y() + ":" + position.z();
    }

    /** NaN and infinity are not JSON: the field is left out, and the page shows a dash. */
    private static void number(JsonObject object, String name, double value) {
        if (Double.isFinite(value)) object.addProperty(name, value);
    }
}
