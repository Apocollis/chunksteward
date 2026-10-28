package com.apocollis.chunksteward.rtg;

import com.apocollis.chunksteward.server.FeatureRandom;
import com.apocollis.chunksteward.server.FeatureStage;
import com.apocollis.chunksteward.server.ThreadRtg;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.IChunkGenerator;
import rtg.api.world.RTGWorld;
import rtg.api.world.biome.IRealisticBiome;
import rtg.world.gen.ChunkGeneratorRTG;

import java.util.Random;

/**
 * RTG's biome decorations ({@code IRealisticBiome.rDecorate}) as a staged populate piece. On a worker it runs against
 * the worker's own generator copy with a copy of the generator's {@code Random}; inline it runs on the server generator
 * with the same starting state, so both give the world the same blocks.
 *
 * <p>The server thread continues after the stage from the random state the worker ended in
 * ({@link #beforeTail()}), so the draws that follow (animals) are the ones an unstaged populate makes.
 */
public final class RtgDecorationStage implements FeatureStage {
    private final RtgSplitAccess generator;
    private final IRealisticBiome biome;
    private final ChunkPos chunkPos;
    private final float river;
    private final boolean hasVillage;
    /** The generator's random where the decorations start; the inline run starts from it. Never drawn from. */
    private final Random startState;
    /** The worker's copy. */
    private final Random jobRandom;
    private volatile boolean ranOnWorker;
    private volatile long finalState;

    public RtgDecorationStage(RtgSplitAccess generator, IRealisticBiome biome, ChunkPos chunkPos, float river,
                              boolean hasVillage, Random generatorRandom) {
        this.generator = generator;
        this.biome = biome;
        this.chunkPos = chunkPos;
        this.river = river;
        this.hasVillage = hasVillage;
        this.startState = FeatureRandom.copy(generatorRandom);
        this.jobRandom = FeatureRandom.copy(generatorRandom);
    }

    @Override
    public String name() {
        return "rtg-decorations";
    }

    @Override
    public void run(IChunkGenerator workerGenerator) {
        RTGWorld worker = ((ChunkGeneratorRTG) workerGenerator).rtgWorld;
        // rtgWorld.rand() answers this thread's published random: the same object the decorations are given.
        ThreadRtg.generator(jobRandom);
        biome.rDecorate(worker, jobRandom, chunkPos, river, hasVillage, biome);
        if (FeatureRandom.hasGaussian(jobRandom)) {
            // A pending Gaussian cannot be carried back to the generator's random: let the server thread redo it.
            throw new IllegalStateException("decoration left a Gaussian pending");
        }
        finalState = FeatureRandom.state(jobRandom);
        ranOnWorker = true;
    }

    @Override
    public void runInline() {
        ranOnWorker = false;
        Random random = generator.chunksteward$rand();
        FeatureRandom.transfer(startState, random);
        biome.rDecorate(((ChunkGeneratorRTG) generator).rtgWorld, random, chunkPos, river, hasVillage, biome);
    }

    /** Server thread, before the tail: continue from where the worker's decorations left the random. */
    public void beforeTail() {
        if (ranOnWorker) {
            FeatureRandom.setState(generator.chunksteward$rand(), finalState);
        }
    }

    public IRealisticBiome biome() {
        return biome;
    }

    public boolean hasVillage() {
        return hasVillage;
    }
}
