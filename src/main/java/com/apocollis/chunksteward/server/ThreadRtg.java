package com.apocollis.chunksteward.server;

import java.lang.reflect.Constructor;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

/**
 * RTG state that is one object per world, held per thread while a worker or verify leg runs.
 * {@link #generator(Random)} is that leg's {@code ChunkGeneratorRTG} random. The cellular maps fill as the thread
 * visits chunks and stay for the thread; a miss recomputes the same points the shared map would.
 */
public final class ThreadRtg {
    private static final ThreadLocal<Random> GENERATOR = new ThreadLocal<Random>();
    private static final ThreadLocal<Map<Object, Map<?, ?>>> CELLULAR =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static volatile Constructor<?> cacheConstructor;

    private ThreadRtg() {
    }

    public static void generator(Random random) {
        if (random == null) {
            GENERATOR.remove();
        } else {
            GENERATOR.set(random);
        }
    }

    /** The generator random published for this thread, or null when this thread is not inside a generation call. */
    public static Random generator() {
        return GENERATOR.get();
    }

    public static void clearGenerator() {
        GENERATOR.remove();
    }

    /** This thread's cache for {@code noise}, created at capacity 256 the first time. */
    public static Map<?, ?> cellular(Object noise) {
        Map<Object, Map<?, ?>> maps = CELLULAR.get();
        Map<?, ?> cache = maps.get(noise);
        if (cache == null) {
            cache = newCache();
            maps.put(noise, cache);
        }
        return cache;
    }

    private static Map<?, ?> newCache() {
        try {
            if (cacheConstructor == null) {
                Class<?> type = Class.forName("rtg.api.util.LimitedArrayCacheMap");
                cacheConstructor = type.getConstructor(int.class);
            }
            return (Map<?, ?>) cacheConstructor.newInstance(256);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("RTG cellular cache", failed);
        }
    }
}
