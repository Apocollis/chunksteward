package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;
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

    public static boolean provide(PlayerChunkMap map, PlayerChunkMapEntry entry, boolean canGenerate) {
        if (!canGenerate || !LazyChunksConfig.throttle.enabled) {
            return provided(entry, entry.providePlayerChunk(canGenerate));
        }
        boolean bypass = false;
        if (!Dimensions.applies(map.getWorldServer().provider.getDimension())) {
            bypass = true;
        } else if (isEmergency(entry) || Warmup.isWarm(entry)) {
            bypass = true;
        } else if (TickMonitor.savePaused()) {
            TickMonitor.countSkipped();
            return false;
        } else if (!withinBudget()) {
            TickMonitor.countSkipped();
            return false;
        }
        if (bypass) {
            TickMonitor.countBypassed();
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
        return provided(entry, provided);
    }

    private static boolean provided(PlayerChunkMapEntry entry, boolean provided) {
        if (provided) {
            GenMetrics.onProvided(entry);
        }
        return provided;
    }

    private static boolean withinBudget() {
        LazyChunksConfig.Throttle cfg = LazyChunksConfig.throttle;
        if (!TickMonitor.primed || TickMonitor.otherEmaMs < cfg.idleTickMs) {
            return true;
        }
        double allowanceMs = cfg.targetTickMs - TickMonitor.otherEmaMs;
        double usedMs = TickMonitor.chunkNanos / 1.0e6;
        if (usedMs + genCostEmaMs <= allowanceMs) {
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
        LazyChunksConfig.Throttle cfg = LazyChunksConfig.throttle;
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
