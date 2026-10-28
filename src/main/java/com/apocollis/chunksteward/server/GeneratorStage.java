package com.apocollis.chunksteward.server;

import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.List;

/**
 * One run of mod {@code IWorldGenerator}s (see {@link GeneratorPlan}) as a staged populate piece. On a worker it runs
 * against the job's scope with the worker's generator copy; inline it runs the same run on the server thread with the
 * server's generator. Each generator gets Forge's own per-chunk seed either way, so both give the world the same blocks.
 */
final class GeneratorStage implements FeatureStage {
    private final WorldServer world;
    private final int x;
    private final int z;
    private final List<Boolean> eligible;
    private final int segment;
    private final IChunkGenerator serverGenerator;

    GeneratorStage(WorldServer world, int x, int z, List<Boolean> eligible, int segment, IChunkGenerator serverGenerator) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.eligible = eligible;
        this.segment = segment;
        this.serverGenerator = serverGenerator;
    }

    @Override
    public String name() {
        return "mod-generators#" + segment;
    }

    @Override
    public boolean needsDecorationLock() {
        return false;
    }

    @Override
    public void run(IChunkGenerator workerGenerator) {
        GeneratorPlan.runSegment(eligible, segment, true, x, z, world, workerGenerator);
    }

    @Override
    public void runInline() {
        FeatureStats.count(FeatureStats.GEN_INLINE);
        GeneratorPlan.runSegment(eligible, segment, false, x, z, world, serverGenerator);
    }
}
