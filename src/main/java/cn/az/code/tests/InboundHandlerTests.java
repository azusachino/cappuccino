package cn.az.code.tests;

import cn.az.code.handlers.InboundHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;

public class InboundHandlerTests {

    public static void main(String[] args) {
        final InboundHandler handler = new InboundHandler();
        ChannelInitializer<EmbeddedChannel> ci = new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) throws Exception {
                ch.pipeline().addLast(handler);

            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(ci);
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(1);
        channel.writeInbound(buf);
        channel.flush();

        channel.writeInbound(buf);
        channel.finish();
        channel.close();
    }
}
