package org.apache.zookeeper;

import org.apache.zookeeper.data.Stat;

public abstract class OpResult {
    private int type;

    private OpResult(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }

    public static class CreateResult extends OpResult {
        private String path;

        public CreateResult(String path) {
            super(ZooDefs.OpCode.create);
            this.path = path;
        }

        public String getPath() {
            return path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CreateResult)) return false;

            CreateResult other = (CreateResult) o;
            return getType() == other.getType() && path.equals(other.getPath());
        }

        @Override
        public int hashCode() {
            return getType() * 35 + path.hashCode();
        }
    }

    /**
     * A result from a delete operation.  No special values are available.
     */
    public static class DeleteResult extends OpResult {
        public DeleteResult() {
            super(ZooDefs.OpCode.delete);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DeleteResult)) return false;

            DeleteResult opResult = (DeleteResult) o;
            return getType() == opResult.getType();
        }

        @Override
        public int hashCode() {
            return getType();
        }
    }

    /**
     * A result from a setData operation.  This kind of result provides access
     * to the Stat structure from the update.
     */
    public static class SetDataResult extends OpResult {
        private Stat stat;

        public SetDataResult(Stat stat) {
            super(ZooDefs.OpCode.setData);
            this.stat = stat;
        }

        public Stat getStat() {
            return stat;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SetDataResult)) return false;

            SetDataResult other = (SetDataResult) o;
            return getType() == other.getType() && stat.getMzxid() == other.stat.getMzxid();
        }

        @Override
        public int hashCode() {
            return (int) (getType() * 35 + stat.getMzxid());
        }
    }

    /**
     * A result from a version check operation.  No special values are available.
     */
    public static class CheckResult extends OpResult {
        public CheckResult() {
            super(ZooDefs.OpCode.check);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CheckResult)) return false;

            CheckResult other = (CheckResult) o;
            return getType() == other.getType();
        }

        @Override
        public int hashCode() {
            return getType();
        }
    }

    public static class ErrorResult extends OpResult {
        private int err;

        public ErrorResult(int err) {
            super(ZooDefs.OpCode.error);
            this.err = err;
        }

        public int getErr() {
            return err;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ErrorResult)) return false;

            ErrorResult other = (ErrorResult) o;
            return getType() == other.getType() && err == other.getErr();
        }

        @Override
        public int hashCode() {
            return getType() * 35 + err;
        }
    }
}
