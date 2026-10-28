package com.apocollis.chunksteward.api;

import com.apocollis.chunksteward.server.PacingView;

/**
 * Read-only pacing information from Chunk Steward, for mods that spread their own work across ticks (dungeon
 * builders, structure pasters, anything that can run in slices).
 *
 * <p>Call it on the server thread, inside guarded code: put calls in a small holder class that is only loaded when
 * {@code Loader.isModLoaded("chunksteward")}, so this class is never touched when Chunk Steward is absent. See
 * {@code docs/api.md} for the pattern.
 *
 * <p>The averages cover about the last 20 ticks, so they suit pacing and not exact decisions about the current tick;
 * use {@link #getRemainingTickBudgetMs(double)} for in-tick decisions. This API only grows: existing methods keep their
 * meaning, new ones are added, and {@link #API_VERSION} increases when something is added. Version 3 adds the
 * pipeline queries below. Call those from a class that is loaded only after {@code API_VERSION >= 3}, so a jar
 * that is still version 2 does not throw {@code NoSuchMethodError} while this class is linking.
 */
public final class ChunkStewardAPI {
    /** Incremented when methods are added. Never changes the meaning of an existing method. */
    public static final int API_VERSION = 3;

    private ChunkStewardAPI() {
    }

    /** Average ms of whole-tick work over about the last 20 ticks (0 before the first measurement). */
    public static double getAverageTickMs() {
        return PacingView.averageTickMs();
    }

    /** Average ms of tick work excluding chunk provision (what other work, entities and mods, costs). */
    public static double getNonChunkTickMs() {
        return PacingView.nonChunkTickMs();
    }

    /**
     * Spare ms per tick on average: what is left of a 50 ms tick after {@link #getAverageTickMs()}, scaled by
     * {@link #getLoadFactor()}, never below 0. Under load (low TPS) it shrinks to nothing even when the average looks low.
     */
    public static double getHeadroomMs() {
        return Math.max(0.0, 50.0 - PacingView.averageTickMs()) * PacingView.loadFactor();
    }

    /** Measured TPS over about the last 5 seconds; 20 whenever the average tick work is at most 50 ms. (Version 2.) */
    public static double getMeasuredTps() {
        return PacingView.measuredTps();
    }

    /**
     * 1.0 when the server has room (measured TPS at {@code throttle.tpsFull}, default 19), 0.0 when it is under load
     * (at or below {@code throttle.tpsLoaded}, default 15), linear in between. Scale optional work by this. (Version 2.)
     */
    public static double getLoadFactor() {
        return PacingView.loadFactor();
    }

    /**
     * Ms left of {@code targetMs} in the current tick: {@code targetMs} minus the time this tick has used so far, never
     * below 0. Returns {@code targetMs} off the server thread or before the first measurement. For example, a build job
     * that has a 15 ms slice can use {@code getRemainingTickBudgetMs(30.0)} to also respect the tick as a whole.
     */
    public static double getRemainingTickBudgetMs(double targetMs) {
        return Math.max(0.0, targetMs - PacingView.currentTickElapsedMs());
    }

    /** Measured TPS is below {@code throttle.tpsFull}: the server is not keeping a full 20 TPS. */
    public static boolean isServerBehind() {
        return PacingView.loadFactor() < 1.0;
    }

    /** Chunks are being generated for players at the rate where preload holds off. A good moment to pause optional work. */
    public static boolean isGeneratingChunks() {
        return PacingView.generatingChunks();
    }

    /** Chunks whose populate is queued behind the tick budget. */
    public static int getPopulateQueueSize() {
        return PacingView.populateQueueSize();
    }

    /**
     * Workers may generate terrain for a listed class and the CPU-guard cap is above 0. This is not
     * {@link #isGeneratingChunks()}: that one is the player-demand rate preload uses to hold off. (Version 3.)
     */
    public static boolean isPipelineActive() {
        return PacingView.pipelineActive();
    }

    /**
     * Terrain jobs that are queued, in flight, or finished and waiting to install. Includes preload and stage 2.
     * A large value means chunks are held ahead of the server thread, not that players are waiting. (Version 3.)
     */
    public static int getPipelineQueueSize() {
        return PacingView.pipelineQueueSize();
    }

    /**
     * Average milliseconds the server thread spent installing a finished worker chunk ({@code recreateStructures}),
     * smoothed like the tick averages. 0 until the first install. (Version 3.)
     */
    public static double getAverageTerrainInstallMs() {
        return PacingView.averageTerrainInstallMs();
    }

    /**
     * A worker has finished this chunk and it is waiting to be installed. The next {@code provideChunk} on the server
     * thread adopts it instead of generating. False when the world is not loaded, or the chunk was never submitted,
     * or it is still generating. Call this on the server thread. (Version 3.)
     */
    public static boolean isChunkReady(int dimension, int chunkX, int chunkZ) {
        return PacingView.chunkReady(dimension, chunkX, chunkZ);
    }

    /**
     * This thread is a terrain worker, or the server thread while it is copying a worker generator. Mods that stash
     * the live generator on the server thread should skip that stash when this is true. (Version 3.)
     */
    public static boolean isPipelineContext() {
        return PacingView.pipelineContext();
    }

    /**
     * Contexts from each registered {@link JobPreparer#prepare}, in registration order, while a worker is inside
     * {@code generateChunk}. Null on any other thread, and null when nobody has registered. One preparer is a
     * one-element array. (Version 3.)
     */
    public static Object currentJobContext() {
        return PacingView.jobContext();
    }

    /** Registers {@code preparer} once. A second call with the same instance does nothing. (Version 3.) */
    public static void registerJobPreparer(JobPreparer preparer) {
        PacingView.registerPreparer(preparer);
    }
}
