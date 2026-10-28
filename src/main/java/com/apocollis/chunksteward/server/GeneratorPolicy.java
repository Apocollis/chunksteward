package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Which mod {@code IWorldGenerator}s may run on a worker. Every generator is tried unless {@code features.serverOnlyGenerators}
 * lists it. A generator that aborts a job or throws on a worker gets a strike; at {@code features.demoteAfterAborts}
 * strikes it is demoted to the server thread for the rest of the session. A generator's lock keeps a worker run and a
 * server-thread run of the same class from overlapping, since generators keep per-call state.
 */
final class GeneratorPolicy {
    private static final Set<String> DEMOTED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> STRIKES = new ConcurrentHashMap<String, Integer>();
    private static final Map<Class<?>, ReentrantLock> LOCKS = new ConcurrentHashMap<Class<?>, ReentrantLock>();

    private GeneratorPolicy() {
    }

    /** Mod generators of staged chunks are staged at all. */
    static boolean enabled() {
        return ChunkStewardConfig.features.enabled && ChunkStewardConfig.features.modGenerators;
    }

    /** True when this generator class may run on a worker now. */
    static boolean staged(Class<?> generator) {
        if (!enabled()) {
            return false;
        }
        String name = generator.getName();
        return !DEMOTED.contains(name) && !GeneratorSegments.matches(ChunkStewardConfig.features.serverOnlyGenerators, name);
    }

    /** A job aborted or failed inside this generator. */
    static void strike(String className, String why) {
        if (className == null) {
            return;
        }
        int strikes = STRIKES.merge(className, 1, Integer::sum);
        if (strikes >= ChunkStewardConfig.features.demoteAfterAborts && DEMOTED.add(className)) {
            FeatureStats.count(FeatureStats.GEN_DEMOTED);
            ChunkSteward.LOGGER.info("staged populate: {} runs on the server thread from now on ({} aborts, last: {})",
                    className, strikes, why);
        }
    }

    static int demotedCount() {
        return DEMOTED.size();
    }

    static ReentrantLock lockFor(Class<?> generator) {
        ReentrantLock lock = LOCKS.get(generator);
        if (lock == null) {
            lock = new ReentrantLock();
            ReentrantLock raced = LOCKS.putIfAbsent(generator, lock);
            if (raced != null) {
                lock = raced;
            }
        }
        return lock;
    }
}
