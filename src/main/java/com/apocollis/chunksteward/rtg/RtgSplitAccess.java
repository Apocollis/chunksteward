package com.apocollis.chunksteward.rtg;

import rtg.api.world.biome.IRealisticBiome;

import java.util.Random;

/** Implemented by the mixin on {@code ChunkGeneratorRTG}: the parts of the generator staged populate needs. */
public interface RtgSplitAccess {
    /** The generator's own {@code Random}, the one {@code populate} seeds and {@code rtgWorld.rand()} returns. */
    Random chunksteward$rand();

    /**
     * The part of RTG's {@code populate} that follows the decorations: animals, ice and snow, the post-populate event
     * and the light fix. Same code and order as RTG-Plus 9.0.3, run when the staged decorations were applied.
     */
    void chunksteward$tail(IRealisticBiome biome, int chunkX, int chunkZ, boolean hasVillage);
}
