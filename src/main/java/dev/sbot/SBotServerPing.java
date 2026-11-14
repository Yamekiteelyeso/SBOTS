package dev.sbot;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The server-list status response is NOT produced from PlayerList the same way
 * as /list. Correct the backend ping count and profile sample explicitly.
 *
 * Network ping events may run outside the main thread. Everything from Bukkit
 * or NMS is captured into an immutable snapshot on the main thread.
 */
final class SBotServerPing implements Listener {
    private record Snapshot(int online, List<PlayerProfile> bots, Set<UUID> botIds) { }

    private final SBotPlugin plugin;
    private final BotManager manager;
    private volatile Snapshot snapshot = new Snapshot(0, List.of(), Set.of());

    SBotServerPing(SBotPlugin plugin, BotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Main server thread only. */
    void update() {
        List<PlayerProfile> profiles = new ArrayList<>();
        Set<UUID> identities = new HashSet<>();
        for (Bot bot : manager.all()) {
            if (!NmsPlayerService.isRegistered(bot)) continue;
            profiles.add(Bukkit.createProfileExact(bot.profile.getId(), bot.name));
            identities.add(bot.profile.getId());
        }
        int total = Bukkit.getOnlinePlayers().size();
        snapshot = new Snapshot(total, List.copyOf(profiles), Set.copyOf(identities));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(PaperServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("server-list-enabled", true)) return;
        Snapshot current = snapshot;
        // Count registered bots + real online players, not only real TCP clients.
        event.setNumPlayers(current.online());

        if (!plugin.getConfig().getBoolean("server-list-show-names", true)) return;
        // The client will otherwise show 'Anonymous Player' for profiles not
        // opted in to server listings. Never expose names behind that privacy
        // setting; remove the anonymous sample entries instead.
        List<PlayerProfile> list = event.getPlayerSample();
        list.removeIf(p -> p == null || p.getName() == null
                || p.getName().equalsIgnoreCase("Anonymous Player")
                || current.botIds().contains(p.getId()));
        int maxSamples = Math.max(1, Math.min(50,
                plugin.getConfig().getInt("server-list-max-sample", 12)));
        int appended = 0;
        for (PlayerProfile bot : current.bots()) {
            if (appended >= maxSamples) break;
            list.add(bot);
            appended++;
        }
        if (list.size() > maxSamples) list.subList(maxSamples, list.size()).clear();
    }
}
