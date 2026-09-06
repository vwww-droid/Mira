package android.net.wifi;

public class WifiManager {
    public MulticastLock createMulticastLock(String tag) {
        return new MulticastLock();
    }

    public static final class MulticastLock {
        private boolean held;

        public void setReferenceCounted(boolean referenceCounted) {}

        public void acquire() {
            held = true;
        }

        public void release() {
            held = false;
        }

        public boolean isHeld() {
            return held;
        }
    }
}
