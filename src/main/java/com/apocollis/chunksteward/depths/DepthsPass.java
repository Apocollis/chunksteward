package com.apocollis.chunksteward.depths;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkGeneratorDebug;
import net.minecraft.world.gen.ChunkGeneratorFlat;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.IChunkGenerator;
import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.core.BedrockFilter;
import sayys.depthsupdate.core.DeepFill;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.util.BlockUtils;
import sayys.depthsupdate.world.generation.ChunkPrimerAdapter;
import sayys.depthsupdate.world.generation.noise.CaveNoiseGenerator;
import sayys.depthsupdate.world.generation.river.UndergroundRiverGenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Depths Update 1.12.2-1.0.0-a12's {@code MixinChunkProviderServer.depthsupdate$onGenerateChunk}, minus the generator
 * call, for a terrain worker. The code after the generator call is copied unchanged; the conditions are the handler's own.
 * Each worker slot has its own noise objects, since Depths keeps them in the (server thread's) provider and they keep
 * scratch state. Server-thread Depths and a worker never share one.
 */
final class DepthsPass {
    private static final Map<Long, Instances> INSTANCES = new HashMap<Long, Instances>();

    private DepthsPass() {
    }

    private static final class Instances {
        final Random fillRandom = new Random();
        UndergroundRiverGenerator river;
        CaveNoiseGenerator caves;
    }

    private static final class Token {
        final boolean filterBedrock;
        final boolean fillCustom;

        Token(boolean filterBedrock, boolean fillCustom) {
            this.filterBedrock = filterBedrock;
            this.fillCustom = fillCustom;
        }
    }

    static Object begin(WorldServer world, IChunkGenerator generator) {
        boolean vanillaOverworld = generator.getClass() == ChunkGeneratorOverworld.class;
        boolean flatOrDebug = generator instanceof ChunkGeneratorFlat || generator instanceof ChunkGeneratorDebug;
        boolean deepWorld = !flatOrDebug
                && HeightManager.isExtended(world)
                && HeightManager.get(world).minY() < 0;
        boolean fillCustom = deepWorld && !vanillaOverworld
                && DepthsUpdateConfig.heightExtension.extendCustomWorldTypes;
        boolean filterBedrock = deepWorld && (vanillaOverworld || fillCustom);
        if (filterBedrock) {
            BedrockFilter.begin();
        }
        return new Token(filterBedrock, fillCustom);
    }

    static void end(Object token) {
        if (token instanceof Token && ((Token) token).filterBedrock) {
            BedrockFilter.end();
        }
    }

    private static Instances instances(WorldServer world, int slot) {
        long key = ((long) world.provider.getDimension() << 8) ^ slot;
        synchronized (INSTANCES) {
            Instances found = INSTANCES.get(key);
            if (found == null) {
                found = new Instances();
                INSTANCES.put(key, found);
            }
            return found;
        }
    }

    /** Everything Depths' handler does after the generator returned {@code chunk}. */
    static void finish(Object tokenObject, WorldServer world, Chunk chunk, int x, int z, int slot) {
        Token token = (Token) tokenObject;
        if (!token.fillCustom || chunk == null) {
            return;
        }
        Instances own = instances(world, slot);

        HeightContext ctx = HeightManager.get(world);
        int minY = ctx.minY();

        own.fillRandom.setSeed((long) x * 341873128712L + (long) z * 132897987541L);

        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();
        IBlockState bedrock = Blocks.BEDROCK.getDefaultState();

        int fillMaxY = Math.max(4, DepthsUpdateConfig.deepslateMaxY);

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        boolean hasSkyLight = world.provider.hasSkyLight();

        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = minY; by <= fillMaxY; by++) {
                    // Backstop for generators that write bedrock without going through ChunkPrimer. Runs before the
                    // carve so caves cut through the converted stone.
                    if (by >= 0 && by <= 4) {
                        int storageIdx = ctx.toStorageIndex(by);

                        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
                            ExtendedBlockStorage section = storageArrays[storageIdx];

                            if (section != Chunk.NULL_BLOCK_STORAGE
                                    && section.get(bx, by & 15, bz).getBlock() == Blocks.BEDROCK) {
                                section.set(bx, by & 15, bz, stone);
                            }
                        }
                    }

                    IBlockState state = DeepFill.bandAt(by, minY, own.fillRandom, bedrock, deepslate, stone);

                    if (state == null) {
                        continue;
                    }

                    int storageIdx = ctx.toStorageIndex(by);

                    if (storageIdx < 0 || storageIdx >= storageArrays.length) {
                        continue;
                    }

                    ExtendedBlockStorage section = storageArrays[storageIdx];

                    if (section == Chunk.NULL_BLOCK_STORAGE) {
                        section = new ExtendedBlockStorage(by >> 4 << 4, hasSkyLight);
                        storageArrays[storageIdx] = section;
                    }

                    // Above zero this chunk holds finished terrain, caves included; the transition may only recolor
                    // stone there.
                    if (by >= 0 && section.get(bx, by & 15, bz).getBlock() != Blocks.STONE) {
                        continue;
                    }

                    section.set(bx, by & 15, bz, state);
                }
            }
        }

        ChunkPrimerAdapter adapter = new ChunkPrimerAdapter(chunk, ctx);

        if (DepthsUpdateConfig.generateUndergroundRivers) {
            if (own.river == null) {
                own.river = new UndergroundRiverGenerator(world);
            }

            own.river.generate(x, z, adapter);
        }

        if (own.caves == null) {
            own.caves = new CaveNoiseGenerator(world);
        }

        // Chunk biome bytes use index z << 4 | x, which is the same flat layout the carve expects for column (x, z).
        byte[] biomeIds = chunk.getBiomeArray();
        Biome[] biomes = new Biome[biomeIds.length];

        for (int i = 0; i < biomeIds.length; i++) {
            biomes[i] = Biome.getBiome(biomeIds[i] & 255, Biomes.PLAINS);
        }

        own.caves.generate(x, z, adapter, biomes);

        chunk.generateSkylightMap();
    }
}
