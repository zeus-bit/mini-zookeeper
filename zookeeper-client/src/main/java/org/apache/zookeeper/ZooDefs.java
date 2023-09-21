package org.apache.zookeeper;

public class ZooDefs {
    public interface OpCode {
        int create = 1;
        int delete = 2;
        int setData = 5;
        int ping = 11;
        int check = 13;
        int error = -1;
        int closeSession = -11;
    }
}
