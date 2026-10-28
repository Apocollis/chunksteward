package com.apocollis.chunksteward.server;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;

import java.util.List;
import java.util.Random;

/**
 * The biome provider a terrain worker sees while it generates one chunk: single-block and rectangle lookups are answered
 * from the job's {@link BiomeSnapshot}; anything outside the snapshot (far structure checks, a different layer) goes to
 * the worker's own copy and is counted. One instance per job, used by one thread.
 */
final class SnapshotBiomeProvider extends BiomeProvider {
    private final BiomeSnapshot snapshot;
    private final BiomeProvider fallback;

    SnapshotBiomeProvider(BiomeSnapshot snapshot, BiomeProvider fallback) {
        super();
        this.snapshot = snapshot;
        this.fallback = fallback;
    }

    @Override
    public Biome getBiome(BlockPos pos, Biome defaultBiome) {
        Biome biome = snapshot.at(pos.getX(), pos.getZ());
        if (biome != null) {
            return biome;
        }
        PipelineStats.snapshotMiss();
        return fallback.getBiome(pos, defaultBiome);
    }

    @Override
    public Biome[] getBiomes(Biome[] listToReuse, int x, int z, int width, int length) {
        return getBiomes(listToReuse, x, z, width, length, true);
    }

    @Override
    public Biome[] getBiomes(Biome[] listToReuse, int x, int z, int width, int length, boolean cacheFlag) {
        if (!snapshot.containsRect(x, z, width, length)) {
            PipelineStats.snapshotMiss();
            return fallback.getBiomes(listToReuse, x, z, width, length, cacheFlag);
        }
        Biome[] out = listToReuse != null && listToReuse.length >= width * length ? listToReuse : new Biome[width * length];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < length; j++) {
                out[i + j * width] = snapshot.at(x + i, z + j);
            }
        }
        return out;
    }

    @Override
    public Biome[] getBiomesForGeneration(Biome[] biomes, int x, int z, int width, int height) {
        PipelineStats.snapshotMiss();
        return fallback.getBiomesForGeneration(biomes, x, z, width, height);
    }

    @Override
    public boolean areBiomesViable(int x, int z, int radius, List<Biome> allowed) {
        PipelineStats.snapshotMiss();
        return fallback.areBiomesViable(x, z, radius, allowed);
    }

    @Override
    public BlockPos findBiomePosition(int x, int z, int range, List<Biome> biomes, Random random) {
        PipelineStats.snapshotMiss();
        return fallback.findBiomePosition(x, z, range, biomes, random);
    }

    @Override
    public List<Biome> getBiomesToSpawnIn() {
        return fallback.getBiomesToSpawnIn();
    }

    @Override
    public float getTemperatureAtHeight(float temperature, int height) {
        return fallback.getTemperatureAtHeight(temperature, height);
    }

    @Override
    public boolean isFixedBiome() {
        return fallback.isFixedBiome();
    }

    @Override
    public Biome getFixedBiome() {
        return fallback.getFixedBiome();
    }

    @Override
    public void cleanupCache() {
    }
}
