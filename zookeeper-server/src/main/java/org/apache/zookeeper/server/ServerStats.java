package org.apache.zookeeper.server;

public class ServerStats {

    private final Provider provider;

    private long packetsReceived;

    private long packetsSent;

    public interface Provider {
        long getOutstandingRequests();
        long getLastProcessedZxid();
        String getState();
        int getNumAliveConnections();
    }

    public ServerStats(Provider provider) {
        this.provider = provider;
    }

    synchronized public void incrementPacketsReceived() {
        packetsReceived++;
    }

    synchronized public void incrementPacketsSent() {
        packetsSent++;
    }
}
