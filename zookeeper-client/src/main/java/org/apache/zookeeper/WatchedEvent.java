package org.apache.zookeeper;

import org.apache.zookeeper.Watcher.Event.*;

public class WatchedEvent {

    final private KeeperState keeperState;
    final private EventType eventType;
    private String path;

    public WatchedEvent(EventType eventType, KeeperState keeperState, String path) {
        this.keeperState = keeperState;
        this.eventType = eventType;
        this.path = path;
    }

    public KeeperState getState() {
        return keeperState;
    }

    public EventType getType() {
        return eventType;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "WatchedEvent state:" + keeperState
                + " type:" + eventType + " path:" + path;
    }
}
