package dev.sbot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** SBot: native server-list simulated players for Paper 1.20.1. */
public final class SBotPlugin extends JavaPlugin implements Listener {
    private final Map<String, String> messages = new HashMap<>();
    private BotManager botManager;
    private SkinService skinService;
    private BotVisibility visibility;
    private SBotServerPing serverPing;

    @Override
    public void onEnable() {
        if (!Bukkit.getBukkitVersion().startsWith("1.20.1-")) {
            getLogger().severe("SBot requiere exactamente Paper 1.20.1: " + Bukkit.getBukkitVersion());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        // Existing 2.0.0 installations had keep-bot-chunks-loaded: false.
        // Migrate once so an upgrade fixes unloaded chunks without manual edits.
        if (!getConfig().contains("auto-reconnect-missing-bots", true)) {
            getConfig().set("keep-bot-chunks-loaded", true);
            getConfig().set("prevent-automatic-kicks", true);
            getConfig().set("auto-reconnect-missing-bots", true);
            saveConfig();
        }
        loadMessages();
        skinService = new SkinService(this);
        botManager = new BotManager(this, skinService);
        visibility = new BotVisibility(this, botManager);
        botManager.setVisibility(visibility);
        serverPing = new SBotServerPing(this, botManager);

        SBotCommand command = new SBotCommand(this, botManager);
        PluginCommand registered = getCommand("sbot");
        if (registered == null) throw new IllegalStateException("Falta /sbot en plugin.yml");
        registered.setExecutor(command);
        registered.setTabCompleter(command);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(serverPing, this);
        Bukkit.getScheduler().runTaskTimer(this, serverPing::update, 1L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, visibility::sync, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (getConfig().getBoolean("auto-reconnect-missing-bots", true)) botManager.maintain();
        }, 40L, 40L);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new SBotExpansion(botManager).register();
            } catch (Throwable ex) {
                getLogger().warning("No se pudo registrar PlaceholderAPI: " + ex.getMessage());
            }
        }
        if (getConfig().getBoolean("restore-on-startup", true)) {
            // Worlds and other plugin managers are ready after the initial enable phase.
            Bukkit.getScheduler().runTaskLater(this, botManager::restore, 20L);
        }
        getLogger().info("SBot iniciado con jugadores registrados por NMS (sin TAB falso independiente).");
    }

    @Override
    public void onDisable() {
        if (botManager != null) {
            botManager.save();
            botManager.clear(false);
        }
        if (skinService != null) skinService.close();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (botManager == null || botManager.isBot(event.getPlayer())) return;
        // A genuine player always takes precedence when using the same username.
        Bot fake = botManager.find(event.getPlayer().getName());
        if (fake != null) botManager.delete(fake.name);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onViewerJoin(PlayerJoinEvent event) {
        if (visibility != null) visibility.viewerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotQuit(PlayerQuitEvent event) {
        if (visibility != null) visibility.viewerQuit(event.getPlayer());
        if (botManager != null && botManager.isBot(event.getPlayer())) {
            getLogger().warning("Jugador simulado desconectado: " + event.getPlayer().getName()
                    + ". SBot intentara restaurarlo.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBotKick(PlayerKickEvent event) {
        if (botManager == null || !botManager.isBot(event.getPlayer())) return;
        String reason = ChatColor.stripColor(event.getReason()).toLowerCase(java.util.Locale.ROOT);
        // Cancel only automatic disconnects related to absent fake clients.
        // Do not suppress kicks imposed by other plugins or staff.
        if (getConfig().getBoolean("prevent-automatic-kicks", true)
                && (reason.contains("timed out") || reason.contains("timeout")
                    || reason.contains("idling") || reason.contains("idle")
                    || reason.contains("flying is not enabled"))) {
            event.setCancelled(true);
            getLogger().warning("Se evito la expulsion automatica de SBot "
                    + event.getPlayer().getName() + ": " + reason);
        } else {
            getLogger().warning("SBot " + event.getPlayer().getName()
                    + " fue expulsado: " + reason);
        }
    }

    void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        messages.clear();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) messages.put(key, yaml.getString(key, ""));
        }
    }

    String msg(String key, String... pairs) {
        String raw = messages.getOrDefault("prefix", "&8[&bSBot&8] &7")
                + messages.getOrDefault(key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    void reloadSBot() {
        reloadConfig();
        loadMessages();
        if (serverPing != null) serverPing.update();
    }
}
