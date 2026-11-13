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

/**
 * Connection compatible with the 1.20.1 server-side login/player-list lifecycle.
 * No Microsoft session or TCP client is created; outbound gameplay packets are discarded.
 */
final class FakeConnection extends Connection {
    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress("127.0.0.1", 25565);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        if (packet instanceof ClientboundKeepAlivePacket keepAlive
                && getPacketListener() instanceof ServerGamePacketListenerImpl gameListener) {
            gameListener.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
        }
    }

    @Override
    public void send(Packet<?> packet) {
        send(packet, null);
    }

    @Override
    public void handleDisconnection() {
        // Removal uses PlayerList.remove(ServerPlayer), not socket disconnect.
    }
}
