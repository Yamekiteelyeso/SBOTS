package dev.sbot;

import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps actual Minecraft clients informed about the NMS entities.
 *
 * PlayerList registration guarantees /list and Bukkit APIs, but an offline
 * synthetic connection is not a normal player chunk-watcher; relying solely on
 * the ordinary tracker can leave the player physically invisible.
 *
 * We retain the native registered ServerPlayer. These are real player spawn
 * packets, not an alternative tab or standalone NPC.
 */
final class BotVisibility {
    private record Seen(int id, double x, double y, double z, float yaw, float pitch, long tick) { }

    private final SBotPlugin plugin;
    private final BotManager manager;
    private final Map<UUID, Map<UUID, Seen>> perViewer = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();

    BotVisibility(SBotPlugin plugin, BotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    void viewerJoined(Player viewer) {
        if (manager.isBot(viewer)) return;
        perViewer.remove(viewer.getUniqueId());
        readyAt.put(viewer.getUniqueId(), (long) Bukkit.getCurrentTick() + 30);
    }

    void viewerQuit(Player viewer) {
        perViewer.remove(viewer.getUniqueId());
        readyAt.remove(viewer.getUniqueId());
    }

    void botRemoved(Bot bot) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Map<UUID, Seen> map = perViewer.get(viewer.getUniqueId());
            if (map == null) continue;
            Seen seen = map.remove(bot.profile.getId());
            if (seen != null && viewer instanceof CraftPlayer craft && viewer.isOnline()) {
                craft.getHandle().connection.send(new ClientboundRemoveEntitiesPacket(seen.id()));
            }
        }
    }

    /** Called by BotManager after changing a skin already shown to viewers. */
    void botSkinUpdated(Bot bot) {
        if (!NmsPlayerService.isRegistered(bot)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!(viewer instanceof CraftPlayer craft) || manager.isBot(viewer)) continue;
            Map<UUID, Seen> map = perViewer.get(viewer.getUniqueId());
            if (map == null || !map.containsKey(bot.profile.getId())) continue;
            craft.getHandle().connection.send(
                    new ClientboundRemoveEntitiesPacket(bot.handle.getId()));
            craft.getHandle().connection.send(
                    new ClientboundPlayerInfoRemovePacket(List.of(bot.profile.getId())));
            craft.getHandle().connection.send(
                    ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot.handle)));
            spawnPacket(craft, bot.handle);
            Location pos = bot.handle.getBukkitEntity().getLocation();
            map.put(bot.profile.getId(),
                    new Seen(bot.handle.getId(), pos.getX(), pos.getY(), pos.getZ(),
                            pos.getYaw(), pos.getPitch(), Bukkit.getCurrentTick()));
        }
    }

    /**
     * Call on the Bukkit server thread every ten ticks and immediately after
     * teleports. Handles joins, entering/leaving range, and changed positions.
     */
    void sync() {
        long tick = Bukkit.getCurrentTick();
        Set<UUID> present = new HashSet<>();
        int range = Math.max(24, Math.min(256,
                plugin.getConfig().getInt("visibility-range-blocks", 96)));
        double rangeSquared = (double) range * range;
        long refreshTicks = Math.max(60, plugin.getConfig().getLong("visibility-resync-ticks", 200));

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (manager.isBot(viewer) || !(viewer instanceof CraftPlayer craft)) continue;
            UUID viewerId = viewer.getUniqueId();
            present.add(viewerId);
            if (tick < readyAt.getOrDefault(viewerId, 0L)) continue;
            Map<UUID, Seen> map = perViewer.computeIfAbsent(viewerId, unused -> new HashMap<>());

            for (Bot bot : manager.all()) {
                UUID botId = bot.profile.getId();
                Seen seen = map.get(botId);
                boolean eligible = NmsPlayerService.isRegistered(bot);
                Location at = eligible ? bot.handle.getBukkitEntity().getLocation() : null;
                boolean visible = eligible && viewer.canSee(bot.handle.getBukkitEntity())
                        && viewer.getWorld().equals(at.getWorld())
                        && viewer.getLocation().distanceSquared(at) <= rangeSquared;

                if (!visible) {
                    if (seen != null) {
                        craft.getHandle().connection.send(new ClientboundRemoveEntitiesPacket(seen.id()));
                        map.remove(botId);
                    }
                    continue;
                }

                if (seen == null || seen.id() != bot.handle.getId()) {
                    if (seen != null) craft.getHandle().connection.send(
                            new ClientboundRemoveEntitiesPacket(seen.id()));
                    craft.getHandle().connection.send(
                            ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot.handle)));
                    spawnPacket(craft, bot.handle);
                    map.put(botId, new Seen(bot.handle.getId(), at.getX(), at.getY(), at.getZ(),
                            at.getYaw(), at.getPitch(), tick));
                    continue;
                }

                boolean moved = Math.abs(seen.x() - at.getX()) > 0.02
                        || Math.abs(seen.y() - at.getY()) > 0.02
                        || Math.abs(seen.z() - at.getZ()) > 0.02
                        || Math.abs(seen.yaw() - at.getYaw()) > 0.2
                        || Math.abs(seen.pitch() - at.getPitch()) > 0.2;

                if (moved) {
                    craft.getHandle().connection.send(new ClientboundTeleportEntityPacket(bot.handle));
                    craft.getHandle().connection.send(new ClientboundRotateHeadPacket(bot.handle,
                            angle(at.getYaw())));
                    map.put(botId, new Seen(seen.id(), at.getX(), at.getY(), at.getZ(),
                            at.getYaw(), at.getPitch(), tick));
                } else if (tick - seen.tick() >= refreshTicks) {
                    // Protect against another plugin or server tracker silently
                    // dropping the client's player entity. No tab list recreation.
                    spawnPacket(craft, bot.handle);
                    map.put(botId, new Seen(seen.id(), at.getX(), at.getY(), at.getZ(),
                            at.getYaw(), at.getPitch(), tick));
                }
            }
        }

        perViewer.keySet().removeIf(id -> !present.contains(id));
        readyAt.keySet().removeIf(id -> !present.contains(id));
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }

    private static void spawnPacket(CraftPlayer viewer, ServerPlayer handle) {
        viewer.getHandle().connection.send(new ClientboundAddPlayerPacket(handle));
        viewer.getHandle().connection.send(
                new ClientboundRotateHeadPacket(handle, angle(handle.getYHeadRot())));
    }
}
