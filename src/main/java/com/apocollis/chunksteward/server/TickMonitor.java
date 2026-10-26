package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.AccessorPlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Server-thread tick bookkeeping: how much of each tick was chunk provision and how much was everything else.
 * All state is touched from the server thread only.
 */
public final class TickMonitor {
    private static final double ALPHA = 0.1;
    private static final int TPS_WINDOW = 100;
    private static final double[] WORK_RING = new double[TPS_WINDOW];
    private static int ringCount;
    private static int ringPos;
    private static double ringSum;

    static int tick;
    /** Thread that runs {@code MinecraftServer.tick}; metrics and gates ignore every other thread. */
    static Thread serverThread;
    static long tickStartNanos;
    static long chunkNanos;
    static int generatedThisTick;
    /** Chunks generated this tick by anything but preload (player demand, cascades, other mods). */
    static int externalGenThisTick;
    private static final int[] EXTERNAL_RING = new int[256];
    private static int recentCacheTick = -1;
    private static int recentCacheWindow = -1;
    private static int recentCacheValue;
    private static int waitingCacheTick = -1;
    private static int waitingCacheValue;
    static int lastGenTick = Integer.MIN_VALUE / 2;
    static int lastSaveTick = Integer.MIN_VALUE / 2;

    /** Average ms of tick work excluding chunk provision. */
    static double otherEmaMs;
    /** Average ms of whole-tick work. */
    static double totalEmaMs;
    /** Average ms of throttled chunk provision (generation) per tick. */
    static double chunkEmaMs;
    static boolean primed;

    private static long statGenerated;
    private static long statSkipped;
    private static long statBypassed;
    private static long statPreloaded;
    private static long statPreloadedTerrainOnly;
    private static int statMaxEmergencyRadius;
    private static long lastLogNanos = System.nanoTime();

    private TickMonitor() {
    }

    public static void onTickStart(int serverTick) {
        // The integrated server restarts its tick counter at 0 for every world entered: everything keyed on tick numbers
        // (and everything that belongs to the previous save) must be cleared, or comparisons against stale ticks go wrong.
        if (serverTick < tick) {
            SessionState.reset("tick counter went backwards, " + tick + " -> " + serverTick);
        } else if (serverThread != null && serverThread != Thread.currentThread()) {
            SessionState.reset("a different thread is running the server");
        }
        tick = serverTick;
        serverThread = Thread.currentThread();
        GenMetrics.onTickStart();
        StageGate.onTickStart();
        RandomTickGuard.onTickStart();
        tickStartNanos = System.nanoTime();
        chunkNanos = 0L;
        generatedThisTick = 0;
        externalGenThisTick = 0;
        ChunkThrottle.onTickStart();
    }

    public static void onTickEnd() {
        long now = System.nanoTime();
        EXTERNAL_RING[tick & 255] = externalGenThisTick;
        double totalMs = (now - tickStartNanos) / 1.0e6;
        recordWork(totalMs);
        double chunkMs = chunkNanos / 1.0e6;
        double otherMs = Math.max(0.0, totalMs - chunkMs);
        if (!primed) {
            totalEmaMs = totalMs;
            otherEmaMs = otherMs;
            chunkEmaMs = chunkMs;
            primed = true;
        } else {
            totalEmaMs += ALPHA * (totalMs - totalEmaMs);
            otherEmaMs += ALPHA * (otherMs - otherEmaMs);
            chunkEmaMs += ALPHA * (chunkMs - chunkEmaMs);
        }
        int interval = ChunkStewardConfig.logging.statusIntervalSeconds;
        if (interval > 0 && now - lastLogNanos >= interval * 1_000_000_000L) {
            lastLogNanos = now;
            ChunkSteward.LOGGER.info("status: tick avg {} ms (non-chunk {} ms, chunk {} ms), chunks generated {}, "
                            + "deferred {}, bypassed {} (max emergency radius {}), waiting {}, preloaded {} "
                            + "(+{} terrain-only); {}; {}; {}; {}; TPS {} (load factor {}, admission factor {})",
                    String.format("%.1f", totalEmaMs), String.format("%.1f", otherEmaMs),
                    String.format("%.1f", chunkEmaMs), statGenerated, statSkipped, statBypassed,
                    statMaxEmergencyRadius, waitingNow(), statPreloaded, statPreloadedTerrainOnly,
                    StageGate.reportAndReset(), IdlePreload.reportAndReset(), Stage2Pregen.reportAndReset(),
                    RandomTickGuard.reportAndReset(), String.format("%.2f", measuredTps()),
                    String.format("%.2f", loadFactor()),
                    String.format("%.2f", ChunkThrottle.admissionFactor()));
            ChunkSteward.LOGGER.info("status: {}", GenMetrics.reportAndReset());
            statGenerated = statSkipped = statBypassed = statPreloaded = statPreloadedTerrainOnly = 0L;
            statMaxEmergencyRadius = 0;
        }
    }

