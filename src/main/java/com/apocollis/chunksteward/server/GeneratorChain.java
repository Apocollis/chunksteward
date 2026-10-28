package com.apocollis.chunksteward.server;

import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.Collections;
import java.util.List;

/**
 * Runs the mod world generators of a chunk whose decorations were staged, in Forge's order. Generators that may go to
 * a worker are grouped into runs; each run is a feature job of its own, and the runs that must stay on the server
 * thread (server-only or demoted generators) run in between, inline. Each job's finish starts the next run, so the chunk
 * stays held until the last one is applied. Server thread only.
 */
public final class GeneratorChain {
    private final WorldServer world;
    private final int x;
    private final int z;
    private final IChunkGenerator generator;
    private final List<Boolean> eligible;
    private final List<Boolean> runs;
    private final Runnable done;

    private GeneratorChain(WorldServer world, int x, int z, IChunkGenerator generator, List<Boolean> eligible,
                           Runnable done) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.generator = generator;
        this.eligible = eligible;
        this.runs = GeneratorSegments.runs(eligible);
        this.done = done;
    }

    /** Runs every generator for the chunk, then {@code done}. Staged where allowed, inline otherwise. */
    public static void run(WorldServer world, int x, int z, IChunkGenerator generator, Runnable done) {
        if (GeneratorPolicy.enabled() && FeatureLane.available(world)) {
            List<Boolean> eligible = GeneratorPlan.probe(x, z, world, generator);
            if (eligible.contains(Boolean.TRUE)) {
                new GeneratorChain(world, x, z, generator, eligible, done).step(0);
                return;
            }
        }
        GameRegistry.generateWorld(x, z, world, generator, world.getChunkProvider());
        done.run();
    }

    private void step(int from) {
        int run = from;
        while (run < runs.size()) {
            if (runs.get(run).booleanValue()) {
                final int next = run + 1;
                GeneratorStage stage = new GeneratorStage(world, x, z, eligible, run, generator);
                FeatureJob job = FeatureLane.submit(world, x, z, Collections.<FeatureStage>singletonList(stage),
                        new Runnable() {
                            @Override
                            public void run() {
                                step(next);
                            }
                        });
                if (job != null) {
                    return;
                }
                FeatureStats.count(FeatureStats.GEN_INLINE);
            }
            GeneratorPlan.runSegment(eligible, run, false, x, z, world, generator);
            run++;
        }
        done.run();
    }
}
