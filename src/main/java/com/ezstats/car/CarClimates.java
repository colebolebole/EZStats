package com.ezstats.car;

import com.ezstats.control.DeviceControl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * The climate control of cars from <a href="https://github.com/colebolebole/MinetopiaVehicles">MTVehicles</a> (its
 * {@code CarClimateApi}, see its {@code docs/car-climate-api.md}), when that plugin is installed.
 *
 * <p>The API is made of static methods with plain Java types, so it is called by reflection: EZStats does not need
 * MTVehicles to be built or to run. A car is a device like the others, with the key {@code car:<license plate>}, and
 * its owner links it in game with {@code /vehicle hvac link}, then claims it with {@code /ezstats own} while sitting in it.
 *
 * <p>Reading and {@link #licenseOf(Player)} are for the main thread. {@link #control} may be called from any thread.
 */
public final class CarClimates {
    /** What the key of a car starts with: {@code car:AB-123}. Keys of EZvac devices start with a world's id. */
    public static final String KEY_PREFIX = "car:";

    private final Plugin plugin;
    private final Method licenseOfMethod;
    private final Method ownerOfMethod;
    private final Method isLinkedMethod;
    private final Method describeMethod;
    private final Method setEnabledMethod;
    private final Method setTargetMethod;
    private final Method setModeMethod;
    private final Method setFanMethod;

    private CarClimates(Plugin plugin, Class<?> api) throws NoSuchMethodException {
        this.plugin = plugin;
        licenseOfMethod = api.getMethod("licenseOf", Player.class);
        ownerOfMethod = api.getMethod("ownerOf", String.class);
        isLinkedMethod = api.getMethod("isLinked", String.class);
        describeMethod = api.getMethod("describe", String.class);
        setEnabledMethod = api.getMethod("setEnabled", UUID.class, String.class, boolean.class);
        setTargetMethod = api.getMethod("setTargetF", UUID.class, String.class, double.class);
        setModeMethod = api.getMethod("setMode", UUID.class, String.class, String.class);
        setFanMethod = api.getMethod("setFan", UUID.class, String.class, String.class);
    }

    /**
     * Connects to MTVehicles' climate API.
     *
     * @return null when MTVehicles isn't installed, is too old, or has its climate control switched off
     */
    public static CarClimates create(Plugin plugin) {
        Plugin vehicles = Bukkit.getPluginManager().getPlugin("MTVehicles");
        if (vehicles == null || !vehicles.isEnabled()) return null;
        try {
            Class<?> api = Class.forName("nl.mtvehicles.core.api.CarClimateApi", true, vehicles.getClass().getClassLoader());
            if (api.getField("API_VERSION").getInt(null) < 1) return null;
            if (!(Boolean) api.getMethod("isAvailable").invoke(null)) {
                plugin.getLogger().info("MTVehicles has its car climate control switched off (hvac.enabled), so cars are not shown.");
                return null;
            }
            CarClimates cars = new CarClimates(plugin, api);
            plugin.getLogger().info("MTVehicles found: cars with a linked climate control can be put on the website.");
            return cars;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("MTVehicles is installed, but it has no car climate control (it needs a newer version).");
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not use MTVehicles' car climate control", e);
            return null;
        }
    }

    // ---- Keys ----------------------------------------------------------------------------------

    public static boolean isCarKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    public static String keyOf(String license) {
        return KEY_PREFIX + license;
    }

    public static String licenseOfKey(String key) {
        return key.substring(KEY_PREFIX.length());
    }

    // ---- Reading (main thread) -----------------------------------------------------------------

    /** The license plate of the car with a climate control the player is sitting in. */
    public Optional<String> licenseOf(Player player) {
        return Optional.ofNullable((String) call(licenseOfMethod, player));
    }

    public Optional<UUID> ownerOf(String license) {
        return Optional.ofNullable((UUID) call(ownerOfMethod, license));
    }

    /** Whether its owner has linked it ({@code /vehicle hvac link}). */
    public boolean isLinked(String license) {
        return Boolean.TRUE.equals(call(isLinkedMethod, license));
    }

    /** What MTVehicles says about a car, see its docs. Empty when the car doesn't exist or has no climate control. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> describe(String license) {
        return Optional.ofNullable((Map<String, Object>) call(describeMethod, license));
    }

    private static Object call(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("MTVehicles' car climate API failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Changing (any thread) -----------------------------------------------------------------

    /**
     * Changes a car. MTVehicles wants the main thread, so a web thread waits (up to five seconds) for it.
     *
     * @param what {@code enabled}, {@code target}, {@code mode} or {@code fan}
     */
    public DeviceControl.Result control(UUID actor, String key, String what, Object value) {
        String license = licenseOfKey(key);
        try {
            Map<String, Object> result;
            if (Bukkit.isPrimaryThread()) {
                result = run(actor, license, what, value);
            } else {
                result = Bukkit.getScheduler().callSyncMethod(plugin, () -> run(actor, license, what, value))
                        .get(5, TimeUnit.SECONDS);
            }
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            int status = result.get("status") instanceof Number n ? n.intValue() : ok ? 200 : 500;
            String message = String.valueOf(result.getOrDefault("message", ""));
            if (!ok) return DeviceControl.Result.failure(status, message);
            return Boolean.TRUE.equals(result.get("adjusted"))
                    ? DeviceControl.Result.adjusted(message) : DeviceControl.Result.success(message);
        } catch (TimeoutException e) {
            return DeviceControl.Result.failure(504, "The server didn't answer in time. Try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeviceControl.Result.failure(503, "The server was interrupted. Try again.");
        } catch (ExecutionException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not change a car's climate control", e);
            return DeviceControl.Result.failure(500, "Something went wrong on the server.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(UUID actor, String license, String what, Object value) {
        return (Map<String, Object>) switch (what) {
            case "enabled" -> call(setEnabledMethod, actor, license, value);
            case "target" -> call(setTargetMethod, actor, license, value);
            case "mode" -> call(setModeMethod, actor, license, value);
            case "fan" -> call(setFanMethod, actor, license, value);
            default -> throw new IllegalArgumentException(what);
        };
    }
}
