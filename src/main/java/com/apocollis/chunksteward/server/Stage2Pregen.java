package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.data.HotspotData;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Stage 2: home-area pregen. Works on the hotspots from {@link Hotspots} while a player is near one and the server has
 * room (the same hold-offs as preload, {@link Pregen#holdOffReason}), after that player's preload queue is finished.
 *
 * <p>Per hotspot, in square rings out from its center chunk:
 * <ol>
 * <li><b>Terrain:</b> rings 0..radiusTerrain get terrain only (populate deferred through {@link StageGate}), released
 * at once, so the neighbours every later populate needs exist on disk.</li>
 * <li><b>Populate:</b> rings 0..radiusFull. A target is loaded, and if it is not yet populated its +x, +z and +x+z
 * neighbours are loaded too, so vanilla's own populate runs; the chunks are then held briefly (the next target shares
 * neighbours) and released. Populates run with {@link StageGate#beginStage2()}, which lets them past the tick budget
 * deferral: stage 2 has already checked there is room.</li>
 * </ol>
 * Progress is the last completed ring of each phase, saved in {@link HotspotData}; a ring cut short is redone, and
 * chunks that already exist or are populated only cost a load. One operation at most every {@code intervalTicks}.
 */
@Mod.EventBusSubscriber
public final class Stage2Pregen {
    private static final int MAX_CHECKS_PER_TICK = 16;
    private static final int MINUTE_TICKS = 1200;
    private static final int NEIGHBOUR_HOLD_TICKS = 100;

    private static final int PHASE_TERRAIN = 0;
    private static final int PHASE_POPULATE = 1;

    private static final class Work {
        int phase;
        int ring;
        LongArrayFIFOQueue queue;
        int windowStart;
        int windowCount;
    }

    private static final Map<Long, Work> WORK = new HashMap<>();
    private static int rotate;
    private static boolean warned;

    private static long statGenerated;
    private static long statPopulated;
    private static long statYielded;
    private static String lastYield = "-";

    // what is being worked on, for /chunksteward status
    static int activeDim = Integer.MIN_VALUE;
    static int activeId = -1;
    static String activeWhat = "-";

    private Stage2Pregen() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ChunkStewardConfig.Stage2 cfg = ChunkStewardConfig.stage2;
        if (!cfg.enabled || Pregen.paused || TickMonitor.tick % cfg.intervalTicks != 0) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get((rotate + i) % players.size());
            if (!eligible(player)) {
                continue;
            }
            WorldServer world = player.getServerWorld();
            HotspotData data = HotspotData.get(world);
            HotspotData.Hotspot hotspot = nearestActive(data, player, cfg);
            if (hotspot == null) {
                continue;
            }
            String reason = Pregen.holdOffReason(cfg.maxTickMs);
            if (reason == null && cfg.maxTotalChunks > 0 && totalPregenerated() >= cfg.maxTotalChunks) {
                reason = "total limit";
            }
            if (reason != null) {
                lastYield = reason;
                statYielded++;
                return;
            }
            activeDim = world.provider.getDimension();
            activeId = hotspot.id;
            if (work(world, data, hotspot, cfg)) {
                rotate = (rotate + i + 1) % players.size();
                return;
            }
        }
        activeId = -1;
        activeWhat = "-";
    }

    private static boolean eligible(EntityPlayerMP player) {
        return !player.isSpectator() && Dimensions.applies(player.dimension) && IdlePreload.stage1Done(player)
                && PlayerMotion.speed(player) <= ChunkStewardConfig.preload.movingMaxSpeed;
    }

    /** The unfinished hotspot nearest the player and within {@code activeRadiusChunks}, or null. */
    private static HotspotData.Hotspot nearestActive(HotspotData data, EntityPlayerMP player,
                                                     ChunkStewardConfig.Stage2 cfg) {
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        HotspotData.Hotspot best = null;
        int bestDist = Integer.MAX_VALUE;
        for (HotspotData.Hotspot h : data.hotspots) {
            int dist = Math.max(Math.abs(h.x - cx), Math.abs(h.z - cz));
            if (dist <= cfg.activeRadiusChunks && dist < bestDist && !isDone(h)) {
                best = h;
                bestDist = dist;
            }
        }
        return best;
    }

    static int terrainRadius() {
        ChunkStewardConfig.Stage2 cfg = ChunkStewardConfig.stage2;
        return Math.max(cfg.radiusTerrain, cfg.radiusFull + 1);
    }

    static boolean isDone(HotspotData.Hotspot h) {
        return h.terrainRing >= terrainRadius() && h.popRing >= ChunkStewardConfig.stage2.radiusFull;
    }

    private static long workKey(int dim, int id) {
        return ((long) dim << 32) | (id & 0xffffffffL);
    }

    /** One operation for the hotspot. Returns true if a chunk was generated or populated. */
    private static boolean work(WorldServer world, HotspotData data, HotspotData.Hotspot h,
                                ChunkStewardConfig.Stage2 cfg) {
        int dim = world.provider.getDimension();
        Work w = WORK.computeIfAbsent(workKey(dim, h.id), k -> new Work());
        if (TickMonitor.tick - w.windowStart >= MINUTE_TICKS) {
            w.windowStart = TickMonitor.tick;
            w.windowCount = 0;
        }
        if (w.windowCount >= cfg.maxPerMinute) {
            lastYield = "minute limit";
            statYielded++;
            return false;
        }
        ChunkProviderServer provider = world.getChunkProvider();
        int checks = 0;
        // Skipping chunks that already exist still costs a disk check or load, so stop scanning once the tick is full.
        while (checks < MAX_CHECKS_PER_TICK && (checks == 0 || TickMonitor.currentTickMs() < cfg.maxTickMs)) {
            if (w.queue == null && !startRing(h, w)) {
                return false;
            }
            if (w.queue.isEmpty()) {
                finishRing(data, h, w);
                continue;
            }
            long key = w.queue.dequeueLong();
            int x = (int) key;
            int z = (int) (key >> 32);
            checks++;
            if (!world.getWorldBorder().contains(new ChunkPos(x, z))) {
                continue;
            }
            activeWhat = (w.phase == PHASE_TERRAIN ? "terrain ring " : "populate ring ") + w.ring;
            boolean did = w.phase == PHASE_TERRAIN
                    ? terrainOp(world, provider, dim, data, h, x, z)
                    : populateOp(world, provider, dim, h, x, z);
            if (did) {
                w.windowCount++;
                h.lastWorked = world.getTotalWorldTime();
                data.markDirty();
                checkTotals();
                return true;
            }
        }
        return false;
    }

    /** Picks the next ring from the saved progress and queues its chunks. False when the hotspot is finished. */
    private static boolean startRing(HotspotData.Hotspot h, Work w) {
        int rt = terrainRadius();
        int rf = ChunkStewardConfig.stage2.radiusFull;
        if (h.terrainRing < rt) {
            w.phase = PHASE_TERRAIN;
            w.ring = h.terrainRing + 1;
        } else if (h.popRing < rf) {
            w.phase = PHASE_POPULATE;
            w.ring = h.popRing + 1;
        } else {
            return false;
        }
        w.queue = ring(h.x, h.z, w.ring);
        return true;
    }

    private static void finishRing(HotspotData data, HotspotData.Hotspot h, Work w) {
        if (w.phase == PHASE_TERRAIN) {
            h.terrainRing = w.ring;
        } else {
            h.popRing = w.ring;
        }
        w.queue = null;
        data.markDirty();
    }

    /** The chunks at Chebyshev distance {@code r} from the center. */
    private static LongArrayFIFOQueue ring(int cx, int cz, int r) {
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        if (r == 0) {
            q.enqueue(ChunkPos.asLong(cx, cz));
            return q;
        }
        for (int d = -r; d <= r; d++) {
            q.enqueue(ChunkPos.asLong(cx + d, cz - r));
            q.enqueue(ChunkPos.asLong(cx + d, cz + r));
        }
        for (int d = -r + 1; d <= r - 1; d++) {
            q.enqueue(ChunkPos.asLong(cx - r, cz + d));
            q.enqueue(ChunkPos.asLong(cx + r, cz + d));
        }
        return q;
    }

    private static boolean terrainOp(WorldServer world, ChunkProviderServer provider, int dim, HotspotData data,
                                     HotspotData.Hotspot h, int x, int z) {
        if (GeneratedCache.known(dim, x, z)) {
            return false;
        }
        if (StageGate.peek(provider, x, z) != null || provider.isChunkGeneratedAt(x, z)) {
            GeneratedCache.add(dim, x, z);
            return false;
        }
        Chunk chunk;
        StageGate.beginTerrainOnly(dim, x, z);
        try {
            chunk = provider.provideChunk(x, z);
        } finally {
            StageGate.endTerrainOnly();
        }
        if (chunk != null && !world.getPlayerChunkMap().contains(x, z)) {
            ChunkHolds.releaseNow(dim, x, z);
        }
        data.pregenTotal++;
        h.generated++;
        statGenerated++;
        return true;
    }

    private static boolean populateOp(WorldServer world, ChunkProviderServer provider, int dim,
                                      HotspotData.Hotspot h, int x, int z) {
        StageGate.beginStage2();
        try {
            Chunk target = StageGate.peek(provider, x, z);
            boolean loadedBefore = target != null;
            if (target == null) {
                if (!GeneratedCache.known(dim, x, z) && !provider.isChunkGeneratedAt(x, z)) {
                    return false; // the terrain phase did not produce it (world border, error): nothing to populate
                }
                target = provider.provideChunk(x, z);
            }
            if (target == null || target.isTerrainPopulated()) {
                if (!loadedBefore) {
                    ChunkHolds.releaseNow(dim, x, z);
                }
                return false;
            }
            // vanilla populates a chunk when the +x, +z and +x+z neighbours are loaded
            load(provider, x + 1, z);
            load(provider, x, z + 1);
            load(provider, x + 1, z + 1);
            if (!target.isTerrainPopulated()) {
                target.populate(provider, provider.chunkGenerator);
            }
            ChunkHolds.hold(dim, x, z, NEIGHBOUR_HOLD_TICKS);
            ChunkHolds.hold(dim, x + 1, z, NEIGHBOUR_HOLD_TICKS);
            ChunkHolds.hold(dim, x, z + 1, NEIGHBOUR_HOLD_TICKS);
            ChunkHolds.hold(dim, x + 1, z + 1, NEIGHBOUR_HOLD_TICKS);
            if (target.isTerrainPopulated()) {
                h.populated++;
                statPopulated++;
                return true;
            }
            return false;
        } finally {
            StageGate.endStage2();
        }
    }

    private static void load(ChunkProviderServer provider, int x, int z) {
        if (StageGate.peek(provider, x, z) == null) {
            provider.provideChunk(x, z);
        }
    }

    // ---- totals ---------------------------------------------------------------------------------------------------

    static long totalPregenerated() {
        long total = 0L;
        for (WorldServer world : DimensionManager.getWorlds()) {
            total += HotspotData.get(world).pregenTotal;
        }
        return total;
    }

    static int hotspotCount() {
        int n = 0;
        for (WorldServer world : DimensionManager.getWorlds()) {
            n += HotspotData.get(world).hotspots.size();
        }
        return n;
    }

    private static void checkTotals() {
        int warn = ChunkStewardConfig.stage2.warnTotalChunks;
        if (warned || warn <= 0 || totalPregenerated() < warn) {
            return;
        }
        warned = true;
        String message = "Chunk Steward stage 2 has pregenerated " + totalPregenerated() + " chunks (warning level "
                + warn + ", about " + (totalPregenerated() * 94 / 10240) + " MB at 9.4 KB each). "
                + "Set stage2.maxTotalChunks to limit it, or stage2.enabled = false to stop.";
        ChunkSteward.LOGGER.warn(message);
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                if (server.getPlayerList().canSendCommands(player.getGameProfile())) {
                    TextComponentString text = new TextComponentString(message);
                    text.getStyle().setColor(TextFormatting.YELLOW);
                    player.sendMessage(text);
                }
            }
        }
    }

    /** Forgets progress state of hotspots in a dimension (reset command, world unload). */
    static void forget(int dim) {
        Iterator<Long> it = WORK.keySet().iterator();
        while (it.hasNext()) {
            if ((int) (it.next() >> 32) == dim) {
                it.remove();
            }
        }
    }

    static void forget(int dim, int id) {
        WORK.remove(workKey(dim, id));
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            forget(event.getWorld().provider.getDimension());
        }
    }

    static String lastYield() {
        return lastYield;
    }

    static String reportAndReset() {
        String s = "stage2 hotspots " + hotspotCount() + ", generated " + statGenerated + ", populated "
                + statPopulated + ", yielded " + statYielded + " (last: " + lastYield + "), total "
                + totalPregenerated();
        statGenerated = statPopulated = statYielded = 0L;
        return s;
    }
}
