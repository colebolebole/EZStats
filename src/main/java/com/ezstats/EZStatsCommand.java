package com.ezstats;

import com.ezstats.link.LinkManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /ezstats link | unlink | status | reload}. */
final class EZStatsCommand implements CommandExecutor, TabCompleter {
    private final EZStatsPlugin plugin;
    private final LinkManager links;

    EZStatsCommand(EZStatsPlugin plugin, LinkManager links) {
        this.plugin = plugin;
        this.links = links;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link" -> link(sender);
            case "unlink" -> unlink(sender);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private void link(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can link: the website signs in as your Minecraft account.",
                    NamedTextColor.RED));
            return;
        }
        if (!plugin.webRunning()) {
            player.sendMessage(Component.text("The website isn't running. Ask an admin to check the console.",
                    NamedTextColor.RED));
            return;
        }
        String token = links.createToken(player.getUniqueId(), player.getName());
        String url = plugin.publicUrl() + "/link?token=" + token;

        player.sendMessage(Component.text("Open this link to sign in to EZStats: ", NamedTextColor.GREEN)
                .append(Component.text("[click to open]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Opens the EZStats website")))));
        player.sendMessage(Component.text("It works once and expires, so don't share it. ", NamedTextColor.GRAY)
                .append(Component.text("/ezstats unlink", NamedTextColor.WHITE))
                .append(Component.text(" signs every browser out.", NamedTextColor.GRAY)));
        if (!plugin.control().writable()) {
            player.sendMessage(Component.text("The website is read-only for now: EZvac's API can't change "
                    + "thermostats yet.", NamedTextColor.GRAY));
        }
    }

    private void unlink(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player has a link to remove.", NamedTextColor.RED));
            return;
        }
        int ended = links.revoke(player.getUniqueId());
        player.sendMessage(Component.text(ended == 0
                ? "You had no browsers linked."
                : "Signed out " + ended + (ended == 1 ? " browser." : " browsers."), NamedTextColor.GREEN));
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("EZStats", NamedTextColor.GOLD)
                .append(Component.text(plugin.webRunning() ? " website is up at " : " website is DOWN; its address would be ",
                        NamedTextColor.GRAY))
                .append(Component.text(plugin.publicUrl(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text(plugin.control().writable()
                ? "Changes from the website: on." : "Changes from the website: off (EZvac's API is read-only).",
                NamedTextColor.GRAY));
        if (sender instanceof Player player) {
            int mine = links.sessionCount(player.getUniqueId());
            sender.sendMessage(Component.text("Your linked browsers: " + mine, NamedTextColor.GRAY));
        }
        if (sender.hasPermission("ezstats.admin")) {
            sender.sendMessage(Component.text("Linked browsers: " + links.sessionCount()
                    + ", links waiting to be opened: " + links.pendingCount(), NamedTextColor.GRAY));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("ezstats.admin")) {
            sender.sendMessage(Component.text("You can't do that.", NamedTextColor.RED));
            return;
        }
        plugin.reload();
        sender.sendMessage(Component.text(plugin.webRunning()
                ? "EZStats reloaded: the website is up at " + plugin.publicUrl()
                : "EZStats reloaded, but the website did not start. See the console.",
                plugin.webRunning() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Component.text("EZStats", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/" + label + " link", NamedTextColor.WHITE)
                .append(Component.text(" - get a link that signs you in to the website", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " unlink", NamedTextColor.WHITE)
                .append(Component.text(" - sign every browser of yours out", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " status", NamedTextColor.WHITE)
                .append(Component.text(" - the website's address and who is linked", NamedTextColor.GRAY)));
        if (sender.hasPermission("ezstats.admin")) {
            sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.WHITE)
                    .append(Component.text(" - read config.yml again and restart the website", NamedTextColor.GRAY)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("link", "unlink", "status"));
        if (sender.hasPermission("ezstats.admin")) options.add("reload");
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
