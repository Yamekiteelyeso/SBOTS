package dev.sbot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import org.bukkit.entity.Player;

/** Paper 1.20.1 only. Creates registered server-side players, NOT packet-only NPCs. */
final class NmsPlayerService {
    private NmsPlayerService() { }

    private static MinecraftServer server() {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }

    static void spawn(Bot bot) {
        if (bot.spawned() || bot.location.getWorld() == null) return;
        MinecraftServer server = server();
        var level = ((CraftWorld) bot.location.getWorld()).getHandle();
        ServerPlayer handle = new ServerPlayer(server, level, bot.profile);
        Location position = bot.location;
        handle.absMoveTo(position.getX(), position.getY(), position.getZ(),
                position.getYaw(), position.getPitch());
        handle.setDeltaMovement(Vec3.ZERO);
        handle.setNoGravity(true);
        handle.setInvulnerable(true);

        FakeConnection connection = new FakeConnection();
        // The NMS PlayerList sends regular player-info updates, tracks the entity,
        // adds the Bukkit Player, and fires the normal join event.
        bot.handle = handle;
        bot.connection = connection;
        server.getPlayerList().placeNewPlayer(connection, handle);

        Player bukkit = handle.getBukkitEntity();
        bukkit.setSleepingIgnored(true);
        bukkit.setCollidable(false);
        // placeNewPlayer can update the spawn location on some Paper builds.
        if (!bukkit.getWorld().equals(position.getWorld())
                || bukkit.getLocation().distanceSquared(position) > 0.01) {
            bukkit.teleport(position);
        }
    }

    static boolean teleport(Bot bot, Location destination) {
        if (destination.getWorld() == null) return false;
        if (!bot.spawned()) {
            bot.location = destination.clone();
            return true;
        }
        Player player = bot.handle.getBukkitEntity();
        boolean done = player.teleport(destination);
        if (done) {
            bot.location = player.getLocation();
            bot.handle.setDeltaMovement(Vec3.ZERO);
        }
        return done;
    }

    static void remove(Bot bot) {
        if (!bot.spawned()) return;
        ServerPlayer handle = bot.handle;
        // Player.kick() is insufficient for synthetic connections. The native
        // removal path updates Bukkit.getOnlinePlayers(), /list, tab and tracking.
        server().getPlayerList().remove(handle);
        bot.handle = null;
        bot.connection = null;
    }
}
