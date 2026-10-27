package com.apocollis.chunksteward.mixin.endexpansion;

import com.example.structure.world.Biome.BiomeBarrendLands;
import git.jbredwards.nether_api.api.world.INetherAPIChunkGenerator;
import net.minecraft.world.chunk.ChunkPrimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/**
 * Barrend Bogs rolls its top block from a {@code Random} owned by the biome singleton, never reseeded from the chunk and
 * shared by every thread. Every roll in {@code buildSurface} uses the chunk generator's random instead, which the
 * generator reseeds from the chunk position. The depth roll already did. Populate keeps the biome random.
 */
@Mixin(value = BiomeBarrendLands.class, remap = false)
public abstract class MixinBiomeBarrendLands {

    @Redirect(method = "buildSurface", at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I"))
    private int chunksteward$chunkRandom(Random ignored, int bound, INetherAPIChunkGenerator chunkGenerator,
                                         int chunkX, int chunkZ, ChunkPrimer primer, int x, int z,
                                         double terrainNoise) {
        return chunkGenerator.getRand().nextInt(bound);
    }
}
