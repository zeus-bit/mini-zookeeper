package org.apache.zookeeper;

import org.apache.zookeeper.client.ConnectStringParser;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;

import java.io.IOException;
import java.util.*;

public class ZooKeeper {

    private final ZKWatchManager watchManager = new ZKWatchManager();
    protected final ClientCnxn cnxn;

    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher,
                     boolean canBeReadOnly) throws IOException {
        watchManager.defaultWatcher = watcher;

        ConnectStringParser connectStringParser = new ConnectStringParser(
                connectString);
        HostProvider hostProvider = new StaticHostProvider(
                connectStringParser.getServerAddresses());
        cnxn = new ClientCnxn(connectStringParser.getChrootPath(),
                hostProvider, sessionTimeout, this, watchManager,
                getClientCnxnSocket(), canBeReadOnly);
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

    List<String> getDataWatches() {
        synchronized(watchManager.dataWatches) {
            List<String> rc = new ArrayList<String>(watchManager.dataWatches.keySet());
            return rc;
        }
    }
    List<String> getExistWatches() {
        synchronized(watchManager.existWatches) {
            List<String> rc =  new ArrayList<String>(watchManager.existWatches.keySet());
            return rc;
        }
    }
    List<String> getChildWatches() {
        synchronized(watchManager.childWatches) {
            List<String> rc = new ArrayList<String>(watchManager.childWatches.keySet());
            return rc;
        }
    }

    public enum States {
        CONNECTING, ASSOCIATING, CONNECTED, CONNECTEDREADONLY,
        CLOSED, AUTH_FAILED, NOT_CONNECTED;

        public boolean isAlive() {
            return this != CLOSED && this != AUTH_FAILED;
        }

        public boolean isConnected() {
            return this == CONNECTED || this == CONNECTEDREADONLY;
        }
    }

    private static class ZKWatchManager implements ClientWatchManager {
        private volatile Watcher defaultWatcher;

        private final Map<String, Set<Watcher>> dataWatches =
                new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> existWatches =
                new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> childWatches =
                new HashMap<String, Set<Watcher>>();

        @Override
        public Set<Watcher> materialize(Watcher.Event.KeeperState state, Watcher.Event.EventType type, String clientPath) {
            Set<Watcher> result = new HashSet<Watcher>();

            switch (type) {
                case None:
                    result.add(defaultWatcher);
                    boolean clear = ClientCnxn.getDisableAutoResetWatch() &&
                            state != Watcher.Event.KeeperState.SyncConnected;

                    synchronized(dataWatches) {
                        for(Set<Watcher> ws: dataWatches.values()) {
                            result.addAll(ws);
                        }
                        if (clear) {
                            dataWatches.clear();
                        }
                    }

                    synchronized(existWatches) {
                        for(Set<Watcher> ws: existWatches.values()) {
                            result.addAll(ws);
                        }
                        if (clear) {
                            existWatches.clear();
                        }
                    }

                    synchronized(childWatches) {
                        for(Set<Watcher> ws: childWatches.values()) {
                            result.addAll(ws);
                        }
                        if (clear) {
                            childWatches.clear();
                        }
                    }

                    return result;
                case NodeDataChanged:
                case NodeCreated:
                    synchronized (dataWatches) {
                        addTo(dataWatches.remove(clientPath), result);
                    }
                    synchronized (existWatches) {
                        addTo(existWatches.remove(clientPath), result);
                    }
                    break;
                case NodeChildrenChanged:
                    synchronized (childWatches) {
                        addTo(childWatches.remove(clientPath), result);
                    }
                    break;
                case NodeDeleted:
                    synchronized (dataWatches) {
                        addTo(dataWatches.remove(clientPath), result);
                    }
                    // XXX This shouldn't be needed, but just in case
                    synchronized (existWatches) {
                        Set<Watcher> list = existWatches.remove(clientPath);
                        if (list != null) {
                            addTo(list, result);
                        }
                    }
                    synchronized (childWatches) {
                        addTo(childWatches.remove(clientPath), result);
                    }
                    break;
                default:
                    String msg = "Unhandled watch event type " + type
                            + " with state " + state + " on path " + clientPath;
                    throw new RuntimeException(msg);
            }

            return result;
        }

        final private void addTo(Set<Watcher> from, Set<Watcher> to) {
            if (from != null) {
                to.addAll(from);
            }
        }
    }

    abstract class WatchRegistration {
        private Watcher watcher;
        private String clientPath;
        public WatchRegistration(Watcher watcher, String clientPath)
        {
            this.watcher = watcher;
            this.clientPath = clientPath;
        }

        abstract protected Map<String, Set<Watcher>> getWatches(int rc);

        /**
         * Register the watcher with the set of watches on path.
         * @param rc the result code of the operation that attempted to
         * add the watch on the path.
         */
        public void register(int rc) {
            if (shouldAddWatch(rc)) {
                Map<String, Set<Watcher>> watches = getWatches(rc);
                synchronized(watches) {
                    Set<Watcher> watchers = watches.get(clientPath);
                    if (watchers == null) {
                        watchers = new HashSet<Watcher>();
                        watches.put(clientPath, watchers);
                    }
                    watchers.add(watcher);
                }
            }
        }
        /**
         * Determine whether the watch should be added based on return code.
         * @param rc the result code of the operation that attempted to add the
         * watch on the node
         * @return true if the watch should be added, otw false
         */
        protected boolean shouldAddWatch(int rc) {
            return rc == 0;
        }
    }
}
