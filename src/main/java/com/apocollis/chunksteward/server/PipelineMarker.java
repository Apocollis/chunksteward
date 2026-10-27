package com.apocollis.chunksteward.server;

import java.util.Random;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.storage.MapStorage;

/**
 * Thread markers for pipeline work. {@code WORKER} skips structure layout. {@code VERIFY} is the equivalence run.
 * A production-shaped verify leg sets both. Neither flag is "not the server thread": that field is null at startup.
 */
public final class PipelineMarker {
    private static final int WORKER = 1;
    private static final int VERIFY = 2;
    private static final ThreadLocal<Integer> FLAGS = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<BiomeProvider> BIOME = new ThreadLocal<BiomeProvider>();
    private static final ThreadLocal<Integer> DIMENSION = new ThreadLocal<Integer>();
    private static final ThreadLocal<MapStorage> SCRATCH = new ThreadLocal<MapStorage>();
    private static final ThreadLocal<Random> RANDOM = new ThreadLocal<Random>();

    private PipelineMarker() {
    }

    public static void set(boolean worker, boolean verify) {
        int flags = (worker ? WORKER : 0) | (verify ? VERIFY : 0);
        if (flags == 0) {
            FLAGS.remove();
        } else {
            FLAGS.set(flags);
        }
    }

    /**
     * Also drops a sea level, highlands surface blocks, or an RTG generator random a failed generation left on this
     * thread. The cellular caches stay; they are keyed by noise instance and bounded.
     */
    public static void clear() {
        FLAGS.remove();
        BIOME.remove();
        DIMENSION.remove();
        RANDOM.remove();
        ThreadSeaLevel.clear();
        ThreadSurfaceBlocks.exit();
        ThreadRtg.clearGenerator();
    }

    public static boolean worker() {
        return (FLAGS.get() & WORKER) != 0;
    }

    public static boolean verify() {
        return (FLAGS.get() & VERIFY) != 0;
    }

    /** The job's private provider and the dimension it belongs to. */
    public static void biome(BiomeProvider provider, int dimension) {
        if (provider == null) {
            BIOME.remove();
            DIMENSION.remove();
        } else {
            BIOME.set(provider);
            DIMENSION.set(dimension);
        }
    }

    /**
     * The job's provider when this thread is a worker or verify leg and {@code world} is the job's dimension, else null.
     * A lookup on another dimension's world keeps that world's own provider.
     */
    public static BiomeProvider biomeFor(World world) {
        if (FLAGS.get() == 0) {
            return null;
        }
        BiomeProvider provider = BIOME.get();
        Integer dimension = DIMENSION.get();
        if (provider == null || dimension == null || world == null || world.provider == null
                || world.provider.getDimension() != dimension) {
            return null;
        }
        return provider;
    }

    public static MapStorage scratch() {
        MapStorage storage = SCRATCH.get();
        if (storage == null) {
            storage = new MapStorage(null);
            SCRATCH.set(storage);
        }
        return storage;
    }

    /**
     * This thread's {@code Random}, reseeded with {@code seed}. {@code World.setRandomSeed} returns it on pipeline
     * threads so they do not reseed the shared {@code World.rand} that another thread is drawing from.
     */
    public static Random random(long seed) {
        Random random = RANDOM.get();
        if (random == null) {
            random = new Random(seed);
            RANDOM.set(random);
        } else {
            random.setSeed(seed);
        }
        return random;
    }
}
