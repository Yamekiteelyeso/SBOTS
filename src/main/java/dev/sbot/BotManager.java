package dev.sbot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** SBot roster and persistent real server-side simulated player lifecycle. */
final class BotManager {
    private record ChunkKey(World world, int x, int z) { }

    private final SBotPlugin plugin;
    private final SkinService skins;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final Map<ChunkKey, Integer> tickets = new HashMap<>();
    private final File file;
    private final Random random = new Random();

    BotManager(SBotPlugin plugin, SkinService skins) {
        this.plugin = plugin;
        this.skins = skins;
        this.file = new File(plugin.getDataFolder(), "bots.yml");
    }

    private String key(String name) { return name.toLowerCase(Locale.ROOT); }
    int size() { return bots.size(); }
    int activeCount() { return (int) bots.values().stream().filter(NmsPlayerService::isRegistered).count(); }
    int pendingCount() { return size() - activeCount(); }
    int realOnlineCount() { return Math.max(0, Bukkit.getOnlinePlayers().size() - activeCount()); }

    Collection<String> names() {
        List<String> result = new ArrayList<>();
        for (Bot bot : bots.values()) result.add(bot.name);
        return result;
    }

    Bot find(String name) { return name == null ? null : bots.get(key(name)); }

    boolean isBot(Player player) {
        Bot bot = find(player.getName());
        return bot != null && bot.profile.getId().equals(player.getUniqueId());
    }

    int createMany(int requested, Location location) {
        if (location.getWorld() == null || requested <= 0) return 0;
        int minFree = Math.max(0, plugin.getConfig().getInt("minimum-free-slots", 2));
        int remainingSlots = Math.max(0, Bukkit.getMaxPlayers() - Bukkit.getOnlinePlayers().size()
                - pendingCount() - minFree);
        int maximum = Math.max(0, plugin.getConfig().getInt("max-bots", 40) - size());
        int target = Math.min(requested, Math.min(maximum, remainingSlots));

        int created = 0;
        for (int index = 0; index < target; index++) {
            String candidate = nextUniqueName();
            if (candidate == null) break;
            Location at = spread(location, index, target);
            if (add(candidate, at, randomSkinSource())) created++;
        }
        if (created > 0) save();
        return created;
    }

    private String nextUniqueName() {
        String prefix = plugin.getConfig().getString("name-prefix", "");
        for (int tries = 0; tries < 500; tries++) {
            String name = NameGenerator.generate(random, prefix);
            if (validName(name) && find(name) == null && !nameTaken(name)) return name;
        }
        return null;
    }

    private String randomSkinSource() {
        List<String> sources = plugin.getConfig().getStringList("skin-source-pool");
        if (sources.isEmpty()) return "";
        return sources.get(random.nextInt(sources.size()));
    }

    private boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    private boolean nameTaken(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private boolean add(String name, Location location, String skinSource) {
        if (!validName(name) || location.getWorld() == null || find(name) != null || nameTaken(name)) return false;
        UUID uuid = UUID.nameUUIDFromBytes(("SBot:server-player:" + key(name)).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        Bot bot = new Bot(name, profile, location, skinSource);

        // Cached textures are applied before the instant join, without HTTPS latency.
        SkinService.Skin cached = skinSource.isEmpty() ? null : skins.cached(skinSource);
        if (cached != null) applySkin(bot, cached);

        bots.put(key(name), bot);
        try {
            // Paper does not always keep fake-player chunks ticking by itself.
            addTicket(bot.location);
            NmsPlayerService.spawn(bot);
            if (!NmsPlayerService.isRegistered(bot))
                throw new IllegalStateException("El jugador no se registro en PlayerList");
        } catch (Throwable error) {
            plugin.getLogger().warning("Error al conectar inmediatamente a " + name + ": " + error);
            try { NmsPlayerService.remove(bot); } catch (Throwable ignored) { }
            removeTicket(bot.location);
            bots.remove(key(name));
            return false;
        }

        // A remote skin is optional: the player appears immediately with default
        // skin and is refreshed only after the background HTTP request completes.
        if (!skinSource.isEmpty()) skins.load(skinSource, skin -> {
            if (!plugin.isEnabled() || bots.get(key(name)) != bot || skin == null) return;
            boolean changed = applySkin(bot, skin);
            if (changed) {
                try {
                    NmsPlayerService.refreshSkin(bot);
                } catch (Throwable error) {
                    plugin.getLogger().warning("No se pudo actualizar la skin de " + name + ": " + error);
                }
            }
        });
        // The next tick is a second line of defense against plugins that alter
        // spawn location while the PlayerJoinEvent is being handled.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bots.get(key(bot.name)) != bot) return;
            if (!NmsPlayerService.enforcePosition(bot)) {
                plugin.getLogger().warning("SBot " + bot.name + ": ubicacion no confirmada, se reintentara.");
            }
        }, 2L);
        return true;
    }

