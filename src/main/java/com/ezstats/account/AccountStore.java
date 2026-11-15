package com.ezstats.account;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Website accounts: a Minecraft player, an optional password, the devices they own (thermostats,
 * equipment, humidifiers, sensors, any EZvac device), the nicknames they gave them, and the homes
 * they keep them in. Kept in {@code accounts.yml}. Called from the main thread (commands, events)
 * and from web threads (login, the dashboard), so every method is synchronized.
 *
 * <p>A device is named by its key: its world and block (see {@code SnapshotService.key}).
 */
public final class AccountStore {
    private static final class Account {
        String name;
        String passwordHash;
        final Set<String> owned = new LinkedHashSet<>();
        /** A device's key to the nickname its owner gave it. Only owned devices have one. */
        final Map<String, String> nicknames = new LinkedHashMap<>();
        /** The account's homes, in the order they were made. */
        final List<Home> homes = new ArrayList<>();
        /** A device's key to the id of the home it is in. A device in no home has no entry. */
        final Map<String, String> homeOf = new LinkedHashMap<>();
    }

    /** What {@link #own} did. {@code owner} is who has it when {@code TAKEN}. */
    public record OwnResult(Status status, String owner) {
        public enum Status { CLAIMED, ALREADY_YOURS, TAKEN }
    }

    public static final int MAX_HOMES = 20;

    /** A home a player keeps their devices in. The id is what the website uses. */
    public record Home(String id, String name) {}

    /** One owned device as its owner sees it: its nickname ("" for none) and home id ("" for none). */
    public record Item(String key, String nickname, String home) {}

    /** Everything the website needs of an account: the devices in claim order, and the homes. */
    public record View(List<Item> devices, List<Home> homes) {}

    public enum HomeStatus { OK, LIMIT, DUPLICATE, NOT_FOUND, NOT_OWNED }

