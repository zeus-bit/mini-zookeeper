package org.apache.zookeeper.common;

import java.util.Date;

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

    /**
     * This is to convert the elapsedTime to a Date.
     * @return A date object indicated by the elapsedTime.
     */
    public static Date elapsedTimeToDate(long elapsedTime) {
        long wallTime = currentWallTime() + elapsedTime - currentElapsedTime();
        return new Date(wallTime);
    }
}
