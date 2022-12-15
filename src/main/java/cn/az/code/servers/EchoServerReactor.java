package cn.az.code.servers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class EchoServerReactor implements Runnable {

    private static final int PORT = 9765;

    Selector selector;
    ServerSocketChannel channel;

    public EchoServerReactor() throws IOException {
        this.selector = Selector.open();
        this.channel = ServerSocketChannel.open();
        this.channel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(PORT);
        this.channel.bind(addr);
        this.channel.register(selector, SelectionKey.OP_ACCEPT);
    }

    @Override
    public void run() {
        try {
            while (this.selector.select() > 0) {
                Set<SelectionKey> keys = this.selector.selectedKeys();
                Iterator<SelectionKey> itr = keys.iterator();

                while (itr.hasNext()) {
                    SelectionKey sk = itr.next();
                    if (!sk.isValid()) {
                        continue;
                    }

                    if (sk.isAcceptable()) {
                        SocketChannel sc = (SocketChannel) sk.channel();
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        // write buffer
                        int numRead = sc.read(buffer);
                        if (numRead == -1) {
                            sc.close();
                        } else {
                            System.out.println(new String(buffer.array(), 0, numRead));

                            buffer.flip();
                            // read buffer
                            sc.write(buffer);
                            buffer.clear();
                            sc.close();
                        }
                    }
                    itr.remove();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}
