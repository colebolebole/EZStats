package com.ezstats.account;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
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
 * equipment, humidifiers, sensors, any EZvac device), the nicknames they gave them, and the
 * organizations, folders and homes they keep them in (and the order of both). Kept in {@code accounts.yml}. Called from the main thread (commands, events)
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
        /** The account's organizations, folders and homes, in the order they were made. */
        final List<Group> groups = new ArrayList<>();
        /** A device's key to the id of the organization, folder or home it is in. A device in none has no entry. */
        final Map<String, String> homeOf = new LinkedHashMap<>();
    }

    /** What {@link #own} did. {@code owner} is who has it when {@code TAKEN}. */
    public record OwnResult(Status status, String owner) {
        public enum Status { CLAIMED, ALREADY_YOURS, TAKEN }
    }

    public static final int MAX_HOMES = 20;
    public static final int MAX_FOLDERS = 40;
    public static final int MAX_ORGS = 10;
    /** How deep things nest: a top-level group is depth 0, and nothing goes deeper than this. */
    public static final int MAX_DEPTH = 4;

    /**
     * What a group is. An organization sits at the top. A folder goes in an organization or another folder (or
     * at the top). A home goes in an organization or a folder (or at the top) and holds devices.
     */
    public enum Kind {
        ORG, FOLDER, HOME;

        public int limit() {
            return switch (this) {
                case ORG -> MAX_ORGS;
                case FOLDER -> MAX_FOLDERS;
                case HOME -> MAX_HOMES;
            };
        }

        public String noun() {
            return switch (this) {
                case ORG -> "organization";
                case FOLDER -> "folder";
                case HOME -> "home";
            };
        }
    }

    /**
     * An organization, folder or home a player made. The id is what the website uses; {@code parent} is the id
     * of the organization or folder it is in, or "" at the top.
     */
    public record Group(String id, String name, Kind kind, String parent) {}

    /** One owned device as its owner sees it: its nickname ("" for none) and home id ("" for none). */
    public record Item(String key, String nickname, String home) {}

    /** Everything the website needs of an account: the devices in claim order, and the groups. */
    public record View(List<Item> devices, List<Group> groups) {
        public List<Group> of(Kind kind) {
            return groups.stream().filter(group -> group.kind() == kind).toList();
        }
    }

    public enum GroupStatus { OK, LIMIT, DUPLICATE, NOT_FOUND, NOT_OWNED, INVALID }

    /** What a change to the groups did. {@code id} is the group the change made or touched. */
    public record GroupChange(GroupStatus status, String id) {}

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
                if (entry.contains("groups")) {
                    // From 0.8: organizations, folders and homes, each with the group it is in.
                    for (Map<?, ?> group : entry.getMapList("groups")) {
                        Object groupId = group.get("id");
                        Object groupName = group.get("name");
                        Kind kind = kindOf(group.get("kind"));
                        Object parent = group.get("parent");
                        if (groupId != null && groupName != null && kind != null) {
                            account.groups.add(new Group(groupId.toString(), groupName.toString(), kind,
                                    parent == null ? "" : parent.toString()));
                        }
                    }
                    repairGroups(account);
                } else {
                    // Before 0.8 there were only homes, all at the top.
                    for (Map<?, ?> home : entry.getMapList(oldNames ? "folders" : "homes")) {
                        Object homeId = home.get("id");
                        Object homeName = home.get("name");
                        if (homeId != null && homeName != null) {
                            account.groups.add(new Group(homeId.toString(), homeName.toString(), Kind.HOME, ""));
                        }
                    }
                }
                for (Map<?, ?> placed : entry.getMapList(oldNames ? "folder-of" : "home-of")) {
                    Object key = placed.get("key");
                    Object homeId = placed.get(oldNames ? "folder" : "home");
                    if (key != null && homeId != null && account.owned.contains(key.toString())
                            && hasGroup(account, homeId.toString())) {
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

    // --- organizations, folders and homes ------------------------------------------------------

    /**
     * Makes an organization, folder or home inside {@code parent} (an organization or folder id; "" for the
     * top). {@code playerName} is for an account that doesn't exist yet.
     */
    public synchronized GroupChange create(UUID player, String playerName, Kind kind, String name, String parent) {
        Account account = account(player, playerName);
        if (account.groups.stream().filter(group -> group.kind() == kind).count() >= kind.limit()) {
            return new GroupChange(GroupStatus.LIMIT, "");
        }
        GroupStatus fits = fits(account, kind, null, parent);
        if (fits != GroupStatus.OK) return new GroupChange(fits, "");
        if (nameTaken(account, name, parent, null)) return new GroupChange(GroupStatus.DUPLICATE, "");
        Group group = new Group(newId(account), name, kind, parent);
        account.groups.add(group);
        save();
        return new GroupChange(GroupStatus.OK, group.id());
    }

    /** Renames a group of the given kind. */
    public synchronized GroupStatus rename(UUID player, Kind kind, String id, String name) {
        Account account = accounts.get(player);
        Group group = account == null ? null : find(account, kind, id);
        if (group == null) return GroupStatus.NOT_FOUND;
        if (nameTaken(account, name, group.parent(), id)) return GroupStatus.DUPLICATE;
        replace(account, new Group(id, name, group.kind(), group.parent()));
        save();
        return GroupStatus.OK;
    }

    /**
     * Deletes a group of the given kind. Nothing inside it is deleted: the groups and devices it held move up
     * to where it was (at the top level, devices go back to being in no group).
     */
    public synchronized GroupStatus delete(UUID player, Kind kind, String id) {
        Account account = accounts.get(player);
        Group group = account == null ? null : find(account, kind, id);
        if (group == null) return GroupStatus.NOT_FOUND;
        account.groups.replaceAll(g -> g.parent().equals(id) ? new Group(g.id(), g.name(), g.kind(), group.parent()) : g);
        account.groups.removeIf(g -> g.id().equals(id));
        if (group.parent().isEmpty()) account.homeOf.values().removeIf(id::equals);
        else account.homeOf.replaceAll((device, place) -> place.equals(id) ? group.parent() : place);
        save();
        return GroupStatus.OK;
    }

    /**
     * Moves a folder or home (with everything in it) into an organization or folder, or to the top when
     * {@code parent} is empty. An organization stays at the top, and nothing goes inside itself.
     */
    public synchronized GroupStatus move(UUID player, String id, String parent) {
        Account account = accounts.get(player);
        Group group = account == null ? null : byId(account, id);
        if (group == null) return GroupStatus.NOT_FOUND;
        GroupStatus fits = fits(account, group.kind(), id, parent);
        if (fits != GroupStatus.OK) return fits;
        if (nameTaken(account, group.name(), parent, id)) return GroupStatus.DUPLICATE;
        replace(account, new Group(id, group.name(), group.kind(), parent));
        save();
        return GroupStatus.OK;
    }

    /**
     * Puts an owned device in an organization, folder or home, or in none when {@code groupId} is empty. (The
     * name is from when only homes held devices.)
     */
    public synchronized GroupStatus setHome(UUID player, String key, String groupId) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return GroupStatus.NOT_OWNED;
        if (!groupId.isEmpty() && !hasGroup(account, groupId)) return GroupStatus.NOT_FOUND;
        putIn(account, key, groupId);
        save();
        return GroupStatus.OK;
    }

    /**
     * Drag and drop: puts an owned device in a group (or in none, with an empty {@code groupId}) and just
     * before another device already there, or at the end when {@code before} is empty or isn't there.
     */
    public synchronized GroupStatus placeDevice(UUID player, String key, String groupId, String before) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return GroupStatus.NOT_OWNED;
        if (!groupId.isEmpty() && !hasGroup(account, groupId)) return GroupStatus.NOT_FOUND;
        putIn(account, key, groupId);
        boolean there = !before.isEmpty() && !before.equals(key) && account.owned.contains(before)
                && account.homeOf.getOrDefault(before, "").equals(groupId);
        moveBefore(account, key, there ? before : "");
        save();
        return GroupStatus.OK;
    }

    private static void putIn(Account account, String key, String groupId) {
        if (groupId.isEmpty()) account.homeOf.remove(key);
        else account.homeOf.put(key, groupId);
    }

    // --- order -----------------------------------------------------------------------------

    /**
     * Moves an owned device to just before another in the same place, or to the end of what is there when
     * {@code before} is empty. The order is the order of the account's devices, which is saved.
     */
    public synchronized GroupStatus reorderDevice(UUID player, String key, String before) {
        Account account = accounts.get(player);
        if (account == null || !account.owned.contains(key)) return GroupStatus.NOT_OWNED;
        if (key.equals(before)) return GroupStatus.OK;
        if (!before.isEmpty()) {
            if (!account.owned.contains(before)) return GroupStatus.NOT_FOUND;
            if (!account.homeOf.getOrDefault(key, "").equals(account.homeOf.getOrDefault(before, ""))) {
                return GroupStatus.INVALID; // only among the devices of one place
            }
        }
        moveBefore(account, key, before);
        save();
        return GroupStatus.OK;
    }

    /** Reorders the account's devices so {@code key} is just before {@code before}, or last of its place. */
    private static void moveBefore(Account account, String key, String before) {
        List<String> order = new ArrayList<>(account.owned);
        order.remove(key);
        if (before.isEmpty()) {
            // The end of its place: just after the last device that shares it.
            String place = account.homeOf.getOrDefault(key, "");
            int at = 0;
            for (int i = 0; i < order.size(); i++) {
                if (account.homeOf.getOrDefault(order.get(i), "").equals(place)) at = i + 1;
            }
            order.add(at, key);
        } else {
            order.add(order.indexOf(before), key);
        }
        account.owned.clear();
        account.owned.addAll(order);
    }

    /**
     * Moves an organization, folder or home to just before another group in the same place, or to the end of
     * what sits there when {@code before} is empty.
     */
    public synchronized GroupStatus reorderGroup(UUID player, String id, String before) {
        Account account = accounts.get(player);
        Group group = account == null ? null : byId(account, id);
        if (group == null) return GroupStatus.NOT_FOUND;
        if (id.equals(before)) return GroupStatus.OK;
        if (!before.isEmpty()) {
            Group other = byId(account, before);
            if (other == null) return GroupStatus.NOT_FOUND;
            if (!other.parent().equals(group.parent())) return GroupStatus.INVALID;
        }
        insertGroup(account, group, before);
        save();
        return GroupStatus.OK;
    }

    /**
     * Drag and drop: moves an organization, folder or home into {@code parent} (an organization or folder id;
     * "" for the top) and just before another group there, or at the end when {@code before} is empty or isn't
     * there. The same rules as {@link #move}: an organization stays at the top, nothing goes inside itself or
     * what it holds or too deep, and names stay unique beside each other.
     */
    public synchronized GroupStatus placeGroup(UUID player, String id, String parent, String before) {
        Account account = accounts.get(player);
        Group group = account == null ? null : byId(account, id);
        if (group == null) return GroupStatus.NOT_FOUND;
        GroupStatus fits = fits(account, group.kind(), id, parent);
        if (fits != GroupStatus.OK) return fits;
        if (!parent.equals(group.parent()) && nameTaken(account, group.name(), parent, id)) return GroupStatus.DUPLICATE;
        Group placed = new Group(id, group.name(), group.kind(), parent);
        Group other = before.isEmpty() || before.equals(id) ? null : byId(account, before);
        insertGroup(account, placed, other != null && other.parent().equals(parent) ? before : "");
        save();
        return GroupStatus.OK;
    }

    /** Puts {@code group} (replacing its old entry) just before {@code before}, or last among its siblings. */
    private static void insertGroup(Account account, Group group, String before) {
        account.groups.removeIf(g -> g.id().equals(group.id()));
        if (before.isEmpty()) {
            int at = 0;
            for (int i = 0; i < account.groups.size(); i++) {
                if (account.groups.get(i).parent().equals(group.parent())) at = i + 1;
            }
            account.groups.add(at, group);
        } else {
            int target = 0;
            for (int i = 0; i < account.groups.size(); i++) if (account.groups.get(i).id().equals(before)) target = i;
            account.groups.add(target, group);
        }
    }

    private static Group byId(Account account, String id) {
        for (Group group : account.groups) if (group.id().equals(id)) return group;
        return null;
    }

    private static Group find(Account account, Kind kind, String id) {
        Group group = byId(account, id);
        return group != null && group.kind() == kind ? group : null;
    }

    private static boolean hasGroup(Account account, String id) {
        return byId(account, id) != null;
    }

    private static void replace(Account account, Group changed) {
        account.groups.replaceAll(group -> group.id().equals(changed.id()) ? changed : group);
    }

    private static String newId(Account account) {
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (byId(account, id) != null);
        return id;
    }

    /** How many groups it is inside: 0 at the top. Stops at a loop, which a saved file could contain. */
    private static int depth(Account account, String id) {
        int depth = 0;
        Group group = byId(account, id);
        while (group != null && !group.parent().isEmpty() && depth <= MAX_DEPTH + 1) {
            depth++;
            group = byId(account, group.parent());
        }
        return depth;
    }

    /** How many levels of groups are below this one: 0 for a home, or for an empty folder. */
    private static int height(Account account, String id, int guard) {
        if (guard > MAX_DEPTH + 1) return 0;
        int tallest = -1;
        for (Group group : account.groups) {
            if (group.parent().equals(id)) tallest = Math.max(tallest, height(account, group.id(), guard + 1));
        }
        return tallest + 1;
    }

    /** Whether {@code candidate} is {@code id} or inside it. */
    private static boolean inside(Account account, String candidate, String id) {
        String at = candidate;
        for (int steps = 0; steps <= MAX_DEPTH + 2 && at != null && !at.isEmpty(); steps++) {
            if (at.equals(id)) return true;
            Group group = byId(account, at);
            at = group == null ? null : group.parent();
        }
        return false;
    }

    /**
     * Whether a group of this kind may go in {@code parent}: an organization only at the top, a folder or home
     * in an organization or folder; never inside itself or what it holds, and not so deep that the page
     * becomes a maze. {@code selfId} is the group being moved, null for a new one.
     */
    private static GroupStatus fits(Account account, Kind kind, String selfId, String parent) {
        if (parent.isEmpty()) return GroupStatus.OK;
        if (kind == Kind.ORG) return GroupStatus.INVALID;
        Group holder = byId(account, parent);
        if (holder == null) return GroupStatus.NOT_FOUND;
        if (holder.kind() == Kind.HOME) return GroupStatus.INVALID;
        if (selfId != null && inside(account, parent, selfId)) return GroupStatus.INVALID;
        int below = selfId == null ? 0 : height(account, selfId, 0);
        if (depth(account, parent) + 1 + below > MAX_DEPTH) return GroupStatus.INVALID;
        return GroupStatus.OK;
    }

    /** Names are unique among what sits side by side, ignoring case. {@code exceptId} is the group being renamed. */
    private static boolean nameTaken(Account account, String name, String parent, String exceptId) {
        return account.groups.stream().anyMatch(group -> !group.id().equals(exceptId)
                && group.parent().equals(parent) && group.name().equalsIgnoreCase(name));
    }

    private static Kind kindOf(Object value) {
        if (value == null) return null;
        try {
            return Kind.valueOf(value.toString().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Makes a loaded set of groups sane: a parent that doesn't exist, isn't an organization or folder, would
     * put an organization somewhere but the top, or sits in a loop, is dropped (the group goes to the top).
     */
    private static void repairGroups(Account account) {
        for (Group group : List.copyOf(account.groups)) {
            if (group.parent().isEmpty()) continue;
            Group holder = byId(account, group.parent());
            boolean bad = holder == null || holder.kind() == Kind.HOME || group.kind() == Kind.ORG
                    || inside(account, group.parent(), group.id()) || depth(account, group.id()) > MAX_DEPTH;
            if (bad) replace(account, new Group(group.id(), group.name(), group.kind(), ""));
        }
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

    /** What the website shows a player: their devices in the order they claimed them, and their groups. */
    public synchronized View view(UUID player) {
        Account account = accounts.get(player);
        if (account == null) return new View(List.of(), List.of());
        List<Item> items = new ArrayList<>();
        for (String key : account.owned) {
            items.add(new Item(key, account.nicknames.getOrDefault(key, ""), account.homeOf.getOrDefault(key, "")));
        }
        return new View(items, List.copyOf(account.groups));
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
            List<Map<String, String>> groups = new ArrayList<>();
            List<Map<String, String>> homes = new ArrayList<>();
            for (Group group : account.groups) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", group.id());
                item.put("name", group.name());
                item.put("kind", group.kind().name().toLowerCase(java.util.Locale.ROOT));
                item.put("parent", group.parent());
                groups.add(item);
                if (group.kind() == Kind.HOME) homes.add(Map.of("id", group.id(), "name", group.name()));
            }
            if (!groups.isEmpty()) yaml.set(base + ".groups", groups);
            // Before 0.8 there were only homes: keep that list too, so going back to an older EZStats keeps them.
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
            // The file holds password hashes: only the server's user may read it. The temporary file is made
            // private before anything is written, and it replaces accounts.yml, so that stays private too.
            // (Windows has no POSIX permissions; there the folder's own access rules apply.)
            Files.deleteIfExists(temp.toPath());
            try {
                Files.createFile(temp.toPath(),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
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
