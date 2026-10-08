package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates chunks around players who stand still while the server has spare tick time. One chunk at most every
 * {@code intervalTicks}, on the server thread.
 *
 * <p>Order: the full {@code radius} area first (chunk under the player, front cone, sides, behind; nearer first inside
 * each class), then the terrain-only ring out to {@code terrainOnlyRadius} in the same cone order.
 *
 * <p>A full chunk is only populated once its neighbours exist, so each one is held loaded for {@code holdTicks} and
 * then released through {@code queueUnload}, which saves it. Terrain-only chunks are generated with populate deferred
 * through {@link StageGate} and released at once: holding them would only let a later neighbour populate them.
 * Released chunks are re-checked for a while, because vanilla's {@code getLoadedChunk} (used by every neighbour
 * populate check) cancels a queued unload.
 */
@Mod.EventBusSubscriber
public final class IdlePreload {
    private static final int MAX_GENERATED_CHECKS_PER_TICK = 16;
    private static final double MOVE_EPSILON_SQ = 0.25 * 0.25;
    private static final int RECHECK_TICKS = 20;
    private static final int RECHECK_TIMES = 10;

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
        ArrayDeque<Target> queue;
        int terrainOnlyGenerated;
    }

    private static final class Held {
        final int dim;
        final int x;
        final int z;
        final int dueTick;
        final int rechecks;

        Held(int dim, int x, int z, int dueTick, int rechecks) {
            this.dim = dim;
            this.x = x;
            this.z = z;
            this.dueTick = dueTick;
            this.rechecks = rechecks;
        }
    }

    private static final Map<UUID, PlayerState> STATES = new HashMap<>();
    private static final ArrayDeque<Held> HELD = new ArrayDeque<>();
    private static final ArrayDeque<Held> RELEASED = new ArrayDeque<>();
    private static int rotate;

    private IdlePreload() {
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
        releaseHeld(false);
        recheckReleased();
        LazyChunksConfig.Preload cfg = LazyChunksConfig.preload;
        if (!cfg.enabled) {
            if (!HELD.isEmpty()) {
                releaseHeld(true);
            }
            STATES.clear();
            return;
        }
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (EntityPlayerMP player : players) {
            track(player);
        }
        if (TickMonitor.tick % cfg.intervalTicks != 0 || players.isEmpty() || !serverIdle(cfg)) {
            return;
        }
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get((rotate + i) % players.size());
            PlayerState state = STATES.get(player.getUniqueID());
            if (state != null && state.stillTicks >= cfg.stationaryTicks
                    && Dimensions.applies(player.dimension) && preloadOne(player, state, cfg)) {
                rotate = (rotate + i + 1) % players.size();
                return;
            }
        }
    }

    private static boolean serverIdle(LazyChunksConfig.Preload cfg) {
        return TickMonitor.primed
                && !TickMonitor.savePaused()
                && TickMonitor.totalEmaMs < cfg.maxTickMs
                && TickMonitor.currentTickMs() < cfg.maxTickMs;
    }

    private static void track(EntityPlayerMP player) {
        PlayerState state = STATES.computeIfAbsent(player.getUniqueID(), id -> new PlayerState());
        double dx = player.posX - state.lastX;
        double dz = player.posZ - state.lastZ;
        if (player.dimension != state.lastDim || dx * dx + dz * dz > MOVE_EPSILON_SQ) {
            state.lastX = player.posX;
            state.lastZ = player.posZ;
            state.lastDim = player.dimension;
            state.stillTicks = 0;
            state.queue = null;
            state.terrainOnlyGenerated = 0;
        } else if (state.stillTicks < Integer.MAX_VALUE) {
            state.stillTicks++;
        }
    }

    /** Generates the next missing chunk in the player's queue. Returns true if a chunk was generated. */
    private static boolean preloadOne(EntityPlayerMP player, PlayerState state, LazyChunksConfig.Preload cfg) {
        WorldServer world = player.getServerWorld();
        ChunkProviderServer provider = world.getChunkProvider();
        if (state.queue == null) {
            state.queue = buildQueue(player, world, cfg.radius, cfg.terrainOnlyRadius);
        }
        int dim = world.provider.getDimension();
        int checks = 0;
        while (!state.queue.isEmpty() && checks < MAX_GENERATED_CHECKS_PER_TICK) {
            Target target = state.queue.poll();
            checks++;
            if (target.terrainOnly && state.terrainOnlyGenerated >= cfg.terrainOnlyMaxChunks) {
                state.queue.clear();
                return false;
            }
            if (StageGate.peek(provider, target.x, target.z) != null
                    || provider.isChunkGeneratedAt(target.x, target.z)) {
                continue;
            }
            Chunk chunk;
            if (target.terrainOnly) {
                StageGate.beginTerrainOnly(dim, target.x, target.z);
                try {
                    chunk = provider.provideChunk(target.x, target.z);
                } finally {
                    StageGate.endTerrainOnly();
                }
                state.terrainOnlyGenerated++;
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
            if (chunk != null && !world.getPlayerChunkMap().contains(target.x, target.z)) {
                if (target.terrainOnly) {
                    release(new Held(dim, target.x, target.z, TickMonitor.tick, 0));
                } else {
                    hold(dim, target.x, target.z, cfg);
                }
            }
            return true;
        }
        return false;
    }

    private static void hold(int dim, int x, int z, LazyChunksConfig.Preload cfg) {
        HELD.add(new Held(dim, x, z, TickMonitor.tick + cfg.holdTicks, 0));
        while (HELD.size() > cfg.maxHeldChunks) {
            release(HELD.poll());
        }
    }

    private static void releaseHeld(boolean all) {
        Iterator<Held> it = HELD.iterator();
        while (it.hasNext()) {
            Held held = it.next();
            if (!all && held.dueTick > TickMonitor.tick) {
                break;
            }
            it.remove();
            release(held);
        }
    }

    /** Re-queues released chunks that something pulled back (cleared {@code unloadQueued}) without a player owning them. */
    private static void recheckReleased() {
        while (!RELEASED.isEmpty() && RELEASED.peek().dueTick <= TickMonitor.tick) {
            Held held = RELEASED.poll();
            if (held.rechecks < RECHECK_TIMES) {
                release(held);
            }
        }
    }

    private static void release(Held held) {
        WorldServer world = DimensionManager.getWorld(held.dim);
        if (world == null) {
            return;
        }
        ChunkProviderServer provider = world.getChunkProvider();
        Chunk chunk = StageGate.peek(provider, held.x, held.z);
        if (chunk == null || world.getPlayerChunkMap().contains(held.x, held.z)) {
            return;
        }
        if (!chunk.unloadQueued) {
            provider.queueUnload(chunk);
        }
        RELEASED.add(new Held(held.dim, held.x, held.z, TickMonitor.tick + RECHECK_TICKS, held.rechecks + 1));
    }

    private static ArrayDeque<Target> buildQueue(EntityPlayerMP player, WorldServer world, int radius,
                                                 int terrainOnlyRadius) {
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        double yaw = Math.toRadians(player.rotationYaw);
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);

        int outer = Math.max(radius, terrainOnlyRadius);
        List<long[]> keyed = new ArrayList<>();
        int radiusSq = radius * radius;
        int outerSq = outer * outer;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > outerSq || !world.getWorldBorder().contains(new ChunkPos(cx + dx, cz + dz))) {
                    continue;
                }
                int cls;
                if (distSq == 0) {
                    cls = 0;
                } else {
                    double dot = (dx * lookX + dz * lookZ) / Math.sqrt(distSq);
                    cls = dot >= 0.5 ? 1 : dot >= -0.2 ? 2 : 3;
                }
                boolean terrainOnly = distSq > radiusSq;
                if (terrainOnly) {
                    cls += 3;
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
}
