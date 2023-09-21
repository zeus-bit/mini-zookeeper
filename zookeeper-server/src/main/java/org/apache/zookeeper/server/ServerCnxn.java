package org.apache.zookeeper.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ServerCnxn {

    protected final AtomicLong packetsReceived = new AtomicLong();

    protected final AtomicLong packetsSent = new AtomicLong();

    public abstract InetAddress getSocketAddress();

    public abstract InetSocketAddress getRemoteSocketAddress();

    protected void packetReceived() {
        incrPacketsReceived();
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats().incrementPacketsReceived();
        }
    }

    protected long incrPacketsReceived() {
        return packetsReceived.incrementAndGet();
    }

    protected abstract ServerStats serverStats();

    public abstract void setSessionTimeout(int sessionTimeout);

    abstract void disableRecv();

    abstract void setSessionId(long sessionId);

    abstract int getSessionTimeout();

    abstract long getSessionId();

    protected static class EndOfStreamException extends IOException {

        public EndOfStreamException(String msg) {
            super(msg);
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    abstract void sendBuffer(ByteBuffer closeConn);

    protected void packetSent() {
        incrPacketsSent();
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats().incrementPacketsSent();
        }
    }

    protected long incrPacketsSent() {
        return packetsSent.incrementAndGet();
    }

    abstract void close();
}
