package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.google.common.collect.ImmutableSetMultimap;
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
import net.minecraftforge.common.ForgeChunkManager;
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
 * <li>{@link Stage#POPULATE}: deferred for (1) terrain-only chunks made by {@link IdlePreload} while preload itself is
 * generating, (2) any chunk generated while another chunk is populating (a cascade), and (3) any chunk whose populate
 * would push the current tick past {@code populate.tickBudgetMs}, except chunks near players. Follow-up for (2) and
 * (3): {@link PopulateQueue}, drained each tick within a budget. For (1): nothing while the chunk is unloaded, since
 * vanilla populates it when it is loaded again next to its neighbours; if a player's chunk map claims it while it is
 * still loaded, it is queued like the others.</li>
 * <li>{@link Stage#LIGHT}: {@code checkLight()} retries on terrain-populated, unlit chunks (the per-tick retry
 * {@code Chunk.onTick} makes, through Somnia/Tweaks in this pack), deferred (when enabled) for chunks away from
 * players and outside their travel direction during heavy generation. Follow-up: a per-dimension queue drained
 * within a per-tick budget. Only positions are queued, so an unloaded chunk is simply dropped when drained; it is
 * saved with {@code LightPopulated = false} and vanilla relights it after the next load.</li>
 * </ul>
 *
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
    /** Stage 2 is loading chunks to populate them (see {@link Stage2Pregen}); its populates skip the budget deferral. */
    private static boolean stage2Active;
    private static int preloadDim;
    private static final Map<Integer, LongOpenHashSet> TERRAIN_ONLY = new HashMap<>();

    // Deferred light.
    private static final Map<Integer, DimQueue> LIGHT_QUEUES = new HashMap<>();
    private static int lightQueued;
    private static boolean draining;
    private static final double[] DIR = new double[2];

    // Populate depth: the chunks whose populate has started and not yet returned (frames guard against a cancelled head).
    private static final Chunk[] FRAMES = new Chunk[32];
    private static int populateDepth;
    private static int populatesThisTick;
    private static int lastPopulateTick = Integer.MIN_VALUE / 2;

    // RTG-Plus LatePopulatingChunk: its outer populate runs a 5x5 pass over the chunks around the loaded one. The chunk
    // that was loaded owns the window; chunks that RTG loads or generates inside it are cancelled or queued.
    private static final Chunk[] RTG_WINDOWS = new Chunk[8];
    private static int rtgDepth;
    private static long statRtgWindows;
    private static long statRtgNestedCancelled;

    private static long statPopulateDeferred;
    private static long statPopulateCascadeDeferred;
    private static long statPopulateTicketExempt;
    private static long statBusyIgnored;
    private static long statPopulateResumed;
    private static long statCascades;
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

    private static String populateCode = "";

    private static boolean ret(String code, boolean value) {
        populateCode = code;
        return value;
    }

    private static boolean populateNow(Chunk chunk) {
        boolean run = populateDecision(chunk);
        if (ChunkStewardConfig.logging.debug) {
            Diagnostics.populate(populateCode, run, chunk);
        }
        return run;
    }

    private static boolean populateDecision(Chunk chunk) {
        return populateDecision(chunk, false);
    }

    /**
     * {@code member}: a chunk inside an RTG window that is not the window owner. The drain and stage 2 passes are
     * for the one chunk they asked for, so they are skipped here; everything else applies, including the near-player
     * pass (an eligible member is exactly a chunk vanilla would populate, one decision per chunk).
     */
    private static boolean populateDecision(Chunk chunk, boolean member) {
        World world = chunk.getWorld();
        int dim = world.provider.getDimension();
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        if (preloading && dim == preloadDim) {
            LongOpenHashSet set = TERRAIN_ONLY.get(preloadDim);
            if (set != null && set.contains(key)) {
                statPopulateDeferred++;
                return ret("preload terrain-only chunk", false);
            }
        }
        ChunkStewardConfig.Populate cfg = ChunkStewardConfig.populate;
        if (!cfg.enabled || world.isRemote || Thread.currentThread() != TickMonitor.serverThread
                || !Dimensions.applies(dim)) {
            return ret("pass: disabled, client, other thread or excluded dimension", true);
        }
        if (FeatureLane.conflicts(dim, chunk.x, chunk.z)) {
            // A staged populate nearby has not been applied yet: this chunk's window overlaps its window, so it waits
            // for it, which is the order an unstaged populate would have used.
            PopulateQueue.enqueue(dim, key);
            statPopulateDeferred++;
            return ret("staged populate nearby", false);
        }
        if (!member && populateDepth == 0 && (PopulateQueue.isDraining() || stage2Active)) {
            return ret("pass: queue drain or stage 2", true);
        }
        if (populateDepth == 0 && ticketExempt(world, chunk.x, chunk.z)) {
            PopulateQueue.remove(dim, key);
            statPopulateTicketExempt++;
            return ret("pass: ticket exempt", true);
        }
        if (PopulateQueue.contains(dim, key)) {
            return ret("already queued", false);
        }
        if (PopulateQueue.nearPlayer(world, chunk.x, chunk.z, cfg.protectRadius)
                || Warmup.anyWarmNear(world, chunk.x, chunk.z)) {
            return ret("near a player or login warmup", true);
        }
        boolean cascade = populateDepth > 0 && cfg.cascadeDeferral;
        if (!cascade && withinPopulateBudget(cfg)) {
            return ret("within budget", true);
        }
        if (!PopulateQueue.enqueue(dim, key)) {
            return ret("queue full", true);
        }
        if (cascade) {
            statPopulateCascadeDeferred++;
            return ret("cascade", false);
        }
        statPopulateDeferred++;
        return ret("over budget", false);
    }

    /**
     * The chunk is held by a Forge ticket owned by a mod on {@code populate.ticketExemptMods}. Such a mod forces the
     * chunk on its ticket before it calls populate itself, so deferring that populate would run it after the mod built
     * on the chunk.
     */
    private static boolean ticketExempt(World world, int x, int z) {
        return heldByModTicket(world, x, z, ChunkStewardConfig.populate.ticketExemptMods);
    }

    /** The chunk is held by a Forge chunk ticket owned by one of {@code mods}. */
    private static boolean heldByModTicket(World world, int x, int z, String[] mods) {
        if (mods == null || mods.length == 0) {
            return false;
        }
        ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> forced =
                ForgeChunkManager.getPersistentChunksFor(world);
        if (forced.isEmpty()) {
            return false;
        }
        for (ForgeChunkManager.Ticket ticket : forced.get(new ChunkPos(x, z))) {
            String owner = ticket.getModId();
            for (String mod : mods) {
                if (mod != null && mod.trim().equals(owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The current tick has room for a populate, or the starvation guard says one is overdue. */
    private static boolean withinPopulateBudget(ChunkStewardConfig.Populate cfg) {
        if (TickMonitor.currentTickMs() < cfg.tickBudgetMs) {
            return true;
        }
        return populatesThisTick == 0 && TickMonitor.ticksSince(lastPopulateTick) >= cfg.maxStarveTicks;
    }

    /** A populate passed the gate and is starting. */
    public static void populateEnter(Chunk chunk) {
        if (Thread.currentThread() != TickMonitor.serverThread) {
            return;
        }
        if (populateDepth < FRAMES.length) {
            FRAMES[populateDepth] = chunk;
        }
        populateDepth++;
        if (populateDepth == 1) {
            populatesThisTick++;
            lastPopulateTick = TickMonitor.tick;
        }
    }

    /** A populate returned. A head that was cancelled never entered, so a frame mismatch is ignored. */
    public static void populateExit(Chunk chunk) {
        if (populateDepth == 0 || Thread.currentThread() != TickMonitor.serverThread) {
            return;
        }
        int top = populateDepth - 1;
        if (top < FRAMES.length) {
            if (FRAMES[top] != chunk) {
                return;
            }
            FRAMES[top] = null;
        }
        populateDepth--;
    }

    // ---- RTG-Plus LatePopulatingChunk ------------------------------------------------------------------------------

    private static final String TERRAIN_ONLY_CODE = "preload terrain-only chunk";

    private static boolean rtgApplies(Chunk chunk) {
        World world = chunk.getWorld();
        return ChunkStewardConfig.populate.enabled && !world.isRemote
                && Thread.currentThread() == TickMonitor.serverThread && Dimensions.applies(world.provider.getDimension());
    }

    /**
     * Head of RTG's outer {@code populate(IChunkProvider, IChunkGenerator)}: true lets RTG run its pass for this chunk
     * (and opens a window), false cancels it. One decision per chunk vanilla asked for.
     */
    public static boolean rtgOuterAllow(Chunk chunk) {
        if (!rtgApplies(chunk)) {
            return true;
        }
        World world = chunk.getWorld();
        int dim = world.provider.getDimension();
        long key = ChunkPos.asLong(chunk.x, chunk.z);
        if (rtgDepth > 0) {
            // A load or cascade re-entered the outer method inside a window: never nest another 5x5 pass.
            statRtgNestedCancelled++;
            if (populateDepth > 0 && !chunk.isTerrainPopulated() && !PopulateQueue.contains(dim, key)
                    && PopulateQueue.enqueue(dim, key)) {
                statPopulateCascadeDeferred++;
            }
            return false;
        }
        boolean run = populateNow(chunk);
        if (!run && !TERRAIN_ONLY_CODE.equals(populateCode)
                && PopulateQueue.nearPlayer(world, chunk.x, chunk.z, ChunkStewardConfig.populate.protectRadius + 1)) {
            // The chunks this pass would populate lie within one chunk of it: a player next to them must not wait.
            PopulateQueue.remove(dim, key);
            run = true;
        }
        if (!run) {
            return false;
        }
        if (rtgDepth < RTG_WINDOWS.length) {
            RTG_WINDOWS[rtgDepth] = chunk;
        }
        rtgDepth++;
        statRtgWindows++;
        return true;
    }

    /** RETURN of RTG's outer populate. A cancelled head never opened a window, so a mismatch is ignored. */
    public static void rtgOuterExit(Chunk chunk) {
        if (rtgDepth == 0 || Thread.currentThread() != TickMonitor.serverThread) {
            return;
        }
        int top = rtgDepth - 1;
        if (top < RTG_WINDOWS.length) {
            if (RTG_WINDOWS[top] != chunk) {
                return;
            }
            RTG_WINDOWS[top] = null;
        }
        rtgDepth--;
    }

    /** Head of RTG's static {@code populate(Chunk, IChunkGenerator)}, for a chunk that is not populated yet. */
    public static boolean rtgMemberAllow(Chunk chunk) {
        if (!rtgApplies(chunk)) {
            return true;
        }
        if (rtgDepth == 0) {
            return populateNow(chunk);
        }
        int top = rtgDepth - 1;
        boolean owner = top < RTG_WINDOWS.length && RTG_WINDOWS[top] == chunk;
        boolean run;
        if (owner) {
            populateCode = "rtg window owner";
            run = true;
        } else {
            run = populateDecision(chunk, true);
        }
        if (ChunkStewardConfig.logging.debug) {
            Diagnostics.populate(populateCode, run, chunk);
        }
        return run;
    }

    /** A chunk's terrain is being generated. Inside a populate that is a cascade; the chunk is released later. */
    public static void onGenerate(int dim, int x, int z) {
        GeneratedCache.add(dim, x, z);
        if (Thread.currentThread() != TickMonitor.serverThread) {
            return;
        }
        if (!preloading && !stage2Active) {
            String[] ignore = ChunkStewardConfig.preload.busyIgnoreMods;
            World world = ignore == null || ignore.length == 0 ? null : DimensionManager.getWorld(dim);
            if (world != null && heldByModTicket(world, x, z, ignore)) {
                // A mod building on its own ticketed chunks (a dungeon footprint) is not player demand.
                statBusyIgnored++;
            } else {
                TickMonitor.countExternalGen();
            }
        }
        if (populateDepth > 0) {
            statCascades++;
            GenMetrics.noteCascade();
            ChunkStewardConfig.Populate cfg = ChunkStewardConfig.populate;
            if (cfg.enabled && cfg.cascadeDeferral && Dimensions.applies(dim)) {
                ChunkHolds.hold(dim, x, z, cfg.cascadeHoldTicks);
            }
        }
    }

    static void reset() {
        populateDepth = 0;
        java.util.Arrays.fill(FRAMES, null);
        rtgDepth = 0;
        java.util.Arrays.fill(RTG_WINDOWS, null);
        populatesThisTick = 0;
        lastPopulateTick = Integer.MIN_VALUE / 2;
        preloading = false;
        stage2Active = false;
        TERRAIN_ONLY.clear();
        LIGHT_QUEUES.clear();
        lightQueued = 0;
        draining = false;
    }

    static void beginStage2() {
        stage2Active = true;
    }

    static void endStage2() {
        stage2Active = false;
    }

    /** The chunk was generated terrain-only by preload or stage 2 this session and is still unpopulated. */
    static boolean isTerrainOnly(int dim, int x, int z) {
        LongOpenHashSet set = TERRAIN_ONLY.get(dim);
        return set != null && set.contains(ChunkPos.asLong(x, z));
    }

    static void onTickStart() {
        stage2Active = false;
        populateDepth = 0;
        java.util.Arrays.fill(FRAMES, null);
        rtgDepth = 0;
        java.util.Arrays.fill(RTG_WINDOWS, null);
        populatesThisTick = 0;
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
                    PopulateQueue.enqueue(e.getKey(), key);
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
        ChunkStewardConfig.Light cfg = ChunkStewardConfig.light;
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
        ChunkStewardConfig.Light cfg = ChunkStewardConfig.light;
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
        PopulateQueue.drain();
        drainLight();
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            return;
        }
        int dim = event.getWorld().provider.getDimension();
        TERRAIN_ONLY.remove(dim);
        PopulateQueue.clear(dim);
        DimQueue queue = LIGHT_QUEUES.remove(dim);
        if (queue != null) {
            lightQueued -= queue.order.size();
        }
    }

    static int lightQueued() {
        return lightQueued;
    }

    static int populateQueued() {
        return PopulateQueue.size();
    }

    static String reportAndReset() {
        String s = "populate deferred " + statPopulateDeferred + " (cascade " + statPopulateCascadeDeferred
                + "), resumed " + statPopulateResumed + ", ticket-exempt " + statPopulateTicketExempt
                + (statRtgWindows + statRtgNestedCancelled > 0
                        ? ", rtg windows " + statRtgWindows + " (nested cancelled " + statRtgNestedCancelled + ")" : "")
                + ", mod-ticket generation " + statBusyIgnored + ", cascades " + statCascades + ", "
                + PopulateQueue.reportAndReset() + "; light deferred " + statLightDeferred + ", drained "
                + statLightDrained + ", queued " + lightQueued + "; held " + ChunkHolds.heldCount();
        statPopulateDeferred = statPopulateCascadeDeferred = statPopulateResumed = statCascades = 0L;
        statPopulateTicketExempt = statBusyIgnored = 0L;
        statRtgWindows = statRtgNestedCancelled = 0L;
        statLightDeferred = statLightDrained = 0L;
        return s;
    }
}
