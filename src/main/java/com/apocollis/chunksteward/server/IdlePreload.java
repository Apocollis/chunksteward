package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates chunks beyond the view distance around players while the server has spare time. One chunk at most every
 * {@code intervalTicks}, on the server thread, round-robin over players.
 *
 * <p>Two tiers, both measured from the live view distance and never reaching into the area vanilla loads anyway:
 * <ul>
 * <li><b>Idle</b> (standing still for {@code idleStillTicks}): full generation out to view + {@code idleExtraFull}, then
 * terrain only out to view + {@code idleExtraTerrain}; nearest and forward-facing first.</li>
 * <li><b>Moving</b> (slow only, under {@code movingMaxSpeed}): terrain only, in a forward cone out to view +
 * {@code movingExtraTerrain}.</li>
 * </ul>
 * Preload yields whenever chunks are waiting for a player, recent generation for players is heavy
 * ({@code busyChunks} in {@code busyWindowTicks}), populates are backed up, or the tick is over the tier's limit.
 *
 * <p>A full chunk is only populated once its neighbours exist, so each one is held loaded through {@link ChunkHolds}
 * and then released. Terrain-only chunks are generated with populate deferred through {@link StageGate} and released
 * at once: holding them would only let a later neighbour populate them.
 */
@Mod.EventBusSubscriber
public final class IdlePreload {
    private static final int MAX_GENERATED_CHECKS_PER_TICK = 16;
    private static final double MOVE_EPSILON_SQ = 0.25 * 0.25;
    private static final int REBUILD_MIN_TICKS = 10;
    private static final double TURN_COS = 0.866;
    private static final int MINUTE_TICKS = 1200;

    private static final int TIER_NONE = 0;
    private static final int TIER_MOVING = 1;
    private static final int TIER_IDLE = 2;

    private static final class Target {
        final int x;
        final int z;
        final boolean terrainOnly;

        Target(int x, int z, boolean terrainOnly) {
            this.x = x;
            this.z = z;
            this.terrainOnly = terrainOnly;
        }
    }

    private static final class PlayerState {
        double lastX;
        double lastZ;
        int lastDim = Integer.MIN_VALUE;
        int stillTicks;
        /** Ticks since the last movement sample; starts high so a new player is sampled on the first tick. */
        int sinceSample = Integer.MAX_VALUE / 2;
        ArrayDeque<Target> queue;
        int queueTier;
        int builtCx;
        int builtCz;
        int builtView;
        int builtTick;
        double builtDirX;
        double builtDirZ;
        int windowStart;
        int windowCount;
    }

    private static final Map<UUID, PlayerState> STATES = new HashMap<>();
    private static final double[] DIR = new double[2];
    private static int rotate;

    private static long statIdle;
    private static long statMoving;
    private static long statYieldDemand;
    private static long statYieldBusy;
    private static long statYieldBacklog;
    private static long statLimited;

    private IdlePreload() {
    }

