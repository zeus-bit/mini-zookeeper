package org.apache.zookeeper.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ServerCnxn {

    protected final AtomicLong packetsReceived = new AtomicLong();

    boolean isOldClient = true;

    protected final AtomicLong packetsSent = new AtomicLong();

    abstract void close();

    public abstract InetAddress getSocketAddress();

    public abstract InetSocketAddress getRemoteSocketAddress();

    abstract int getSessionTimeout();

    abstract long getSessionId();

    protected static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -8255690282104294178L;

        public EndOfStreamException(String msg) {
            super(msg);
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

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

    protected static class CloseRequestException extends IOException {
        private static final long serialVersionUID = -7854505709816442681L;

        public CloseRequestException(String msg) {
            super(msg);
        }
    }
}
