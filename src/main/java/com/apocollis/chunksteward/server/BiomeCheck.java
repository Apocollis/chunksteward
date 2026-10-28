package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A worker generates a chunk from its own copy of the dimension's biome provider. A mod that builds that provider's
 * layers from shared state (Geographicraft / Climate Control builds a layer tree per provider) could make the copy
 * answer differently from the server's provider; the chunk would then have biomes, and the terrain shaped by them, that
 * do not match its neighbours. After a worker generates a chunk it records what its provider answers at 64 points of
 * the chunk; when the server installs the chunk it asks its own provider the same and discards the chunk on any
 * difference, so the server thread generates it (the server's provider is the reference).
 */
final class BiomeCheck {
    private static final int POINTS = 64;
    private static final AtomicInteger LOGGED = new AtomicInteger();

    private BiomeCheck() {
    }

    /** The biome ids {@code provider} answers at an 8x8 grid of the chunk, or null when it cannot be asked. */
    static short[] sample(BiomeProvider provider, int chunkX, int chunkZ) {
        if (provider == null) {
            return null;
        }
        short[] ids = new short[POINTS];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        try {
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    pos.setPos(chunkX * 16 + 2 * i + 1, 0, chunkZ * 16 + 2 * j + 1);
                    ids[i * 8 + j] = (short) Biome.getIdForBiome(provider.getBiome(pos));
                }
            }
        } catch (RuntimeException failed) {
            return null;
        }
        return ids;
    }

    /**
     * Single-threaded comparison of a fresh worker copy with the server's provider over a 24x24 grid spread across
     * +-6000 blocks in x and +-12000 in z (climate mods vary with latitude). Null when the copy matches, else a
     * description of the first difference. A copy that fails this is built wrong (not a race), so it is never used.
     */
    static String compareProviders(BiomeProvider server, BiomeProvider copy) {
        if (server == null || copy == null) {
            return null;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int different = 0;
        String first = null;
        for (int i = 0; i < 24; i++) {
            for (int j = 0; j < 24; j++) {
                pos.setPos(-6000 + i * 500, 0, -12000 + j * 1000);
                Biome a = server.getBiome(pos);
                Biome b = copy.getBiome(pos);
                if (a != b) {
                    different++;
                    if (first == null) {
                        first = "at block " + pos.getX() + "," + pos.getZ() + ": server " + a.getRegistryName() + ", copy "
                                + b.getRegistryName();
                    }
                }
            }
        }
        return different == 0 ? null : different + " of 576 points differ, first " + first;
    }

    static int firstDifference(short[] worker, short[] server) {
        return BiomeSamples.firstDifference(worker, server);
    }

    static int differing(short[] worker, short[] server) {
        return BiomeSamples.differing(worker, server);
    }

    /** Logs the first mismatches in a session with enough detail to tell which provider is off. */
    static void report(int dimension, int x, int z, int slot, short[] worker, short[] server) {
        if (LOGGED.incrementAndGet() > 25) {
            return;
        }
        int at = firstDifference(worker, server);
        Biome w = Biome.getBiome(worker[at]);
        Biome s = Biome.getBiome(server[at]);
        ChunkSteward.LOGGER.warn("pipeline biome mismatch at chunk {},{} in dimension {}: worker {} and server thread "
                        + "disagree at {} of {} sample points (first at block {},{}: worker {}, server {}); the chunk is "
                        + "generated on the server thread instead", x, z, dimension, slot, differing(worker, server),
                worker.length, x * 16 + 2 * (at / 8) + 1, z * 16 + 2 * (at % 8) + 1,
                w == null ? worker[at] : w.getRegistryName(), s == null ? server[at] : s.getRegistryName());
    }
}
