package org.apache.zookeeper;

import org.apache.yetus.audience.InterfaceAudience;

public class ZooDefs {

    @InterfaceAudience.Public
    public interface OpCode {
        int createSession = -10;
    }
}
