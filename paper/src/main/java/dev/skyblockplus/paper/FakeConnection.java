package dev.skyblockplus.paper;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * A network connection that goes nowhere.
 *
 * EmbeddedChannel is NOT thread-safe (its event loop reports inEventLoop() == true for every thread).
 * Other plugins (packet libraries, NPC plugins, etc.) send packets to every online player from async
 * threads, so the channel's outbound buffer could get corrupted and the main thread would then spin
 * forever in ChannelOutboundBuffer.addFlush (Paper watchdog crash).
 *
 * Fix: a stateless sink handler swallows every outbound write before it can reach the channel's
 * (non-thread-safe) outbound buffer. It is named "packet_handler" so plugins that do
 * pipeline.addBefore("packet_handler", ...) don't throw NoSuchElementException.
 */
public class FakeConnection extends Connection {
    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("packet_handler", new Sink());
        this.channel = ch;
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
    }

    public EmbeddedChannel embedded() { return (EmbeddedChannel) this.channel; }

    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}

    /** Drops all outbound data. Holds no state, so it is safe to call from any thread. */
    private static final class Sink extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }
        @Override public void flush(ChannelHandlerContext ctx) { /* nothing to flush */ }
    }
}
