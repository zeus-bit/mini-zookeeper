package org.apache.zookeeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class ZooKeeperMain {
    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperMain.class);

    private MyCommandOptions cl = new MyCommandOptions();

    private static CountDownLatch latch = new CountDownLatch(1);

    public static void main(String[] args) throws IOException, InterruptedException {
        ZooKeeperMain main = new ZooKeeperMain();
        latch.await();
    }

    public ZooKeeperMain() throws IOException {
        LOG.info("Connecting to " + cl.getOption("server"));
        connectToZK(cl.getOption("server"));
    }

    private void connectToZK(String host) throws IOException {
        new ZooKeeper(host, Integer.parseInt(cl.getOption("timeout")), new MyWatcher());
    }

    private static class MyWatcher implements Watcher {
        public void process(WatchedEvent event) {
            ZooKeeperMain.printMessage("WATCHER::");
            ZooKeeperMain.printMessage(event.toString());
        }
    }

    private static void printMessage(String msg) {
        LOG.info("\n"+msg);
    }

    private static class MyCommandOptions {
        private Map<String,String> options = new HashMap<String,String>();

        public MyCommandOptions() {
            options.put("server", "localhost:2181,10.2.21.140:2181");
            options.put("timeout", "30000");
        }

        private String getOption(String opt) {
            return options.get(opt);
        }
    }
}
