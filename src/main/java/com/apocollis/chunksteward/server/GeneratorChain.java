package com.apocollis.chunksteward.server;

import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.List;

/**
 * Mod world generators of a chunk whose decorations are staged (see {@link GeneratorPlan}). The generators that may go to
 * a worker are a stage of the same job as the decorations; this is the other side: the probe, and the generators that
 * stay on the server thread (server-only or demoted), run inline after the rest of the populate. Server thread only.
 */
public final class GeneratorChain {
    private GeneratorChain() {
    }

    /**
     * The eligibility of each generator in Forge's order, as {@link GeneratorPlan#probe} lists it, or null when mod
     * generators are not staged. The caller adds a {@link GeneratorStage} to the job when the list holds a true.
     */
    public static List<Boolean> probe(WorldServer world, int x, int z, IChunkGenerator generator) {
        if (!GeneratorPolicy.enabled()) {
            return null;
        }
        return GeneratorPlan.probe(x, z, world, generator);
    }

    /** True when the list has a generator that may run on a worker. */
    public static boolean anyStaged(List<Boolean> eligible) {
        return eligible != null && eligible.contains(Boolean.TRUE);
    }

    /** The light stage of a job: computes the 2x2 window's light on the worker (see {@code LightStage}). */
    public static FeatureStage lightStage(WorldServer world, int x, int z) {
        return new LightStage(world, x, z);
    }

    /** A stage for the generators that may run on a worker. */
    public static FeatureStage stage(WorldServer world, int x, int z, List<Boolean> eligible, IChunkGenerator generator) {
        return new GeneratorStage(world, x, z, eligible, generator);
    }

    /**
     * Runs what the job did not: with no probe, every generator; with one, the generators that stay on the server
     * thread (all of them when none was staged).
     */
    public static void finish(WorldServer world, int x, int z, IChunkGenerator generator, List<Boolean> eligible) {
        if (eligible == null) {
            GameRegistry.generateWorld(x, z, world, generator, world.getChunkProvider());
        } else if (eligible.contains(Boolean.FALSE)) {
            GeneratorPlan.runSegment(eligible, GeneratorPlan.SERVER_RUN, false, x, z, world, generator);
        }
    }
}
