package dev.sbot;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.net.InetSocketAddress;

/** Local, in-memory session. SBot players are not authenticated Minecraft clients. */
final class FakeConnection extends Connection {
    private volatile ServerGamePacketListenerImpl gameListener;

    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress("127.0.0.1", 25565);
    }

    /** Called after Paper installs its real ServerGamePacketListenerImpl during join. */
    void attach(ServerGamePacketListenerImpl listener) {
        this.gameListener = listener;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) {
        // No network peer exists: acknowledge vanilla keepalives immediately instead
        // of allowing the player-list connection to be kicked by its timeout.
        if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
            ServerGamePacketListenerImpl listener = gameListener;
            if (listener == null && getPacketListener() instanceof ServerGamePacketListenerImpl fallback) {
                listener = fallback;
            }
            if (listener != null) {
                listener.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
            }
        }
    }

    @Override
    public void send(Packet<?> packet) {
        send(packet, null);
    }

    @Override
    public void handleDisconnection() {
        // A synthetic connection cannot close a socket. PlayerList.remove()
        // handles removal explicitly.
    }
}
