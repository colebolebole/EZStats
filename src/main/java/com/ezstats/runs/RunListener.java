package com.ezstats.runs;

import com.ezstats.snapshot.Api113;
import com.ezstats.snapshot.DeviceJson;
import com.ezstats.snapshot.SnapshotService;
import com.github.blade.hvac.api.ThermostatView;
import com.github.blade.hvac.api.event.DeviceRemovedEvent;
import com.github.blade.hvac.api.event.ThermostatModeChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;

/** Feeds the {@link RunLog}: a thermostat changing mode ends one run and may start another. */
public final class RunListener implements Listener {
    private final RunLog runs;

    public RunListener(RunLog runs) {
        this.runs = runs;
    }

    @EventHandler
    public void onModeChange(ThermostatModeChangeEvent event) {
        ThermostatView thermostat = event.getThermostat();
        String key = SnapshotService.key(thermostat.position());
        String from = event.getFrom().name().toLowerCase(Locale.ROOT);
        String to = thermostat.mode().name().toLowerCase(Locale.ROOT);
        // From API 1.13 EZvac says exactly when the run that just ended began and stopped, even across a restart.
        RunLog.Run reported = null;
        if (DeviceJson.api113()) {
            try {
                reported = Api113.lastRun(thermostat).orElse(null);
            } catch (LinkageError e) {
                // not really 1.13: fall back to what this log saw itself
            }
        }
        runs.changed(key, from, to, reported, System.currentTimeMillis());
    }

    @EventHandler
    public void onRemoved(DeviceRemovedEvent event) {
        if (event.getDevice() instanceof ThermostatView) runs.forget(SnapshotService.key(event.getDevice().position()));
    }
}
