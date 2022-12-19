package cn.az.code.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@ChannelHandler.Sharable
public class SharableHandler extends ChannelInboundHandlerAdapter {

    public static final SharableHandler INSTANCE = new SharableHandler();

    private static final Logger LOGGER = LoggerFactory.getLogger(SharableHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf in = (ByteBuf) msg;
        LOGGER.info("msg type: " + (in.hasArray() ? "堆内存" : "直接内存"));
        int len = in.readableBytes();
        byte[] arr = new byte[len];
        in.getBytes(0, arr);
        LOGGER.info("server received: " + new String(arr));

        LOGGER.info("写回前, msg.refCnt:" + ((ByteBuf) msg).refCnt());
        // 写回数据，异步任务
        ChannelFuture f = ctx.writeAndFlush(msg);
        f.addListener((ChannelFuturefutureListener) -> {
            LOGGER.info("写回后, msg.refCnt:" + ((ByteBuf) msg).refCnt());
        });
    }

}
