package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;

/** Shared switches for preload (stage 1) and the home-area pregen (stage 2). Server thread only. */
public final class Pregen {
    /** Set by {@code /chunksteward pregen pause}; not saved. */
    static boolean paused;

    private Pregen() {
    }

    /**
     * Why pregeneration should not run this tick, or {@code null} if the server has room: chunks are waiting for a
     * player, recent generation for players is heavy, populates are backed up, a save is due, or the tick is over
     * {@code tickLimitMs}.
     */
    static String holdOffReason(double tickLimitMs) {
        if (!TickMonitor.primed) {
            return "starting";
        }
        if (TickMonitor.savePaused()) {
            return "save";
        }
        if (TickMonitor.waitingNow() > 0) {
            return "chunks waiting";
        }
        ChunkStewardConfig.Preload cfg = ChunkStewardConfig.preload;
        if (TickMonitor.recentExternalGen(cfg.busyWindowTicks) >= cfg.busyChunks) {
            return "busy generating";
        }
        if (PopulateQueue.size() > cfg.maxPopulateBacklog) {
            return "populate backlog";
        }
        if (TickMonitor.totalEmaMs >= tickLimitMs || TickMonitor.currentTickMs() >= tickLimitMs) {
            return "tick over limit";
        }
        return null;
    }
}
