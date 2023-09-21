package org.apache.zookeeper;

import org.apache.zookeeper.client.ConnectStringParser;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class ZooKeeper {

    private static final Logger LOG;
    static {
        LOG = LoggerFactory.getLogger(ZooKeeper.class);
        Environment.logEnv("Client environment:", LOG);
    }

    private final ZKWatchManager watchManager = new ZKWatchManager();
    private final ClientCnxn cnxn;

    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher) throws IOException {
        LOG.info("Initiating client connection, connectString=" + connectString
                + " sessionTimeout=" + sessionTimeout + " watcher=" + watcher);

        ConnectStringParser connectStringParser = new ConnectStringParser(
                connectString);
        HostProvider hostProvider = new StaticHostProvider(
                connectStringParser.getServerAddresses());
        cnxn = new ClientCnxn(connectStringParser.getChrootPath(),
                hostProvider, sessionTimeout, this, watchManager,
                getClientCnxnSocket());
        cnxn.start();
    }

    private static ClientCnxnSocket getClientCnxnSocket() throws IOException {
        String clientCnxnSocketName = ClientCnxnSocketNIO.class.getName();
        try {
            return (ClientCnxnSocket) Class.forName(clientCnxnSocketName).getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new IOException("Couldn't instantiate "
                    + clientCnxnSocketName, e);
        }
    }

    public enum States {
        CONNECTING, CONNECTED, CLOSED, AUTH_FAILED, NOT_CONNECTED;

        public boolean isAlive() {
            return this != CLOSED && this != AUTH_FAILED;
        }

        public boolean isConnected() {
            return this == CONNECTED;
        }
    }

    private static class ZKWatchManager implements ClientWatchManager {
        @Override
        public Set<Watcher> materialize(Watcher.Event.KeeperState state, Watcher.Event.EventType type, String clientPath) {
            return new HashSet<Watcher>();
        }
    }

    abstract class WatchRegistration {

        public void register(int rc) {}
    }
}
