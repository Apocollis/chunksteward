package com.apocollis.chunksteward.server;

/**
 * Which finished feature job the server thread applies next, and when a busy decoration lock defers one. Pure, so the
 * rules are unit-tested without Minecraft.
 *
 * <p>Jobs are scanned in creation order. A job may be applied ahead of an earlier one that cannot go yet (unfinished,
 * or waiting for the lock) only when their read windows do not overlap: they then read and write different chunks, so
 * the order does not change any block. An overlapping job keeps its place behind the earlier one.
 */
final class ApplyDeferral {
    /** Ticks a finished job may wait for a busy decoration lock before the server blocks for it. */
    static final int MAX_DEFERRALS = 40;

    /** Tries the decoration lock without blocking. */
    interface LockProbe {
        boolean tryLock();
    }

    private ApplyDeferral() {
    }

    /** True while the job has waited fewer than {@code cap} ticks: leave it in place and try again next tick. */
    static boolean shouldDefer(int deferrals, int cap) {
        return deferrals < cap;
    }

    /**
     * The index of the job to apply, or -1 for none. {@code deferred[i]} is set for each job passed over because the
     * lock was busy. {@code locked[0]} is set when the probe took the lock for the chosen job, which the caller then
     * owns. A job at the cap is chosen without the lock, and the caller blocks for it.
     */
    static int pick(int n, int[] dimension, int[] x, int[] z, boolean[] finished, boolean[] needsLock, int[] deferrals,
                    int cap, LockProbe probe, boolean[] deferred, boolean[] locked) {
        for (int i = 0; i < n; i++) {
            boolean blocked = !finished[i];
            // Every earlier job was passed over, or this loop would have returned it.
            for (int k = 0; k < i && !blocked; k++) {
                blocked = dimension[k] == dimension[i] && FeatureWindow.conflict(x[k], z[k], x[i], z[i]);
            }
            if (!blocked && needsLock[i]) {
                if (probe.tryLock()) {
                    locked[0] = true;
                } else if (shouldDefer(deferrals[i], cap)) {
                    deferred[i] = true;
                    blocked = true;
                }
            }
            if (!blocked) {
                return i;
            }
        }
        return -1;
    }
}
