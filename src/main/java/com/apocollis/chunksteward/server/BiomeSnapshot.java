package com.apocollis.chunksteward.server;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;

/**
 * The biomes around one chunk, as the server thread's own biome provider answers them. A terrain worker generates from
 * this (see {@link SnapshotBiomeProvider}) instead of asking a biome provider of its own, so the biome layout a mod such
 * as Geographicraft / Climate Control builds is exactly the one the server uses, however that mod's layers keep their
 * state. Taken on the server thread; read-only afterwards, so a worker may read it without locks.
 */
final class BiomeSnapshot {
    private final int x0;
    private final int z0;
    private final int size;
    private final short[] ids;

    private BiomeSnapshot(int x0, int z0, int size, short[] ids) {
        this.x0 = x0;
        this.z0 = z0;
        this.size = size;
        this.ids = ids;
    }

    /** The biomes of the block square {@code radius} blocks around chunk (cx, cz), one per block. Server thread only. */
    static BiomeSnapshot take(BiomeProvider server, int cx, int cz, int radius) {
        int size = 16 + 2 * radius;
        int x0 = cx * 16 - radius;
        int z0 = cz * 16 - radius;
        short[] ids = new short[size * size];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                Biome biome = server.getBiome(pos.setPos(x0 + dx, 0, z0 + dz));
                ids[dx * size + dz] = (short) (biome == null ? -1 : Biome.getIdForBiome(biome));
            }
        }
        return new BiomeSnapshot(x0, z0, size, ids);
    }

    boolean contains(int x, int z) {
        return x >= x0 && z >= z0 && x < x0 + size && z < z0 + size;
    }

    boolean containsRect(int x, int z, int width, int length) {
        return width > 0 && length > 0 && contains(x, z) && contains(x + width - 1, z + length - 1);
    }

    /** The biome at a block, or null when outside the square or the provider gave none. */
    Biome at(int x, int z) {
        if (!contains(x, z)) {
            return null;
        }
        short id = ids[(x - x0) * size + (z - z0)];
        return id < 0 ? null : Biome.getBiome(id);
    }
}