    private boolean applySkin(Bot bot, SkinService.Skin skin) {
        var entries = bot.profile.getProperties().get("textures");
        boolean unchanged = entries.stream().anyMatch(property ->
                property.getValue().equals(skin.value())
                && skin.signature().equals(property.getSignature()));
        if (unchanged) {
            bot.skinLoaded = true;
            return false;
        }
        bot.profile.getProperties().removeAll("textures");
        bot.profile.getProperties().put("textures",
                new Property("textures", skin.value(), skin.signature()));
        bot.skinLoaded = true;
        return true;
    }

    private final Map<String, Long> nextRecovery = new HashMap<>();
    private final Map<String, Integer> recoveryFailures = new HashMap<>();

    /** Runs only on the main thread. Keeps idle bots alive and repairs lost PlayerList entries. */
    void maintain() {
        long now = System.currentTimeMillis();
        for (Bot bot : new ArrayList<>(bots.values())) {
            if (NmsPlayerService.isRegistered(bot)) {
                try {
                    NmsPlayerService.tick(bot);
                } catch (Throwable error) {
                    plugin.getLogger().warning("SBot heartbeat " + bot.name + ": " + error);
                }
                recoveryFailures.remove(key(bot.name));
                if (!NmsPlayerService.enforcePosition(bot)) {
                    plugin.getLogger().warning("SBot " + bot.name + " descolocado: pos se reintentara.");
                }
                continue;
            }
            String id = key(bot.name);
            if (now < nextRecovery.getOrDefault(id, 0L)) continue;
            int minimumFree = Math.max(0, plugin.getConfig().getInt("minimum-free-slots", 2));
            if (Bukkit.getOnlinePlayers().size() >= Bukkit.getMaxPlayers() - minimumFree) {
                nextRecovery.put(id, now + 10000);
                continue;
            }
            try {
                plugin.getLogger().warning("SBot " + bot.name + " salio de PlayerList; restaurando...");
                NmsPlayerService.remove(bot);
                NmsPlayerService.spawn(bot);
                if (!NmsPlayerService.isRegistered(bot))
                    throw new IllegalStateException("No se pudo registrar en PlayerList");
                recoveryFailures.remove(id);
                nextRecovery.remove(id);
                plugin.getLogger().info("SBot " + bot.name + " restaurado.");
            } catch (Throwable error) {
                int attempts = recoveryFailures.merge(id, 1, Integer::sum);
                nextRecovery.put(id, now + (attempts <= 3 ? 5000 : 30000));
                plugin.getLogger().warning("Fallo recuperando SBot " + bot.name
                        + " (intento " + attempts + "): " + error);
            }
        }
    }

    boolean delete(String name) {
        Bot bot = find(name);
        if (bot == null) return false;
        bots.remove(key(name));
        nextRecovery.remove(key(name));
        recoveryFailures.remove(key(name));
        try {
            removeTicket(bot.location);
            NmsPlayerService.remove(bot);
        } catch (Throwable error) {
            plugin.getLogger().severe("No se pudo desregistrar SBot " + name + ": " + error);
        }
        save();
        return true;
    }

    void clear(boolean persist) {
        for (String name : new ArrayList<>(bots.keySet())) {
            Bot bot = bots.remove(name);
            if (bot == null) continue;
            try {
                removeTicket(bot.location);
                NmsPlayerService.remove(bot);
            } catch (Throwable error) {
                plugin.getLogger().severe("No se pudo desregistrar SBot " + bot.name + ": " + error);
            }
        }
        tickets.clear();
        nextRecovery.clear();
        recoveryFailures.clear();
        if (persist) save();
    }

    boolean teleport(String name, Location destination) {
        Bot bot = find(name);
        if (bot == null || destination.getWorld() == null) return false;
        Location previous = bot.location.clone();
        if (!NmsPlayerService.teleport(bot, destination)) return false;
        if (NmsPlayerService.isRegistered(bot)) {
            removeTicket(previous);
            addTicket(bot.location);
        }
        save();
        return true;
    }

    int teleportAll(Location destination) {
        int moved = 0;
        int index = 0;
        for (Bot bot : bots.values()) {
            if (teleport(bot.name, spread(destination, index++, bots.size()))) moved++;
        }
        return moved;
    }

