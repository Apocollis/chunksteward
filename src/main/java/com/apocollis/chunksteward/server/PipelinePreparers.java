package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.api.JobPreparer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Preparers registered through {@link com.apocollis.chunksteward.api.ChunkStewardAPI}. The failure handling and the
 * per-class discard counts are in {@link PreparerCore}.
 */
final class PipelinePreparers {
    private static final CopyOnWriteArrayList<JobPreparer> LIST = new CopyOnWriteArrayList<JobPreparer>();

    private static final PreparerCore.Reporter REPORTER = new PreparerCore.Reporter() {
        @Override
        public void prepareThrew(String name, Throwable error) {
            ChunkSteward.LOGGER.error("pipeline preparer {} threw in prepare; its chunks are discarded and generated on the "
                    + "server thread (logged once)", name, error);
        }

        @Override
        public void validThrew(String name, Throwable error) {
            ChunkSteward.LOGGER.error("pipeline preparer {} threw in isStillValid; its chunks are discarded (logged once)",
                    name, error);
        }

        @Override
        public void firstDiscard(String name) {
            ChunkSteward.LOGGER.warn("pipeline preparer {} discarded its first worker chunk; later discards are counted in "
                    + "the pipeline status", name);
        }
    };

    private PipelinePreparers() {
    }

    static void register(JobPreparer preparer) {
        if (preparer != null && !LIST.contains(preparer)) {
            LIST.add(preparer);
        }
    }

    private static List<PreparerCore.Slot> slots(final World world, final int chunkX, final int chunkZ) {
        List<PreparerCore.Slot> out = new ArrayList<PreparerCore.Slot>(LIST.size());
        for (final JobPreparer preparer : LIST) {
            out.add(new PreparerCore.Slot() {
                @Override
                public String name() {
                    return preparer.getClass().getName();
                }

                @Override
                public Object prepare() {
                    return preparer.prepare(world, chunkX, chunkZ);
                }

                @Override
                public boolean valid(Object context) {
                    return preparer.isStillValid(context, world, chunkX, chunkZ);
                }
            });
        }
        return out;
    }

    /** Contexts in registration order, or null when nobody is registered. */
    static PreparerCore.Prepared prepare(World world, int chunkX, int chunkZ) {
        if (LIST.isEmpty() || world == null) {
            return null;
        }
        return PreparerCore.prepare(slots(world, chunkX, chunkZ), REPORTER);
    }

    static boolean valid(Object context, long failed, World world, int chunkX, int chunkZ) {
        if (!(context instanceof Object[]) || world == null) {
            return true;
        }
        return PreparerCore.valid(slots(world, chunkX, chunkZ), (Object[]) context, failed, REPORTER);
    }

    /** Status text to append after the discard reasons; see {@link PreparerCore#statusSuffix}. */
    static String statusSuffix(Map<String, Long> since) {
        return PreparerCore.statusSuffix(since);
    }
}
