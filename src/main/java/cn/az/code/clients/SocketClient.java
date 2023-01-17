package cn.az.code.clients;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @see java.util.concurrent.FutureTask
 */
public class SocketClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketClient.class);

    private volatile int state;

    private static final int NEW = 0;
    private static final int COMPLETING = 1;

    public SocketClient() {
        this.state = NEW;
    }

    public void startClient() throws IOException {
        if (this.state == NEW) {
            STATE.compareAndSet(this, NEW, COMPLETING);
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

    private static final VarHandle STATE;

    static {
        try {
            var lu = MethodHandles.lookup();
            STATE = lu.findVarHandle(SocketClient.class, "state", int.class);
        } catch (ReflectiveOperationException e) {

            throw new ExceptionInInitializerError(e);
        }
    }
}
