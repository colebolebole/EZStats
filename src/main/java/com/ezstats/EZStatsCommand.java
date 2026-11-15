package com.ezstats;

import com.ezstats.account.AccountStore;
import com.ezstats.account.PasswordHasher;
import com.ezstats.link.LinkManager;
import com.ezstats.snapshot.SnapshotService;
import com.github.blade.hvac.api.EzvacApi;
import com.github.blade.hvac.api.Position;
import com.github.blade.hvac.api.ThermostatView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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

/** {@code /ezstats register | password | own | disown | link | unlink | status | reload}. */
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
            case "own" -> own(sender);
            case "disown" -> disown(sender);
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
                if (!change) say(player, "Now look at a thermostat and run /ezstats own to put it on your page.",
                        NamedTextColor.GRAY);
            });
        });
    }

    /** {@code /ezstats own}: claims the thermostat the player is looking at. */
    private void own(CommandSender sender) {
        Player player = playerOnly(sender, "Only a player can own a thermostat.");
        if (player == null) return;
        Optional<ThermostatView> thermostat = lookedAt(player);
        if (thermostat.isEmpty()) {
            say(player, "Look at a thermostat's sign within " + (int) REACH + " blocks, then run /ezstats own.",
                    NamedTextColor.RED);
            return;
        }
        ThermostatView view = thermostat.get();
        accounts.touch(player.getUniqueId(), player.getName());
        AccountStore.OwnResult result = accounts.own(player.getUniqueId(), player.getName(),
                SnapshotService.key(view.position()));
        switch (result.status()) {
            case CLAIMED -> {
                say(player, "'" + view.id() + "' is now yours. It shows on your EZStats page.", NamedTextColor.GREEN);
                if (!accounts.hasPassword(player.getUniqueId())) {
                    say(player, "You haven't set a password: use /ezstats register <password> to sign in "
                            + "with one, or /ezstats link for a one-time link.", NamedTextColor.GRAY);
                }
            }
            case ALREADY_YOURS -> say(player, "'" + view.id() + "' is already yours.", NamedTextColor.YELLOW);
            case TAKEN -> say(player, "'" + view.id() + "' belongs to " + result.owner()
                    + ". They (or an admin) can release it with /ezstats disown.", NamedTextColor.RED);
        }
    }

    /** {@code /ezstats disown}: lets go of the thermostat the player is looking at. Admins can free any. */
    private void disown(CommandSender sender) {
        Player player = playerOnly(sender, "Only a player can look at a thermostat.");
        if (player == null) return;
        Optional<ThermostatView> thermostat = lookedAt(player);
        if (thermostat.isEmpty()) {
            say(player, "Look at a thermostat's sign within " + (int) REACH + " blocks, then run /ezstats disown.",
                    NamedTextColor.RED);
            return;
        }
        String key = SnapshotService.key(thermostat.get().position());
        Optional<UUID> owner = accounts.ownerOf(key);
        if (owner.isEmpty()) {
            say(player, "Nobody owns '" + thermostat.get().id() + "'.", NamedTextColor.YELLOW);
        } else if (!owner.get().equals(player.getUniqueId()) && !player.hasPermission("ezstats.admin")) {
            say(player, "'" + thermostat.get().id() + "' belongs to " + accounts.name(owner.get()) + ".",
                    NamedTextColor.RED);
        } else {
            accounts.release(key);
            say(player, "'" + thermostat.get().id() + "' is no longer owned by anyone.", NamedTextColor.GREEN);
        }
    }

    /** The thermostat whose sign the player is looking at, found the way EZvac's own commands find a device. */
    private Optional<ThermostatView> lookedAt(Player player) {
        Block block = player.getTargetBlockExact((int) REACH);
        if (block == null) return Optional.empty();
        return api.thermostatAt(Position.of(block.getLocation()));
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
            say(player, "The website is read-only for now: EZvac's API can't change thermostats yet.",
                    NamedTextColor.GRAY);
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
                ? "Changes from the website: on." : "Changes from the website: off (EZvac's API is read-only).",
                NamedTextColor.GRAY);
        if (sender instanceof Player player) {
            say(sender, "You " + (accounts.hasPassword(player.getUniqueId()) ? "have" : "have not set")
                    + " a password, own " + accounts.owned(player.getUniqueId()).size()
                    + " thermostat(s), and have " + links.sessionCount(player.getUniqueId())
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
        line(sender, label, "own", "claim the thermostat you're looking at; it shows on your page");
        line(sender, label, "disown", "let go of the thermostat you're looking at");
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
        List<String> options = new ArrayList<>(List.of("register", "own", "disown", "password", "link", "unlink", "status"));
        if (sender.hasPermission("ezstats.admin")) options.add("reload");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
