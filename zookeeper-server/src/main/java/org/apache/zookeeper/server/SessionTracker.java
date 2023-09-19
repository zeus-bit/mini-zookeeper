package org.apache.zookeeper.server;

public interface SessionTracker {

    interface Session {
        long getSessionId();
        int getTimeout();
        boolean isClosing();
    }

    interface SessionExpirer {
        void expire(Session session);
        long getServerId();
    }

    long createSession(int sessionTimeout);

    boolean touchSession(long sessionId, int sessionTimeout);
}
