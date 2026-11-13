package dev.sbot;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

final class SBotCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOTS = List.of("create", "tp", "tpall", "delete", "list", "info", "status", "reload");
    private final SBotPlugin plugin;
    private final BotManager manager;

    SBotCommand(SBotPlugin plugin, BotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("sbot.admin")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("usage"));
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (args.length != 2) return usage(sender);
                int amount;
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(plugin.msg("invalid-number"));
                    return true;
                }
                if (amount <= 0) {
                    sender.sendMessage(plugin.msg("invalid-number"));
                    return true;
                }
                int max = Math.max(1, plugin.getConfig().getInt("max-create-per-command", 20));
                if (amount > max) {
                    sender.sendMessage(plugin.msg("create-limit", "max", String.valueOf(max)));
                    return true;
                }
                int created = manager.createMany(amount, player.getLocation());
                if (created == 0) {
                    sender.sendMessage(plugin.msg("no-names"));
                } else {
                    sender.sendMessage(plugin.msg(created < amount ? "created-partial" : "created",
                            "amount", String.valueOf(created)));
                }
            }
            case "tpall" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (args.length != 1) return usage(sender);
                int moved = manager.teleportAll(player.getLocation());
                sender.sendMessage(plugin.msg("teleported-all", "amount", String.valueOf(moved)));
            }
            case "tp" -> {
                if (!(sender instanceof Player player)) return playerOnly(sender);
                if (args.length != 2) return usage(sender);
                if (manager.teleport(args[1], player.getLocation())) {
                    sender.sendMessage(plugin.msg("teleported", "name", args[1]));
                } else sender.sendMessage(plugin.msg("not-found", "name", args[1]));
            }
            case "delete" -> {
                if (args.length != 2) return usage(sender);
                if (args[1].equalsIgnoreCase("allbots")) {
                    int count = manager.size();
                    manager.clear(true);
                    sender.sendMessage(plugin.msg("deleted-all", "amount", String.valueOf(count)));
                } else if (manager.delete(args[1])) {
                    sender.sendMessage(plugin.msg("deleted", "name", args[1]));
                } else sender.sendMessage(plugin.msg("not-found", "name", args[1]));
            }
            case "status" -> {
                if (args.length != 1) return usage(sender);
                sender.sendMessage(plugin.msg("status",
                        "bots", String.valueOf(manager.activeCount()),
                        "pending", String.valueOf(manager.pendingCount()),
                        "real", String.valueOf(manager.realOnlineCount()),
                        "total", String.valueOf(org.bukkit.Bukkit.getOnlinePlayers().size())));
            }
            case "list" -> {
                if (args.length != 1) return usage(sender);
                if (manager.size() == 0) sender.sendMessage(plugin.msg("list-empty"));
                else sender.sendMessage(plugin.msg("list-header", "amount", String.valueOf(manager.size()),
                        "names", String.join(", ", manager.names())));
            }
            case "info" -> {
                if (args.length != 2) return usage(sender);
                Bot bot = manager.find(args[1]);
                if (bot == null) sender.sendMessage(plugin.msg("not-found", "name", args[1]));
                else sender.sendMessage(plugin.msg("info",
                        "name", bot.name,
                        "world", bot.location.getWorld().getName(),
                        "x", String.valueOf(bot.location.getBlockX()),
                        "y", String.valueOf(bot.location.getBlockY()),
                        "z", String.valueOf(bot.location.getBlockZ()),
                        "skin", bot.skinLoaded ? "Premium" : "Predeterminada / cargando",
                        "state", bot.spawned() ? "Conectado" : "Cargando"));
            }
            case "reload" -> {
                if (args.length != 1) return usage(sender);
                plugin.reloadSBot();
                sender.sendMessage(plugin.msg("reloaded"));
            }
            default -> { return usage(sender); }
        }
        return true;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(plugin.msg("usage"));
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        sender.sendMessage(plugin.msg("only-player"));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("sbot.admin")) return Collections.emptyList();
        List<String> options = new ArrayList<>();
        if (args.length == 1) options.addAll(ROOTS);
        else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> options.addAll(Arrays.asList("1", "5", "10", "20"));
                case "tp", "info" -> options.addAll(manager.names());
                case "delete" -> {
                    options.add("allbots");
                    options.addAll(manager.names());
                }
            }
        }
        String start = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(start))
                .sorted().collect(Collectors.toList());
    }
}
