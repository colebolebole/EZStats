package com.ezstats.control;

import com.github.blade.hvac.api.ClimateUnitView;
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
import java.util.function.Function;

/**
 * Changes devices through EZvac's control API (1.12, EZvac BETA 1.0 Pilot 7). Each call names the
 * player it acts for, which EZvac records ("Last changed by" in {@code /hvac info}) and passes to a
 * cancellable {@code DeviceControlEvent}, so a claims plugin can still refuse a change. EZvac itself
 * checks nothing: who may change what is decided by the web layer, which only lets an owner through.
 *
 * <p>Keep this class away from servers with an older EZvac: its types don't exist there. The plugin
 * only loads it after checking the API version.
 */
public final class EzvacDeviceControl implements DeviceControl {
    private static final long TIMEOUT_SECONDS = 5;
    private static final Result BAD_REQUEST = Result.failure(400, "Bad request");

    private final EzvacControl control;

    public EzvacDeviceControl(EzvacControl control) {
        this.control = control;
    }

    @Override
    public boolean writable() {
        return true;
    }

    /** Runs a call for {@code actor} on the device named by {@code key}, and waits for the answer. */
    private Result call(UUID actor, String key, Function<Call, CompletableFuture<ControlResult>> change) {
        Position position = position(key);
        if (position == null) return BAD_REQUEST;
        return await(change.apply(new Call(control.as(actor), position)));
    }

    private record Call(EzvacControl control, Position position) {}

    @Override
    public Result setTarget(UUID actor, String key, double targetF) {
        if (!Double.isFinite(targetF)) return BAD_REQUEST;
        return call(actor, key, c -> c.control().setTarget(c.position(), targetF));
    }

    @Override
    public Result setEnabled(UUID actor, String key, boolean enabled) {
        return call(actor, key, c -> c.control().setEnabled(c.position(), enabled));
    }

    @Override
    public Result setProfile(UUID actor, String key, String profile) {
        ThermostatView.Profile wanted = parse(ThermostatView.Profile.class, profile);
        if (wanted == null) return BAD_REQUEST;
        return call(actor, key, c -> c.control().setProfile(c.position(), wanted));
    }

    @Override
    public Result setPowered(UUID actor, String key, boolean powered) {
        return call(actor, key, c -> c.control().setPowered(c.position(), powered));
    }

    @Override
    public Result setSetpoint(UUID actor, String key, int setpoint) {
        return call(actor, key, c -> c.control().setSetpoint(c.position(), setpoint));
    }

    @Override
    public Result setFridgeSetpoint(UUID actor, String key, int setpointF) {
        return call(actor, key, c -> c.control().setFridgeSetpoint(c.position(), setpointF));
    }

    @Override
    public Result setFan(UUID actor, String key, String fan) {
        ClimateUnitView.Fan wanted = parse(ClimateUnitView.Fan.class, fan);
        if (wanted == null) return BAD_REQUEST;
        return call(actor, key, c -> c.control().setFan(c.position(), wanted));
    }

    @Override
    public Result startDefrost(UUID actor, String key) {
        return call(actor, key, c -> c.control().startDefrost(c.position()));
    }

    @Override
    public Result setAutoDefrost(UUID actor, String key, boolean on) {
        return call(actor, key, c -> c.control().setAutoDefrost(c.position(), on));
    }

    @Override
    public Result setEquipmentEnabled(UUID actor, String key, boolean enabled) {
        return call(actor, key, c -> c.control().setEquipmentEnabled(c.position(), enabled));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    /** A device's key ("world-uuid:x:y:z", see SnapshotService.key) as a position, or null if it isn't one. */
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
