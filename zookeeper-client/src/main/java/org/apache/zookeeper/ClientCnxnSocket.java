package org.apache.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

abstract class ClientCnxnSocket {

    protected ClientCnxn.SendThread sendThread;
    protected long sessionId;
    protected long now;
    protected long lastSend;
    protected long lastHeard;

    protected boolean initialized;

    protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

    protected ByteBuffer incomingBuffer = lenBuffer;

    void introduce(ClientCnxn.SendThread sendThread, long sessionId) {
        this.sendThread = sendThread;
        this.sessionId = sessionId;
    }

    void updateNow() {
        now = System.nanoTime() / 1000000;
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

    abstract void cleanup();

    abstract void close();

    abstract void enableReadWriteOnly();

    abstract void wakeupCnxn();

    abstract void doTransport(int waitTimeOut, List<ClientCnxn.Packet> pendingQueue,
                              LinkedList<ClientCnxn.Packet> outgoingQueue, ClientCnxn cnxn)
            throws IOException, InterruptedException;

    abstract void enableWrite();

    abstract void testableCloseSocket() throws IOException;

    abstract void sendPacket(ClientCnxn.Packet p) throws IOException;

    abstract void disableWrite();
}
