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
    private static final double CREDIT_FLOOR_MS = -200.0;
    private static double genCostEmaMs = INITIAL_COST_MS;

    /** Chunk work the server may still spend (ms); accrues each tick, is spent by every provided chunk, may go negative. */
    private static double credit;
    private static int creditTick = Integer.MIN_VALUE;

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

    static double credit() {
        return credit;
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
                Diagnostics.provide("deferred: credit", map, entry, true);
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
        credit = Math.max(CREDIT_FLOOR_MS, credit - spent / 1.0e6);
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
     * Credit-based admission. Each tick the server earns {@code (50 - non-chunk work) * loadFactor} ms of chunk work
     * (the whole rest of the tick while measured TPS is at {@code tpsFull}, nothing at {@code tpsLoaded} or below). A
     * chunk is admitted while the balance is positive and its real cost is subtracted, so the balance can go negative
     * and the following ticks pay it back. The average chunk work per tick then equals the allowance, even when one chunk
     * costs more than a single tick's share.
     */
    private static boolean withinBudget() {
        ChunkStewardConfig.Throttle cfg = ChunkStewardConfig.throttle;
        if (!TickMonitor.primed || TickMonitor.otherEmaMs < cfg.idleTickMs) {
            credit = 0.0;
            return true;
        }
        if (creditTick != TickMonitor.tick) {
            int elapsed = creditTick == Integer.MIN_VALUE ? 1 : Math.min(TickMonitor.tick - creditTick, 20);
            creditTick = TickMonitor.tick;
            double factor = TickMonitor.loadFactor();
            double allowance = Math.max(0.0, FULL_TICK_MS - TickMonitor.otherEmaMs) * factor;
            double cap = Math.max(30.0, 1.5 * genCostEmaMs) * factor;
            credit = Math.min(cap, credit + allowance * elapsed);
        }
        if (credit > 0.0) {
            return true;
        }
        // Starvation guard: one chunk per maxStarveTicks even when the server is busy.
        return TickMonitor.generatedThisTick == 0
                && TickMonitor.tick - TickMonitor.lastGenTick >= cfg.maxStarveTicks;
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
