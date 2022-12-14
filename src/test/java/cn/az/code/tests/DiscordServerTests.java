package cn.az.code.tests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscordServerTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordServerTests.class);

    @Test
    public void startServer() throws IOException {
        // selector - buffer - channel
        Selector selector = Selector.open();
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        // non-blocking
        serverSocketChannel.configureBlocking(false);
        InetSocketAddress socketAddress = new InetSocketAddress(9394);
        serverSocketChannel.bind(socketAddress);

        LOGGER.info("discord server started");

        // register to selector (discord server only deal with accept)
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        // select
        while (selector.select() > 0) {
            Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey sk = iter.next();
                // dispatch a new socketchannel to read client's data
                if (sk.isAcceptable()) {
                    SocketChannel sc = serverSocketChannel.accept();
                    sc.configureBlocking(false);
                    sc.register(selector, SelectionKey.OP_READ);
                } else if (sk.isReadable()) {
                    SocketChannel sc = (SocketChannel) sk.channel();
                    ByteBuffer bb = ByteBuffer.allocate(1024);
                    int len = 0;
                    while ((len = sc.read(bb)) > 0) {
                        // write -> read
                        bb.flip();
                        LOGGER.info("received msg (segment-1024): {}", new String(bb.array(), 0, len));
                        // read -> write
                        bb.clear();
                    }
                    sc.close();
                }
            }
            // clean selectionKeys for next select
            iter.remove();
        }
        serverSocketChannel.close();
    }

    /**
     * client side of Discord
     * 
     * @throws IOException
     */
    @Test
    public void startClient() throws IOException {
        InetSocketAddress socketAddress = new InetSocketAddress("127.0.0.1", 9394);
        SocketChannel socketChannel = SocketChannel.open(socketAddress);
        // non-blocking -> to use selector
        socketChannel.configureBlocking(false);

        // self-spin, wait for connection done
        while (!socketChannel.finishConnect()) {

        }

        LOGGER.info("successfully connected to discord server");
        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.put("Nice to meet you".getBytes());
        // write -> read
        bb.flip();

        socketChannel.write(bb);
        socketChannel.shutdownOutput();

        try {
            TimeUnit.SECONDS.sleep(10L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        socketChannel.close();
    }
}