    private static void recordWork(double totalMs) {
        if (ringCount < TPS_WINDOW) {
            ringCount++;
        } else {
            ringSum -= WORK_RING[ringPos];
        }
        WORK_RING[ringPos] = totalMs;
        ringSum += totalMs;
        ringPos++;
        if (ringPos == TPS_WINDOW) {
            ringPos = 0;
            double sum = 0.0;
            for (double v : WORK_RING) {
                sum += v;
            }
            ringSum = sum; // re-sum once per window so rounding never drifts
        }
    }

    /** TPS from the mean tick work of the last 100 ticks: 20 while the mean is at most 50 ms, 1000 / mean above that. */
    static double measuredTps() {
        if (ringCount == 0) {
            return 20.0;
        }
        double mean = ringSum / ringCount;
        return mean <= 50.0 ? 20.0 : 1000.0 / mean;
    }

    /**
     * 1 when measured TPS is at or above {@code tpsFull} (the server has room), 0 at or below {@code tpsLoaded} (under
     * load), linear in between.
     */
    static double loadFactor() {
        double loaded = ChunkStewardConfig.throttle.tpsLoaded;
        double full = Math.max(ChunkStewardConfig.throttle.tpsFull, loaded + 0.1);
        double f = (measuredTps() - loaded) / (full - loaded);
        return f < 0.0 ? 0.0 : f > 1.0 ? 1.0 : f;
    }

    /** Chunks generated by anything but preload in the last {@code window} ticks (at most 250). */
    static int recentExternalGen(int window) {
        if (recentCacheTick == tick && recentCacheWindow == window) {
            return recentCacheValue;
        }
        int n = Math.min(window, 250);
        int sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += EXTERNAL_RING[(tick - i) & 255];
        }
        sum += externalGenThisTick;
        recentCacheTick = tick;
        recentCacheWindow = window;
        recentCacheValue = sum;
        return sum;
    }

    static void countExternalGen() {
        externalGenThisTick++;
    }

    /** View distance of the world's player chunk map, in chunks. */
    static int viewRadius(WorldServer world) {
        return ((AccessorPlayerChunkMap) world.getPlayerChunkMap()).chunksteward$getViewRadius();
    }

    /** Player chunk map entries still without a chunk, all loaded worlds; computed once per tick. */
    static int waitingNow() {
        if (waitingCacheTick != tick) {
            waitingCacheValue = countWaiting();
            waitingCacheTick = tick;
        }
        return waitingCacheValue;
    }

    private static int countWaiting() {
        int waiting = 0;
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (PlayerChunkMapEntry entry
                    : ((AccessorPlayerChunkMap) world.getPlayerChunkMap()).chunksteward$getEntriesWithoutChunks()) {
                if (entry.getChunk() == null) {
                    waiting++;
                }
            }
        }
        return waiting;
    }

    public static void onSave() {
        lastSaveTick = tick;
    }

    /**
     * Ticks since {@code then}, or {@link Integer#MAX_VALUE} if that tick number lies in the future (a stale value from
     * before the tick counter restarted): a stale tick counts as long ago, never as recent.
     */
    static int ticksSince(int then) {
        int d = tick - then;
        return d < 0 ? Integer.MAX_VALUE : d;
    }

    /** True on a save tick and for the configured number of ticks after. Autosave runs at the end of every 900th tick. */
    static boolean savePaused() {
        int pause = ChunkStewardConfig.throttle.savePauseTicks;
        return tick % 900 == 0 || ticksSince(lastSaveTick) <= pause;
    }

    /** Back to a fresh session: tick numbers, measurements and caches (see {@link SessionState}). */
    static void reset() {
        tick = 0;
        serverThread = null;
        lastGenTick = Integer.MIN_VALUE / 2;
        lastSaveTick = Integer.MIN_VALUE / 2;
        generatedThisTick = 0;
        externalGenThisTick = 0;
        java.util.Arrays.fill(EXTERNAL_RING, 0);
        recentCacheTick = -1;
        recentCacheWindow = -1;
        waitingCacheTick = -1;
        java.util.Arrays.fill(WORK_RING, 0.0);
        ringCount = 0;
        ringPos = 0;
        ringSum = 0.0;
        primed = false;
        totalEmaMs = 0.0;
        otherEmaMs = 0.0;
        chunkEmaMs = 0.0;
    }

    static double currentTickMs() {
        return (System.nanoTime() - tickStartNanos) / 1.0e6;
    }

    static void countGenerated() {
        statGenerated++;
    }

    static void countSkipped() {
        statSkipped++;
    }

    static void countBypassed() {
        statBypassed++;
    }

    static void countPreloaded() {
        statPreloaded++;
    }

    static void countPreloadedTerrainOnly() {
        statPreloadedTerrainOnly++;
    }

    static void noteEmergencyRadius(int radius) {
        if (radius > statMaxEmergencyRadius) {
            statMaxEmergencyRadius = radius;
        }
    }
}
