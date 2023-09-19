package org.apache.zookeeper;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.util.List;

public interface AsyncCallback {

    @InterfaceAudience.Public
    interface StatCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx, Stat stat);
    }

    @InterfaceAudience.Public
    interface DataCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx, byte data[],
                           Stat stat);
    }

    @InterfaceAudience.Public
    interface ACLCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx,
                           List<ACL> acl, Stat stat);
    }

    @InterfaceAudience.Public
    interface ChildrenCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx,
                           List<String> children);
    }

    @InterfaceAudience.Public
    interface Children2Callback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx,
                           List<String> children, Stat stat);
    }

    @InterfaceAudience.Public
    interface StringCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx, String name);
    }

    @InterfaceAudience.Public
    interface VoidCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx);
    }

    @InterfaceAudience.Public
    interface MultiCallback extends AsyncCallback {
        void processResult(int rc, String path, Object ctx,
                           List<OpResult> opResults);
    }
}

