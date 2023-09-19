package org.apache.zookeeper.server;

import org.slf4j.Logger;

public class ZooTrace {

    final static public long SESSION_TRACE_MASK = 1 << 5;

    final static public long CLIENT_REQUEST_TRACE_MASK = 1 << 1;

    final static public long SERVER_PACKET_TRACE_MASK = 1 << 4;

    final static public long WARNING_TRACE_MASK = 1 << 8;

    public static void logTraceMessage(Logger log, long mask, String msg) {
        if (isTraceEnabled(log, mask)) {
            log.trace(msg);
        }
    }

    public static boolean isTraceEnabled(Logger log, long mask) {
        return log.isTraceEnabled() && (mask & traceMask) != 0;
    }

    private static long traceMask = CLIENT_REQUEST_TRACE_MASK
            | SERVER_PACKET_TRACE_MASK | SESSION_TRACE_MASK
            | WARNING_TRACE_MASK;
}