    private Location spread(Location center, int index, int total) {
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, total))));
        int rows = (int) Math.ceil(total / (double) columns);
        double spacing = Math.max(1.0, Math.min(5.0, plugin.getConfig().getDouble("spawn-spacing-blocks", 2.0)));
        int col = index % columns;
        int row = index / columns;
        double dx = (col - (columns - 1) / 2.0) * spacing;
        double dz = (row - (rows - 1) / 2.0) * spacing;
        Location candidate = center.clone().add(dx, 0, dz);
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            Location option = candidate.clone().add(0, dy, 0);
            Block feet = option.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            Block ground = feet.getRelative(0, -1, 0);
            if (feet.isPassable() && head.isPassable() && ground.getType().isSolid()) {
                option.setY(feet.getY());
                return option;
            }
        }
        return center.clone();
    }

    private ChunkKey chunkKey(Location loc) {
        return new ChunkKey(loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private void addTicket(Location loc) {
        if (!plugin.getConfig().getBoolean("keep-bot-chunks-loaded", true)) return;
        ChunkKey key = chunkKey(loc);
        if (tickets.merge(key, 1, Integer::sum) == 1) {
            key.world().addPluginChunkTicket(key.x(), key.z(), plugin);
        }
    }

    private void removeTicket(Location loc) {
        ChunkKey key = chunkKey(loc);
        Integer count = tickets.get(key);
        if (count == null) return;
        if (count <= 1) {
            tickets.remove(key);
            key.world().removePluginChunkTicket(key.x(), key.z(), plugin);
        } else tickets.put(key, count - 1);
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Bot bot : bots.values()) {
            String root = "bots." + bot.name + ".";
            Location loc = bot.location;
            if (loc.getWorld() == null) continue;
            data.set(root + "world", loc.getWorld().getName());
            data.set(root + "skinSource", bot.skinSource);
            data.set(root + "x", loc.getX());
            data.set(root + "y", loc.getY());
            data.set(root + "z", loc.getZ());
            data.set(root + "yaw", loc.getYaw());
            data.set(root + "pitch", loc.getPitch());
        }
        try {
            data.save(file);
        } catch (IOException error) {
            plugin.getLogger().severe("No se pudieron guardar bots.yml: " + error.getMessage());
        }
    }

    /** Rename only identities created from the removed 2.0.0 celebrity roster. */
    private boolean isLegacyCelebrityName(String name) {
        if (plugin.getConfig().getStringList("premium-name-pool").stream()
                .anyMatch(old -> old.equalsIgnoreCase(name))) return true;
        return LEGACY_NAMES.stream().anyMatch(old -> old.equalsIgnoreCase(name));
    }

    private static final java.util.Set<String> LEGACY_NAMES = java.util.Set.of(
            "Notch", "jeb_", "Dinnerbone", "Grumm", "Dream", "Sapnap", "GeorgeNotFound",
            "Technoblade", "DanTDM", "Grian", "MumboJumbo", "EthosLab", "Iskall85",
            "ImpulseSV", "BdoubleO100", "Xisuma", "Purpled", "Fruitberries", "TapL",
            "Tubbo", "Philza", "Skeppy", "Punz", "Antfrost", "Ranboo", "Eret", "Fundy",
            "CaptainSparklez", "SmallishBeans"
    );

    void restore() {
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = data.getConfigurationSection("bots");
        if (section == null) return;
        int count = 0;
        int migrated = 0;
        int max = Math.max(0, Math.min(plugin.getConfig().getInt("max-bots", 40),
                Bukkit.getMaxPlayers() - Math.max(0, plugin.getConfig().getInt("minimum-free-slots", 2))));
        for (String savedName : section.getKeys(false)) {
            if (count >= max) break;
            String root = "bots." + savedName + ".";
            World world = Bukkit.getWorld(data.getString(root + "world", ""));
            if (world == null) {
                plugin.getLogger().warning("SBot " + savedName + ": mundo inexistente, omitido.");
                continue;
            }
            Location loc = new Location(world, data.getDouble(root + "x"), data.getDouble(root + "y"),
                    data.getDouble(root + "z"), (float) data.getDouble(root + "yaw"),
                    (float) data.getDouble(root + "pitch"));
            String name = savedName;
            if (isLegacyCelebrityName(savedName)) {
                name = nextUniqueName();
                if (name == null) continue;
                migrated++;
            }
            String skinSource = data.getString(root + "skinSource", "");
            if (skinSource.isEmpty()) skinSource = isLegacyCelebrityName(savedName) ? savedName : randomSkinSource();
            if (validName(name) && !nameTaken(name) && add(name, loc, skinSource)) count++;
        }
        save();
        plugin.getLogger().info("SBot restauró " + count + " jugadores. Nombres antiguos migrados: " + migrated);
    }
}
