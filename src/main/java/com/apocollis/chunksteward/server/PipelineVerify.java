package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.mixin.AccessorMapGenStructure;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.block.Block;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compares a private reference generator with a production-shaped one over the same chunks in two orders. A clean run of
 * at least 10,000 chunks records a pass for that dimension and class.
 */
final class PipelineVerify {
    private static final int ORIGIN = 30000;
    /** Blocks past the verify square: a structure start up to 8 chunks out, plus the pyramid footprint. */
    private static final int PLATE_MARGIN = 256;
    private static volatile boolean running;
    private static volatile String status = "not run";

    private PipelineVerify() {
    }

    static boolean running() {
        return running;
    }

    static String status() {
        return status;
    }

    static boolean start(WorldServer world, int count) {
        if (running) {
            return false;
        }
        IChunkGenerator main = PipelineGenerators.of(world);
        if (main == null) {
            status = "no chunk generator";
            return false;
        }
        if (!PipelineGenerators.supported(main)) {
            status = "generator is " + main.getClass().getName() + "; no copy for this class";
            return false;
        }
        BiomeProvider referenceBiome = PipelineGenerators.copyBiome(world);
        BiomeProvider productionBiome = PipelineGenerators.copyBiome(world);
        if (referenceBiome == null || productionBiome == null) {
            BiomeProvider current = world.provider.getBiomeProvider();
            status = "biome provider cannot be copied: " + (current == null ? "null" : current.getClass().getName());
            return false;
        }
        IChunkGenerator reference = PipelineGenerators.copy(world, main, referenceBiome);
        IChunkGenerator production = PipelineGenerators.copy(world, main, productionBiome);
        if (reference == null || production == null) {
            status = "could not build verify generators for " + main.getClass().getName();
            return false;
        }
        int dimension = world.provider.getDimension();
        String className = main.getClass().getName();
        running = true;
        status = "running " + count + " in " + dimension;
        TerrainPipeline.pause(dimension);
        Thread thread = new Thread(
                () -> execute(world, count, dimension, className, reference, production, referenceBiome, productionBiome),
                "chunksteward-verify");
        thread.setDaemon(true);
        thread.setContextClassLoader(ChunkSteward.class.getClassLoader());
        thread.start();
        return true;
    }

    private static void execute(WorldServer world, int count, int dimension, String className,
                                IChunkGenerator reference, IChunkGenerator production,
                                BiomeProvider referenceBiome, BiomeProvider productionBiome) {
        java.util.Map<java.lang.reflect.Field, java.util.Map<Object, Object>> stash = PipelineGenerators.snapshotVillageStash();
        java.util.Map<Object, Object> plates = PipelineGenerators.snapshotPyramidPlates();
        int side = (int) Math.ceil(Math.sqrt(count));
        try {
            ConcurrentHashMap<Long, Long> referenceHashes = new ConcurrentHashMap<Long, Long>();
            ConcurrentHashMap<Long, Long> productionHashes = new ConcurrentHashMap<Long, Long>();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread referenceThread = leg(world, reference, referenceBiome, false, side, count, referenceHashes, failure);
            Thread productionThread = leg(world, production, productionBiome, true, side, count, productionHashes, failure);
            referenceThread.start();
            productionThread.start();
            referenceThread.join();
            productionThread.join();
            if (failure.get() != null) {
                throw failure.get();
            }
            String mismatch = firstMismatch(referenceHashes, productionHashes);
            long referenceStructures = hashStructures(reference);
            long productionStructures = hashStructures(production);
            if (mismatch == null && referenceStructures != productionStructures) {
                mismatch = "structure starts";
            }
            if (mismatch == null && count >= 10000) {
                PipelinePasses.grant(dimension, className);
                TerrainPipeline.allow(className);
                status = "passed " + count + " in dimension " + dimension;
                ChunkSteward.LOGGER.info("pipeline verify passed {} chunks of {} in dimension {}", count, className, dimension);
            } else if (mismatch == null) {
                status = "clean for " + count + "; a pass needs 10000";
                ChunkSteward.LOGGER.info("pipeline verify clean for {} chunks of {} in dimension {}; not recorded",
                        count, className, dimension);
            } else {
                PipelinePasses.revoke(dimension, className);
                TerrainPipeline.disallow(className);
                status = "mismatch at " + mismatch;
                ChunkSteward.LOGGER.warn("pipeline verify mismatch for {} in dimension {} at {}", className, dimension, mismatch);
            }
        } catch (Throwable failed) {
            PipelinePasses.revoke(dimension, className);
            TerrainPipeline.disallow(className);
            status = "failed";
            ChunkSteward.LOGGER.warn("pipeline verify failed for {} in dimension {}", className, dimension, failed);
        } finally {
            PipelineGenerators.restoreVillageStash(stash);
            PipelineGenerators.restorePyramidPlates(plates, dimension, ORIGIN * 16 - PLATE_MARGIN,
                    (ORIGIN + side) * 16 + PLATE_MARGIN, ORIGIN * 16 - PLATE_MARGIN, (ORIGIN + side) * 16 + PLATE_MARGIN);
            TerrainPipeline.resume(dimension);
            running = false;
        }
    }

