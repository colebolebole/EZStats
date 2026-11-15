package com.ezstats.snapshot;

import com.ezstats.account.AccountStore;
import com.ezstats.control.ThermostatControl;
import com.github.blade.hvac.api.AirQuality;
import com.github.blade.hvac.api.EzvacApi;
import com.github.blade.hvac.api.OutdoorClimate;
import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.ThermostatView;
import com.github.blade.hvac.api.WeatherStationView;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Reads EZvac on the main thread and keeps the result, so web server threads never touch Bukkit or
 * EZvac: EZvac's API is for the main thread only. Thermostats are read every few ticks; weather,
 * which changes slowly, every few seconds: every station, and the nearest one to each owned thermostat.
 * A web thread asks for the part a player owns with {@link #jsonFor}, or every station with
 * {@link #weatherJson}.
 */
public final class SnapshotService {
    private static final Gson GSON = new Gson();

    /** What one read of the thermostats found. The JSON objects are never changed after they are built. */
    private record Snapshot(long generatedAt, String units, boolean writable, String ezvacVersion,
                            String apiVersion, Map<String, JsonObject> worlds, Map<String, JsonObject> thermostats) {}

    /**
     * Every weather station by key, which station is nearest to each owned thermostat (by the
     * thermostat's key), and all the stations as the finished JSON of the Weather tab.
     */
    private record Weather(Map<String, JsonObject> stations, Map<String, JsonObject> links, String json) {}

    private static final String NO_WEATHER = "{\"generatedAt\":0,\"units\":\"F\",\"stations\":[]}";

    private final Plugin plugin;
    private final EzvacApi api;
    private final ThermostatControl control;
    private final Supplier<Set<String>> ownedKeys;

    private volatile Snapshot snapshot;
    private volatile Weather weather = new Weather(Map.of(), Map.of(), NO_WEATHER);
    private BukkitTask task;
    private BukkitTask weatherTask;
    private boolean warned;
    private boolean weatherWarned;

    public SnapshotService(Plugin plugin, EzvacApi api, ThermostatControl control, Supplier<Set<String>> ownedKeys) {
        this.plugin = plugin;
        this.api = api;
        this.control = control;
        this.ownedKeys = ownedKeys;
        this.snapshot = new Snapshot(System.currentTimeMillis(), "F", control.writable(), "", "", Map.of(), Map.of());
    }

    public void start(int periodTicks, int weatherPeriodTicks) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, Math.max(5, periodTicks));
        weatherTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshWeather, 40L,
                Math.max(20, weatherPeriodTicks));
    }

    public void stop() {
        if (task != null) task.cancel();
        if (weatherTask != null) weatherTask.cancel();
        task = null;
        weatherTask = null;
    }

    /**
     * Reads the thermostats again on the next tick, so a change made from the website shows at once
     * instead of at the next regular read. Safe from any thread.
     */
    public void refreshSoon() {
        try {
            Bukkit.getScheduler().runTask(plugin, this::refresh);
        } catch (RuntimeException e) {
            // The plugin is shutting down: there is nothing to refresh.
        }
    }

    /** Every weather station in every world with its forecast, as JSON. Safe from any thread. */
    public String weatherJson() {
        return weather.json();
    }

    /**
     * The snapshot as JSON for one player: only the thermostats in {@code view}, in its order, with
     * their nicknames and folders, and the worlds and weather stations they are in. Safe from any thread.
     */
    public String jsonFor(AccountStore.View view) {
        Snapshot snap = snapshot;
        Weather wx = weather;
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", snap.generatedAt());
        root.addProperty("units", snap.units());
        root.addProperty("writable", snap.writable());
        if (!snap.writable()) root.addProperty("readOnlyReason", control.readOnlyReason());
        root.addProperty("ezvacVersion", snap.ezvacVersion());
        root.addProperty("apiVersion", snap.apiVersion());

        JsonArray folders = new JsonArray();
        for (AccountStore.Folder folder : view.folders()) {
            JsonObject f = new JsonObject();
            f.addProperty("id", folder.id());
            f.addProperty("name", folder.name());
            folders.add(f);
        }
        root.add("folders", folders);

        JsonArray thermostats = new JsonArray();
        Set<String> worldNames = new LinkedHashSet<>();
        Set<String> stationKeys = new LinkedHashSet<>();
        for (AccountStore.Item item : view.thermostats()) {
            JsonObject shared = snap.thermostats().get(item.key());
            if (shared == null) continue;
            JsonObject thermostat = shared.deepCopy();
            if (!item.nickname().isEmpty()) thermostat.addProperty("nickname", item.nickname());
            thermostat.addProperty("folder", item.folder());
            JsonObject link = wx.links().get(item.key());
            if (link != null) {
                thermostat.add("weather", link);
                stationKeys.add(link.get("station").getAsString());
            }
            thermostats.add(thermostat);
            worldNames.add(shared.get("world").getAsString());
        }
        JsonArray worlds = new JsonArray();
        for (String name : worldNames) {
            JsonObject world = snap.worlds().get(name);
            if (world != null) worlds.add(world);
        }
        JsonObject stations = new JsonObject();
        for (String key : stationKeys) {
            JsonObject station = wx.stations().get(key);
            if (station != null) stations.add(key, station);
        }
        root.add("worlds", worlds);
        root.add("stations", stations);
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

    // --- weather ----------------------------------------------------------------------------

    private void refreshWeather() {
        try {
            weather = buildWeather();
            weatherWarned = false;
        } catch (RuntimeException e) {
            if (!weatherWarned) plugin.getLogger().log(Level.WARNING, "Could not read EZvac's weather stations", e);
            weatherWarned = true;
        }
    }

    /**
     * Every station in every world, working or not, for the Weather tab; and for each owned
     * thermostat the nearest working one that can see its spot: {@code weatherStationsNear} answers
     * nearest first, within the range EZvac's own {@code /hvac forecast} uses. A thermostat with
     * none gets no weather.
     */
    private Weather buildWeather() {
        Map<String, JsonObject> stations = new LinkedHashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (WeatherStationView station : api.weatherStations(world)) {
                String stationKey = key(station.position());
                stations.put(stationKey, stationJson(stationKey, station));
            }
        }

        Map<String, JsonObject> links = new LinkedHashMap<>();
        Set<String> owned = ownedKeys.get();
        if (!owned.isEmpty()) {
            for (ThermostatView thermostat : api.thermostats()) {
                String key = key(thermostat.position());
                if (!owned.contains(key)) continue;
                Location location = thermostat.position().toLocation();
                if (location == null) continue; // its world isn't loaded
                List<WeatherStationView> near = api.weatherStationsNear(location);
                if (near.isEmpty()) continue;

                WeatherStationView station = near.get(0);
                String stationKey = key(station.position());
                stations.computeIfAbsent(stationKey, k -> stationJson(stationKey, station));

                double dx = station.position().x() - thermostat.position().x();
                double dz = station.position().z() - thermostat.position().z();
                JsonObject link = new JsonObject();
                link.addProperty("station", stationKey);
                link.addProperty("distance", (int) Math.round(Math.hypot(dx, dz)));
                links.put(key, link);
            }
        }
        return new Weather(stations, links, weatherTabJson(stations));
    }

    /** All stations, by world and then label, with the units to show them in. */
    private String weatherTabJson(Map<String, JsonObject> stations) {
        List<JsonObject> sorted = new java.util.ArrayList<>(stations.values());
        sorted.sort(java.util.Comparator
                .comparing((JsonObject s) -> s.has("world") ? s.get("world").getAsString() : "")
                .thenComparing(s -> s.get("label").getAsString(), String.CASE_INSENSITIVE_ORDER));
        JsonArray list = new JsonArray();
        sorted.forEach(list::add);
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("units", api.units().name());
        root.add("stations", list);
        return GSON.toJson(root);
    }

    private JsonObject stationJson(String key, WeatherStationView station) {
        Position position = station.position();
        JsonObject out = new JsonObject();
        out.addProperty("key", key);
        out.addProperty("label", station.label());
        World world = Bukkit.getWorld(position.world());
        if (world != null) out.addProperty("world", world.getName());
        out.addProperty("x", position.x());
        out.addProperty("y", position.y());
        out.addProperty("z", position.z());
        out.addProperty("enabled", station.enabled());
        out.addProperty("intact", station.intact());
        out.addProperty("working", station.working());
        out.addProperty("lightningStrikes", station.lightningStrikes());
        out.addProperty("areaRadius", station.areaRadius());
        number(out, "outdoorF", station.outdoorF());
        out.add("wind", windJson(station.wind().speedMs(), station.wind().level().name(),
                station.wind().hasDirection() ? station.wind().compass() : ""));
        station.airQuality().ifPresent(air -> out.add("air", airJson(air)));

        JsonArray days = new JsonArray();
        for (WeatherStationView.ForecastDay day : station.forecast()) {
            JsonObject d = new JsonObject();
            d.addProperty("daysAhead", day.daysAhead());
            number(d, "highF", day.highF());
            number(d, "lowF", day.lowF());
            d.addProperty("sky", day.sky().name().toLowerCase(Locale.ROOT));
            number(d, "rain", day.rainChance());
            number(d, "thunder", day.thunderChance());
            d.addProperty("note", day.note());
            if (day.aqi() >= 0) d.addProperty("aqi", day.aqi());
            day.wind().ifPresent(w -> d.add("wind", windJson(w.meanSpeedMs(), w.level().name(), w.compass())));
            days.add(d);
        }
        out.add("forecast", days);
        return out;
    }

    private static JsonObject windJson(double speedMs, String level, String compass) {
        JsonObject out = new JsonObject();
        number(out, "speedMs", speedMs);
        out.addProperty("level", level.toLowerCase(Locale.ROOT));
        if (!compass.isEmpty()) out.addProperty("from", compass);
        return out;
    }

    private static JsonObject airJson(AirQuality air) {
        JsonObject out = new JsonObject();
        out.addProperty("aqi", air.aqi());
        out.addProperty("band", air.band().name().toLowerCase(Locale.ROOT));
        return out;
    }

    // --- thermostats and worlds -------------------------------------------------------------

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

    /** A device's key: its world and block. Labels can repeat, so they can't be keys. */
    public static String key(Position position) {
        return position.world() + ":" + position.x() + ":" + position.y() + ":" + position.z();
    }

    /** NaN and infinity are not JSON: the field is left out, and the page shows a dash. */
    private static void number(JsonObject object, String name, double value) {
        if (Double.isFinite(value)) object.addProperty(name, value);
    }
}
