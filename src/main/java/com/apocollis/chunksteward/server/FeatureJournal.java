package com.apocollis.chunksteward.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a feature job did to the world, kept apart from the world: every block write in order, the light checks it asked
 * for, and an overlay so the job reads its own writes. The loaded chunks are not touched while a job runs; the server
 * thread replays {@link #writes()} through the real {@code World.setBlockState} when the job is promoted, so block
 * notifications, tile entities and other mods' hooks behave as they do for an inline populate.
 *
 * <p>A position is packed like {@code BlockPos.toLong}. Not thread safe: one job, one worker, then handed to the
 * server thread after the job state moved to done.
 *
 * @param <S> the block state type ({@code IBlockState} in the game)
 */
final class FeatureJournal<S> {

    /** One recorded write. */
    static final class Write<S> {
        final long pos;
        final S state;
        final int flags;

        Write(long pos, S state, int flags) {
            this.pos = pos;
            this.state = state;
            this.flags = flags;
        }
    }

    private final ArrayList<Write<S>> writes = new ArrayList<Write<S>>();
    private final LongMap<S> overlay = new LongMap<S>();
    private final LongMap<Boolean> checkSet = new LongMap<Boolean>();
    private long[] checks = new long[16];
    private int checkCount;

    // BlockPos.toLong in 1.12.2: z in the low 26 bits, then y in 12 bits, then x in the high 26 bits.
    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int Z_SHIFT = 0;
    private static final int Y_SHIFT = Z_BITS;
    private static final int X_SHIFT = Y_SHIFT + Y_BITS;
    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;

    /** The same value {@code new BlockPos(x, y, z).toLong()} gives, so a journal replays through {@code fromLong}. */
    static long pack(int x, int y, int z) {
        return ((long) x & X_MASK) << X_SHIFT | ((long) y & Y_MASK) << Y_SHIFT | ((long) z & Z_MASK) << Z_SHIFT;
    }

    static int unpackX(long pos) {
        return (int) (pos << 64 - X_SHIFT - X_BITS >> 64 - X_BITS);
    }

    static int unpackY(long pos) {
        return (int) (pos << 64 - Y_SHIFT - Y_BITS >> 64 - Y_BITS);
    }

    static int unpackZ(long pos) {
        return (int) (pos << 64 - Z_SHIFT - Z_BITS >> 64 - Z_BITS);
    }

    /** Records a write and makes it visible to {@link #overlay(long)}. */
    void write(long pos, S state, int flags) {
        writes.add(new Write<S>(pos, state, flags));
        overlay.put(pos, state);
    }

    /** The state this job last wrote at {@code pos}, or null when it has not written there. */
    S overlay(long pos) {
        return overlay.get(pos);
    }

    /** A light check the job asked for, kept once per position, in first-asked order. */
    void check(long pos) {
        if (checkSet.put(pos, Boolean.TRUE) == null) {
            if (checkCount == checks.length) {
                checks = Arrays.copyOf(checks, checkCount * 2);
            }
            checks[checkCount++] = pos;
        }
    }

    List<Write<S>> writes() {
        return writes;
    }

    long[] checks() {
        return Arrays.copyOf(checks, checkCount);
    }

    /** The first {@code limit} light checks (those asked before a stage boundary). */
    long[] checks(int limit) {
        return Arrays.copyOf(checks, Math.min(limit, checkCount));
    }

    int checkCount() {
        return checkCount;
    }

    int size() {
        return writes.size();
    }

    boolean isEmpty() {
        return writes.isEmpty() && checkCount == 0;
    }

    /**
     * An open-addressing map from a packed position to a value, for the overlay read on every block lookup a job
     * makes. Keys are never removed. Not thread safe.
     */
    static final class LongMap<V> {
        private long[] keys = new long[64];
        private Object[] values = new Object[64];
        private boolean[] used = new boolean[64];
        private int size;

        /** Calls {@code consumer} for each stored key and value, in no particular order. */
        @SuppressWarnings("unchecked")
        void forEach(java.util.function.BiConsumer<Long, V> consumer) {
            for (int i = 0; i < keys.length; i++) {
                if (used[i]) {
                    consumer.accept(keys[i], (V) values[i]);
                }
            }
        }

        @SuppressWarnings("unchecked")
        V get(long key) {
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    return (V) values[i];
                }
                i = (i + 1) & mask;
            }
            return null;
        }

        /** Stores {@code value} and returns the one it replaced, or null. */
        @SuppressWarnings("unchecked")
        V put(long key, V value) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    V old = (V) values[i];
                    values[i] = value;
                    return old;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            values[i] = value;
            size++;
            return null;
        }

        int size() {
            return size;
        }

        private void grow() {
            long[] oldKeys = keys;
            Object[] oldValues = values;
            boolean[] oldUsed = used;
            keys = new long[oldKeys.length * 2];
            values = new Object[oldKeys.length * 2];
            used = new boolean[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i]) {
                    @SuppressWarnings("unchecked")
                    V value = (V) oldValues[i];
                    put(oldKeys[i], value);
                }
            }
        }

        private static int hash(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ h >>> 32);
        }
    }
}
