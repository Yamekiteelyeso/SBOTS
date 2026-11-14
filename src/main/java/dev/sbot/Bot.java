package dev.sbot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;

/** One server-side simulated player. Its UUID is deliberately NOT a premium account UUID. */
final class Bot {
    final String name;
    final GameProfile profile;
    final String skinSource;
    Location location;
    ServerPlayer handle; // null while the skin is being fetched, or after removal
    FakeConnection connection;
    boolean skinLoaded;

    Bot(String name, GameProfile profile, Location location, String skinSource) {
        this.name = name;
        this.profile = profile;
        this.skinSource = skinSource;
        this.location = location.clone();
    }

    boolean spawned() {
        return handle != null;
    }
}
