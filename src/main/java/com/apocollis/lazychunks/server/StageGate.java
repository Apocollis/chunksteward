package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * One place that answers "run this generation stage for this chunk now, or defer it". The mixins ask
 * {@link #runNow}; a {@code false} answer means the caller skips the stage and this class owns the follow-up.
 *
 * <ul>
 * <li>{@link Stage#POPULATE}: deferred only for terrain-only chunks made by {@link IdlePreload}, and only while
 * preload itself is generating. Follow-up: none needed while the chunk is unloaded, since vanilla populates it when it
 * is loaded again next to its neighbours. If a player's chunk map claims it while it is still loaded,
 * {@link #onServerTick} runs vanilla's {@code chunk.populate(provider, generator)} on it, the same call a load
 * would have made.</li>
 * <li>{@link Stage#LIGHT}: {@code checkLight()} retries on terrain-populated, unlit chunks (the per-tick retry
 * {@code Chunk.onTick} makes, through Somnia/Tweaks in this pack), deferred (when enabled) for chunks away from
 * players and outside their travel direction during heavy generation. Follow-up: a per-dimension queue drained
 * within a per-tick budget. Only positions are queued, so an unloaded chunk is simply dropped when drained; it is
 * saved with {@code LightPopulated = false} and vanilla relights it after the next load.</li>
 * </ul>
 *
 * <p>For 0.3: a populate queue and neighbour tracking slot in as another {@link Stage} case with its own
 * {@code DimQueue}-style follow-up; callers do not change.
 */
@Mod.EventBusSubscriber
public final class StageGate {

    public enum Stage {
        POPULATE,
        LIGHT
    }

    private static final double FRONT_CONE_COS = 0.5;

    private static final class DimQueue {
        final LongOpenHashSet queued = new LongOpenHashSet();
        final LongArrayFIFOQueue order = new LongArrayFIFOQueue();
    }

    // Terrain-only populate suppression.
    private static boolean preloading;
    private static int preloadDim;
    private static final Map<Integer, LongOpenHashSet> TERRAIN_ONLY = new HashMap<>();

    // Deferred light.
    private static final Map<Integer, DimQueue> LIGHT_QUEUES = new HashMap<>();
    private static int lightQueued;
    private static boolean draining;
    private static final double[] DIR = new double[2];

    private static long statPopulateDeferred;
    private static long statPopulateResumed;
    private static long statLightDeferred;
    private static long statLightDrained;

    private StageGate() {
    }

    public static boolean runNow(Stage stage, Chunk chunk) {
        switch (stage) {
            case POPULATE:
                return populateNow(chunk);
            case LIGHT:
                return lightNow(chunk);
            default:
                return true;
        }
    }

    // ---- populate -------------------------------------------------------------------------------------------------

    /** Generates terrain only for {@code (x, z)}: call around the preload's {@code provideChunk}. */
    static void beginTerrainOnly(int dim, int x, int z) {
        preloading = true;
        preloadDim = dim;
        TERRAIN_ONLY.computeIfAbsent(dim, d -> new LongOpenHashSet()).add(ChunkPos.asLong(x, z));
    }

    static void endTerrainOnly() {
        preloading = false;
    }

    /** Preload is generating a full chunk; still keep its populate away from terrain-only chunks. */
    static void beginPreload(int dim) {
        preloading = true;
        preloadDim = dim;
    }

    static void endPreload() {
        preloading = false;
    }

    private static boolean populateNow(Chunk chunk) {
        if (!preloading) {
            return true;
        }
        World world = chunk.getWorld();
        if (world.provider.getDimension() != preloadDim) {
            return true;
        }
        LongOpenHashSet set = TERRAIN_ONLY.get(preloadDim);
        if (set == null || !set.contains(ChunkPos.asLong(chunk.x, chunk.z))) {
            return true;
        }
        statPopulateDeferred++;
        return false;
    }

    /** Drops tracked terrain-only chunks once unloaded; populates the ones a player's chunk map picked up. */
    private static void reconcileTerrainOnly() {
        Iterator<Map.Entry<Integer, LongOpenHashSet>> dims = TERRAIN_ONLY.entrySet().iterator();
        while (dims.hasNext()) {
            Map.Entry<Integer, LongOpenHashSet> e = dims.next();
            WorldServer world = DimensionManager.getWorld(e.getKey());
            if (world == null) {
                dims.remove();
                continue;
            }
            ChunkProviderServer provider = world.getChunkProvider();
            LongIterator it = e.getValue().iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int x = (int) key;
                int z = (int) (key >> 32);
                Chunk chunk = peek(provider, x, z);
                if (chunk == null || chunk.isTerrainPopulated()) {
                    it.remove();
                } else if (world.getPlayerChunkMap().contains(x, z)) {
                    it.remove();
                    statPopulateResumed++;
                    chunk.populate(provider, provider.chunkGenerator);
                }
            }
            if (e.getValue().isEmpty()) {
                dims.remove();
            }
        }
    }

    /**
     * The loaded chunk or null, without side effects. {@code getLoadedChunk} clears {@code unloadQueued}, which would
     * cancel a pending unload and leave the chunk loaded.
     */
    static Chunk peek(ChunkProviderServer provider, int x, int z) {
        return provider.loadedChunks.get(ChunkPos.asLong(x, z));
    }

    // ---- light ----------------------------------------------------------------------------------------------------

    private static boolean lightNow(Chunk chunk) {
        LazyChunksConfig.Light cfg = LazyChunksConfig.light;
        if (!cfg.deferEnabled || draining || Thread.currentThread() != TickMonitor.serverThread) {
            return true;
        }
        World world = chunk.getWorld();
        if (world.isRemote) {
            return true;
        }
        int dim = world.provider.getDimension();
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        DimQueue queue = LIGHT_QUEUES.get(dim);
        boolean queued = queue != null && queue.queued.contains(key);
        if (!queued && (TickMonitor.chunkEmaMs < cfg.heavyGenMs || lightQueued >= cfg.maxQueued
                || !Dimensions.applies(dim))) {
            return true;
        }
        if (isProtected(world, chunk.x, chunk.z, cfg.protectRadius)) {
            return true;
        }
        if (!queued) {
            if (queue == null) {
                queue = new DimQueue();
                LIGHT_QUEUES.put(dim, queue);
            }
            queue.queued.add(key);
            queue.order.enqueue(key);
            lightQueued++;
            statLightDeferred++;
        }
        return false;
    }

    /** Near a player, or inside the 60 degree cone of their direction. */
    private static boolean isProtected(World world, int cx, int cz, int radius) {
        for (EntityPlayer player : world.playerEntities) {
            int dx = cx - (((int) Math.floor(player.posX)) >> 4);
            int dz = cz - (((int) Math.floor(player.posZ)) >> 4);
            if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) {
                return true;
            }
            PlayerMotion.direction(player, DIR);
            if ((dx * DIR[0] + dz * DIR[1]) / Math.sqrt(dx * dx + dz * dz) >= FRONT_CONE_COS) {
                return true;
            }
        }
        return false;
    }

    private static void drainLight() {
        if (lightQueued == 0) {
            return;
        }
        LazyChunksConfig.Light cfg = LazyChunksConfig.light;
        long deadline = System.nanoTime() + (long) (cfg.drainBudgetMs * 1.0e6);
        int budget = cfg.drainPerTick;
        Iterator<Map.Entry<Integer, DimQueue>> dims = LIGHT_QUEUES.entrySet().iterator();
        while (dims.hasNext() && budget > 0) {
            Map.Entry<Integer, DimQueue> e = dims.next();
            DimQueue queue = e.getValue();
            WorldServer world = DimensionManager.getWorld(e.getKey());
            while (!queue.order.isEmpty() && budget > 0 && System.nanoTime() < deadline) {
                long key = queue.order.dequeueLong();
                queue.queued.remove(key);
                lightQueued--;
                if (world == null) {
                    continue;
                }
                Chunk chunk = peek(world.getChunkProvider(), (int) key, (int) (key >> 32));
                if (chunk != null && !chunk.isLightPopulated() && chunk.isTerrainPopulated()) {
                    draining = true;
                    try {
                        chunk.checkLight();
                    } finally {
                        draining = false;
                    }
                    statLightDrained++;
                    budget--;
                }
            }
            if (queue.order.isEmpty()) {
                dims.remove();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        preloading = false;
        if (!TERRAIN_ONLY.isEmpty()) {
            reconcileTerrainOnly();
        }
        drainLight();
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            return;
        }
        int dim = event.getWorld().provider.getDimension();
        TERRAIN_ONLY.remove(dim);
        DimQueue queue = LIGHT_QUEUES.remove(dim);
        if (queue != null) {
            lightQueued -= queue.order.size();
        }
    }

    static int lightQueued() {
        return lightQueued;
    }

    static String reportAndReset() {
        String s = "populate deferred " + statPopulateDeferred + ", resumed " + statPopulateResumed
                + "; light deferred " + statLightDeferred + ", drained " + statLightDrained + ", queued " + lightQueued;
        statPopulateDeferred = statPopulateResumed = statLightDeferred = statLightDrained = 0L;
        return s;
    }
}
