package cn.az.code.servers;

import java.io.IOException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class EchoNettyServer {

    public static void main(String[] args) throws Exception {
        new EchoNettyServer(9786).run();
    }

    private int port;

    public EchoNettyServer(int port) {
        this.port = port;
    }

    public void run() throws IOException, InterruptedException {
        // acceptor and worker
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            // selector
            ServerBootstrap sb = new ServerBootstrap();
            // bind all stuffs
            sb.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // real handler
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    System.out.printf("Oh, some msg from client, %s", msg.toString());
                                    ctx.write("Thank you client, nice to see ya");
                                    super.channelRead(ctx, msg);
                                }

                            });
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            // bind and start to accept incoming connections
            ChannelFuture cf = sb.bind(this.port).sync();

            // wait until the server socket is closed
            cf.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

}
