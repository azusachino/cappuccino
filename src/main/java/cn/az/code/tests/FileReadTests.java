package cn.az.code.tests;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.CharBuffer;

public class FileReadTests {

    void readFile(File f) {

        try (FileOutputStream fos = new FileOutputStream(f)) {

        } catch (IOException e) {
            e.printStackTrace();
        }

        try (FileReader fr = new FileReader(f)) {
            CharBuffer cb = CharBuffer.allocate(1024);
            fr.read(cb);
            cb.flip();
            char[] cc = new char[cb.length()];
            int id = 0;
            while (cb.hasRemaining()) {
                cc[++id] = cb.get();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
