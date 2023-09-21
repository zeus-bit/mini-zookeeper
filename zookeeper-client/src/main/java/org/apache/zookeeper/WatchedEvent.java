package org.apache.zookeeper;

import org.apache.zookeeper.Watcher.Event.*;
import org.apache.zookeeper.proto.WatcherEvent;

public class WatchedEvent {

    final private KeeperState keeperState;
    final private EventType eventType;
    private String path;

    public WatchedEvent(EventType eventType, KeeperState keeperState, String path) {
        this.keeperState = keeperState;
        this.eventType = eventType;
        this.path = path;
    }

    public WatchedEvent(WatcherEvent eventMessage) {
        keeperState = KeeperState.fromInt(eventMessage.getState());
        eventType = EventType.fromInt(eventMessage.getType());
        path = eventMessage.getPath();
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
