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
        /** A thermostat's key to the nickname its owner gave it. Only owned thermostats have one. */
        final Map<String, String> nicknames = new LinkedHashMap<>();
        /** The account's folders, in the order they were made. */
        final List<Folder> folders = new ArrayList<>();
        /** A thermostat's key to the id of the folder it is in. A thermostat in no folder has no entry. */
        final Map<String, String> folderOf = new LinkedHashMap<>();
    }

    /** What {@link #own} did. {@code owner} is who has it when {@code TAKEN}. */
    public record OwnResult(Status status, String owner) {
        public enum Status { CLAIMED, ALREADY_YOURS, TAKEN }
    }

    public static final int MAX_FOLDERS = 20;

    /** A folder a player keeps their thermostats in. The id is what the website uses. */
    public record Folder(String id, String name) {}

    /** One owned thermostat as its owner sees it: its nickname ("" for none) and folder id ("" for none). */
    public record Item(String key, String nickname, String folder) {}

    /** Everything the website needs of an account: the thermostats in claim order, and the folders. */
    public record View(List<Item> thermostats, List<Folder> folders) {}

    public enum FolderStatus { OK, LIMIT, DUPLICATE, NOT_FOUND, NOT_OWNED }

    /** What a folder change did. {@code id} is the folder the change made or touched. */
    public record FolderChange(FolderStatus status, String id) {}

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
                for (Map<?, ?> nickname : entry.getMapList("nicknames")) {
                    Object key = nickname.get("key");
                    Object value = nickname.get("name");
                    if (key != null && value != null && account.owned.contains(key.toString())) {
                        account.nicknames.put(key.toString(), value.toString());
                    }
                }
                for (Map<?, ?> folder : entry.getMapList("folders")) {
                    Object folderId = folder.get("id");
                    Object folderName = folder.get("name");
                    if (folderId != null && folderName != null) {
                        account.folders.add(new Folder(folderId.toString(), folderName.toString()));
                    }
                }
                for (Map<?, ?> placed : entry.getMapList("folder-of")) {
                    Object key = placed.get("key");
                    Object folderId = placed.get("folder");
                    if (key != null && folderId != null && account.owned.contains(key.toString())
                            && hasFolder(account, folderId.toString())) {
                        account.folderOf.put(key.toString(), folderId.toString());
                    }
                }
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
        for (Account account : accounts.values()) {
            changed |= account.owned.remove(key);
            account.nicknames.remove(key);
            account.folderOf.remove(key);
        }
        if (changed) save();
        return changed;
    }

    // --- folders --------------------------------------------------------------------------

    /** Makes a folder. {@code playerName} is for an account that doesn't exist yet. */
    public synchronized FolderChange createFolder(UUID player, String playerName, String name) {
        Account account = account(player, playerName);
        if (account.folders.size() >= MAX_FOLDERS) return new FolderChange(FolderStatus.LIMIT, "");
        if (nameTaken(account, name, null)) return new FolderChange(FolderStatus.DUPLICATE, "");
        Folder folder = new Folder(UUID.randomUUID().toString().substring(0, 8), name);
        account.folders.add(folder);
        save();
        return new FolderChange(FolderStatus.OK, folder.id());
    }

    public synchronized FolderStatus renameFolder(UUID player, String id, String name) {
        Account account = accounts.get(player);
        if (account == null || !hasFolder(account, id)) return FolderStatus.NOT_FOUND;
        if (nameTaken(account, name, id)) return FolderStatus.DUPLICATE;
        account.folders.replaceAll(folder -> folder.id().equals(id) ? new Folder(id, name) : folder);
        save();
        return FolderStatus.OK;
    }

    /** Deletes a folder. Its thermostats aren't deleted: they go back to being in no folder. */
    public synchronized FolderStatus deleteFolder(UUID player, String id) {
        Account account = accounts.get(player);
        if (account == null || !hasFolder(account, id)) return FolderStatus.NOT_FOUND;
        account.folders.removeIf(folder -> folder.id().equals(id));
        account.folderOf.values().removeIf(id::equals);
        save();
        return FolderStatus.OK;
    }

    /** Puts an owned thermostat in a folder, or in none when {@code folderId} is empty. */
    public synchronized FolderStatus setFolder(UUID player, String key, String folderId) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return FolderStatus.NOT_OWNED;
        if (folderId.isEmpty()) {
            account.folderOf.remove(key);
        } else {
            if (!hasFolder(account, folderId)) return FolderStatus.NOT_FOUND;
            account.folderOf.put(key, folderId);
        }
        save();
        return FolderStatus.OK;
    }

    private static boolean hasFolder(Account account, String id) {
        return account.folders.stream().anyMatch(folder -> folder.id().equals(id));
    }

    /** Folder names are unique per account, ignoring case. {@code exceptId} is the folder being renamed. */
    private static boolean nameTaken(Account account, String name, String exceptId) {
        return account.folders.stream()
                .anyMatch(folder -> !folder.id().equals(exceptId) && folder.name().equalsIgnoreCase(name));
    }

    /**
     * Sets a thermostat's nickname, or clears it when {@code nickname} is empty. Only the owner can:
     * returns false when the player doesn't own the thermostat.
     */
    public synchronized boolean setNickname(UUID player, String key, String nickname) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return false;
        if (nickname.isEmpty()) account.nicknames.remove(key);
        else account.nicknames.put(key, nickname);
        save();
        return true;
    }

    /** What the website shows a player: their thermostats in the order they claimed them, and their folders. */
    public synchronized View view(UUID player) {
        Account account = accounts.get(player);
        if (account == null) return new View(List.of(), List.of());
        List<Item> items = new ArrayList<>();
        for (String key : account.owned) {
            items.add(new Item(key, account.nicknames.getOrDefault(key, ""), account.folderOf.getOrDefault(key, "")));
        }
        return new View(items, List.copyOf(account.folders));
    }

    /** Every thermostat anyone owns: the weather is only read for these. */
    public synchronized Set<String> allOwned() {
        Set<String> all = new LinkedHashSet<>();
        for (Account account : accounts.values()) all.addAll(account.owned);
        return all;
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
            List<Map<String, String>> nicknames = new ArrayList<>();
            for (Map.Entry<String, String> nickname : account.nicknames.entrySet()) {
                nicknames.add(Map.of("key", nickname.getKey(), "name", nickname.getValue()));
            }
            if (!nicknames.isEmpty()) yaml.set(base + ".nicknames", nicknames);
            List<Map<String, String>> folders = new ArrayList<>();
            for (Folder folder : account.folders) folders.add(Map.of("id", folder.id(), "name", folder.name()));
            if (!folders.isEmpty()) yaml.set(base + ".folders", folders);
            List<Map<String, String>> placed = new ArrayList<>();
            for (Map.Entry<String, String> in : account.folderOf.entrySet()) {
                placed.add(Map.of("key", in.getKey(), "folder", in.getValue()));
            }
            if (!placed.isEmpty()) yaml.set(base + ".folder-of", placed);
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
