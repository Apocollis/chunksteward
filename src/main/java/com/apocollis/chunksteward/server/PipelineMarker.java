package com.apocollis.chunksteward.server;

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
    private static final ThreadLocal<MapStorage> SCRATCH = new ThreadLocal<MapStorage>();

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

    public static void clear() {
        FLAGS.remove();
        BIOME.remove();
    }

    public static boolean worker() {
        return (FLAGS.get() & WORKER) != 0;
    }

    public static boolean verify() {
        return (FLAGS.get() & VERIFY) != 0;
    }

    public static void biome(BiomeProvider provider) {
        if (provider == null) {
            BIOME.remove();
        } else {
            BIOME.set(provider);
        }
    }

    public static BiomeProvider biome() {
        return BIOME.get();
    }

    public static MapStorage scratch() {
        MapStorage storage = SCRATCH.get();
        if (storage == null) {
            storage = new MapStorage(null);
            SCRATCH.set(storage);
        }
        return storage;
    }
}
