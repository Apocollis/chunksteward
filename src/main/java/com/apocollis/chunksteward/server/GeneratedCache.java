package com.apocollis.chunksteward.server;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunks known to exist on disk or in memory. A chunk that exists never stops existing, so positive answers are kept
 * and spare the region file check ({@code isChunkGeneratedAt} ends in a {@code File.exists} per call). Bounded: it is
 * simply cleared when it reaches its cap. Server thread only.
 */
@Mod.EventBusSubscriber
public final class GeneratedCache {
    private static final int CAP = 1 << 18;
    private static final Map<Integer, LongOpenHashSet> BY_DIM = new HashMap<>();

    private GeneratedCache() {
    }

    static boolean known(int dim, int x, int z) {
        LongOpenHashSet set = BY_DIM.get(dim);
        return set != null && set.contains(ChunkPos.asLong(x, z));
    }

    static void add(int dim, int x, int z) {
        LongOpenHashSet set = BY_DIM.computeIfAbsent(dim, d -> new LongOpenHashSet());
        if (set.size() >= CAP) {
            set.clear();
        }
        set.add(ChunkPos.asLong(x, z));
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            BY_DIM.remove(event.getWorld().provider.getDimension());
        }
    }
}