    static void reset() {
        STATES.clear();
        rotate = 0;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        ChunkStewardConfig.Preload cfg = ChunkStewardConfig.preload;
        if (!cfg.enabled) {
            STATES.clear();
            return;
        }
        if (Pregen.paused) {
            return;
        }
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (EntityPlayerMP player : players) {
            track(player);
        }
        if (TickMonitor.tick % cfg.intervalTicks != 0 || players.isEmpty() || !TickMonitor.primed
                || TickMonitor.savePaused()) {
            return;
        }
        if (TickMonitor.waitingNow() > 0) {
            statYieldDemand++;
            return;
        }
        if (TickMonitor.recentExternalGen(cfg.busyWindowTicks) >= cfg.busyChunks) {
            statYieldBusy++;
            return;
        }
        if (PopulateQueue.size() > cfg.maxPopulateBacklog) {
            statYieldBacklog++;
            return;
        }
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get((rotate + i) % players.size());
            PlayerState state = STATES.get(player.getUniqueID());
            if (state == null || !Dimensions.applies(player.dimension) || TerrainPipeline.isPaused(player.dimension)) {
                continue;
            }
            int tier = tierOf(player, state, cfg);
            if (tier == TIER_NONE) {
                state.queue = null;
                continue;
            }
            double limit = tier == TIER_IDLE ? cfg.maxTickMs : cfg.movingMaxTickMs;
            if (TickMonitor.totalEmaMs >= limit || TickMonitor.currentTickMs() >= limit) {
                continue;
            }
            if (preloadOne(player, state, tier, cfg)) {
                rotate = (rotate + i + 1) % players.size();
                return;
            }
        }
    }

    /** The player's preload queue has been built and emptied: stage 2 may start for them. */
    static boolean stage1Done(EntityPlayerMP player) {
        PlayerState state = STATES.get(player.getUniqueID());
        return state != null && state.queue != null && state.queue.isEmpty();
    }

    private static int tierOf(EntityPlayerMP player, PlayerState state, ChunkStewardConfig.Preload cfg) {
        if (state.stillTicks >= cfg.idleStillTicks) {
            return TIER_IDLE;
        }
        if (cfg.movingEnabled && PlayerMotion.speed(player) <= cfg.movingMaxSpeed) {
            return TIER_MOVING;
        }
        return TIER_NONE;
    }

    private static void track(EntityPlayerMP player) {
        PlayerState state = STATES.computeIfAbsent(player.getUniqueID(), id -> new PlayerState());
        if (state.sinceSample < Integer.MAX_VALUE / 4) {
            state.sinceSample++;
        }
        int sampleTicks = Math.max(1, ChunkStewardConfig.movement.sampleTicks);
        if (state.sinceSample < sampleTicks) {
            return;
        }
        int elapsed = Math.min(state.sinceSample, 4 * sampleTicks);
        state.sinceSample = 0;
        double dx = player.posX - state.lastX;
        double dz = player.posZ - state.lastZ;
        if (player.dimension != state.lastDim || dx * dx + dz * dz > MOVE_EPSILON_SQ) {
            state.lastX = player.posX;
            state.lastZ = player.posZ;
            state.lastDim = player.dimension;
            state.stillTicks = 0;
        } else if (state.stillTicks < Integer.MAX_VALUE - elapsed) {
            state.stillTicks += elapsed;
        }
    }

    /** Generates the next missing chunk in the player's queue. Returns true if a chunk was generated. */
    private static boolean preloadOne(EntityPlayerMP player, PlayerState state, int tier,
                                      ChunkStewardConfig.Preload cfg) {
        if (TickMonitor.ticksSince(state.windowStart) >= MINUTE_TICKS) {
            state.windowStart = TickMonitor.tick;
            state.windowCount = 0;
        }
        if (state.windowCount >= cfg.maxPerMinute) {
            statLimited++;
            return false;
        }
        WorldServer world = player.getServerWorld();
        ChunkProviderServer provider = world.getChunkProvider();
        int dim = world.provider.getDimension();
        int view = TickMonitor.viewRadius(world);
        if (needsRebuild(player, state, tier, view)) {
            state.queue = buildQueue(player, world, tier, view, cfg);
            state.queueTier = tier;
            state.builtCx = ((int) Math.floor(player.posX)) >> 4;
            state.builtCz = ((int) Math.floor(player.posZ)) >> 4;
            state.builtView = view;
            state.builtTick = TickMonitor.tick;
            PlayerMotion.direction(player, DIR);
            state.builtDirX = DIR[0];
            state.builtDirZ = DIR[1];
        }
        int checks = 0;
        while (state.queue != null && !state.queue.isEmpty() && checks < MAX_GENERATED_CHECKS_PER_TICK) {
            Target target = state.queue.poll();
            checks++;
            if (GeneratedCache.known(dim, target.x, target.z)) {
                continue;
            }
            if (StageGate.peek(provider, target.x, target.z) != null
                    || provider.isChunkGeneratedAt(target.x, target.z)) {
                GeneratedCache.add(dim, target.x, target.z);
                continue;
            }
            if (TerrainPipeline.deferSpeculative(world, target.x, target.z)) {
                state.queue.addFirst(target);
                return false;
            }
            Chunk chunk;
            if (target.terrainOnly) {
                StageGate.beginTerrainOnly(dim, target.x, target.z);
                try {
                    chunk = provider.provideChunk(target.x, target.z);
                } finally {
                    StageGate.endTerrainOnly();
                }
                TickMonitor.countPreloadedTerrainOnly();
            } else {
                StageGate.beginPreload(dim);
                try {
                    chunk = provider.provideChunk(target.x, target.z);
                } finally {
                    StageGate.endPreload();
                }
                TickMonitor.countPreloaded();
            }
            state.windowCount++;
            if (tier == TIER_IDLE) {
                statIdle++;
            } else {
                statMoving++;
            }
            if (chunk != null && !world.getPlayerChunkMap().contains(target.x, target.z)) {
                if (target.terrainOnly) {
                    ChunkHolds.releaseNow(dim, target.x, target.z);
                } else {
                    ChunkHolds.hold(dim, target.x, target.z, cfg.holdTicks);
                }
            }
            return true;
        }
        return false;
    }

    private static boolean needsRebuild(EntityPlayerMP player, PlayerState state, int tier, int view) {
        if (state.queue == null || state.queueTier != tier || state.builtView != view) {
            return true;
        }
        if (tier != TIER_MOVING || TickMonitor.ticksSince(state.builtTick) < REBUILD_MIN_TICKS) {
            return false;
        }
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        if (cx != state.builtCx || cz != state.builtCz) {
            return true;
        }
        PlayerMotion.direction(player, DIR);
        return DIR[0] * state.builtDirX + DIR[1] * state.builtDirZ < TURN_COS;
    }

    private static ArrayDeque<Target> buildQueue(EntityPlayerMP player, WorldServer world, int tier, int view,
                                                 ChunkStewardConfig.Preload cfg) {
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        PlayerMotion.direction(player, DIR);
        double dirX = DIR[0];
        double dirZ = DIR[1];

        boolean moving = tier == TIER_MOVING;
        int fullRadius = view + cfg.idleExtraFull;
        int outer = moving ? view + cfg.movingExtraTerrain
                : Math.max(fullRadius, cfg.idleExtraTerrain > 0 ? view + cfg.idleExtraTerrain : 0);
        int outerSq = outer * outer;
        int fullSq = fullRadius * fullRadius;
        double coneCos = Math.cos(Math.toRadians(cfg.movingForwardAngleDegrees));

        List<long[]> keyed = new ArrayList<>();
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                if (Math.abs(dx) <= view && Math.abs(dz) <= view) {
                    continue; // vanilla loads this area for the player anyway
                }
                int distSq = dx * dx + dz * dz;
                if (distSq > outerSq) {
                    continue;
                }
                double dot = (dx * dirX + dz * dirZ) / Math.sqrt(distSq);
                boolean terrainOnly;
                int cls;
                if (moving) {
                    if (dot < coneCos) {
                        continue;
                    }
                    terrainOnly = true;
                    cls = 0;
                } else {
                    terrainOnly = distSq > fullSq;
                    cls = dot >= 0.5 ? 0 : dot >= -0.2 ? 1 : 2;
                    if (terrainOnly) {
                        cls += 3;
                    }
                }
                if (!world.getWorldBorder().contains(new ChunkPos(cx + dx, cz + dz))) {
                    continue;
                }
                keyed.add(new long[] {cls * 100000L + distSq, cx + dx, cz + dz, terrainOnly ? 1 : 0});
            }
        }
        keyed.sort((a, b) -> Long.compare(a[0], b[0]));
        ArrayDeque<Target> queue = new ArrayDeque<>(keyed.size());
        for (long[] k : keyed) {
            queue.add(new Target((int) k[1], (int) k[2], k[3] != 0));
        }
        return queue;
    }

    /** Heads of player preload queues, skipping chunks already known to exist. At most {@code limit}. */
    static void collectSpeculative(List<int[]> out, int limit) {
        if (limit <= 0 || out == null) {
            return;
        }
        for (PlayerState state : STATES.values()) {
            if (out.size() >= limit || state.queue == null || state.lastDim == Integer.MIN_VALUE) {
                continue;
            }
            int seen = 0;
            for (Target target : state.queue) {
                if (seen++ >= MAX_GENERATED_CHECKS_PER_TICK) {
                    break;
                }
                if (GeneratedCache.known(state.lastDim, target.x, target.z)) {
                    continue;
                }
                out.add(new int[] {state.lastDim, target.x, target.z});
                break;
            }
            if (out.size() >= limit) {
                return;
            }
        }
    }

    static String reportAndReset() {
        String s = "preload idle " + statIdle + ", moving " + statMoving + "; yielded to demand " + statYieldDemand
                + ", busy " + statYieldBusy + ", populate backlog " + statYieldBacklog + ", minute limit "
                + statLimited;
        statIdle = statMoving = statYieldDemand = statYieldBusy = statYieldBacklog = statLimited = 0L;
        return s;
    }
}
