package com.apocollis.chunksteward.server;

import java.util.concurrent.atomic.AtomicInteger;

/** One terrain job. Transitions are compare-and-set on {@link #state}. */
final class PipelineJob {
    static final int QUEUED = 0;
    static final int CLAIMED_WORKER = 1;
    static final int CLAIMED_MAIN = 2;
    static final int DONE = 3;
    static final int CANCELLED = 4;

    final int dimension;
    final int x;
    final int z;
    final int epoch;
    final AtomicInteger state = new AtomicInteger(QUEUED);
    volatile long claimedNanos;
    volatile int workerSlot = -1;
    /** Preload or stage 2. Player demand clears this. Evicted first when the ready map is full. */
    volatile boolean speculative;
    /** {@link com.apocollis.chunksteward.api.JobPreparer} contexts, or null. */
    volatile Object context;

    PipelineJob(int dimension, int x, int z, int epoch) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.epoch = epoch;
    }

    boolean claimWorker() {
        return state.compareAndSet(QUEUED, CLAIMED_WORKER);
    }

    boolean claimMain() {
        return state.compareAndSet(QUEUED, CLAIMED_MAIN);
    }

    /** Worker finished. Fails if the server already cancelled the claim. */
    boolean publish() {
        return state.compareAndSet(CLAIMED_WORKER, DONE);
    }

    /** Server gave up waiting on a worker claim. */
    boolean steal() {
        return state.compareAndSet(CLAIMED_WORKER, CANCELLED);
    }
}
