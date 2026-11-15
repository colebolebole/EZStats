package com.ezstats.account;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Website accounts: a Minecraft player, an optional password and the thermostats they own.
 * Kept in {@code accounts.yml}. Called from the main thread (commands, events) and from web
 * threads (login, the dashboard), so every method is synchronized.
 */
public final class AccountStore {
    private static final class Account {
        String name;
        String passwordHash;
        final Set<String> owned = new LinkedHashSet<>();
    }

    /** What {@link #own} did. {@code owner} is who has it when {@code TAKEN}. */
    public record OwnResult(Status status, String owner) {
        public enum Status { CLAIMED, ALREADY_YOURS, TAKEN }
    }

    private final File file;
    private final Logger log;
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();

    public AccountStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public synchronized void load() {
        accounts.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("accounts");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) continue;
            try {
                Account account = new Account();
                account.name = entry.getString("name", "");
                account.passwordHash = entry.getString("password");
                account.owned.addAll(entry.getStringList("thermostats"));
                accounts.put(UUID.fromString(id), account);
            } catch (IllegalArgumentException e) {
                log.warning("Skipping an account in " + file.getName() + " with a bad id: " + id);
            }
        }
    }

    public synchronized boolean hasPassword(UUID player) {
        Account account = accounts.get(player);
        return account != null && account.passwordHash != null;
    }

    public synchronized void setPassword(UUID player, String name, String hash) {
        account(player, name).passwordHash = hash;
        save();
    }

    /** Remembers a player's current name: a login is by name. */
    public synchronized void touch(UUID player, String name) {
        Account account = accounts.get(player);
        if (account != null && !name.equals(account.name)) {
            account.name = name;
            save();
        }
    }

    /** The accounts with a password whose name matches (a renamed player can leave a stale match). */
    public synchronized List<UUID> loginCandidates(String name) {
        List<UUID> found = new ArrayList<>();
        for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
            Account account = entry.getValue();
            if (account.passwordHash != null && account.name.equalsIgnoreCase(name)) found.add(entry.getKey());
        }
        return found;
    }

    public synchronized String passwordHash(UUID player) {
        Account account = accounts.get(player);
        return account == null ? null : account.passwordHash;
    }

    public synchronized String name(UUID player) {
        Account account = accounts.get(player);
        return account == null ? null : account.name;
    }

    public synchronized OwnResult own(UUID player, String name, String key) {
        for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
            if (entry.getValue().owned.contains(key)) {
                return entry.getKey().equals(player)
                        ? new OwnResult(OwnResult.Status.ALREADY_YOURS, entry.getValue().name)
                        : new OwnResult(OwnResult.Status.TAKEN, entry.getValue().name);
            }
        }
        account(player, name).owned.add(key);
        save();
        return new OwnResult(OwnResult.Status.CLAIMED, name);
    }

    public synchronized Optional<UUID> ownerOf(String key) {
        for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
            if (entry.getValue().owned.contains(key)) return Optional.of(entry.getKey());
        }
        return Optional.empty();
    }

    /** Takes a thermostat away from whoever owns it. Returns whether anyone did. */
    public synchronized boolean release(String key) {
        boolean changed = false;
        for (Account account : accounts.values()) changed |= account.owned.remove(key);
        if (changed) save();
        return changed;
    }

    public synchronized Set<String> owned(UUID player) {
        Account account = accounts.get(player);
        // A copy in the order they were claimed, so the page keeps a steady order.
        return account == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(account.owned));
    }

    private Account account(UUID player, String name) {
        Account account = accounts.computeIfAbsent(player, id -> new Account());
        account.name = name;
        return account;
    }

    /** Writes to a temporary file and moves it over, so a crash can't leave half an accounts.yml. */
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Account> entry : accounts.entrySet()) {
            String base = "accounts." + entry.getKey();
            Account account = entry.getValue();
            yaml.set(base + ".name", account.name);
            if (account.passwordHash != null) yaml.set(base + ".password", account.passwordHash);
            yaml.set(base + ".thermostats", new ArrayList<>(account.owned));
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
            log.log(Level.SEVERE, "Could not save " + file.getName(), e);
        }
    }

}
