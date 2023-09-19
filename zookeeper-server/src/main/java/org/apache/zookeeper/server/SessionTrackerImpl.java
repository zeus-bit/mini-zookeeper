package org.apache.zookeeper.server;

import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionTrackerImpl extends ZooKeeperCriticalThread implements SessionTracker {
    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackerImpl.class);

    SessionExpirer expirer;
    int expirationInterval;
    ConcurrentHashMap<Long, Integer> sessionsWithTimeout;
    long nextExpirationTime;
    long nextSessionId = 0;

    public SessionTrackerImpl(SessionExpirer expirer,
                              ConcurrentHashMap<Long, Integer> sessionsWithTimeout, int tickTime,
                              long sid, ZooKeeperServerListener listener) {
        super("SessionTracker", listener);
        this.expirer = expirer;
        this.expirationInterval = tickTime;
        this.sessionsWithTimeout = sessionsWithTimeout;
        nextExpirationTime = roundToInterval(Time.currentElapsedTime());
        this.nextSessionId = initializeNextSession(sid);
//        for (Map.Entry<Long, Integer> e : sessionsWithTimeout.entrySet()) {
//            addSession(e.getKey(), e.getValue());
//        }
    }

    private long roundToInterval(long time) {
        // We give a one interval grace period
        return (time / expirationInterval + 1) * expirationInterval;
    }

    public static long initializeNextSession(long id) {
        long nextSid = 0;
        nextSid = (Time.currentElapsedTime() << 24) >>> 8;
        nextSid =  nextSid | (id <<56);
        return nextSid;
    }

    @Override
    public long createSession(int sessionTimeout) {
        return 0;
    }

    @Override
    public boolean touchSession(long sessionId, int sessionTimeout) {
        return false;
    }
}
