package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;

/**
 * Clears every piece of state that is tied to one server session: tick numbers (the integrated server restarts its tick
 * counter at 0 every time a world is entered), queues, holds, per-player state and the known-generated chunk cache
 * (which belongs to one save). Without this, a stale "last save tick" or "last generated tick" from the previous world
 * makes every comparison against the new, smaller tick counter come out wrong: the server believes a save is in
 * progress, the throttle credit drains instead of accruing, and the starvation guard never fires.
 *
 * <p>Called when a server starts and stops, and defensively from {@link TickMonitor#onTickStart} whenever the tick counter
 * goes backwards or a different thread starts ticking.
 */
public final class SessionState {
    private SessionState() {
    }

    public static void reset(String reason) {
        ChunkSteward.LOGGER.info("session state reset ({})", reason);
        TickMonitor.reset();
        ChunkThrottle.reset();
        StageGate.reset();
        PopulateQueue.reset();
        ChunkHolds.reset();
        IdlePreload.reset();
        Stage2Pregen.reset();
        Hotspots.reset();
        PlayerMotion.reset();
        Warmup.reset();
        GeneratedCache.reset();
        RandomTickGuard.onTickStart();
        Diagnostics.reset();
        TerrainPipeline.reset();
    }
}
