package org.apache.zookeeper;

import org.apache.jute.BinaryInputArchive;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

abstract class ClientCnxnSocket {
    private static final Logger LOG = LoggerFactory.getLogger(ClientCnxnSocket.class);

    protected ClientCnxn.SendThread sendThread;
    protected long sessionId;
    protected long now;
    protected long lastSend;
    protected long lastHeard;
    protected boolean initialized;
    protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);
    protected ByteBuffer incomingBuffer = lenBuffer;

    protected long sentCount = 0;
    protected long recvCount = 0;

    void introduce(ClientCnxn.SendThread sendThread, long sessionId) {
        this.sendThread = sendThread;
        this.sessionId = sessionId;
    }

    void updateNow() {
        now = Time.currentElapsedTime();
    }

    void updateLastSendAndHeard() {
        this.lastSend = now;
        this.lastHeard = now;
    }

    abstract boolean isConnected();

    abstract void connect(InetSocketAddress addr) throws IOException;

    int getIdleRecv() {
        return (int) (now - lastHeard);
    }

    int getIdleSend() {
        return (int) (now - lastSend);
    }

    void updateLastSend() {
        this.lastSend = now;
    }

    abstract void doTransport(int waitTimeOut, List<ClientCnxn.Packet> pendingQueue,
                              LinkedList<ClientCnxn.Packet> outgoingQueue, ClientCnxn cnxn)
            throws IOException, InterruptedException;

    abstract void enableReadWriteOnly();

    abstract SocketAddress getRemoteSocketAddress();

    abstract void enableWrite();

    abstract void disableWrite();

    void readConnectResult() throws IOException {
        if (LOG.isTraceEnabled()) {
            StringBuilder buf = new StringBuilder("0x[");
            for (byte b : incomingBuffer.array()) {
                buf.append(Integer.toHexString(b)).append(",");
            }
            buf.append("]");
            LOG.trace("readConnectResult " + incomingBuffer.remaining() + " "
                    + buf.toString());
        }
        ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
        ConnectResponse conRsp = new ConnectResponse();
        conRsp.deserialize(bbia, "connect");

        this.sessionId = conRsp.getSessionId();
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId,
                conRsp.getPasswd());
    }

    void updateLastHeard() {
        this.lastHeard = now;
    }

    abstract void wakeupCnxn();

    abstract void cleanup();

    abstract void close();

    abstract SocketAddress getLocalSocketAddress();

    long getSentCount() {
        return sentCount;
    }

    long getRecvCount() {
        return recvCount;
    }
}
