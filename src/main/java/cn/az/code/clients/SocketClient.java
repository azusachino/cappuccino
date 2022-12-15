package cn.az.code.clients;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketClient.class);

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
