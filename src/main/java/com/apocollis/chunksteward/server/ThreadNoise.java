package com.apocollis.chunksteward.server;

import java.util.IdentityHashMap;

/**
 * Per-thread copies of noise buffers that a mod keeps on a shared object (the Betweenlands biome generators and their
 * features are singletons). Keyed by the owning object, so a subclass shares its parent's slot. Holds no mod types.
 */
public final class ThreadNoise {
    private static final ThreadLocal<IdentityHashMap<Object, Slot>> SLOTS =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private ThreadNoise() {
    }

    /** Up to two buffers, the "already filled for this chunk" flag, and whether this thread has seen the init. */
    public static final class Slot {
        public double[] first = new double[256];
        public double[] second = new double[256];
        public boolean generated;
        public boolean initialized;
    }

    public static Slot of(Object owner) {
        IdentityHashMap<Object, Slot> slots = SLOTS.get();
        Slot slot = slots.get(owner);
        if (slot == null) {
            slot = new Slot();
            slots.put(owner, slot);
        }
        return slot;
    }
}
