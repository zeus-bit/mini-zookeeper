package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class ServerCnxnFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ServerCnxnFactory.class);

    public static final String ZOOKEEPER_SERVER_CNXN_FACTORY = "zookeeper.serverCnxnFactory";

    protected ZooKeeperServer zkServer;

    protected final HashSet<ServerCnxn> cnxns = new HashSet<ServerCnxn>();

    // sessionMap is used to speed up closeSession()
    protected final ConcurrentMap<Long, ServerCnxn> sessionMap =
            new ConcurrentHashMap<Long, ServerCnxn>();

    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    static public ServerCnxnFactory createFactory() throws IOException {
        String serverCnxnFactoryName =
                System.getProperty(ZOOKEEPER_SERVER_CNXN_FACTORY);
        if (serverCnxnFactoryName == null) {
            serverCnxnFactoryName = NIOServerCnxnFactory.class.getName();
        }
        try {
            ServerCnxnFactory serverCnxnFactory = (ServerCnxnFactory) Class.forName(serverCnxnFactoryName)
                    .getDeclaredConstructor().newInstance();
            LOG.info("Using {} as server connection factory", serverCnxnFactoryName);
            return serverCnxnFactory;
        } catch (Exception e) {
            throw new IOException("Couldn't instantiate "
                    + serverCnxnFactoryName, e);
        }
    }

    public abstract void configure(InetSocketAddress addr,
                                   int maxClientCnxns) throws IOException;

    public abstract void startup(ZooKeeperServer zkServer)
            throws IOException, InterruptedException;

    public abstract void start();

    final public void setZooKeeperServer(ZooKeeperServer zk) {
        this.zkServer = zk;
        if (zk != null) {
            zk.setServerCnxnFactory(this);
        }
    }

    abstract void closeAll();

    public int getNumAliveConnections() {
        synchronized(cnxns) {
            return cnxns.size();
        }
    }

    public abstract void closeSession(long sessionId);

    public void addSession(long sessionId, ServerCnxn cnxn) {
        sessionMap.put(sessionId, cnxn);
    }
}
