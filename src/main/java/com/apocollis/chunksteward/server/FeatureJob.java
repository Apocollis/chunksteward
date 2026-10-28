package com.apocollis.chunksteward.server;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The staged part of one chunk's populate. Created on the server thread when the populate reaches the stage, run by one
 * terrain worker, then applied on the server thread in the order the jobs were created ({@link FeatureLane}).
 */
public final class FeatureJob {
    static final int QUEUED = 0;
    static final int RUNNING = 1;
    static final int DONE = 2;
    static final int FAILED = 3;
    /** Given up on by the server thread; whatever the worker produces is dropped. */
    static final int ABANDONED = 4;

    final WorldServer world;
    final int dimension;
    final int x;
    final int z;
    final long seq;
    final long createdNanos = System.nanoTime();
    /** The 4x4 read window, row by row from (x-1, z-1). The centre chunk is {@link #center()}. */
    final Chunk[] window;
    final List<FeatureStage> stages;
    /** Server thread, after the stages' writes were applied: the rest of populate. */
    final Runnable finish;
    final AtomicInteger state = new AtomicInteger(QUEUED);
    volatile FeatureScope scope;
    volatile String failure;
    volatile String culprit;
    /** Stages that finished on the worker, and the journal sizes right after the last of them (see FeatureLane). */
    volatile int doneStages;
    volatile int doneWrites;
    volatile int doneChecks;
    volatile long workNanos;

    FeatureJob(WorldServer world, int x, int z, long seq, Chunk[] window, List<FeatureStage> stages,
               Runnable finish) {
        this.world = world;
        this.dimension = world.provider.getDimension();
        this.x = x;
        this.z = z;
        this.seq = seq;
        this.window = window;
        this.stages = stages;
        this.finish = finish;
    }

    Chunk center() {
        return window[FeatureWindow.index(x, z, x, z)];
    }

    /** The job is for exactly this chunk. */
    boolean holds(int dim, int chunkX, int chunkZ) {
        return dimension == dim && x == chunkX && z == chunkZ;
    }
}
