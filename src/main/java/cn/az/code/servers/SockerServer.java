package cn.az.code.servers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SockerServer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SockerServer.class);

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
}
