package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.google.common.base.Predicate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;

/**
 * Decides, per entry, whether {@code providePlayerChunk(true)} may run this tick. Calls with
 * {@code canGenerate == false} only load from disk and always run.
 */
public final class ChunkThrottle {
    private static final double INITIAL_COST_MS = 10.0;
    private static final double FULL_TICK_MS = 50.0;
    private static double genCostEmaMs = INITIAL_COST_MS;


    private static int emergencyX;
    private static int emergencyZ;

    private static final Predicate<EntityPlayerMP> NEAR_PLAYER = new Predicate<EntityPlayerMP>() {
        @Override
        public boolean apply(EntityPlayerMP player) {
            if (player == null) {
                return false;
            }
            int radius = emergencyRadius(player);
            TickMonitor.noteEmergencyRadius(radius);
            return Math.abs((((int) Math.floor(player.posX)) >> 4) - emergencyX) <= radius
                    && Math.abs((((int) Math.floor(player.posZ)) >> 4) - emergencyZ) <= radius;
        }
    };

    private ChunkThrottle() {
    }

    static void reset() {
        genCostEmaMs = INITIAL_COST_MS;
        emergencyX = 0;
        emergencyZ = 0;
    }

    public static boolean provide(PlayerChunkMap map, PlayerChunkMapEntry entry, boolean canGenerate) {
        boolean debug = Diagnostics.on();
        if (!canGenerate || !ChunkStewardConfig.throttle.enabled) {
            if (debug) {
                Diagnostics.provide(canGenerate ? "pass: throttle off" : "pass: load only", map, entry, canGenerate);
            }
            boolean loaded = entry.providePlayerChunk(canGenerate);
            Diagnostics.sanity(entry, loaded);
            return provided(entry, loaded);
        }
        boolean bypass = false;
        String outcome = "admitted";
        if (!Dimensions.applies(map.getWorldServer().provider.getDimension())) {
            bypass = true;
            outcome = "bypass: dimension excluded";
        } else if (isEmergency(entry)) {
            bypass = true;
            outcome = "bypass: emergency radius";
        } else if (Warmup.isWarm(entry)) {
            bypass = true;
            outcome = "bypass: login warmup";
        } else if (TickMonitor.savePaused()) {
            TickMonitor.countSkipped();
            if (debug) {
                Diagnostics.provide("deferred: save", map, entry, true);
            }
            return false;
        } else if (!withinBudget()) {
            TickMonitor.countSkipped();
            if (debug) {
                Diagnostics.provide("deferred: headroom", map, entry, true);
            }
            return false;
        }
        if (bypass) {
            TickMonitor.countBypassed();
        }
        if (debug) {
            Diagnostics.provide(outcome, map, entry, true);
        }

        long start = System.nanoTime();
        boolean provided = entry.providePlayerChunk(true);
        long spent = System.nanoTime() - start;
        TickMonitor.chunkNanos += spent;
        if (provided) {
            genCostEmaMs += 0.2 * (spent / 1.0e6 - genCostEmaMs);
            TickMonitor.lastGenTick = TickMonitor.tick;
            TickMonitor.generatedThisTick++;
            TickMonitor.countGenerated();
        }
        Diagnostics.sanity(entry, provided);
        return provided(entry, provided);
    }

    private static boolean provided(PlayerChunkMapEntry entry, boolean provided) {
        if (provided) {
            GenMetrics.onProvided(entry);
        }
        return provided;
    }

    /**
     * Per-tick headroom admission (the behaviour before the pacing API): below {@code idleTickMs} of non-chunk work the
     * server is idle and chunks load at vanilla speed; otherwise a chunk may generate while this tick's chunk time plus
     * the average cost of one chunk stays within {@code targetTickMs - non-chunk work}. The starvation guard admits one
     * chunk every {@code maxStarveTicks} even when the server is busy. Measured TPS and the load factor are only reported
     * (status log, {@code ChunkStewardAPI}); they do not gate chunks.
     *
     * <p>Before that, with {@code admitAtFullTps}: while the server holds 20 TPS (measured TPS at {@code tpsFull}, or the
     * whole-tick average of about 20 ticks under 50 ms) every chunk is admitted, however long the current tick already
     * is; vanilla's own loop still stops 50 ms after a success. If chunk work pushes the average past 50 ms, TPS drops
     * below 20 and the headroom rule takes over. In this pack one chunk costs 25 to 50 ms, more than the headroom under
     * {@code targetTickMs}, so the headroom rule alone held chunks to the starvation rate (5 a second) at 20 TPS.
     */
    private static boolean withinBudget() {
        ChunkStewardConfig.Throttle cfg = ChunkStewardConfig.throttle;
        if (!TickMonitor.primed || TickMonitor.otherEmaMs < cfg.idleTickMs) {
            return true;
        }
        if (cfg.admitAtFullTps
                && (TickMonitor.measuredTps() >= cfg.tpsFull || TickMonitor.totalEmaMs < FULL_TICK_MS)) {
            return true;
        }
        double allowanceMs = cfg.targetTickMs - TickMonitor.otherEmaMs;
        double usedMs = TickMonitor.chunkNanos / 1.0e6;
        if (usedMs + genCostEmaMs <= allowanceMs) {
            return true;
        }
        // Starvation guard: one chunk per maxStarveTicks even when the server is busy.
        return TickMonitor.generatedThisTick == 0
                && TickMonitor.ticksSince(TickMonitor.lastGenTick) >= cfg.maxStarveTicks;
    }

    private static boolean isEmergency(PlayerChunkMapEntry entry) {
        ChunkPos pos = entry.getPos();
        emergencyX = pos.x;
        emergencyZ = pos.z;
        return entry.hasPlayerMatching(NEAR_PLAYER);
    }

    /**
     * {@code emergencyRadius}, plus {@code ceil(speed * emergencyLookaheadTicks / 16)} once the player moves faster than
     * {@code emergencyMinSpeed}, capped at {@code emergencyMaxRadius}. Walking and sprinting keep the base radius.
     */
    static int emergencyRadius(EntityPlayerMP player) {
        ChunkStewardConfig.Throttle cfg = ChunkStewardConfig.throttle;
        int base = cfg.emergencyRadius;
        if (cfg.emergencyLookaheadTicks <= 0) {
            return base;
        }
        double speed = PlayerMotion.speed(player);
        if (speed <= cfg.emergencyMinSpeed) {
            return base;
        }
        int extra = (int) Math.ceil(speed * cfg.emergencyLookaheadTicks / 16.0);
        return Math.min(base + extra, Math.max(base, cfg.emergencyMaxRadius));
    }
}
