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
    int activeCount() { return (int) bots.values().stream().filter(Bot::spawned).count(); }
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
        if (location.getWorld() == null) return 0;
        // Reserve server slots for real players. Fake players really take slots.
        int minFree = Math.max(0, plugin.getConfig().getInt("minimum-free-slots", 2));
        int remainingSlots = Math.max(0, Bukkit.getMaxPlayers() - Bukkit.getOnlinePlayers().size()
                - pendingCount() - minFree);
        int maximum = Math.max(0, plugin.getConfig().getInt("max-bots", 40) - size());
        int target = Math.min(requested, Math.min(maximum, remainingSlots));
        List<String> pool = new ArrayList<>(plugin.getConfig().getStringList("premium-name-pool"));
        Collections.shuffle(pool, random);
        int created = 0;
        for (String candidate : pool) {
            if (created >= target) break;
            if (!validName(candidate) || find(candidate) != null || nameTaken(candidate)) continue;
            if (add(candidate, spread(location, created, target))) created++;
        }
        if (created > 0) save();
        return created;
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

    private boolean add(String name, Location location) {
        if (!validName(name) || location.getWorld() == null || find(name) != null || nameTaken(name)) return false;
        // Synthetic stable UUID: using the real premium UUID would impersonate its account/data.
        UUID uuid = UUID.nameUUIDFromBytes(("SBot:server-player:" + key(name)).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        Bot bot = new Bot(name, profile, location);
        bots.put(key(name), bot);
        // Prepare the public signed texture BEFORE registration so the native
        // player list broadcasts the correct skin to new and existing players.
        skins.load(name, skin -> {
            if (!plugin.isEnabled() || bots.get(key(name)) != bot) return;
            if (skin != null) {
                bot.profile.getProperties().removeAll("textures");
                bot.profile.getProperties().put("textures", new Property("textures", skin.value(), skin.signature()));
                bot.skinLoaded = true;
            }
            try {
                // Do not consume the last human-reserved slots if someone joined
                // while this bot's skin was downloading.
                int reserved = Math.max(0, plugin.getConfig().getInt("minimum-free-slots", 2));
                if (Bukkit.getOnlinePlayers().size() >= Bukkit.getMaxPlayers() - reserved) {
                    plugin.getLogger().warning("SBot " + name + " cancelado: plazas reservadas para jugadores reales.");
                    bots.remove(key(name));
                    save();
                    return;
                }
                if (nameTaken(name)) {
                    plugin.getLogger().warning("SBot " + name + " omitido: otro jugador tomo su nombre.");
                    bots.remove(key(name));
                    save();
                    return;
                }
                NmsPlayerService.spawn(bot);
                if (!bot.spawned()) throw new IllegalStateException("Paper no registro el jugador");
                addTicket(bot.location);
            } catch (Throwable error) {
                // A partial registration must not leave an online ghost entry.
                plugin.getLogger().warning("Error al registrar SBot " + name + ": " + error);
                try { NmsPlayerService.remove(bot); } catch (Throwable ignored) { }
                bots.remove(key(name));
            }
            save();
        });
        return true;
    }

    boolean delete(String name) {
        Bot bot = find(name);
        if (bot == null) return false;
        bots.remove(key(name));
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
        if (persist) save();
    }

    boolean teleport(String name, Location destination) {
        Bot bot = find(name);
        if (bot == null || destination.getWorld() == null) return false;
        Location previous = bot.location.clone();
        if (!NmsPlayerService.teleport(bot, destination)) return false;
        if (bot.spawned()) {
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
        if (!plugin.getConfig().getBoolean("keep-bot-chunks-loaded", false)) return;
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

    void restore() {
        if (!file.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = data.getConfigurationSection("bots");
        if (section == null) return;
        int count = 0;
        int max = Math.max(0, Math.min(plugin.getConfig().getInt("max-bots", 40),
                Bukkit.getMaxPlayers() - Math.max(0, plugin.getConfig().getInt("minimum-free-slots", 2))));
        for (String name : section.getKeys(false)) {
            if (count >= max) break;
            String root = "bots." + name + ".";
            World world = Bukkit.getWorld(data.getString(root + "world", ""));
            if (world == null) {
                plugin.getLogger().warning("SBot " + name + ": mundo inexistente, omitido.");
                continue;
            }
            Location loc = new Location(world, data.getDouble(root + "x"), data.getDouble(root + "y"),
                    data.getDouble(root + "z"), (float) data.getDouble(root + "yaw"),
                    (float) data.getDouble(root + "pitch"));
            if (validName(name) && !nameTaken(name) && add(name, loc)) count++;
        }
        plugin.getLogger().info("SBot restaurando " + count + " jugadores simulados.");
    }
}
