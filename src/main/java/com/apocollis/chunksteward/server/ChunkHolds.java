package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Chunks this mod loaded that no player owns (preload, cascades). A fully generated chunk is only populated once its
 * neighbours exist, so each is held loaded for a while and then released through {@code queueUnload}, which saves it.
 * Without the release nothing else would ever unload them.
 *
 * <p>Released chunks are re-checked for a while, because vanilla's {@code getLoadedChunk} (used by every neighbour
 * populate check) cancels a queued unload. Chunks with a player watching them or a Forge ticket are never released.
 */
@Mod.EventBusSubscriber
public final class ChunkHolds {
    private static final int RECHECK_TICKS = 20;
    private static final int RECHECK_TIMES = 10;

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

    private static final ArrayDeque<Held> HELD = new ArrayDeque<>();
    private static final ArrayDeque<Held> RELEASED = new ArrayDeque<>();

    private ChunkHolds() {
    }

    /** Keeps the chunk loaded for {@code ticks}, then releases it. Past the cap the oldest holds are released. */
    static void hold(int dim, int x, int z, int ticks) {
        HELD.add(new Held(dim, x, z, TickMonitor.tick + ticks, 0));
        while (HELD.size() > ChunkStewardConfig.preload.maxHeldChunks) {
            release(HELD.poll());
        }
    }

    /** Releases the chunk now. */
    static void releaseNow(int dim, int x, int z) {
        release(new Held(dim, x, z, TickMonitor.tick, 0));
    }

    static int heldCount() {
        return HELD.size();
    }

    /** Ticks until the chunk's hold ends, or -1 if it is not held. */
    static int holdRemaining(int dim, int x, int z) {
        for (Held held : HELD) {
            if (held.dim == dim && held.x == x && held.z == z) {
                return Math.max(0, held.dueTick - TickMonitor.tick);
            }
        }
        return -1;
    }

    /** Ticks until the oldest hold ends, or -1 if nothing is held. */
    static int oldestRemaining() {
        Held first = HELD.peekFirst();
        return first == null ? -1 : Math.max(0, first.dueTick - TickMonitor.tick);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Iterator<Held> it = HELD.iterator();
        while (it.hasNext()) {
            Held held = it.next();
            if (held.dueTick > TickMonitor.tick) {
                break;
            }
            it.remove();
            release(held);
        }
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
        if (chunk == null || world.getPlayerChunkMap().contains(held.x, held.z)
                || world.getPersistentChunks().containsKey(new ChunkPos(held.x, held.z))) {
            return;
        }
        if (!chunk.unloadQueued) {
            provider.queueUnload(chunk);
        }
        RELEASED.add(new Held(held.dim, held.x, held.z, TickMonitor.tick + RECHECK_TICKS, held.rechecks + 1));
    }
}
