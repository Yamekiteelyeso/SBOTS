package dev.sbot;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_20_R1.CraftPlayer;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import org.bukkit.entity.Player;

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

    static void spawn(Bot bot) {
        if (isRegistered(bot) || bot.location.getWorld() == null) return;
        MinecraftServer server = server();
        var level = ((CraftWorld) bot.location.getWorld()).getHandle();
        ServerPlayer handle = new ServerPlayer(server, level, bot.profile);
        Location position = bot.location.clone();
        handle.absMoveTo(position.getX(), position.getY(), position.getZ(),
                position.getYaw(), position.getPitch());
        handle.setDeltaMovement(Vec3.ZERO);
        handle.setNoGravity(true);
        handle.setInvulnerable(true);
        handle.resetLastActionTime();

        FakeConnection connection = new FakeConnection();
        bot.handle = handle;
        bot.connection = connection;
        // This calls the native login entry path: same backend online list as humans.
        server.getPlayerList().placeNewPlayer(connection, handle);

        // Paper has installed the session listener as part of placeNewPlayer.
        // Attach it explicitly for keepalive acknowledgements.
        connection.attach(handle.connection);
        handle.markClientLoaded();
        Player bukkit = handle.getBukkitEntity();
        bukkit.setSleepingIgnored(true);
        bukkit.setCollidable(false);
        if (!bukkit.getWorld().equals(position.getWorld())
                || bukkit.getLocation().distanceSquared(position) > 0.01) {
            bukkit.teleport(position);
        }
    }

    static boolean teleport(Bot bot, Location destination) {
        if (destination.getWorld() == null) return false;
        if (!isRegistered(bot)) {
            bot.location = destination.clone();
            return true;
        }
        Player player = bot.handle.getBukkitEntity();
        boolean done = player.teleport(destination);
        if (done) {
            bot.location = player.getLocation();
            bot.handle.setDeltaMovement(Vec3.ZERO);
            bot.handle.markClientLoaded();
        }
        return done;
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
        bot.handle.markClientLoaded();
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