    /** What a home change did. {@code id} is the home the change made or touched. */
    public record HomeChange(HomeStatus status, String id) {}

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
        boolean migrated = false;
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) continue;
            try {
                Account account = new Account();
                account.name = entry.getString("name", "");
                account.passwordHash = entry.getString("password");
                // "thermostats" is what this list was called before it held every kind of device.
                account.owned.addAll(entry.getStringList(entry.contains("devices") ? "devices" : "thermostats"));
                for (Map<?, ?> nickname : entry.getMapList("nicknames")) {
                    Object key = nickname.get("key");
                    Object value = nickname.get("name");
                    if (key != null && value != null && account.owned.contains(key.toString())) {
                        account.nicknames.put(key.toString(), value.toString());
                    }
                }
                // Homes were called folders before 0.6.1: read them as homes, and save them as homes.
                boolean oldNames = !entry.contains("homes") && entry.contains("folders");
                migrated |= oldNames || !entry.contains("devices");
                for (Map<?, ?> home : entry.getMapList(oldNames ? "folders" : "homes")) {
                    Object homeId = home.get("id");
                    Object homeName = home.get("name");
                    if (homeId != null && homeName != null) {
                        account.homes.add(new Home(homeId.toString(), homeName.toString()));
                    }
                }
                for (Map<?, ?> placed : entry.getMapList(oldNames ? "folder-of" : "home-of")) {
                    Object key = placed.get("key");
                    Object homeId = placed.get(oldNames ? "folder" : "home");
                    if (key != null && homeId != null && account.owned.contains(key.toString())
                            && hasHome(account, homeId.toString())) {
                        account.homeOf.put(key.toString(), homeId.toString());
                    }
                }
                accounts.put(UUID.fromString(id), account);
            } catch (IllegalArgumentException e) {
                log.warning("Skipping an account in " + file.getName() + " with a bad id: " + id);
            }
        }
        if (migrated) {
            log.info("Updating " + file.getName() + ": folders are now homes, and a home can hold any device.");
            save();
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

    /** Takes a device away from whoever owns it. Returns whether anyone did. */
    public synchronized boolean release(String key) {
        boolean changed = false;
        for (Account account : accounts.values()) {
            changed |= account.owned.remove(key);
            account.nicknames.remove(key);
            account.homeOf.remove(key);
        }
        if (changed) save();
        return changed;
    }

    // --- homes ----------------------------------------------------------------------------

    /** Makes a home. {@code playerName} is for an account that doesn't exist yet. */
    public synchronized HomeChange createHome(UUID player, String playerName, String name) {
        Account account = account(player, playerName);
        if (account.homes.size() >= MAX_HOMES) return new HomeChange(HomeStatus.LIMIT, "");
        if (nameTaken(account, name, null)) return new HomeChange(HomeStatus.DUPLICATE, "");
        Home home = new Home(UUID.randomUUID().toString().substring(0, 8), name);
        account.homes.add(home);
        save();
        return new HomeChange(HomeStatus.OK, home.id());
    }

    public synchronized HomeStatus renameHome(UUID player, String id, String name) {
        Account account = accounts.get(player);
        if (account == null || !hasHome(account, id)) return HomeStatus.NOT_FOUND;
        if (nameTaken(account, name, id)) return HomeStatus.DUPLICATE;
        account.homes.replaceAll(home -> home.id().equals(id) ? new Home(id, name) : home);
        save();
        return HomeStatus.OK;
    }

    /** Deletes a home. Its devices aren't deleted: they go back to being in no home. */
    public synchronized HomeStatus deleteHome(UUID player, String id) {
        Account account = accounts.get(player);
        if (account == null || !hasHome(account, id)) return HomeStatus.NOT_FOUND;
        account.homes.removeIf(home -> home.id().equals(id));
        account.homeOf.values().removeIf(id::equals);
        save();
        return HomeStatus.OK;
    }

    /** Puts an owned device in a home, or in none when {@code homeId} is empty. */
    public synchronized HomeStatus setHome(UUID player, String key, String homeId) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return HomeStatus.NOT_OWNED;
        if (homeId.isEmpty()) {
            account.homeOf.remove(key);
        } else {
            if (!hasHome(account, homeId)) return HomeStatus.NOT_FOUND;
            account.homeOf.put(key, homeId);
        }
        save();
        return HomeStatus.OK;
    }

    private static boolean hasHome(Account account, String id) {
        return account.homes.stream().anyMatch(home -> home.id().equals(id));
    }

    /** Home names are unique per account, ignoring case. {@code exceptId} is the home being renamed. */
    private static boolean nameTaken(Account account, String name, String exceptId) {
        return account.homes.stream()
                .anyMatch(home -> !home.id().equals(exceptId) && home.name().equalsIgnoreCase(name));
    }

    // --- nicknames ------------------------------------------------------------------------

    /**
     * Sets a device's nickname, or clears it when {@code nickname} is empty. Only the owner can:
     * returns false when the player doesn't own the device.
     */
    public synchronized boolean setNickname(UUID player, String key, String nickname) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return false;
        if (nickname.isEmpty()) account.nicknames.remove(key);
        else account.nicknames.put(key, nickname);
        save();
        return true;
    }

    /** What the website shows a player: their devices in the order they claimed them, and their homes. */
    public synchronized View view(UUID player) {
        Account account = accounts.get(player);
        if (account == null) return new View(List.of(), List.of());
        List<Item> items = new ArrayList<>();
        for (String key : account.owned) {
            items.add(new Item(key, account.nicknames.getOrDefault(key, ""), account.homeOf.getOrDefault(key, "")));
        }
        return new View(items, List.copyOf(account.homes));
    }

    /** Every device anyone owns: only these are read from EZvac. */
    public synchronized Set<String> allOwned() {
        Set<String> all = new LinkedHashSet<>();
        for (Account account : accounts.values()) all.addAll(account.owned);
        return all;
    }

    /** How many devices a player owns. */
    public synchronized int ownedCount(UUID player) {
        Account account = accounts.get(player);
        return account == null ? 0 : account.owned.size();
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
            yaml.set(base + ".devices", new ArrayList<>(account.owned));
            List<Map<String, String>> nicknames = new ArrayList<>();
            for (Map.Entry<String, String> nickname : account.nicknames.entrySet()) {
                nicknames.add(Map.of("key", nickname.getKey(), "name", nickname.getValue()));
            }
            if (!nicknames.isEmpty()) yaml.set(base + ".nicknames", nicknames);
            List<Map<String, String>> homes = new ArrayList<>();
            for (Home home : account.homes) homes.add(Map.of("id", home.id(), "name", home.name()));
            if (!homes.isEmpty()) yaml.set(base + ".homes", homes);
            List<Map<String, String>> placed = new ArrayList<>();
            for (Map.Entry<String, String> in : account.homeOf.entrySet()) {
                placed.add(Map.of("key", in.getKey(), "home", in.getValue()));
            }
            if (!placed.isEmpty()) yaml.set(base + ".home-of", placed);
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
