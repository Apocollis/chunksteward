package com.apocollis.chunksteward.server;

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

    /** The throttle's target tick work in ms ({@code throttle.targetTickMs}). */
    public static double targetTickMs() {
        return ChunkStewardConfig.throttle.targetTickMs;
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
}
