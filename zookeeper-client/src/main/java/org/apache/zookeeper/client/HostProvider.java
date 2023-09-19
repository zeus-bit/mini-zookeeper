package org.apache.zookeeper.client;

import java.net.InetSocketAddress;

public interface HostProvider {

    int size();

    InetSocketAddress next(long spinDelay);

    void onConnected();
}
