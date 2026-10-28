package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.AccessorPlayerChunkMapEntry;
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
    /** Smoothed 0 at {@code tpsClamp}, 1 at {@code tpsFull}. Widens the headroom below full TPS. Not the API load factor. */
    private static double admissionFactor = 1.0;


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
        admissionFactor = 1.0;
        emergencyX = 0;
        emergencyZ = 0;
    }

    static double admissionFactor() {
        return admissionFactor;
    }

    /**
     * One step of the admission factor, from {@link TickMonitor#onTickStart}. {@code withinBudget} runs once per missing
     * chunk, so the smooth must not live there.
     */
    static void onTickStart() {
        ChunkStewardConfig.Throttle cfg = ChunkStewardConfig.throttle;
        double raw = AdmissionFactor.raw(TickMonitor.measuredTps(), cfg.tpsFull, cfg.tpsClamp);
        admissionFactor = AdmissionFactor.smooth(admissionFactor, raw);
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
        if (TerrainPipeline.serving(map.getWorldServer())) {
            return providePipelined(map, entry, debug);
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

    /**
     * A dimension whose generator has passed verify. A chunk Forge is already loading is not published. Ordinary chunks
     * wait for a worker result. Emergency, warmup, and the starvation guard still generate on the server thread.
     */
    private static boolean providePipelined(PlayerChunkMap map, PlayerChunkMapEntry entry, boolean debug) {
        if (((AccessorPlayerChunkMapEntry) entry).chunksteward$loading()) {
            PipelineStats.provide(PipelineStats.PROVIDE_LOADING);
            if (debug) {
                Diagnostics.provide("deferred: loading", map, entry, true);
            }
            return false;
        }
        boolean urgent = urgent(entry);
        if (!urgent && TickMonitor.savePaused()) {
            PipelineStats.provide(PipelineStats.PROVIDE_SAVING);
            TickMonitor.countSkipped();
            if (debug) {
                Diagnostics.provide("deferred: save", map, entry, true);
            }
            return false;
        }
        boolean finished = TerrainPipeline.ready(map.getWorldServer(), entry.getPos().x, entry.getPos().z);
        if (!urgent && !finished && !starvationDue()) {
            PipelineStats.provide(PipelineStats.PROVIDE_NOT_FINISHED);
            TickMonitor.countSkipped();
            if (debug) {
                Diagnostics.provide("deferred: pipeline", map, entry, true);
            }
            return false;
        }
        if (!urgent && finished && !withinBudget()) {
            PipelineStats.provide(PipelineStats.PROVIDE_HEADROOM);
            TickMonitor.countSkipped();
            if (debug) {
                Diagnostics.provide("deferred: headroom", map, entry, true);
            }
            return false;
        }
        if (urgent) {
            TickMonitor.countBypassed();
        }
        PipelineStats.provide(urgent ? PipelineStats.PROVIDE_URGENT
                : (finished ? PipelineStats.PROVIDE_INSTALLED : PipelineStats.PROVIDE_STARVED));
        if (debug) {
            Diagnostics.provide(finished ? "admitted: pipeline" : "admitted", map, entry, true);
        }
        return timeProvide(entry);
    }

    static boolean urgent(PlayerChunkMapEntry entry) {
        return isEmergency(entry) || Warmup.isWarm(entry);
    }

    private static boolean starvationDue() {
        return TickMonitor.generatedThisTick == 0
                && TickMonitor.ticksSince(TickMonitor.lastGenTick) >= ChunkStewardConfig.throttle.maxStarveTicks;
    }

    private static boolean timeProvide(PlayerChunkMapEntry entry) {
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
     * Per-tick headroom admission. Below {@code idleTickMs} of non-chunk work the server is idle and chunks load at
     * vanilla speed. With {@code admitAtFullTps}, measured TPS at or above {@code tpsFull} admits every chunk; vanilla's
     * own loop still stops 50 ms after a success. Below that, a chunk may generate while this tick's chunk time plus the
     * average cost of one chunk stays within {@code targetTickMs - non-chunk work}, plus {@code headroomBoostMs} scaled
     * by the admission factor (1 just under {@code tpsFull}, 0 at {@code tpsClamp}). The starvation guard admits one
     * chunk every {@code maxStarveTicks} even when that allowance says no. {@code admitAtFullTps = false} is the fixed
     * headroom with no boost. The API load factor does not gate chunks.
     */
    private static boolean withinBudget() {
        ChunkStewardConfig.Throttle cfg = ChunkStewardConfig.throttle;
        if (!TickMonitor.primed || TickMonitor.otherEmaMs < cfg.idleTickMs) {
            return true;
        }
        if (cfg.admitAtFullTps && TickMonitor.measuredTps() >= cfg.tpsFull) {
            return true;
        }
        double allowanceMs = AdmissionFactor.allowance(cfg.targetTickMs, TickMonitor.otherEmaMs,
                cfg.admitAtFullTps ? admissionFactor : 0.0,
                cfg.admitAtFullTps ? cfg.headroomBoostMs : 0.0);
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
