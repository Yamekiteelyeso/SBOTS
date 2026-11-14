package dev.sbot;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.net.InetSocketAddress;

/**
 * Simulated, local-only client session. A bot must acknowledge both keepalive
 * and teleport synchronization, or Paper may stop processing its movement.
 */
final class FakeConnection extends Connection {
    private volatile ServerGamePacketListenerImpl gameListener;
    private Integer pendingTeleportId;

    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress("127.0.0.1", 25565);
    }

    void attach(ServerGamePacketListenerImpl listener) {
        gameListener = listener;
        if (pendingTeleportId != null) {
            int id = pendingTeleportId;
            pendingTeleportId = null;
            listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
        }
    }

    @Override
    public boolean isConnected() { return true; }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) {
        ServerGamePacketListenerImpl listener = gameListener;
        if (listener == null && getPacketListener() instanceof ServerGamePacketListenerImpl fallback)
            listener = fallback;
        if (packet instanceof ClientboundKeepAlivePacket keepAlive && listener != null) {
            listener.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
        } else if (packet instanceof ClientboundPlayerPositionPacket position) {
            if (listener != null) {
                listener.handleAcceptTeleportPacket(
                        new ServerboundAcceptTeleportationPacket(position.getId()));
            } else {
                pendingTeleportId = position.getId();
            }
        }
    }

    @Override
    public void send(Packet<?> packet) { send(packet, null); }

    @Override
    public void handleDisconnection() { /* PlayerList.remove handles synthetic sessions. */ }
}
