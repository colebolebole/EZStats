package com.ezstats;

import com.ezstats.account.AccountStore;
import com.ezstats.account.Nicknames;
import com.ezstats.account.PasswordHasher;
import com.ezstats.link.LinkManager;
import com.ezstats.snapshot.DeviceJson;
import com.ezstats.snapshot.SnapshotService;
import com.github.blade.hvac.api.EquipmentView;
import com.github.blade.hvac.api.EzvacApi;
import com.github.blade.hvac.api.HvacDevice;
import com.github.blade.hvac.api.InstrumentView;
import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.ThermostatView;
import com.github.blade.hvac.api.VentView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** {@code /ezstats register | password | own | nick | disown | link | unlink | status | reload}. */
final class EZStatsCommand implements CommandExecutor, TabCompleter {
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 128;
    private static final double REACH = 6;

    private final EZStatsPlugin plugin;
    private final EzvacApi api;
    private final LinkManager links;
    private final AccountStore accounts;

    EZStatsCommand(EZStatsPlugin plugin, EzvacApi api, LinkManager links, AccountStore accounts) {
        this.plugin = plugin;
        this.api = api;
        this.links = links;
        this.accounts = accounts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "register" -> register(sender, args, false);
            case "password" -> register(sender, args, true);
            case "own" -> own(sender, args);
            case "disown" -> disown(sender);
            case "nick", "rename" -> nick(sender, args);
            case "link" -> link(sender);
            case "unlink" -> unlink(sender);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    // --- accounts -------------------------------------------------------------------------

    /** {@code /ezstats register <password>}, or {@code /ezstats password <new>} to change it. */
    private void register(CommandSender sender, String[] args, boolean change) {
        Player player = playerOnly(sender, "The website account is your Minecraft account.");
        if (player == null) return;
        UUID id = player.getUniqueId();
        boolean has = accounts.hasPassword(id);
        if (!change && has) {
            say(player, "You already have a password. Change it with /ezstats password <new password>.", NamedTextColor.RED);
            return;
        }
        if (change && !has) {
            say(player, "You haven't registered yet. Use /ezstats register <password>.", NamedTextColor.RED);
            return;
        }
        if (args.length != 2) {
            say(player, "Use /ezstats " + args[0].toLowerCase(Locale.ROOT)
                    + " <password>, a single word with no spaces.", NamedTextColor.RED);
            return;
        }
        String password = args[1];
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            say(player, "A password is " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters.", NamedTextColor.RED);
            return;
        }

        String name = player.getName();
        say(player, "Setting your password...", NamedTextColor.GRAY);
        // PBKDF2 takes a moment: keep it off the main thread, then come back to save and answer.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = PasswordHasher.hash(password.toCharArray());
            Bukkit.getScheduler().runTask(plugin, () -> {
                accounts.setPassword(id, name, hash);
                if (change) links.revoke(id);
                if (!player.isOnline()) return;
                say(player, change ? "Password changed. Every browser was signed out."
                        : "Registered. Sign in at " + plugin.publicUrl() + " as " + name + ".", NamedTextColor.GREEN);
                say(player, "Don't use a password from anywhere else: commands you type can show up in the "
                        + "server's console and logs.", NamedTextColor.GRAY);
                if (!change) say(player, "Now look at a device and run /ezstats own to put it on your page.",
                        NamedTextColor.GRAY);
            });
        });
    }

    /**
     * {@code /ezstats own}: claims the device the player is looking at. {@code /ezstats own system}
     * claims every device of its system too: the thermostat, its equipment, vents and panels.
     */
    private void own(CommandSender sender, String[] args) {
        Player player = playerOnly(sender, "Only a player can own a device.");
        if (player == null) return;
        boolean wholeSystem = args.length > 1 && args[1].equalsIgnoreCase("system");
        if (args.length > 1 && !wholeSystem) {
            say(player, "Use /ezstats own, or /ezstats own system to claim its whole system.", NamedTextColor.RED);
            return;
        }
        Optional<HvacDevice> found = lookedAt(player);
        if (found.isEmpty()) {
            say(player, "Look at a device's sign, button or dispenser within " + (int) REACH
                    + " blocks, then run /ezstats own.", NamedTextColor.RED);
            return;
        }
        HvacDevice device = found.get();
        accounts.touch(player.getUniqueId(), player.getName());
        if (wholeSystem) {
            ownSystem(player, device);
            return;
        }
        AccountStore.OwnResult result = accounts.own(player.getUniqueId(), player.getName(),
                SnapshotService.key(device.position()));
        String name = DeviceJson.describe(device);
        switch (result.status()) {
            case CLAIMED -> {
                say(player, "The " + name + " is now yours. It shows on your EZStats page.", NamedTextColor.GREEN);
                if (!accounts.hasPassword(player.getUniqueId())) {
                    say(player, "You haven't set a password: use /ezstats register <password> to sign in "
                            + "with one, or /ezstats link for a one-time link.", NamedTextColor.GRAY);
                }
            }
            case ALREADY_YOURS -> say(player, "The " + name + " is already yours.", NamedTextColor.YELLOW);
            case TAKEN -> say(player, "The " + name + " belongs to " + result.owner()
                    + ". They (or an admin) can release it with /ezstats disown.", NamedTextColor.RED);
        }
    }

    /** Claims every device that belongs to the same system as {@code device}, in its world. */
    private void ownSystem(Player player, HvacDevice device) {
        Optional<String> system = systemOf(device);
        if (system.isEmpty()) {
            say(player, "That device isn't part of a system. Use /ezstats own for just this one.", NamedTextColor.RED);
            return;
        }
        World world = Bukkit.getWorld(device.position().world());
        if (world == null) return;
        int claimed = 0;
        int already = 0;
        int others = 0;
        for (HvacDevice member : api.devices(world)) {
            if (!systemOf(member).equals(system)) continue;
            AccountStore.OwnResult result = accounts.own(player.getUniqueId(), player.getName(),
                    SnapshotService.key(member.position()));
            switch (result.status()) {
                case CLAIMED -> claimed++;
                case ALREADY_YOURS -> already++;
                case TAKEN -> others++;
            }
        }
        say(player, "Claimed " + claimed + " device" + (claimed == 1 ? "" : "s") + " of this system"
                + (already > 0 ? ", " + already + " already yours" : "")
                + (others > 0 ? ", " + others + " belong to someone else" : "") + ".",
                claimed > 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
    }

    /** The system a device belongs to, if it is in one: thermostats, equipment, vents, panels and monitors are. */
    private static Optional<String> systemOf(HvacDevice device) {
        if (device instanceof ThermostatView view) return Optional.of(view.system());
        if (device instanceof EquipmentView view) return Optional.of(view.system());
        if (device instanceof VentView view) return Optional.of(view.system());
        if (device instanceof InstrumentView view) return view.system();
        return Optional.empty();
    }

    /** {@code /ezstats disown}: lets go of the device the player is looking at. Admins can free any. */
    private void disown(CommandSender sender) {
        Player player = playerOnly(sender, "Only a player can look at a device.");
        if (player == null) return;
        Optional<HvacDevice> found = lookedAt(player);
        if (found.isEmpty()) {
            say(player, "Look at a device within " + (int) REACH + " blocks, then run /ezstats disown.",
                    NamedTextColor.RED);
            return;
        }
        String key = SnapshotService.key(found.get().position());
        String name = DeviceJson.describe(found.get());
        Optional<UUID> owner = accounts.ownerOf(key);
        if (owner.isEmpty()) {
            say(player, "Nobody owns the " + name + ".", NamedTextColor.YELLOW);
        } else if (!owner.get().equals(player.getUniqueId()) && !player.hasPermission("ezstats.admin")) {
            say(player, "The " + name + " belongs to " + accounts.name(owner.get()) + ".", NamedTextColor.RED);
        } else {
            accounts.release(key);
            say(player, "The " + name + " is no longer owned by anyone.", NamedTextColor.GREEN);
        }
    }

    /**
     * {@code /ezstats nick <nickname>}: names the device the player is looking at on their EZStats
     * page. With no nickname it goes back to its own label. The device itself isn't changed:
     * EZvac's API can't, and {@code /hvac rename} does that for signs.
     */
    private void nick(CommandSender sender, String[] args) {
        Player player = playerOnly(sender, "Only a player can look at a device.");
        if (player == null) return;
        Optional<HvacDevice> found = lookedAt(player);
        if (found.isEmpty()) {
            say(player, "Look at a device within " + (int) REACH + " blocks, then run /ezstats "
                    + args[0].toLowerCase(Locale.ROOT) + " <nickname>.", NamedTextColor.RED);
            return;
        }
        HvacDevice device = found.get();
        String key = SnapshotService.key(device.position());
        String name = DeviceJson.describe(device);
        if (!accounts.ownerOf(key).equals(Optional.of(player.getUniqueId()))) {
            say(player, accounts.ownerOf(key).isPresent()
                    ? "The " + name + " belongs to someone else."
                    : "Claim the " + name + " first with /ezstats own.", NamedTextColor.RED);
            return;
        }
        String nickname = Nicknames.clean(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
        if (nickname == null) {
            say(player, "A nickname is at most " + Nicknames.MAX_LENGTH + " characters, with no control characters.",
                    NamedTextColor.RED);
            return;
        }
        accounts.setNickname(player.getUniqueId(), key, nickname);
        say(player, nickname.isEmpty()
                ? "The " + name + " is shown by its own name again."
                : "The " + name + " is now called '" + nickname + "' on your EZStats page.",
                NamedTextColor.GREEN);
    }

    /** The device the player is looking at: the one whose sign, button or dispenser is that block. */
    private Optional<HvacDevice> lookedAt(Player player) {
        Block block = player.getTargetBlockExact((int) REACH);
        if (block == null) return Optional.empty();
        Position position = Position.of(block.getLocation());
        for (HvacDevice device : api.devices(block.getWorld())) {
            if (device.position().equals(position)) return Optional.of(device);
        }
        return Optional.empty();
    }

    // --- sign-in ---------------------------------------------------------------------------

    private void link(CommandSender sender) {
        Player player = playerOnly(sender, "Only a player can link: the website signs in as your Minecraft account.");
        if (player == null) return;
        if (!plugin.webRunning()) {
            say(player, "The website isn't running. Ask an admin to check the console.", NamedTextColor.RED);
            return;
        }
        accounts.touch(player.getUniqueId(), player.getName());
        String token = links.createToken(player.getUniqueId(), player.getName());
        String url = plugin.publicUrl() + "/link?token=" + token;

        player.sendMessage(Component.text("Open this link to sign in to EZStats: ", NamedTextColor.GREEN)
                .append(Component.text("[click to open]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Opens the EZStats website")))));
        say(player, "It works once and expires, so don't share it. /ezstats unlink signs every browser out.",
                NamedTextColor.GRAY);
        if (!plugin.control().writable()) {
            say(player, "The website is read-only. " + plugin.control().readOnlyReason(), NamedTextColor.GRAY);
        }
    }

    private void unlink(CommandSender sender) {
        Player player = playerOnly(sender, "Only a player has a link to remove.");
        if (player == null) return;
        int ended = links.revoke(player.getUniqueId());
        say(player, ended == 0
                ? "You had no browsers linked."
                : "Signed out " + ended + (ended == 1 ? " browser." : " browsers."), NamedTextColor.GREEN);
    }

    // --- server ----------------------------------------------------------------------------

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("EZStats", NamedTextColor.GOLD)
                .append(Component.text(plugin.webRunning() ? " website is up at " : " website is DOWN; its address would be ",
                        NamedTextColor.GRAY))
                .append(Component.text(plugin.publicUrl(), NamedTextColor.WHITE)));
        say(sender, plugin.control().writable()
                ? "Changes from the website: on (owners can set the target, power and profile)."
                : "Changes from the website: off. " + plugin.control().readOnlyReason(),
                NamedTextColor.GRAY);
        if (sender instanceof Player player) {
            say(sender, "You " + (accounts.hasPassword(player.getUniqueId()) ? "have" : "have not set")
                    + " a password, own " + accounts.ownedCount(player.getUniqueId())
                    + " device(s), and have " + links.sessionCount(player.getUniqueId())
                    + " browser(s) signed in.", NamedTextColor.GRAY);
        }
        if (sender.hasPermission("ezstats.admin")) {
            say(sender, "Signed-in browsers: " + links.sessionCount()
                    + ", links waiting to be opened: " + links.pendingCount(), NamedTextColor.GRAY);
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("ezstats.admin")) {
            say(sender, "You can't do that.", NamedTextColor.RED);
            return;
        }
        plugin.reload();
        say(sender, plugin.webRunning()
                ? "EZStats reloaded: the website is up at " + plugin.publicUrl()
                : "EZStats reloaded, but the website did not start. See the console.",
                plugin.webRunning() ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Component.text("EZStats", NamedTextColor.GOLD));
        line(sender, label, "register <password>", "make a website account and set its password");
        line(sender, label, "own [system]", "claim the device you're looking at (or its whole system)");
        line(sender, label, "nick [nickname]", "name the device you're looking at (none clears it)");
        line(sender, label, "disown", "let go of the device you're looking at");
        line(sender, label, "password <new>", "change your password");
        line(sender, label, "link", "get a one-time link that signs you in, no password");
        line(sender, label, "unlink", "sign every browser of yours out");
        line(sender, label, "status", "the website's address and who is signed in");
        if (sender.hasPermission("ezstats.admin")) {
            line(sender, label, "reload", "read config.yml again and restart the website");
        }
    }

    private static void line(CommandSender sender, String label, String usage, String about) {
        sender.sendMessage(Component.text("/" + label + " " + usage, NamedTextColor.WHITE)
                .append(Component.text(" - " + about, NamedTextColor.GRAY)));
    }

    private static void say(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message, color));
    }

    private static Player playerOnly(CommandSender sender, String why) {
        if (sender instanceof Player player) return player;
        say(sender, "Players only. " + why, NamedTextColor.RED);
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("register", "own", "nick", "rename", "disown", "password", "link", "unlink", "status"));
        if (sender.hasPermission("ezstats.admin")) options.add("reload");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
