package dev.sbot;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

final class SBotExpansion extends PlaceholderExpansion {
    private final BotManager manager;

    SBotExpansion(BotManager manager) { this.manager = manager; }
    @Override public @NotNull String getIdentifier() { return "sbot"; }
    @Override public @NotNull String getAuthor() { return "SBot"; }
    @Override public @NotNull String getVersion() { return "2.2.0"; }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "bots", "count" -> String.valueOf(manager.activeCount());
            case "pending" -> String.valueOf(manager.pendingCount());
            case "real" -> String.valueOf(manager.realOnlineCount());
            case "total" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            case "names" -> String.join(", ", manager.names());
            default -> null;
        };
    }
}
