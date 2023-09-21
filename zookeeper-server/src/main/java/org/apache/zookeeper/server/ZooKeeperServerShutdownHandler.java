package org.apache.zookeeper.server;

import java.util.concurrent.CountDownLatch;

class ZooKeeperServerShutdownHandler {
    private final CountDownLatch shutdownLatch;

    ZooKeeperServerShutdownHandler(CountDownLatch shutdownLatch) {
        this.shutdownLatch = shutdownLatch;
    }

    void handle(ZooKeeperServer.State state) {
        if (state == ZooKeeperServer.State.ERROR || state == ZooKeeperServer.State.SHUTDOWN) {
            shutdownLatch.countDown();
        }
    }
}