    private static Thread leg(WorldServer world, IChunkGenerator generator, BiomeProvider biome, boolean production,
                              int side, int count, ConcurrentHashMap<Long, Long> hashes, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    int x = production ? ORIGIN + (i / side) : ORIGIN + (i % side);
                    int z = production ? ORIGIN + (i % side) : ORIGIN + (i / side);
                    Chunk chunk = generate(world, generator, biome, production, x, z);
                    hashes.put(pack(x, z), hashChunk(chunk));
                }
            } catch (Throwable failed) {
                failure.compareAndSet(null, failed);
            }
        }, production ? "chunksteward-verify-worker" : "chunksteward-verify-reference");
        thread.setDaemon(true);
        thread.setContextClassLoader(ChunkSteward.class.getClassLoader());
        return thread;
    }

    private static Chunk generate(WorldServer world, IChunkGenerator generator, BiomeProvider biome, boolean production,
                                  int x, int z) {
        Chunk[] held = new Chunk[1];
        PipelineMarker.set(production, true);
        PipelineMarker.biome(biome, world.provider.getDimension());
        try {
            BedrockShim.run(world, () -> held[0] = generator.generateChunk(x, z));
            if (production) {
                PipelineMarker.set(false, true);
                generator.recreateStructures(held[0], x, z);
            }
        } finally {
            PipelineMarker.clear();
        }
        if (held[0] == null) {
            throw new IllegalStateException("generateChunk returned null at " + x + ", " + z);
        }
        return held[0];
    }

    private static String firstMismatch(ConcurrentHashMap<Long, Long> reference, ConcurrentHashMap<Long, Long> production) {
        if (reference.size() != production.size()) {
            return "chunk count " + reference.size() + " / " + production.size();
        }
        for (java.util.Map.Entry<Long, Long> entry : reference.entrySet()) {
            Long other = production.get(entry.getKey());
            if (other == null || other.longValue() != entry.getValue().longValue()) {
                long packed = entry.getKey();
                int x = (int) (packed >> 32);
                int z = (int) packed;
                return x + ", " + z;
            }
        }
        return null;
    }

    private static long hashChunk(Chunk chunk) {
        long hash = 1L;
        for (int height : chunk.getHeightMap()) {
            hash = hash * 31L + height;
        }
        for (byte biome : chunk.getBiomeArray()) {
            hash = hash * 31L + biome;
        }
        for (int y = 0; y < 256; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    hash = hash * 31L + Block.getStateId(chunk.getBlockState(x, y, z));
                }
            }
        }
        return hash;
    }

    /** Every structure generator on the instance, including a subclass field the parent does not use. */
    private static long hashStructures(IChunkGenerator generator) {
        long hash = 1L;
        boolean any = false;
        for (Class<?> type = generator.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    if (MapGenStructure.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        hash = mixStructure(hash, (MapGenStructure) field.get(generator));
                        any = true;
                    } else if (field.getType().isArray()
                            && MapGenStructure.class.isAssignableFrom(field.getType().getComponentType())) {
                        field.setAccessible(true);
                        Object array = field.get(generator);
                        if (array == null) {
                            continue;
                        }
                        int length = Array.getLength(array);
                        for (int i = 0; i < length; i++) {
                            hash = mixStructure(hash, (MapGenStructure) Array.get(array, i));
                            any = true;
                        }
                    }
                } catch (ReflectiveOperationException ignored) {
                    return 0L;
                }
            }
        }
        return any ? hash : 0L;
    }

    private static long mixStructure(long hash, MapGenStructure structures) {
        hash = hash * 31L + (structures == null ? 0 : structures.getClass().getName().hashCode());
        if (!(structures instanceof AccessorMapGenStructure)) {
            return hash;
        }
        Long2ObjectMap<StructureStart> starts = ((AccessorMapGenStructure) structures).chunksteward$structureMap();
        if (starts == null || starts.isEmpty()) {
            return hash;
        }
        long[] keys = starts.keySet().toLongArray();
        Arrays.sort(keys);
        for (long key : keys) {
            StructureStart start = starts.get(key);
            hash = hash * 31L + key;
            if (start != null) {
                hash = hash * 31L + start.getChunkPosX();
                hash = hash * 31L + start.getChunkPosZ();
                hash = hash * 31L + start.getComponents().size();
            }
        }
        return hash;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
