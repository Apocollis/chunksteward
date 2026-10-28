package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.api.JobPreparer;
import com.apocollis.chunksteward.config.ChunkStewardConfig;

/**
 * Public, read-only view of the tick accounting for {@code ChunkStewardAPI}. The numbers are only meaningful on the
 * server thread, which is where mods pace their work.
 */
public final class PacingView {
    private PacingView() {
    }

    /** Average ms of whole-tick work (about the last 20 ticks); 0 until the first tick has been measured. */
    public static double averageTickMs() {
        return TickMonitor.primed ? TickMonitor.totalEmaMs : 0.0;
    }

    /** Average ms of tick work excluding chunk provision. */
    public static double nonChunkTickMs() {
        return TickMonitor.primed ? TickMonitor.otherEmaMs : 0.0;
    }

    /** Measured TPS over about the last 100 ticks: 20 while the mean tick work is at most 50 ms. */
    public static double measuredTps() {
        return TickMonitor.measuredTps();
    }

    /** 1 = the server has room (TPS at {@code throttle.tpsFull}), 0 = under load (at or below {@code tpsLoaded}). */
    public static double loadFactor() {
        return TickMonitor.loadFactor();
    }

    /** Ms the current tick has used so far, or 0 off the server thread (where the figure is meaningless). */
    public static double currentTickElapsedMs() {
        if (!TickMonitor.primed || Thread.currentThread() != TickMonitor.serverThread) {
            return 0.0;
        }
        return TickMonitor.currentTickMs();
    }

    /** Chunks are being generated for players at the busy rate preload uses to hold off. */
    public static boolean generatingChunks() {
        ChunkStewardConfig.Preload cfg = ChunkStewardConfig.preload;
        return TickMonitor.primed && TickMonitor.recentExternalGen(cfg.busyWindowTicks) >= cfg.busyChunks;
    }

    public static int populateQueueSize() {
        return PopulateQueue.size();
    }

    public static boolean pipelineActive() {
        return TerrainPipeline.isPipelineActive();
    }

    public static int pipelineQueueSize() {
        return TerrainPipeline.queueSize();
    }

    public static double averageTerrainInstallMs() {
        return TerrainPipeline.averageInstallMs();
    }

    public static boolean chunkReady(int dimension, int x, int z) {
        return TerrainPipeline.chunkReady(dimension, x, z);
    }

    public static boolean pipelineContext() {
        return PipelineMarker.pipelineContext();
    }

    public static Object jobContext() {
        return PipelineMarker.context();
    }

    public static void registerPreparer(JobPreparer preparer) {
        PipelinePreparers.register(preparer);
    }

    public static void registerPopulateScope(com.apocollis.chunksteward.api.PopulateScope scope) {
        PopulateScopes.register(scope);
    }
}
