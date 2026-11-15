package com.ezstats.snapshot;

import com.ezstats.control.ThermostatControl;
import com.github.blade.hvac.api.EzvacApi;
import com.github.blade.hvac.api.OutdoorClimate;
import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.SeasonView;
import com.github.blade.hvac.api.ThermostatView;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Reads EZvac on the main thread every few ticks and keeps the result as a JSON string, so web
 * server threads never touch Bukkit or EZvac: EZvac's API is for the main thread only.
 */
public final class SnapshotService {
    private static final Gson GSON = new Gson();

    private final Plugin plugin;
    private final EzvacApi api;
    private final ThermostatControl control;

    private volatile String json;
    private volatile Set<String> hiddenWorlds = Set.of();
    private BukkitTask task;
    private boolean warned;

    public SnapshotService(Plugin plugin, EzvacApi api, ThermostatControl control) {
        this.plugin = plugin;
        this.api = api;
        this.control = control;
        JsonObject empty = new JsonObject();
        empty.addProperty("generatedAt", System.currentTimeMillis());
        empty.addProperty("units", "F");
        empty.addProperty("writable", control.writable());
        empty.add("worlds", new JsonArray());
        empty.add("thermostats", new JsonArray());
        this.json = GSON.toJson(empty);
    }

    public void start(int periodTicks, Collection<String> hidden) {
        stop();
        setHiddenWorlds(hidden);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, Math.max(5, periodTicks));
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public void setHiddenWorlds(Collection<String> names) {
        Set<String> lower = new HashSet<>();
        for (String name : names) lower.add(name.toLowerCase(Locale.ROOT));
        hiddenWorlds = Set.copyOf(lower);
    }

    /** The latest snapshot. Safe from any thread. */
    public String json() {
        return json;
    }

    private void refresh() {
        try {
            json = GSON.toJson(build());
            warned = false;
        } catch (RuntimeException e) {
            // Keep serving the last good snapshot; say so once, not twenty times a second.
            if (!warned) plugin.getLogger().log(Level.WARNING, "Could not read EZvac for the website", e);
            warned = true;
        }
    }

    private JsonObject build() {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("units", api.units().name());
        root.addProperty("writable", control.writable());
        root.addProperty("ezvacVersion", api.pluginVersion());
        root.addProperty("apiVersion", api.apiVersion());

        JsonArray worlds = new JsonArray();
        JsonArray thermostats = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            if (hiddenWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) continue;
            Collection<ThermostatView> views = api.thermostats(world);
            worlds.add(worldJson(world));
            for (ThermostatView view : views) thermostats.add(thermostatJson(world, view));
        }
        root.add("worlds", worlds);
        root.add("thermostats", thermostats);
        return root;
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

    /** A thermostat's key on the website: its world and block. Labels can repeat, so they can't be keys. */
    public static String key(Position position) {
        return position.world() + ":" + position.x() + ":" + position.y() + ":" + position.z();
    }

    /** NaN and infinity are not JSON: the field is left out, and the page shows a dash. */
    private static void number(JsonObject object, String name, double value) {
        if (Double.isFinite(value)) object.addProperty(name, value);
    }
}
