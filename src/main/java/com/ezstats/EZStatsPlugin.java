package com.ezstats;

import com.ezstats.account.AccountStore;
import com.ezstats.account.OwnershipListener;
import com.ezstats.control.Controls;
import com.ezstats.control.DeviceControl;
import com.ezstats.link.LinkManager;
import com.ezstats.runs.RunListener;
import com.ezstats.runs.RunLog;
import com.ezstats.snapshot.DeviceJson;
import com.ezstats.snapshot.SnapshotService;
import com.ezstats.web.WebServer;
import com.github.blade.hvac.api.EzvacApi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class EZStatsPlugin extends JavaPlugin {
    private DeviceControl control;
    private AccountStore accounts;
    private LinkManager links;
    private SnapshotService snapshots;
    private RunLog runs;
    private WebServer web;
    private String publicUrl;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        EzvacApi api;
        try {
            api = EzvacApi.get();
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!apiAtLeast(api.apiVersion(), 1, 8)) {
            getLogger().severe("EZStats needs EZvac BETA 1.0 Pilot 3 or newer (API 1.8); this EZvac has API "
                    + api.apiVersion() + ".");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        FileConfiguration config = getConfig();
        // Controlling devices needs API 1.12; set once at start, so changing control.enabled needs a restart.
        control = Controls.create(this, api, config.getBoolean("control.enabled", true),
                apiAtLeast(api.apiVersion(), 1, 12));
        // API 1.13 (Pilot 8) says whether a device's chunk is loaded and how long a thermostat has been in its mode.
        DeviceJson.setApi113(apiAtLeast(api.apiVersion(), 1, 13));
        links = new LinkManager(codeTtl(config), config.getLong("session.ttl-hours", 72));
        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"), getLogger());
        accounts.load();
        snapshots = new SnapshotService(this, api, control, accounts::allOwned);
        getServer().getPluginManager().registerEvents(new OwnershipListener(accounts), this);

        // The last few heating and cooling runs of every thermostat, for the log on its card. Saved off the
        // main thread twice a minute when something changed, and once more when the server stops.
        runs = new RunLog(new File(getDataFolder(), "runs.yml"), getLogger());
        runs.load();
        snapshots.setRunLog(runs);
        getServer().getPluginManager().registerEvents(new RunListener(runs), this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, runs::saveIfChanged, 600L, 600L);

        EZStatsCommand command = new EZStatsCommand(this, api, links, accounts);
        PluginCommand registered = getCommand("ezstats");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }

        startWeb();
        getLogger().info("EZStats " + getPluginMeta().getVersion() + " is up with EZvac " + api.pluginVersion()
                + " (API " + api.apiVersion() + "). The website is "
                + (control.writable() ? "read and write." : "read-only. " + control.readOnlyReason()));
    }

    @Override
    public void onDisable() {
        stopWeb();
        if (runs != null) runs.saveIfChanged();
    }

    /** Reads the config again and restarts the website. */
    public void reload() {
        reloadConfig();
        links.configure(codeTtl(getConfig()), getConfig().getLong("session.ttl-hours", 72));
        stopWeb();
        startWeb();
    }

    /** How long a QuickLogin code works, in seconds. A config from before QuickLogin still has the old link key. */
    private static long codeTtl(FileConfiguration config) {
        return config.getLong("quicklogin.ttl-seconds", config.getLong("link.token-ttl-seconds", 300));
    }

    /** Whether an API version such as "1.11" is at least major.minor. */
    private static boolean apiAtLeast(String version, int major, int minor) {
        try {
            String[] parts = version.split("\\.");
            int have = Integer.parseInt(parts[0]);
            int haveMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return have > major || (have == major && haveMinor >= minor);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void startWeb() {
        FileConfiguration config = getConfig();
        String bind = config.getString("web.bind", "0.0.0.0");
        int port = config.getInt("web.port", 8765);
        publicUrl = resolvePublicUrl(config.getString("web.public-url", ""), port);

        snapshots.start(config.getInt("refresh-ticks", 20), config.getInt("weather-refresh-ticks", 200));
        web = new WebServer(getLogger(), links, accounts, snapshots, control, publicUrl.startsWith("https://"),
                config.getBoolean("web.trust-forwarded-for", false), config.getBoolean("weather-tab.enabled", true));
        try {
            web.start(bind, port);
            getLogger().info("Website listening on " + bind + ":" + port + "; players are told " + publicUrl);
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Could not start the website on " + bind + ":" + port + ": " + e.getMessage()
                    + ". Change web.port in config.yml, then run /ezstats reload.");
            web = null;
        }
    }

    private void stopWeb() {
        if (snapshots != null) snapshots.stop();
        if (web != null) web.stop();
        web = null;
    }

    private String resolvePublicUrl(String configured, int port) {
        String url = configured == null ? "" : configured.trim();
        if (!url.isEmpty()) return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String ip = Bukkit.getIp();
        if (ip == null || ip.isBlank()) {
            ip = "localhost";
            getLogger().warning("web.public-url is empty and server-ip is not set, so /ezstats quicklogin will tell players "
                    + "http://localhost:" + port + ", which only works on this machine. Set web.public-url.");
        }
        return "http://" + ip + ":" + port;
    }

    public String publicUrl() {
        return publicUrl;
    }

    public boolean webRunning() {
        return web != null;
    }

    public DeviceControl control() {
        return control;
    }
}
