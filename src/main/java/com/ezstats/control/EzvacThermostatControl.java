package com.ezstats.control;

import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.ThermostatView;
import com.github.blade.hvac.api.control.ControlResult;
import com.github.blade.hvac.api.control.EzvacControl;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Changes thermostats through EZvac's control API (1.12, EZvac BETA 1.0 Pilot 7). Each call names the
 * player it acts for, which EZvac records ("Last changed by" in {@code /hvac info}) and passes to a
 * cancellable {@code DeviceControlEvent}, so a claims plugin can still refuse a change. EZvac itself
 * checks nothing: who may change what is decided by the web layer, which only lets an owner through.
 *
 * <p>Keep this class away from servers with an older EZvac: its types don't exist there. The plugin
 * only loads it after checking the API version.
 */
public final class EzvacThermostatControl implements ThermostatControl {
    private static final long TIMEOUT_SECONDS = 5;

    private final EzvacControl control;

    public EzvacThermostatControl(EzvacControl control) {
        this.control = control;
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public Result setTarget(UUID actor, String key, double targetF) {
        Position position = position(key);
        if (position == null || !Double.isFinite(targetF)) return Result.failure(400, "Bad request");
        return await(control.as(actor).setTarget(position, targetF));
    }

    @Override
    public Result setEnabled(UUID actor, String key, boolean enabled) {
        Position position = position(key);
        if (position == null) return Result.failure(400, "Bad request");
        return await(control.as(actor).setEnabled(position, enabled));
    }

    @Override
    public Result setProfile(UUID actor, String key, String profile) {
        Position position = position(key);
        if (position == null) return Result.failure(400, "Bad request");
        ThermostatView.Profile wanted;
        try {
            wanted = ThermostatView.Profile.valueOf(profile.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Result.failure(400, "Bad request");
        }
        return await(control.as(actor).setProfile(position, wanted));
    }

    /** Waits for the main thread to make the change. Web threads wait; the main thread never calls this. */
    private static Result await(CompletableFuture<ControlResult> future) {
        try {
            return map(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            return Result.failure(504, "EZvac didn't answer in time. Try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(503, "Interrupted.");
        } catch (ExecutionException e) {
            return Result.failure(500, "EZvac couldn't make that change.");
        }
    }

    private static Result map(ControlResult result) {
        return switch (result.status()) {
            case DONE, UNCHANGED -> Result.success(result.message());
            case ADJUSTED -> Result.adjusted(result.message());
            case NOT_FOUND -> Result.failure(404, result.message());
            case NOT_SUPPORTED -> Result.failure(400, result.message());
            case CANCELLED -> Result.failure(403, result.message());
            // A status a later EZvac adds: show its message, and treat it as not done.
            default -> Result.failure(500, result.message());
        };
    }

    /** A thermostat's key ("world-uuid:x:y:z", see SnapshotService.key) as a position, or null if it isn't one. */
    public static Position position(String key) {
        String[] parts = key.split(":");
        if (parts.length != 4) return null;
        try {
            return new Position(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
