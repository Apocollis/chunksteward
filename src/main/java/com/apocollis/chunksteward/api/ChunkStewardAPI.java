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
 * meaning, new ones are added, and {@link #API_VERSION} increases when something is added.
 */
public final class ChunkStewardAPI {
    /** Incremented when methods are added. Never changes the meaning of an existing method. */
    public static final int API_VERSION = 1;

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

    /** Spare ms per tick on average: Chunk Steward's tick target minus {@link #getAverageTickMs()}, never below 0. */
    public static double getHeadroomMs() {
        return Math.max(0.0, PacingView.targetTickMs() - PacingView.averageTickMs());
    }

    /**
     * Ms left of {@code targetMs} in the current tick: {@code targetMs} minus the time this tick has used so far, never
     * below 0. Returns {@code targetMs} off the server thread or before the first measurement. For example, a build job
     * that has a 15 ms slice can use {@code getRemainingTickBudgetMs(30.0)} to also respect the tick as a whole.
     */
    public static double getRemainingTickBudgetMs(double targetMs) {
        return Math.max(0.0, targetMs - PacingView.currentTickElapsedMs());
    }

    /** The average tick work is at or over Chunk Steward's tick target: the server is struggling to keep up. */
    public static boolean isServerBehind() {
        return PacingView.averageTickMs() >= PacingView.targetTickMs();
    }

    /** Chunks are being generated for players at the rate where preload holds off. A good moment to pause optional work. */
    public static boolean isGeneratingChunks() {
        return PacingView.generatingChunks();
    }

    /** Chunks whose populate is queued behind the tick budget. */
    public static int getPopulateQueueSize() {
        return PacingView.populateQueueSize();
    }
}
