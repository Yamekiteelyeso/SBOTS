package dev.sbot;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

/**
 * Native Paper 1.20.1 players: registered in the ordinary PlayerList,
 * not only visible through NPC packets.
 */
final class NmsPlayerService {
    private NmsPlayerService() { }

    private static MinecraftServer server() {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }

    static boolean isRegistered(Bot bot) {
        if (bot.handle == null) return false;
        Player online = Bukkit.getPlayer(bot.profile.getId());
        return online != null && online.isOnline()
                && online == bot.handle.getBukkitEntity();
    }

    /** Native login may overwrite the pre-join position with the default world spawn. */
    static void spawn(Bot bot) {
        if (isRegistered(bot) || bot.location.getWorld() == null) return;
        MinecraftServer minecraft = server();
        Location requested = bot.location.clone();
        var level = ((CraftWorld) requested.getWorld()).getHandle();
        ServerPlayer handle = new ServerPlayer(minecraft, level, bot.profile);
        handle.absMoveTo(requested.getX(), requested.getY(), requested.getZ(),
                requested.getYaw(), requested.getPitch());
        handle.setDeltaMovement(Vec3.ZERO);
        handle.setNoGravity(true);
        handle.setInvulnerable(true);
        handle.resetLastActionTime();

        FakeConnection connection = new FakeConnection();
        bot.handle = handle;
        bot.connection = connection;
        minecraft.getPlayerList().placeNewPlayer(connection, handle);
        connection.attach(handle.connection);

        Player bukkit = handle.getBukkitEntity();
        bukkit.setSleepingIgnored(true);
        bukkit.setCollidable(false);
        // Paper login may reset ServerPlayer movement settings.
        // Reapply after PlayerList.placeNewPlayer so synthetic players never fall.
        bukkit.setGravity(false);
        handle.setNoGravity(true);
        handle.setDeltaMovement(Vec3.ZERO);
        // Reposition AFTER registration: placeNewPlayer may reset the NMS
        // coordinates to world spawn. Unlike old code, teleport failures are
        // not silently ignored.
        enforcePosition(bot);
    }

    /** Keep the server-side entity at the requested location, not world spawn. */
    static boolean enforcePosition(Bot bot) {
        if (!isRegistered(bot) || bot.location.getWorld() == null) return false;
        Location target = bot.location.clone();
        Player player = bot.handle.getBukkitEntity();
        Location actual = player.getLocation();
        if (actual.getWorld().equals(target.getWorld())
                && actual.distanceSquared(target) < 0.04
                && Math.abs(actual.getYaw() - target.getYaw()) < 0.1
                && Math.abs(actual.getPitch() - target.getPitch()) < 0.1) return true;

        if (!actual.getWorld().equals(target.getWorld())) {
            // Bukkit handles world changes; a native connection teleport
            // alone cannot change dimensions.
            if (!player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)) return false;
        } else {
            // NMS owns this player's location: teleport the session itself to
            // force Paper to update the entity AND its chunk tracking.
            bot.handle.connection.teleport(target.getX(), target.getY(), target.getZ(),
                    target.getYaw(), target.getPitch());
        }
        Location moved = player.getLocation();
        return moved.getWorld().equals(target.getWorld())
                && moved.distanceSquared(target) < 0.04;
    }

    static boolean teleport(Bot bot, Location destination) {
        if (destination.getWorld() == null) return false;
        if (!isRegistered(bot)) {
            bot.location = destination.clone();
            return true;
        }
        Location previous = bot.location.clone();
        bot.location = destination.clone();
        boolean moved = enforcePosition(bot);
        if (moved) {
            bot.handle.setDeltaMovement(Vec3.ZERO);
            return true;
        }
        bot.location = previous;
        return false;
    }

    static void refreshSkin(Bot bot) {
        if (!isRegistered(bot)) return;
        var removed = new ClientboundPlayerInfoRemovePacket(List.of(bot.profile.getId()));
        var added = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot.handle));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(bot.profile.getId())) continue;
            if (!(viewer instanceof CraftPlayer craft)) continue;
            // This refreshes the ordinary vanilla player info entry, not a second tablist.
            craft.getHandle().connection.send(removed);
            craft.getHandle().connection.send(added);
        }
    }

    static void tick(Bot bot) {
        if (!isRegistered(bot)) return;
        // Do not idle-kick a simulated player whose nonexistent client cannot move.
        bot.handle.resetLastActionTime();
        bot.handle.setDeltaMovement(Vec3.ZERO);
        bot.handle.setNoGravity(true);
        bot.handle.setInvulnerable(true);
    }

    static void remove(Bot bot) {
        if (bot.handle == null) return;
        ServerPlayer handle = bot.handle;
        try {
            // Only remove this exact SBot instance, never an actual player that
            // subsequently logged in using the same username.
            if (Bukkit.getPlayer(bot.profile.getId()) == handle.getBukkitEntity()) {
                server().getPlayerList().remove(handle);
            }
        } finally {
            bot.handle = null;
            bot.connection = null;
        }
    }
}
