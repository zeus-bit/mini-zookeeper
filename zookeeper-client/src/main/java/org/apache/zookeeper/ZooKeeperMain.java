package org.apache.zookeeper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class ZooKeeperMain {

    protected ZooKeeper zk;
    protected String host = "";
    protected boolean printWatches = true;

    private static CountDownLatch latch = new CountDownLatch(1);

    public static void main(String[] args) throws IOException, InterruptedException {
        ZooKeeperMain main = new ZooKeeperMain(args);
        latch.await();
    }

    public ZooKeeperMain(String args[]) throws IOException {
        connectToZK("localhost:2181");
    }

    protected void connectToZK(String newHost) throws IOException {
        host = newHost;
        zk = new ZooKeeper(host, 30000, new MyWatcher(), false);
    }

    private class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            if (getPrintWatches()) {
                ZooKeeperMain.printMessage("WATCHER::");
                ZooKeeperMain.printMessage(event.toString());
            }
        }
    }

    public boolean getPrintWatches( ) {
        return printWatches;
    }

    public static void printMessage(String msg) {
        System.out.println("\n"+msg);
    }
}
