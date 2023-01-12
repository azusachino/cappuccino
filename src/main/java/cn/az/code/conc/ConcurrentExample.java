package cn.az.code.conc;

public class ConcurrentExample {

    int x = 0;
    volatile boolean v = false;

    // happens before rule 1,2,3
    public static void main(String[] args) {
        ConcurrentExample ce = new ConcurrentExample();
        Runnable a = () -> ce.write();
        Runnable b = () -> ce.read();

        a.run();
        b.run();
    }

    void write() {
        x = 42;
        v = true;
    }

    void read() {
        if (v == true) {
            System.out.println(x);
        }
    }

}
