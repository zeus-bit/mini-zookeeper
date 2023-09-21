package org.apache.zookeeper.server;

import org.apache.jute.BinaryInputArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.LinkedBlockingQueue;

public class NIOServerCnxn extends ServerCnxn {

    private static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxn.class);

    private final ZooKeeperServer zkServer;
    private final SocketChannel sock;
    private final SelectionKey sk;
    private final NIOServerCnxnFactory factory;
    private int outstandingLimit = 1;

    long sessionId;

    private ByteBuffer lenBuffer = ByteBuffer.allocate(4);
    private ByteBuffer incomingBuffer = lenBuffer;

    private boolean initialized;

    private int sessionTimeout;

    private LinkedBlockingQueue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();


    public NIOServerCnxn(ZooKeeperServer zk, SocketChannel sock,
                         SelectionKey sk, NIOServerCnxnFactory factory) throws IOException {
        this.zkServer = zk;
        this.sock = sock;
        this.sk = sk;
        this.factory = factory;
        if (zk != null) {
            outstandingLimit = zk.getGlobalOutstandingLimit();
        }
        sock.socket().setTcpNoDelay(true);
        sock.socket().setSoLinger(false, -1);
        sk.interestOps(SelectionKey.OP_READ);
    }

    void doIO(SelectionKey k) throws InterruptedException {
        try {
            if (!isSocketOpen()) {
                LOG.warn("trying to do i/o on a null socket for session:0x"
                        + Long.toHexString(sessionId));
                return;
            }
            if (k.isReadable()) {
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new EndOfStreamException(
                            "Unable to read additional data from client sessionid 0x"
                                    + Long.toHexString(sessionId)
                                    + ", likely client has closed socket");
                }
                if (incomingBuffer.remaining() == 0) {
                    boolean isPayload;
                    if (incomingBuffer == lenBuffer) { // start of next request
                        incomingBuffer.flip();
                        isPayload = readLength(k);
                        incomingBuffer.clear();
                    } else {
                        isPayload = true;
                    }
                    if (isPayload) { // not the case for 4letterword
                        readPayload();
                    }
                    else {
                        // four letter words take care
                        // need not do anything else
                        return;
                    }
                }
            }
            if (k.isWritable()) {
                //todo
            }
        } catch (EndOfStreamException e) {
            LOG.warn(e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("EndOfStreamException stack trace", e);
            }
            // expecting close to log session closure
            close();
        } catch (IOException e) {
            LOG.warn("Exception causing close of session 0x"
                    + Long.toHexString(sessionId) + ": " + e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("IOException stack trace", e);
            }
            close();
        }
    }

    private boolean isSocketOpen() {
        return sock.isOpen();
    }

    private boolean readLength(SelectionKey k) throws IOException {
        // Read the length, now get the buffer
        int len = lenBuffer.getInt();
        if (!initialized && checkFourLetterWord(sk, len)) {
            return false;
        }
        if (len < 0 || len > BinaryInputArchive.maxBuffer) {
            throw new IOException("Len error " + len);
        }
        if (!isZKServerRunning()) {
            throw new IOException("ZooKeeperServer not running");
        }
        incomingBuffer = ByteBuffer.allocate(len);
        return true;
    }

    private boolean checkFourLetterWord(final SelectionKey k, final int len){
        //todo
        return false;
    }

    private boolean isZKServerRunning() {
        return zkServer != null && zkServer.isRunning();
    }

    private void readPayload() throws IOException {
        if (incomingBuffer.remaining() != 0) {
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from client sessionid 0x"
                                + Long.toHexString(sessionId)
                                + ", likely client has closed socket");
            }
        }
        if (incomingBuffer.remaining() == 0) {
            packetReceived();
            incomingBuffer.flip();
            if (!initialized) {
                readConnectRequest();
            } else {
                //todo
//                readRequest();
            }
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }
    }

    private void readConnectRequest() throws IOException {
        if (!isZKServerRunning()) {
            throw new IOException("ZooKeeperServer not running");
        }
        zkServer.processConnectRequest(this, incomingBuffer);
        initialized = true;
    }

    @Override
    public InetAddress getSocketAddress() {
        if (sock == null) {
            return null;
        }
        return sock.socket().getInetAddress();
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        if (!sock.isOpen()) {
            return null;
        }
        return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
    }

    @Override
    protected ServerStats serverStats() {
        if (!isZKServerRunning()) {
            return null;
        }
        return zkServer.serverStats();
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    @Override
    public void disableRecv() {
        sk.interestOps(sk.interestOps() & (~SelectionKey.OP_READ));
    }

    @Override
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        this.factory.addSession(sessionId, this);
    }

    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public long getSessionId() {
        return sessionId;
    }

    public void sendBuffer(ByteBuffer bb) {
        try {
            internalSendBuffer(bb);
        } catch(Exception e) {
            LOG.error("Unexpected Exception: ", e);
        }
    }

    private void internalSendBuffer(ByteBuffer bb) {
        if (bb != ServerCnxnFactory.closeConn) {
            // We check if write interest here because if it is NOT set,
            // nothing is queued, so we can try to send the buffer right
            // away without waking up the selector
            if(sk.isValid() &&
                    ((sk.interestOps() & SelectionKey.OP_WRITE) == 0)) {
                try {
                    sock.write(bb);
                } catch (IOException e) {
                    // we are just doing best effort right now
                }
            }
            // if there is nothing left to send, we are done
            if (bb.remaining() == 0) {
                packetSent();
                return;
            }
        }

        synchronized(this.factory){
            sk.selector().wakeup();
            if (LOG.isTraceEnabled()) {
                LOG.trace("Add a buffer to outgoingBuffers, sk " + sk
                        + " is valid: " + sk.isValid());
            }
            outgoingBuffers.add(bb);
            if (sk.isValid()) {
                sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    @Override
    public void close() {

    }
}
