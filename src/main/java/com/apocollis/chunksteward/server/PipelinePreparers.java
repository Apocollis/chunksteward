package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.api.JobPreparer;
import net.minecraft.world.World;

import java.util.concurrent.CopyOnWriteArrayList;

/** Preparers registered through {@link com.apocollis.chunksteward.api.ChunkStewardAPI}. */
final class PipelinePreparers {
    private static final CopyOnWriteArrayList<JobPreparer> LIST = new CopyOnWriteArrayList<JobPreparer>();

    private PipelinePreparers() {
    }

    static void register(JobPreparer preparer) {
        if (preparer != null && !LIST.contains(preparer)) {
            LIST.add(preparer);
        }
    }

    /** Contexts in registration order, or null when nobody is registered. */
    static Object prepare(World world, int chunkX, int chunkZ) {
        if (LIST.isEmpty() || world == null) {
            return null;
        }
        Object[] contexts = new Object[LIST.size()];
        for (int i = 0; i < contexts.length; i++) {
            contexts[i] = LIST.get(i).prepare(world, chunkX, chunkZ);
        }
        return contexts;
    }

    static boolean valid(Object context, World world, int chunkX, int chunkZ) {
        if (!(context instanceof Object[]) || world == null) {
            return true;
        }
        Object[] contexts = (Object[]) context;
        int n = Math.min(LIST.size(), contexts.length);
        for (int i = 0; i < n; i++) {
            if (contexts[i] != null && !LIST.get(i).isStillValid(contexts[i], world, chunkX, chunkZ)) {
                return false;
            }
        }
        return true;
    }
}
