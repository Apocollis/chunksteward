package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.LazyChunks;
import com.apocollis.lazychunks.config.LazyChunksConfig;
import com.apocollis.lazychunks.mixin.AccessorPlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Server-thread tick bookkeeping: how much of each tick was chunk provision and how much was everything else.
 * All state is touched from the server thread only.
 */
public final class TickMonitor {
    private static final double ALPHA = 0.1;

    static int tick;
    /** Thread that runs {@code MinecraftServer.tick}; metrics and gates ignore every other thread. */
    static Thread serverThread;
    static long tickStartNanos;
    static long chunkNanos;
    static int generatedThisTick;
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
        tick = serverTick;
        serverThread = Thread.currentThread();
        GenMetrics.onTickStart();
        tickStartNanos = System.nanoTime();
        chunkNanos = 0L;
        generatedThisTick = 0;
    }

    public static void onTickEnd() {
        long now = System.nanoTime();
        double totalMs = (now - tickStartNanos) / 1.0e6;
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
        int interval = LazyChunksConfig.logging.statusIntervalSeconds;
        if (interval > 0 && now - lastLogNanos >= interval * 1_000_000_000L) {
            lastLogNanos = now;
            LazyChunks.LOGGER.info("status: tick avg {} ms (non-chunk {} ms, chunk {} ms), chunks generated {}, "
                            + "deferred {}, bypassed {} (max emergency radius {}), waiting {}, preloaded {} "
                            + "(+{} terrain-only); {}",
                    String.format("%.1f", totalEmaMs), String.format("%.1f", otherEmaMs),
                    String.format("%.1f", chunkEmaMs), statGenerated, statSkipped, statBypassed,
                    statMaxEmergencyRadius, countWaiting(), statPreloaded, statPreloadedTerrainOnly,
                    StageGate.reportAndReset());
            LazyChunks.LOGGER.info("status: {}", GenMetrics.reportAndReset());
            statGenerated = statSkipped = statBypassed = statPreloaded = statPreloadedTerrainOnly = 0L;
            statMaxEmergencyRadius = 0;
        }
    }

    /** Player chunk map entries still without a chunk, all loaded worlds. */
    private static int countWaiting() {
        int waiting = 0;
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (PlayerChunkMapEntry entry
                    : ((AccessorPlayerChunkMap) world.getPlayerChunkMap()).lazychunks$getEntriesWithoutChunks()) {
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

    /** True on a save tick and for the configured number of ticks after. Autosave runs at the end of every 900th tick. */
    static boolean savePaused() {
        int pause = LazyChunksConfig.throttle.savePauseTicks;
        return tick % 900 == 0 || tick - lastSaveTick <= pause;
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
