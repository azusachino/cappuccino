package cn.az.code.clients;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class SimpleNettyClient {

    private String host;
    private int port;

    public SimpleNettyClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void run() throws Exception {
        // The EventLoopGroup is responsible for providing the event loop that handles
        // the I/O of the channel
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            // The Bootstrap is a helper class that sets up a client
            Bootstrap b = new Bootstrap();
            b.group(workerGroup)
                    // Specifies the use of an NIO channel
                    .channel(NioSocketChannel.class)
                    // Handles incoming traffic
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            // Here we can add any number of custom channel handlers
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                    ctx.writeAndFlush("hello, server");
                                    super.channelActive(ctx);
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    System.out.printf("Oh, I received from server, %s", msg.toString());
                                    super.channelRead(ctx, msg);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    System.out.printf("Byebye server");
                                    super.channelInactive(ctx);
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
                                        throws Exception {
                                    cause.printStackTrace();
                                    ctx.close();
                                    super.exceptionCaught(ctx, cause);
                                }
                            });
                        }
                    })
                    // Option for the SocketChannel
                    .option(ChannelOption.SO_KEEPALIVE, true);

            // Start the client
            ChannelFuture f = b.connect(host, port).sync();

            // Wait until the connection is closed
            f.channel().closeFuture().sync();
        } finally {
            // Shut down the EventLoopGroup to terminate all threads
            workerGroup.shutdownGracefully();
        }
    }

}
