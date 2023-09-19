package org.apache.zookeeper;

import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.AsyncCallback.*;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.ZooKeeper.WatchRegistration;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.proto.*;
import org.apache.zookeeper.server.ZooKeeperThread;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class manages the socket i/o for the client. ClientCnxn maintains a list
 * of available servers to connect to and "transparently" switches servers it is
 * connected to as needed.
 *
 */
public class ClientCnxn {

    private static final int SET_WATCHES_MAX_LENGTH = 128 * 1024;

    private static boolean disableAutoWatchReset;
    static {
        // this var should not be public, but otw there is no easy way
        // to test
        disableAutoWatchReset =
                Boolean.getBoolean("zookeeper.disableAutoWatchReset");
    }

    static class AuthData {
        AuthData(String scheme, byte data[]) {
            this.scheme = scheme;
            this.data = data;
        }

        String scheme;

        byte data[];
    }

    private final CopyOnWriteArraySet<AuthData> authInfo = new CopyOnWriteArraySet<AuthData>();

    /**
     * These are the packets that have been sent and are waiting for a response.
     */
    private final LinkedList<Packet> pendingQueue = new LinkedList<Packet>();

    /**
     * These are the packets that need to be sent.
     */
    private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

    private int connectTimeout;

    /**
     * The timeout in ms the client negotiated with the server. This is the
     * "real" timeout, not the timeout request by the client (which may have
     * been increased/decreased by the server which applies bounds to this
     * value.
     */
    private volatile int negotiatedSessionTimeout;

    private int readTimeout;

    private final int sessionTimeout;

    private final ZooKeeper zooKeeper;

    private final ClientWatchManager watcher;

    private long sessionId;

    private byte sessionPasswd[] = new byte[16];

    /**
     * If true, the connection is allowed to go to r-o mode. This field's value
     * is sent, besides other data, during session creation handshake. If the
     * server on the other side of the wire is partitioned it'll accept
     * read-only clients only.
     */
    private boolean readOnly;

    final String chrootPath;

    final SendThread sendThread;

    final EventThread eventThread;

    /**
     * Set to true when close is called. Latches the connection such that we
     * don't attempt to re-connect to the server if in the middle of closing the
     * connection (client sends session disconnect to server as part of close
     * operation)
     */
    private volatile boolean closing = false;

    /**
     * A set of ZooKeeper hosts this client could connect to.
     */
    private final HostProvider hostProvider;

    /**
     * Is set to true when a connection to a r/w server is established for the
     * first time; never changed afterwards.
     * <p>
     * Is used to handle situations when client without sessionId connects to a
     * read-only server. Such client receives "fake" sessionId from read-only
     * server, but this sessionId is invalid for other servers. So when such
     * client finds a r/w server, it sends 0 instead of fake sessionId during
     * connection handshake and establishes new, valid session.
     * <p>
     * If this field is false (which implies we haven't seen r/w server before)
     * then non-zero sessionId is fake, otherwise it is valid.
     */
    volatile boolean seenRwServerBefore = false;


    public long getSessionId() {
        return sessionId;
    }

    public byte[] getSessionPasswd() {
        return sessionPasswd;
    }

    public int getSessionTimeout() {
        return negotiatedSessionTimeout;
    }

    static class Packet {
        RequestHeader requestHeader;

        ReplyHeader replyHeader;

        Record request;

        Record response;

        ByteBuffer bb;

        /** Client's view of the path (may differ due to chroot) **/
        String clientPath;
        /** Servers's view of the path (may differ due to chroot) **/
        String serverPath;

        boolean finished;

        AsyncCallback cb;

        Object ctx;

        WatchRegistration watchRegistration;

        public boolean readOnly;

        /** Convenience ctor */
        Packet(RequestHeader requestHeader, ReplyHeader replyHeader,
               Record request, Record response,
               WatchRegistration watchRegistration) {
            this(requestHeader, replyHeader, request, response,
                    watchRegistration, false);
        }

        Packet(RequestHeader requestHeader, ReplyHeader replyHeader,
               Record request, Record response,
               WatchRegistration watchRegistration, boolean readOnly) {

            this.requestHeader = requestHeader;
            this.replyHeader = replyHeader;
            this.request = request;
            this.response = response;
            this.readOnly = readOnly;
            this.watchRegistration = watchRegistration;
        }

