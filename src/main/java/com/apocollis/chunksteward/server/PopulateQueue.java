package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunks whose populate was deferred by {@link StageGate}, and the per-tick drain that runs them.
 *
 * <p>A queued chunk stays loaded and unpopulated, so it is not sent to players yet. Each tick, at tick END, the
 * drain runs the nearest and most-forward queued chunks (the same direction weighting as the missing-chunk order)
 * with vanilla's own {@code chunk.populate(provider, generator)}, the call a load would have made. It spends at
 * most {@code drainPerTick} populates while the tick is under {@code tickBudgetMs}, guarantees one every
 * {@code maxStarveTicks}, and always runs chunks that have a player close by. A chunk that was unloaded in the
 * meantime is dropped: it is saved unpopulated and vanilla populates it after its next load.
 */
final class PopulateQueue {
    private static final int MAX_URGENT_PER_TICK = 4;
    private static final int MAX_ROUNDS_PER_TICK = 64;

    private static final class Dim {
        final LongOpenHashSet set = new LongOpenHashSet();
        final LongArrayList list = new LongArrayList();
    }

    private static final Map<Integer, Dim> DIMS = new HashMap<>();
    private static int total;
    private static boolean draining;
    private static int lastRunTick = Integer.MIN_VALUE / 2;

    private static long statDrained;
    private static long statDropped;

    // best-candidate scratch
    private static int bestDim;
    private static int bestIndex;
    private static long bestKey;
    private static double bestScore;
    private static boolean bestNear;
    private static final double[] DIR = new double[2];

    private PopulateQueue() {
    }

    static boolean contains(int dim, long key) {
        Dim d = DIMS.get(dim);
        return d != null && d.set.contains(key);
    }

    /** Queues the chunk. False when the queue is full: the caller should let the populate run. */
    static boolean enqueue(int dim, long key) {
        if (total >= ChunkStewardConfig.populate.maxQueued) {
            return false;
        }
        Dim d = DIMS.computeIfAbsent(dim, k -> new Dim());
        if (d.set.add(key)) {
            d.list.add(key);
            total++;
        }
        return true;
    }

    /** Takes the chunk out of the queue (it is about to populate by other means). */
    static void remove(int dim, long key) {
        Dim d = DIMS.get(dim);
        if (d != null && d.set.remove(key)) {
            d.list.rem(key);
            total--;
            if (d.list.isEmpty()) {
                DIMS.remove(dim);
            }
        }
    }

