package org.apache.zookeeper.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;

public final class StaticHostProvider implements HostProvider {
    public interface Resolver {
        InetAddress[] getAllByName(String name) throws UnknownHostException;
    }

    private static final Logger LOG = LoggerFactory
            .getLogger(StaticHostProvider.class);


    private Resolver resolver;
    private final List<InetSocketAddress> serverAddresses = new ArrayList<InetSocketAddress>(5);

    private int lastIndex = -1;

    private int currentIndex = -1;

    public StaticHostProvider(Collection<InetSocketAddress> serverAddresses) {
        this.resolver = new Resolver() {
            @Override
            public InetAddress[] getAllByName(String name) throws UnknownHostException {
                return InetAddress.getAllByName(name);
            }
        };
        init(serverAddresses);
    }

    private void init(Collection<InetSocketAddress> serverAddresses) {
        if (serverAddresses.isEmpty()) {
            throw new IllegalArgumentException(
                    "A HostProvider may not be empty!");
        }

        this.serverAddresses.addAll(serverAddresses);
        Collections.shuffle(this.serverAddresses);
    }

    public int size() {
        return serverAddresses.size();
    }

    public InetSocketAddress next(long spinDelay) {
        currentIndex = ++currentIndex % serverAddresses.size();
        LOG.info("currentIndex:{}, lastIndex:{}", currentIndex, lastIndex);
        if (currentIndex == lastIndex && spinDelay > 0) {
            try {
                Thread.sleep(spinDelay);
            } catch (InterruptedException e) {
                LOG.warn("Unexpected exception", e);
            }
        } else if (lastIndex == -1) {
            lastIndex = 0;
        }

        InetSocketAddress curAddr = serverAddresses.get(currentIndex);
        try {
            String curHostString = getHostString(curAddr);
            List<InetAddress> resolvedAddresses = new ArrayList<InetAddress>(Arrays.asList(resolver.getAllByName(curHostString)));
            if (resolvedAddresses.isEmpty()) {
                return curAddr;
            }
            Collections.shuffle(resolvedAddresses);
            return new InetSocketAddress(resolvedAddresses.get(0), curAddr.getPort());
        } catch (UnknownHostException e) {
            return curAddr;
        }
    }

    private String getHostString(InetSocketAddress addr) {
        String hostString = "";

        if (addr == null) {
            return hostString;
        }
        if (!addr.isUnresolved()) {
            InetAddress ia = addr.getAddress();

            // If the string starts with '/', then it has no hostname
            // and we want to avoid the reverse lookup, so we return
            // the string representation of the address.
            if (ia.toString().startsWith("/")) {
                hostString = ia.getHostAddress();
            } else {
                hostString = addr.getHostName();
            }
        } else {
            // According to the Java 6 documentation, if the hostname is
            // unresolved, then the string before the colon is the hostname.
            String addrString = addr.toString();
            hostString = addrString.substring(0, addrString.lastIndexOf(':'));
        }

        return hostString;
    }

    @Override
    public void onConnected() {
        lastIndex = currentIndex;
    }
}
