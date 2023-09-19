package org.apache.zookeeper.server;

import java.util.concurrent.ConcurrentHashMap;

public class ZKDatabase {

    protected ConcurrentHashMap<Long, Integer> sessionsWithTimeouts;


    public ZKDatabase() {
        sessionsWithTimeouts = new ConcurrentHashMap<Long, Integer>();
    }

    public ConcurrentHashMap<Long, Integer> getSessionWithTimeOuts() {
        return sessionsWithTimeouts;
    }

    public long getDataTreeLastProcessedZxid() {
        return 0;
        //todo
//        return dataTree.lastProcessedZxid;
    }
}
