package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperCriticalThread extends ZooKeeperThread {
    private static final Logger LOG = LoggerFactory
            .getLogger(ZooKeeperCriticalThread.class);
    private final ZooKeeperServerListener listener;

    public ZooKeeperCriticalThread(String threadName,
                                   ZooKeeperServerListener listener) {
        super(threadName);
        this.listener = listener;
    }

    @Override
    protected void handleException(String threadName, Throwable e) {
        LOG.error("Severe unrecoverable error, from thread : {}", threadName, e);
        listener.notifyStopping(threadName, 1);
    }
}
