package com.apocollis.chunksteward.server;

import net.minecraft.world.gen.IChunkGenerator;

/**
 * One piece of a chunk's populate that can run on a worker: a decoration pass, later a mod's world generator.
 * The same piece can also run inline on the server thread, which is what happens when the worker run aborts.
 */
public interface FeatureStage {
    /** Short name for logs and metrics. */
    String name();

    /** True when the stage uses the shared decoration objects and must hold {@link FeatureLane#decoLock()} while it runs. */
    default boolean needsDecorationLock() {
        return true;
    }

    /**
     * Worker thread, inside the job's {@code FeatureScope}: every {@code World} call is answered from the scope.
     * {@code workerGenerator} is this worker's own copy of the dimension's generator.
     */
    void run(IChunkGenerator workerGenerator) throws Exception;

    /** Server thread: the same work against the live world, used when {@link #run} aborted, failed or never ran. */
    void runInline();
}
