package com.ezstats.runs;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The last {@value #KEEP} heating and cooling runs of each thermostat, for the log on its card.
 *
 * <p>EZvac's API 1.13 keeps only the latest finished run of a thermostat, so EZStats keeps the rest itself: it
 * is told each time a system starts or stops heating or cooling ({@code ThermostatModeChangeEvent}) and
 * writes a run down when it ends. The log is saved to {@code runs.yml}, so a restart keeps it, and a
 * thermostat that is removed loses its log. It holds at most {@value #KEEP} short entries per thermostat.
 *
 * <p>Modes are lower-case words ({@code heating}, {@code cooling}, {@code idle}) so this class doesn't depend on
 * EZvac. Safe from any thread.
 */
public final class RunLog {
    /** How many runs are kept for each thermostat. */
    public static final int KEEP = 5;
    /** A run EZvac reports for a change should have ended about now: further off than this isn't trusted. */
    private static final long TRUST_MS = 60_000L;

    /** A finished run: {@code heating} or {@code cooling}, and when it started and ended (epoch milliseconds). */
    public record Run(String mode, long startedAt, long endedAt) {}

    private final File file;
    private final Logger log;
    private final Map<String, Deque<Run>> history = new HashMap<>(); // newest first
    private final Map<String, Long> open = new HashMap<>();           // a run that has started: not saved
    private final Object saveLock = new Object();
    private boolean dirty;

    public RunLog(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    private static boolean isRun(String mode) {
        return "heating".equals(mode) || "cooling".equals(mode);
    }

    /**
     * A thermostat changed from one mode to another. If it was heating or cooling that run is over: it is
     * written down using {@code reported} (the run EZvac says has just finished, if it says) or else when
     * this log saw it start. If it is now heating or cooling, a new run begins.
     */
    public synchronized void changed(String key, String from, String to, Run reported, long now) {
        Long started = open.remove(key);
        if (isRun(from)) {
            Run run = null;
            if (reported != null && from.equals(reported.mode()) && Math.abs(reported.endedAt() - now) <= TRUST_MS) {
                run = reported;
            } else if (started != null) {
                run = new Run(from, started, now);
            }
            if (run != null) add(key, run);
        }
        if (isRun(to)) open.put(key, now);
    }

    private void add(String key, Run run) {
        Deque<Run> runs = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        Run newest = runs.peekFirst();
        if (newest != null && newest.mode().equals(run.mode()) && newest.endedAt() == run.endedAt()) return; // seen already
        runs.addFirst(run);
        while (runs.size() > KEEP) runs.removeLast();
        dirty = true;
    }

    /** The thermostat's runs, newest first: at most {@value #KEEP}. */
    public synchronized List<Run> runs(String key) {
        Deque<Run> runs = history.get(key);
        return runs == null ? List.of() : List.copyOf(runs);
    }

    /** A thermostat is gone: so is its log. */
    public synchronized void forget(String key) {
        open.remove(key);
        if (history.remove(key) != null) dirty = true;
    }

    // --- saving --------------------------------------------------------------------------------

    public synchronized void load() {
        history.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("runs");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Deque<Run> runs = new ArrayDeque<>();
            for (Map<?, ?> entry : section.getMapList(key)) {
                try {
                    String mode = String.valueOf(entry.get("mode"));
                    long start = ((Number) entry.get("start")).longValue();
                    long end = ((Number) entry.get("end")).longValue();
                    if (isRun(mode) && end >= start && runs.size() < KEEP) runs.addLast(new Run(mode, start, end));
                } catch (RuntimeException e) {
                    // a damaged entry is dropped, the rest are kept
                }
            }
            if (!runs.isEmpty()) history.put(key, runs);
        }
    }

    /**
     * Writes the log if it changed since the last write. Call from any thread; cheap when nothing changed.
     * Two writes never run at once (the timer's and the one when the server stops could): they would share
     * the temporary file.
     */
    public void saveIfChanged() {
        synchronized (saveLock) {
            write();
        }
    }

    private void write() {
        YamlConfiguration yaml;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            yaml = new YamlConfiguration();
            for (Map.Entry<String, Deque<Run>> entry : history.entrySet()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Run run : entry.getValue()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("mode", run.mode());
                    item.put("start", run.startedAt());
                    item.put("end", run.endedAt());
                    list.add(item);
                }
                yaml.set("runs." + entry.getKey(), list);
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            File temp = new File(file.getPath() + ".tmp");
            yaml.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            synchronized (this) {
                dirty = true; // try again at the next save
            }
            log.log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
