package org.apache.zookeeper.common;

public class Time {

    public static long currentElapsedTime() {
        return System.nanoTime() / 1000000;
    }

    /**
     * Explicitly returns system dependent current wall time.
     * @return Current time in msec.
     */
    public static long currentWallTime() {
        return System.currentTimeMillis();
    }
}
