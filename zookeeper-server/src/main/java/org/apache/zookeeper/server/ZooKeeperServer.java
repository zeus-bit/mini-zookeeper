package org.apache.zookeeper.server;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.SessionTracker.SessionExpirer;
import org.apache.zookeeper.server.SessionTracker.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class ZooKeeperServer implements SessionExpirer, ServerStats.Provider {
    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServer.class);

    public static final int DEFAULT_TICK_TIME = 3000;
    private static final long superSecret = 0XB3415C00L;

    private final ServerStats serverStats;
    private final ZooKeeperServerListener listener;

    private ZooKeeperServerShutdownHandler zkShutdownHandler;

    private int tickTime = DEFAULT_TICK_TIME;
    private int minSessionTimeout = -1;
    private int maxSessionTimeout = -1;

    private ServerCnxnFactory serverCnxnFactory;

    private ZKDatabase zkDb;

    protected SessionTracker sessionTracker;

    protected volatile State state = State.INITIAL;

    enum State {
        INITIAL, RUNNING, SHUTDOWN, ERROR;
    }

    public ZooKeeperServer() {
        serverStats = new ServerStats(this);
        listener = new ZooKeeperServerListenerImpl(this);
    }

    public void registerServerShutdownHandler(ZooKeeperServerShutdownHandler zkShutdownHandler) {
        this.zkShutdownHandler = zkShutdownHandler;
    }

    public void setTickTime(int tickTime) {
        LOG.info("tickTime set to " + tickTime);
        this.tickTime = tickTime;
    }

    public void setMinSessionTimeout(int min) {
        LOG.info("minSessionTimeout set to " + min);
        this.minSessionTimeout = min;
    }

    public void setMaxSessionTimeout(int max) {
        LOG.info("maxSessionTimeout set to " + max);
        this.maxSessionTimeout = max;
    }

    public void setServerCnxnFactory(ServerCnxnFactory factory) {
        serverCnxnFactory = factory;
    }

    public void startdata() {
        if (zkDb == null) {
            zkDb = new ZKDatabase();
        }
        //todo
    }

    public synchronized void startup() {
        if (sessionTracker == null) {
            createSessionTracker();
        }
        startSessionTracker();
        setupRequestProcessors();

        setState(State.RUNNING);
        notifyAll();
    }

    private void createSessionTracker() {
        sessionTracker = new SessionTrackerImpl(this, zkDb.getSessionWithTimeOuts(),
                tickTime, 1, listener);
    }

    private void startSessionTracker() {
        ((SessionTrackerImpl)sessionTracker).start();
    }

    private void setupRequestProcessors() {
        //todo
    }


    void setState(State state) {
        this.state = state;
        if (zkShutdownHandler != null) {
            zkShutdownHandler.handle(state);
        } else {
            LOG.debug("ZKShutdownHandler is not registered, so ZooKeeper server "
                    + "won't take any action on ERROR or SHUTDOWN server state changes");
        }
    }

    public int getGlobalOutstandingLimit() {
        String sc = System.getProperty("zookeeper.globalOutstandingLimit");
        int limit;
        try {
            limit = Integer.parseInt(sc);
        } catch (Exception e) {
            limit = 1000;
        }
        return limit;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public ServerStats serverStats() {
        return serverStats;
    }

    public void processConnectRequest(ServerCnxn cnxn, ByteBuffer incomingBuffer) throws IOException {
        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        connReq.deserialize(bia, "connect");
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session establishment request from client "
                    + cnxn.getRemoteSocketAddress()
                    + " client's lastZxid is 0x"
                    + Long.toHexString(connReq.getLastZxidSeen()));
        }
        //todo
