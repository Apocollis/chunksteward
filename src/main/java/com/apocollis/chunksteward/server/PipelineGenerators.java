package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.AccessorChunkGeneratorEnd;
import com.apocollis.chunksteward.mixin.AccessorChunkGeneratorHell;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.ChunkGeneratorEnd;
import net.minecraft.world.gen.ChunkGeneratorHell;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;

/** Copies of the vanilla nether and end generators. Other classes stay on the server thread. */
final class PipelineGenerators {
    static final String HELL = "net.minecraft.world.gen.ChunkGeneratorHell";
    static final String END = "net.minecraft.world.gen.ChunkGeneratorEnd";

    private PipelineGenerators() {
    }

    static boolean supported(IChunkGenerator generator) {
        return generator instanceof ChunkGeneratorHell || generator instanceof ChunkGeneratorEnd;
    }

    static boolean listed(IChunkGenerator generator) {
        if (generator == null) {
            return false;
        }
        String name = generator.getClass().getName();
        String[] allowed = ChunkStewardConfig.pipeline.allowedGenerators;
        if (allowed == null) {
            return false;
        }
        for (String entry : allowed) {
            if (entry != null && name.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    static IChunkGenerator of(World world) {
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            return null;
        }
        return ((ChunkProviderServer) world.getChunkProvider()).chunkGenerator;
    }

    static World worldOf(IChunkGenerator generator) {
        if (generator instanceof ChunkGeneratorHell) {
            return ((AccessorChunkGeneratorHell) generator).chunksteward$world();
        }
        if (generator instanceof ChunkGeneratorEnd) {
            return ((AccessorChunkGeneratorEnd) generator).chunksteward$world();
        }
        return null;
    }

    /** A new instance. Call on the server thread so {@code InitMapGenEvent} runs there. */
    static IChunkGenerator copy(World world, IChunkGenerator main) {
        if (main instanceof ChunkGeneratorHell) {
            boolean structures = ((AccessorChunkGeneratorHell) main).chunksteward$structures();
            return new ChunkGeneratorHell(world, structures, world.getSeed());
        }
        if (main instanceof ChunkGeneratorEnd) {
            AccessorChunkGeneratorEnd access = (AccessorChunkGeneratorEnd) main;
            return new ChunkGeneratorEnd(world, access.chunksteward$mapFeatures(), world.getSeed(), access.chunksteward$spawn());
        }
        return null;
    }

    /**
     * A provider the worker can mutate. Nether and end use a single biome. A plain overworld {@code BiomeProvider} is
     * copied from the world info. Anything else is left synchronous.
     */
    static BiomeProvider copyBiome(World world) {
        BiomeProvider current = world.provider.getBiomeProvider();
        if (current instanceof BiomeProviderSingle) {
            return new BiomeProviderSingle(((BiomeProviderSingle) current).getFixedBiome());
        }
        if (current != null && current.getClass() == BiomeProvider.class) {
            return new BiomeProvider(world.getWorldInfo());
        }
        return null;
    }
}
