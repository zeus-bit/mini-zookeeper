package org.apache.zookeeper.server;

public interface ZooKeeperServerListener {
    void notifyStopping(String threadName, int errorCode);
}
