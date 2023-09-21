package org.apache.zookeeper.common;

public class Time {
    public static long currentElapsedTime() {
        return System.nanoTime() / 1000000;
    }
}
