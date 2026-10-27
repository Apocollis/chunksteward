package com.apocollis.chunksteward.server;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code IntCache} with one pool per thread. Vanilla keeps one static pool, and {@code resetIntCache} hands arrays that
 * another thread is still filling back out. The logic per thread is vanilla's, unsynchronized.
 */
public final class ThreadIntCache {
    private static final ThreadLocal<Pool> POOL = ThreadLocal.withInitial(Pool::new);

    private ThreadIntCache() {
    }

    private static final class Pool {
        int size = 256;
        final List<int[]> freeSmall = new ArrayList<int[]>();
        final List<int[]> inUseSmall = new ArrayList<int[]>();
        final List<int[]> freeLarge = new ArrayList<int[]>();
        final List<int[]> inUseLarge = new ArrayList<int[]>();
    }

    public static int[] get(int size) {
        Pool pool = POOL.get();
        if (size <= 256) {
            int[] array = pool.freeSmall.isEmpty() ? new int[256] : pool.freeSmall.remove(pool.freeSmall.size() - 1);
            pool.inUseSmall.add(array);
            return array;
        }
        if (size > pool.size) {
            pool.size = size;
            pool.freeLarge.clear();
            pool.inUseLarge.clear();
            int[] array = new int[pool.size];
            pool.inUseLarge.add(array);
            return array;
        }
        int[] array = pool.freeLarge.isEmpty() ? new int[pool.size] : pool.freeLarge.remove(pool.freeLarge.size() - 1);
        pool.inUseLarge.add(array);
        return array;
    }

    public static void reset() {
        Pool pool = POOL.get();
        if (!pool.freeLarge.isEmpty()) {
            pool.freeLarge.remove(pool.freeLarge.size() - 1);
        }
        if (!pool.freeSmall.isEmpty()) {
            pool.freeSmall.remove(pool.freeSmall.size() - 1);
        }
        pool.freeLarge.addAll(pool.inUseLarge);
        pool.freeSmall.addAll(pool.inUseSmall);
        pool.inUseLarge.clear();
        pool.inUseSmall.clear();
    }

    public static String sizes() {
        Pool pool = POOL.get();
        return "cache: " + pool.freeLarge.size() + ", tcache: " + pool.freeSmall.size() + ", allocated: "
                + pool.inUseLarge.size() + ", tallocated: " + pool.inUseSmall.size();
    }
}