    /** Up to {@code limit} queued chunks as {dimension, x, z}. */
    static java.util.List<int[]> sample(int limit) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Dim> e : DIMS.entrySet()) {
            LongArrayList list = e.getValue().list;
            for (int i = 0; i < list.size() && out.size() < limit; i++) {
                long key = list.getLong(i);
                out.add(new int[] {e.getKey(), (int) key, (int) (key >> 32)});
            }
        }
        return out;
    }

    static boolean isDraining() {
        return draining;
    }

    static int size() {
        return total;
    }

    static void clear(int dim) {
        Dim d = DIMS.remove(dim);
        if (d != null) {
            total -= d.list.size();
        }
    }

    /** Within {@code radius} chunks of a player in this world, or within that player's speed-scaled emergency radius. */
    static boolean nearPlayer(World world, int cx, int cz, int radius) {
        for (EntityPlayer player : world.playerEntities) {
            int r = radius;
            if (player instanceof EntityPlayerMP) {
                r = Math.max(r, ChunkThrottle.emergencyRadius((EntityPlayerMP) player));
            }
            if (Math.abs(cx - (((int) Math.floor(player.posX)) >> 4)) <= r
                    && Math.abs(cz - (((int) Math.floor(player.posZ)) >> 4)) <= r) {
                return true;
            }
        }
        return false;
    }

    static void drain() {
        if (total == 0) {
            return;
        }
        ChunkStewardConfig.Populate cfg = ChunkStewardConfig.populate;
        boolean starved = TickMonitor.tick - lastRunTick >= cfg.maxStarveTicks;
        int done = 0;
        int urgent = 0;
        int rounds = 0;
        // The round cap bounds the scans when many queued chunks turn out to be unloaded already.
        while (total > 0 && rounds++ < MAX_ROUNDS_PER_TICK && pickBest(cfg)) {
            if (bestNear) {
                if (urgent >= MAX_URGENT_PER_TICK) {
                    break;
                }
            } else {
                if (done >= cfg.drainPerTick) {
                    break;
                }
                if (TickMonitor.currentTickMs() >= cfg.tickBudgetMs && !(starved && done == 0 && urgent == 0)) {
                    break;
                }
            }
            Dim d = DIMS.get(bestDim);
            int last = d.list.size() - 1;
            d.list.set(bestIndex, d.list.getLong(last));
            d.list.removeLong(last);
            d.set.remove(bestKey);
            total--;
            if (d.list.isEmpty()) {
                DIMS.remove(bestDim);
            }
            if (run(bestDim, bestKey)) {
                if (bestNear) {
                    urgent++;
                } else {
                    done++;
                }
            }
        }
    }

    /** Runs vanilla's populate for the chunk if it is still loaded and unpopulated. */
    private static boolean run(int dim, long key) {
        WorldServer world = DimensionManager.getWorld(dim);
        if (world == null) {
            return false;
        }
        ChunkProviderServer provider = world.getChunkProvider();
        Chunk chunk = StageGate.peek(provider, (int) key, (int) (key >> 32));
        if (chunk == null || chunk.isTerrainPopulated()) {
            statDropped++;
            return false;
        }
        draining = true;
        try {
            chunk.populate(provider, provider.chunkGenerator);
        } finally {
            draining = false;
        }
        statDrained++;
        lastRunTick = TickMonitor.tick;
        return true;
    }

    /** Finds the queued chunk to run next across all dimensions; fills the best* fields. */
    private static boolean pickBest(ChunkStewardConfig.Populate cfg) {
        boolean found = false;
        bestScore = Double.MAX_VALUE;
        for (Map.Entry<Integer, Dim> e : DIMS.entrySet()) {
            WorldServer world = DimensionManager.getWorld(e.getKey());
            LongArrayList list = e.getValue().list;
            for (int i = 0; i < list.size(); i++) {
                long key = list.getLong(i);
                int x = (int) key;
                int z = (int) (key >> 32);
                double score = world == null ? -1.0 : score(world, x, z);
                if (!found || score < bestScore) {
                    found = true;
                    bestScore = score;
                    bestDim = e.getKey();
                    bestIndex = i;
                    bestKey = key;
                    bestNear = world != null && nearPlayer(world, x, z, cfg.protectRadius);
                }
            }
        }
        return found;
    }

    /** Squared distance to the closest player, scaled by the direction penalty. Missing world: sorts first (dropped). */
    private static double score(WorldServer world, int cx, int cz) {
        ChunkStewardConfig.Ordering ord = ChunkStewardConfig.ordering;
        double penalty = ord.enabled ? ord.directionPenalty : 0.0;
        double centerX = cx * 16 + 8;
        double centerZ = cz * 16 + 8;
        double best = 1.0e18;
        for (EntityPlayer player : world.playerEntities) {
            double dx = centerX - player.posX;
            double dz = centerZ - player.posZ;
            double distSq = dx * dx + dz * dz;
            double score = distSq;
            if (penalty > 0.0 && distSq > 1.0e-6) {
                PlayerMotion.direction(player, DIR);
                double cos = (dx * DIR[0] + dz * DIR[1]) / Math.sqrt(distSq);
                double factor = 1.0 + penalty * (1.0 - cos) * 0.5;
                score = distSq * factor * factor;
            }
            if (score < best) {
                best = score;
            }
        }
        return best;
    }

    static String reportAndReset() {
        String s = "populate queue " + total + " (drained " + statDrained + ", dropped " + statDropped + ")";
        statDrained = statDropped = 0L;
        return s;
    }

    static long key(int x, int z) {
        return ChunkPos.asLong(x, z);
    }
}