        public void createBB() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
                boa.writeInt(-1, "len"); // We'll fill this in later
                if (requestHeader != null) {
                    requestHeader.serialize(boa, "header");
                }
                if (request instanceof ConnectRequest) {
                    request.serialize(boa, "connect");
                    // append "am-I-allowed-to-be-readonly" flag
                    boa.writeBool(readOnly, "readOnly");
                } else if (request != null) {
                    request.serialize(boa, "request");
                }
                baos.close();
                this.bb = ByteBuffer.wrap(baos.toByteArray());
                this.bb.putInt(this.bb.capacity() - 4);
                this.bb.rewind();
            } catch (IOException e) {
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            sb.append("clientPath:" + clientPath);
            sb.append(" serverPath:" + serverPath);
            sb.append(" finished:" + finished);

            sb.append(" header:: " + requestHeader);
            sb.append(" replyHeader:: " + replyHeader);
            sb.append(" request:: " + request);
            sb.append(" response:: " + response);

            // jute toString is horrible, remove unnecessary newlines
            return sb.toString().replaceAll("\r*\n+", " ");
        }
    }

    /**
     * Creates a connection object. The actual network connect doesn't get
     * established until needed. The start() instance method must be called
     * subsequent to construction.
     *
     * @param chrootPath - the chroot of this client. Should be removed from this Class in ZOOKEEPER-838
     * @param hostProvider
     *                the list of ZooKeeper servers to connect to
     * @param sessionTimeout
     *                the timeout for connections.
     * @param zooKeeper
     *                the zookeeper object that this connection is related to.
     * @param watcher watcher for this connection
     * @param clientCnxnSocket
     *                the socket implementation used (e.g. NIO/Netty)
     * @param canBeReadOnly
     *                whether the connection is allowed to go to read-only
     *                mode in case of partitioning
     * @throws IOException
     */
    public ClientCnxn(String chrootPath, HostProvider hostProvider, int sessionTimeout, ZooKeeper zooKeeper,
                      ClientWatchManager watcher, ClientCnxnSocket clientCnxnSocket, boolean canBeReadOnly)
            throws IOException {
        this(chrootPath, hostProvider, sessionTimeout, zooKeeper, watcher,
                clientCnxnSocket, 0, new byte[16], canBeReadOnly);
    }

    /**
     * Creates a connection object. The actual network connect doesn't get
     * established until needed. The start() instance method must be called
     * subsequent to construction.
     *
     * @param chrootPath - the chroot of this client. Should be removed from this Class in ZOOKEEPER-838
     * @param hostProvider
     *                the list of ZooKeeper servers to connect to
     * @param sessionTimeout
     *                the timeout for connections.
     * @param zooKeeper
     *                the zookeeper object that this connection is related to.
     * @param watcher watcher for this connection
     * @param clientCnxnSocket
     *                the socket implementation used (e.g. NIO/Netty)
     * @param sessionId session id if re-establishing session
     * @param sessionPasswd session passwd if re-establishing session
     * @param canBeReadOnly
     *                whether the connection is allowed to go to read-only
     *                mode in case of partitioning
     * @throws IOException
     */
    public ClientCnxn(String chrootPath, HostProvider hostProvider, int sessionTimeout, ZooKeeper zooKeeper,
                      ClientWatchManager watcher, ClientCnxnSocket clientCnxnSocket,
                      long sessionId, byte[] sessionPasswd, boolean canBeReadOnly) {
        this.zooKeeper = zooKeeper;
        this.watcher = watcher;
        this.sessionId = sessionId;
        this.sessionPasswd = sessionPasswd;
        this.sessionTimeout = sessionTimeout;
        this.hostProvider = hostProvider;
        this.chrootPath = chrootPath;

        connectTimeout = sessionTimeout / hostProvider.size();
        readTimeout = sessionTimeout * 2 / 3;
        readOnly = canBeReadOnly;

        sendThread = new SendThread(clientCnxnSocket);
        eventThread = new EventThread();

    }

    /**
     * tests use this to check on reset of watches
     * @return if the auto reset of watches are disabled
     */
    public static boolean getDisableAutoResetWatch() {
        return disableAutoWatchReset;
    }
    /**
     * tests use this to set the auto reset
     * @param b the value to set disable watches to
     */
    public static void setDisableAutoResetWatch(boolean b) {
        disableAutoWatchReset = b;
    }
    public void start() {
        sendThread.start();
        eventThread.start();
    }

    private Object eventOfDeath = new Object();

    private static class WatcherSetEventPair {
        private final Set<Watcher> watchers;
        private final WatchedEvent event;

        public WatcherSetEventPair(Set<Watcher> watchers, WatchedEvent event) {
            this.watchers = watchers;
            this.event = event;
        }
    }

    /**
     * Guard against creating "-EventThread-EventThread-EventThread-..." thread
     * names when ZooKeeper object is being created from within a watcher.
     * See ZOOKEEPER-795 for details.
     */
    private static String makeThreadName(String suffix) {
        String name = Thread.currentThread().getName().
                replaceAll("-EventThread", "");
        return name + suffix;
    }

    class EventThread extends ZooKeeperThread {
        private final LinkedBlockingQueue<Object> waitingEvents =
                new LinkedBlockingQueue<Object>();

        /** This is really the queued session state until the event
         * thread actually processes the event and hands it to the watcher.
         * But for all intents and purposes this is the state.
         */
        private volatile KeeperState sessionState = KeeperState.Disconnected;

        private volatile boolean wasKilled = false;
        private volatile boolean isRunning = false;

        EventThread() {
            super(makeThreadName("-EventThread"));
            setDaemon(true);
        }

        public void queueEvent(WatchedEvent event) {
            if (event.getType() == EventType.None
                    && sessionState == event.getState()) {
                return;
            }
            sessionState = event.getState();

            // materialize the watchers based on the event
            WatcherSetEventPair pair = new WatcherSetEventPair(
                    watcher.materialize(event.getState(), event.getType(),
                            event.getPath()),
                    event);
            // queue the pair (watch set & event) for later processing
            waitingEvents.add(pair);
        }

        public void queuePacket(Packet packet) {
            if (wasKilled) {
                synchronized (waitingEvents) {
                    if (isRunning) waitingEvents.add(packet);
                    else processEvent(packet);
                }
            } else {
                waitingEvents.add(packet);
            }
        }

        public void queueEventOfDeath() {
            waitingEvents.add(eventOfDeath);
        }

        @Override
        public void run() {
            try {
                isRunning = true;
                while (true) {
                    Object event = waitingEvents.take();
                    if (event == eventOfDeath) {
                        wasKilled = true;
                    } else {
                        processEvent(event);
                    }
                    if (wasKilled)
                        synchronized (waitingEvents) {
                            if (waitingEvents.isEmpty()) {
                                isRunning = false;
                                break;
                            }
                        }
                }
            } catch (InterruptedException e) {
            }

        }

        private void processEvent(Object event) {
            try {
                if (event instanceof WatcherSetEventPair) {
                    // each watcher will process the event
                    WatcherSetEventPair pair = (WatcherSetEventPair) event;
                    for (Watcher watcher : pair.watchers) {
                        try {
                            watcher.process(pair.event);
                        } catch (Throwable t) {
                        }
                    }
                } else {
                    Packet p = (Packet) event;
                    int rc = 0;
                    String clientPath = p.clientPath;
                    if (p.replyHeader.getErr() != 0) {
                        rc = p.replyHeader.getErr();
                    }
                    if (p.cb == null) {
                    } else if (p.response instanceof ExistsResponse
                            || p.response instanceof SetDataResponse
                            || p.response instanceof SetACLResponse) {
                        StatCallback cb = (StatCallback) p.cb;
                        if (rc == 0) {
                            if (p.response instanceof ExistsResponse) {
                                cb.processResult(rc, clientPath, p.ctx,
                                        ((ExistsResponse) p.response)
                                                .getStat());
                            } else if (p.response instanceof SetDataResponse) {
                                cb.processResult(rc, clientPath, p.ctx,
                                        ((SetDataResponse) p.response)
                                                .getStat());
                            } else if (p.response instanceof SetACLResponse) {
                                cb.processResult(rc, clientPath, p.ctx,
                                        ((SetACLResponse) p.response)
                                                .getStat());
                            }
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    } else if (p.response instanceof GetDataResponse) {
                        DataCallback cb = (DataCallback) p.cb;
                        GetDataResponse rsp = (GetDataResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getData(), rsp.getStat());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null,
                                    null);
                        }
                    } else if (p.response instanceof GetACLResponse) {
                        ACLCallback cb = (ACLCallback) p.cb;
                        GetACLResponse rsp = (GetACLResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getAcl(), rsp.getStat());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null,
                                    null);
                        }
                    } else if (p.response instanceof GetChildrenResponse) {
                        ChildrenCallback cb = (ChildrenCallback) p.cb;
                        GetChildrenResponse rsp = (GetChildrenResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getChildren());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    } else if (p.response instanceof GetChildren2Response) {
                        Children2Callback cb = (Children2Callback) p.cb;
                        GetChildren2Response rsp = (GetChildren2Response) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx, rsp
                                    .getChildren(), rsp.getStat());
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null, null);
                        }
                    } else if (p.response instanceof CreateResponse) {
                        StringCallback cb = (StringCallback) p.cb;
                        CreateResponse rsp = (CreateResponse) p.response;
                        if (rc == 0) {
                            cb.processResult(rc, clientPath, p.ctx,
                                    (chrootPath == null
                                            ? rsp.getPath()
                                            : rsp.getPath()
                                            .substring(chrootPath.length())));
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    } else if (p.response instanceof MultiResponse) {
                        MultiCallback cb = (MultiCallback) p.cb;
                        MultiResponse rsp = (MultiResponse) p.response;
                        if (rc == 0) {
                            List<OpResult> results = rsp.getResultList();
                            int newRc = rc;
                            for (OpResult result : results) {
                                if (result instanceof ErrorResult
                                        && KeeperException.Code.OK.intValue()
                                        != (newRc = ((ErrorResult) result).getErr())) {
                                    break;
                                }
                            }
                            cb.processResult(newRc, clientPath, p.ctx, results);
                        } else {
                            cb.processResult(rc, clientPath, p.ctx, null);
                        }
                    }  else if (p.cb instanceof VoidCallback) {
                        VoidCallback cb = (VoidCallback) p.cb;
                        cb.processResult(rc, clientPath, p.ctx);
                    }
                }
            } catch (Throwable t) {
            }
        }
    }

    private void finishPacket(Packet p) {
        if (p.watchRegistration != null) {
            p.watchRegistration.register(p.replyHeader.getErr());
        }

        if (p.cb == null) {
            synchronized (p) {
                p.finished = true;
                p.notifyAll();
            }
        } else {
            p.finished = true;
            eventThread.queuePacket(p);
        }
    }

    private void conLossPacket(Packet p) {
        if (p.replyHeader == null) {
            return;
        }
        switch (state) {
            case AUTH_FAILED:
                p.replyHeader.setErr(KeeperException.Code.AUTHFAILED.intValue());
                break;
            case CLOSED:
                p.replyHeader.setErr(KeeperException.Code.SESSIONEXPIRED.intValue());
                break;
            default:
                p.replyHeader.setErr(KeeperException.Code.CONNECTIONLOSS.intValue());
        }
        finishPacket(p);
    }

    private volatile long lastZxid;

    public long getLastZxid() {
        return lastZxid;
    }

    static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -5438877188796231422L;

        public EndOfStreamException(String msg) {
            super(msg);
        }

        @Override
        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    private static class SessionTimeoutException extends IOException {
        private static final long serialVersionUID = 824482094072071178L;

        public SessionTimeoutException(String msg) {
            super(msg);
        }
    }

    private static class SessionExpiredException extends IOException {
        private static final long serialVersionUID = -1388816932076193249L;

        public SessionExpiredException(String msg) {
            super(msg);
        }
    }

    private static class RWServerFoundException extends IOException {
        private static final long serialVersionUID = 90431199887158758L;

        public RWServerFoundException(String msg) {
            super(msg);
        }
    }

    public static final int packetLen = Integer.getInteger("jute.maxbuffer",
            4096 * 1024);

    /**
     * This class services the outgoing request queue and generates the heart
     * beats. It also spawns the ReadThread.
     */
    class SendThread extends ZooKeeperThread {
        private long lastPingSentNs;
        private final ClientCnxnSocket clientCnxnSocket;
        private Random r = new Random(System.nanoTime());
        private boolean isFirstConnect = true;

//        void readResponse(ByteBuffer incomingBuffer) throws IOException {
//            ByteBufferInputStream bbis = new ByteBufferInputStream(
//                    incomingBuffer);
//            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
//            ReplyHeader replyHdr = new ReplyHeader();
//
//            replyHdr.deserialize(bbia, "header");
//            if (replyHdr.getXid() == -2) {
//                // -2 is the xid for pings
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Got ping response for sessionid: 0x"
//                            + Long.toHexString(sessionId)
//                            + " after "
//                            + ((System.nanoTime() - lastPingSentNs) / 1000000)
//                            + "ms");
//                }
//                return;
//            }
//            if (replyHdr.getXid() == -4) {
//                // -4 is the xid for AuthPacket
//                if(replyHdr.getErr() == KeeperException.Code.AUTHFAILED.intValue()) {
//                    state = States.AUTH_FAILED;
//                    eventThread.queueEvent( new WatchedEvent(Watcher.Event.EventType.None,
//                            Watcher.Event.KeeperState.AuthFailed, null) );
//                }
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Got auth sessionid:0x"
//                            + Long.toHexString(sessionId));
//                }
//                return;
//            }
//            if (replyHdr.getXid() == -1) {
//                // -1 means notification
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Got notification sessionid:0x"
//                            + Long.toHexString(sessionId));
//                }
//                WatcherEvent event = new WatcherEvent();
//                event.deserialize(bbia, "response");
//
//                // convert from a server path to a client path
//                if (chrootPath != null) {
//                    String serverPath = event.getPath();
//                    if(serverPath.compareTo(chrootPath)==0)
//                        event.setPath("/");
//                    else if (serverPath.length() > chrootPath.length())
//                        event.setPath(serverPath.substring(chrootPath.length()));
//                    else {
//                        LOG.warn("Got server path " + event.getPath()
//                                + " which is too short for chroot path "
//                                + chrootPath);
//                    }
//                }
//
//                WatchedEvent we = new WatchedEvent(event);
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Got " + we + " for sessionid 0x"
//                            + Long.toHexString(sessionId));
//                }
//
//                eventThread.queueEvent( we );
//                return;
//            }
//
//            // If SASL authentication is currently in progress, construct and
//            // send a response packet immediately, rather than queuing a
//            // response as with other packets.
//            if (clientTunneledAuthenticationInProgress()) {
//                GetSASLRequest request = new GetSASLRequest();
//                request.deserialize(bbia,"token");
//                zooKeeperSaslClient.respondToServer(request.getToken(),
//                        ClientCnxn.this);
//                return;
//            }
//
//            Packet packet;
//            synchronized (pendingQueue) {
//                if (pendingQueue.size() == 0) {
//                    throw new IOException("Nothing in the queue, but got "
//                            + replyHdr.getXid());
//                }
//                packet = pendingQueue.remove();
//            }
//            /*
//             * Since requests are processed in order, we better get a response
//             * to the first request!
//             */
//            try {
//                if (packet.requestHeader.getXid() != replyHdr.getXid()) {
//                    packet.replyHeader.setErr(
//                            KeeperException.Code.CONNECTIONLOSS.intValue());
//                    throw new IOException("Xid out of order. Got Xid "
//                            + replyHdr.getXid() + " with err " +
//                            + replyHdr.getErr() +
//                            " expected Xid "
//                            + packet.requestHeader.getXid()
//                            + " for a packet with details: "
//                            + packet );
//                }
//
//                packet.replyHeader.setXid(replyHdr.getXid());
//                packet.replyHeader.setErr(replyHdr.getErr());
//                packet.replyHeader.setZxid(replyHdr.getZxid());
//                if (replyHdr.getZxid() > 0) {
//                    lastZxid = replyHdr.getZxid();
//                }
//                if (packet.response != null && replyHdr.getErr() == 0) {
//                    packet.response.deserialize(bbia, "response");
//                }
//
//                if (LOG.isDebugEnabled()) {
//                    LOG.debug("Reading reply sessionid:0x"
//                            + Long.toHexString(sessionId) + ", packet:: " + packet);
//                }
//            } finally {
//                finishPacket(packet);
//            }
//        }

        SendThread(ClientCnxnSocket clientCnxnSocket) {
            super(makeThreadName("-SendThread()"));
            state = States.CONNECTING;
            this.clientCnxnSocket = clientCnxnSocket;
            setDaemon(true);
        }

        // TODO: can not name this method getState since Thread.getState()
        // already exists
        // It would be cleaner to make class SendThread an implementation of
        // Runnable
        /**
         * Used by ClientCnxnSocket
         *
         * @return
         */
        ZooKeeper.States getZkState() {
            return state;
        }

        ClientCnxnSocket getClientCnxnSocket() {
            return clientCnxnSocket;
        }

        void primeConnection() throws IOException {
            isFirstConnect = false;
            long sessId = (seenRwServerBefore) ? sessionId : 0;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid,
                    sessionTimeout, sessId, sessionPasswd);
            synchronized (outgoingQueue) {
                // We add backwards since we are pushing into the front
                // Only send if there's a pending watch
                // TODO: here we have the only remaining use of zooKeeper in
                // this class. It's to be eliminated!
                outgoingQueue.addFirst(new Packet(null, null, conReq,
                        null, null, readOnly));
            }
            clientCnxnSocket.enableReadWriteOnly();
        }

        private List<String> prependChroot(List<String> paths) {
            if (chrootPath != null && !paths.isEmpty()) {
                for (int i = 0; i < paths.size(); ++i) {
                    String clientPath = paths.get(i);
                    String serverPath;
                    // handle clientPath = "/"
                    if (clientPath.length() == 1) {
                        serverPath = chrootPath;
                    } else {
                        serverPath = chrootPath + clientPath;
                    }
                    paths.set(i, serverPath);
                }
            }
            return paths;
        }

        private void sendPing() {
            lastPingSentNs = System.nanoTime();
            RequestHeader h = new RequestHeader(-2, OpCode.ping);
            queuePacket(h, null, null, null, null, null, null, null, null);
        }

        private InetSocketAddress rwServerAddress = null;

        private final static int minPingRwTimeout = 100;

        private final static int maxPingRwTimeout = 60000;

        private int pingRwTimeout = minPingRwTimeout;

        // Set to true if and only if constructor of ZooKeeperSaslClient
        // throws a LoginException: see startConnect() below.
        private boolean saslLoginFailed = false;

        private void startConnect(InetSocketAddress addr) throws IOException {
            // initializing it for new connection
            saslLoginFailed = false;
            state = States.CONNECTING;

            setName(getName().replaceAll("\\(.*\\)",
                    "(" + addr.getHostName() + ":" + addr.getPort() + ")"));
            logStartConnect(addr);

            clientCnxnSocket.connect(addr);
        }

        private void logStartConnect(InetSocketAddress addr) {
        }

        private static final String RETRY_CONN_MSG =
                ", closing socket connection and attempting reconnect";

        @Override
        public void run() {
            clientCnxnSocket.introduce(this,sessionId);
            clientCnxnSocket.updateNow();
            clientCnxnSocket.updateLastSendAndHeard();
            int to;
            long lastPingRwServer = System.nanoTime()/1000000;
            final int MAX_SEND_PING_INTERVAL = 10000; //10 seconds
            InetSocketAddress serverAddress = null;
            while (state.isAlive()) {
                try {
                    if (!clientCnxnSocket.isConnected()) {
                        if(!isFirstConnect){
                            try {
                                sleep(r.nextInt(1000));
                            } catch (InterruptedException e) {
                            }
                        }
                        // don't re-establish connection if we are closing
                        if (closing || !state.isAlive()) {
                            break;
                        }
                        if (rwServerAddress != null) {
                            serverAddress = rwServerAddress;
                            rwServerAddress = null;
                        } else {
                            serverAddress = hostProvider.next(1000);
                        }
                        startConnect(serverAddress);
                        clientCnxnSocket.updateLastSendAndHeard();
                    }

                    if (state.isConnected()) {
                        to = readTimeout - clientCnxnSocket.getIdleRecv();
                    } else {
                        to = connectTimeout - clientCnxnSocket.getIdleRecv();
                    }

                    if (to <= 0) {
                        String warnInfo;
                        warnInfo = "Client session timed out, have not heard from server in "
                                + clientCnxnSocket.getIdleRecv()
                                + "ms"
                                + " for sessionid 0x"
                                + Long.toHexString(sessionId);
                        throw new SessionTimeoutException(warnInfo);
                    }
                    if (state.isConnected()) {
                        //1000(1 second) is to prevent race condition missing to send the second ping
                        //also make sure not to send too many pings when readTimeout is small
                        int timeToNextPing = readTimeout / 2 - clientCnxnSocket.getIdleSend() -
                                ((clientCnxnSocket.getIdleSend() > 1000) ? 1000 : 0);
                        //send a ping request either time is due or no packet sent out within MAX_SEND_PING_INTERVAL
                        if (timeToNextPing <= 0 || clientCnxnSocket.getIdleSend() > MAX_SEND_PING_INTERVAL) {
                            sendPing();
                            clientCnxnSocket.updateLastSend();
                        } else {
                            if (timeToNextPing < to) {
                                to = timeToNextPing;
                            }
                        }
                    }

                    clientCnxnSocket.doTransport(to, pendingQueue, outgoingQueue, ClientCnxn.this);
                } catch (Throwable e) {
                    if (closing) {
                        break;
                    } else {
                        cleanup();
                        if (state.isAlive()) {
                            eventThread.queueEvent(new WatchedEvent(
                                    Event.EventType.None,
                                    Event.KeeperState.Disconnected,
                                    null));
                        }
                        clientCnxnSocket.updateNow();
                        clientCnxnSocket.updateLastSendAndHeard();
                    }
                }
            }
            cleanup();
            clientCnxnSocket.close();
            if (state.isAlive()) {
                eventThread.queueEvent(new WatchedEvent(Event.EventType.None,
                        Event.KeeperState.Disconnected, null));
            }
        }

        private void pingRwServer() throws RWServerFoundException, UnknownHostException {
            String result = null;
            InetSocketAddress addr = hostProvider.next(0);

            Socket sock = null;
            BufferedReader br = null;
            try {
                sock = new Socket(addr.getHostName(), addr.getPort());
                sock.setSoLinger(false, -1);
                sock.setSoTimeout(1000);
                sock.setTcpNoDelay(true);
                sock.getOutputStream().write("isro".getBytes());
                sock.getOutputStream().flush();
                sock.shutdownOutput();
                br = new BufferedReader(
                        new InputStreamReader(sock.getInputStream()));
                result = br.readLine();
            } catch (ConnectException e) {
                // ignore, this just means server is not up
            } catch (IOException e) {
                // some unexpected error, warn about it
            } finally {
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (IOException e) {
                    }
                }
                if (br != null) {
                    try {
                        br.close();
                    } catch (IOException e) {
                    }
                }
            }

            if ("rw".equals(result)) {
                pingRwTimeout = minPingRwTimeout;
                // save the found address so that it's used during the next
                // connection attempt
                rwServerAddress = addr;
                throw new RWServerFoundException("Majority server found at "
                        + addr.getHostName() + ":" + addr.getPort());
            }
        }

        private void cleanup() {
            clientCnxnSocket.cleanup();
            synchronized (pendingQueue) {
                for (Packet p : pendingQueue) {
                    conLossPacket(p);
                }
                pendingQueue.clear();
            }
            synchronized (outgoingQueue) {
                for (Packet p : outgoingQueue) {
                    conLossPacket(p);
                }
                outgoingQueue.clear();
            }
        }

        /**
         * Callback invoked by the ClientCnxnSocket once a connection has been
         * established.
         *
         * @param _negotiatedSessionTimeout
         * @param _sessionId
         * @param _sessionPasswd
         * @param isRO
         * @throws IOException
         */
        void onConnected(int _negotiatedSessionTimeout, long _sessionId,
                         byte[] _sessionPasswd, boolean isRO) throws IOException {
            negotiatedSessionTimeout = _negotiatedSessionTimeout;
            if (negotiatedSessionTimeout <= 0) {
                state = States.CLOSED;

                eventThread.queueEvent(new WatchedEvent(
                        Watcher.Event.EventType.None,
                        Watcher.Event.KeeperState.Expired, null));
                eventThread.queueEventOfDeath();

                String warnInfo;
                warnInfo = "Unable to reconnect to ZooKeeper service, session 0x"
                        + Long.toHexString(sessionId) + " has expired";
                throw new SessionExpiredException(warnInfo);
            }
            if (!readOnly && isRO) {
            }
            readTimeout = negotiatedSessionTimeout * 2 / 3;
            connectTimeout = negotiatedSessionTimeout / hostProvider.size();
            hostProvider.onConnected();
            sessionId = _sessionId;
            sessionPasswd = _sessionPasswd;
            state = (isRO) ?
                    States.CONNECTEDREADONLY : States.CONNECTED;
            seenRwServerBefore |= !isRO;
            KeeperState eventState = (isRO) ?
                    KeeperState.ConnectedReadOnly : KeeperState.SyncConnected;
            eventThread.queueEvent(new WatchedEvent(
                    Watcher.Event.EventType.None,
                    eventState, null));
        }

        void close() {
            state = States.CLOSED;
            clientCnxnSocket.wakeupCnxn();
        }

        void testableCloseSocket() throws IOException {
            clientCnxnSocket.testableCloseSocket();
        }

        public boolean clientTunneledAuthenticationInProgress() {
            return false;
        }

        public void sendPacket(Packet p) throws IOException {
            clientCnxnSocket.sendPacket(p);
        }
    }

    /**
     * Shutdown the send/event threads. This method should not be called
     * directly - rather it should be called as part of close operation. This
     * method is primarily here to allow the tests to verify disconnection
     * behavior.
     */
    public void disconnect() {
        sendThread.close();
        eventThread.queueEventOfDeath();
    }

    /**
     * Close the connection, which includes; send session disconnect to the
     * server, shutdown the send/event threads.
     *
     * @throws IOException
     */
    public void close() throws IOException {
        try {
            RequestHeader h = new RequestHeader();
            h.setType(ZooDefs.OpCode.closeSession);

            submitRequest(h, null, null, null);
        } catch (InterruptedException e) {
            // ignore, close the send/event threads
        } finally {
            disconnect();
        }
    }

    private int xid = 1;

    // @VisibleForTesting
    volatile States state = States.NOT_CONNECTED;

    /*
     * getXid() is called externally by ClientCnxnNIO::doIO() when packets are sent from the outgoingQueue to
     * the server. Thus, getXid() must be public.
     */
    synchronized public int getXid() {
        return xid++;
    }

    public ReplyHeader submitRequest(RequestHeader h, Record request,
                                     Record response, WatchRegistration watchRegistration)
            throws InterruptedException {
        ReplyHeader r = new ReplyHeader();
        Packet packet = queuePacket(h, r, request, response, null, null, null,
                null, watchRegistration);
        synchronized (packet) {
            while (!packet.finished) {
                packet.wait();
            }
        }
        return r;
    }

    public void enableWrite() {
        sendThread.getClientCnxnSocket().enableWrite();
    }

    public void sendPacket(Record request, Record response, AsyncCallback cb, int opCode)
            throws IOException {
        // Generate Xid now because it will be sent immediately,
        // by call to sendThread.sendPacket() below.
        int xid = getXid();
        RequestHeader h = new RequestHeader();
        h.setXid(xid);
        h.setType(opCode);

        ReplyHeader r = new ReplyHeader();
        r.setXid(xid);

        Packet p = new Packet(h, r, request, response, null, false);
        p.cb = cb;
        sendThread.sendPacket(p);
    }

    Packet queuePacket(RequestHeader h, ReplyHeader r, Record request,
                       Record response, AsyncCallback cb, String clientPath,
                       String serverPath, Object ctx, WatchRegistration watchRegistration)
    {
        Packet packet = null;

        // Note that we do not generate the Xid for the packet yet. It is
        // generated later at send-time, by an implementation of ClientCnxnSocket::doIO(),
        // where the packet is actually sent.
        synchronized (outgoingQueue) {
            packet = new Packet(h, r, request, response, watchRegistration);
            packet.cb = cb;
            packet.ctx = ctx;
            packet.clientPath = clientPath;
            packet.serverPath = serverPath;
            if (!state.isAlive() || closing) {
                conLossPacket(packet);
            } else {
                // If the client is asking to close the session then
                // mark as closing
                if (h.getType() == OpCode.closeSession) {
                    closing = true;
                }
                outgoingQueue.add(packet);
            }
        }
        sendThread.getClientCnxnSocket().wakeupCnxn();
        return packet;
    }

    States getState() {
        return state;
    }
}