//        if (connReq.getLastZxidSeen() > zkDb.dataTree.lastProcessedZxid) {
//            String msg = "Refusing session request for client "
//                    + cnxn.getRemoteSocketAddress()
//                    + " as it has seen zxid 0x"
//                    + Long.toHexString(connReq.getLastZxidSeen())
//                    + " our last zxid is 0x"
//                    + Long.toHexString(getZKDatabase().getDataTreeLastProcessedZxid())
//                    + " client must try another server";
//
//            LOG.info(msg);
//            throw new CloseRequestException(msg);
//        }
        int sessionTimeout = connReq.getTimeOut();
        byte[] passwd = connReq.getPasswd();
        int minSessionTimeout = getMinSessionTimeout();
        if (sessionTimeout < minSessionTimeout) {
            sessionTimeout = minSessionTimeout;
        }
        int maxSessionTimeout = getMaxSessionTimeout();
        if (sessionTimeout > maxSessionTimeout) {
            sessionTimeout = maxSessionTimeout;
        }
        cnxn.setSessionTimeout(sessionTimeout);
        // We don't want to receive any packets until we are sure that the
        // session is setup
        cnxn.disableRecv();
        long sessionId = connReq.getSessionId();
        if (sessionId != 0) {
            long clientSessionId = connReq.getSessionId();
            LOG.info("Client attempting to renew session 0x"
                    + Long.toHexString(clientSessionId)
                    + " at " + cnxn.getRemoteSocketAddress());
            serverCnxnFactory.closeSession(sessionId);
            cnxn.setSessionId(sessionId);
            reopenSession(cnxn, sessionId, passwd, sessionTimeout);
        } else {
            LOG.info("Client attempting to establish new session at "
                    + cnxn.getRemoteSocketAddress());
            createSession(cnxn, passwd, sessionTimeout);
        }
    }

    private int getMinSessionTimeout() {
        return minSessionTimeout == -1 ? tickTime * 2 : minSessionTimeout;
    }

    private int getMaxSessionTimeout() {
        return maxSessionTimeout == -1 ? tickTime * 20 : maxSessionTimeout;
    }

    private long createSession(ServerCnxn cnxn, byte[] passwd, int timeout) {
        long sessionId = sessionTracker.createSession(timeout);
        Random r = new Random(sessionId ^ superSecret);
        r.nextBytes(passwd);
        ByteBuffer to = ByteBuffer.allocate(4);
        to.putInt(timeout);
        cnxn.setSessionId(sessionId);
        //todo
//        submitRequest(cnxn, sessionId, OpCode.createSession, 0, to, null);
        return sessionId;
    }

    private void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
                              int sessionTimeout) throws IOException {
        if (!checkPasswd(sessionId, passwd)) {
            finishSessionInit(cnxn, false);
        } else {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        }
    }

    private boolean checkPasswd(long sessionId, byte[] passwd) {
        return sessionId != 0
                && Arrays.equals(passwd, generatePasswd(sessionId));
    }

    private byte[] generatePasswd(long id) {
        Random r = new Random(id ^ superSecret);
        byte[] p = new byte[16];
        r.nextBytes(p);
        return p;
    }

    private void finishSessionInit(ServerCnxn cnxn, boolean valid) {
        // register with JMX
        //todo
//        try {
//            if (valid) {
//                serverCnxnFactory.registerConnection(cnxn);
//            }
//        } catch (Exception e) {
//            LOG.warn("Failed to register with JMX", e);
//        }

        try {
            ConnectResponse rsp = new ConnectResponse(0, valid ? cnxn.getSessionTimeout()
                    : 0, valid ? cnxn.getSessionId() : 0, // send 0 if session is no
                    // longer valid
                    valid ? generatePasswd(cnxn.getSessionId()) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            cnxn.sendBuffer(bb);

            if (!valid) {
                LOG.info("Invalid session 0x"
                        + Long.toHexString(cnxn.getSessionId())
                        + " for client "
                        + cnxn.getRemoteSocketAddress()
                        + ", probably expired");
                cnxn.sendBuffer(ServerCnxnFactory.closeConn);
            } else {
                //todo
//                LOG.info("Established session 0x"
//                        + Long.toHexString(cnxn.getSessionId())
//                        + " with negotiated timeout " + cnxn.getSessionTimeout()
//                        + " for client "
//                        + cnxn.getRemoteSocketAddress());
//                cnxn.enableRecv();
            }

        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            cnxn.close();
        }
    }

    private void revalidateSession(ServerCnxn cnxn, long sessionId,
                                     int sessionTimeout) {
        boolean rc = sessionTracker.touchSession(sessionId, sessionTimeout);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG,ZooTrace.SESSION_TRACE_MASK,
                    "Session 0x" + Long.toHexString(sessionId) +
                            " is valid: " + rc);
        }
        finishSessionInit(cnxn, rc);
    }

    public void shutdown() {
        shutdown(false);
    }

    protected boolean canShutdown() {
        return state == State.RUNNING || state == State.ERROR;
    }

    public synchronized void shutdown(boolean fullyShutDown) {

    }

    /*************************************************SessionExpirer*********************************/

    @Override
    public void expire(Session session) {
        long sessionId = session.getSessionId();
        LOG.info("Expiring session 0x" + Long.toHexString(sessionId)
                + ", timeout of " + session.getTimeout() + "ms exceeded");
        close(sessionId);
    }

    private void close(long sessionId) {
        //todo
//        submitRequest(null, sessionId, ZooDefs.OpCode.closeSession, 0, null, null);
    }

    @Override
    public long getServerId() {
        return 0;
    }

    /**********************************************ServerStats.Provider******************************/

    private final AtomicInteger requestsInProcess = new AtomicInteger(0);

    @Override
    public long getOutstandingRequests() {
        return getInProcess();
    }

    public int getInProcess() {
        return requestsInProcess.get();
    }

    @Override
    public long getLastProcessedZxid() {
        return zkDb.getDataTreeLastProcessedZxid();
    }

    @Override
    public String getState() {
        return "standalone";
    }

    @Override
    public int getNumAliveConnections() {
        return serverCnxnFactory.getNumAliveConnections();
    }
}
