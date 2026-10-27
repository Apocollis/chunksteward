package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadSeaLevel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import git.jbredwards.nether_api.api.world.INetherAPIChunkGenerator;
import git.jbredwards.nether_api.mod.common.compat.netherex.AbstractNetherExBiome;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Nether API's NetherEx biome wrapper sets the world sea level to 31 around {@code generateTerrain} and writes the old
 * value back. That field is shared by every thread generating the nether. On every thread, {@code buildSurface} holds
 * the level in {@link ThreadSeaLevel} for that world instead, and drops it in a {@code finally}.
 */
@Mixin(value = AbstractNetherExBiome.class, remap = false)
public abstract class MixinAbstractNetherExBiome {

    @WrapMethod(method = "buildSurface")
    private void chunksteward$holdSeaLevel(INetherAPIChunkGenerator chunkGenerator, int chunkX, int chunkZ,
                                           ChunkPrimer primer, int x, int z, double[] soulSandNoise,
                                           double[] gravelNoise, double[] depthBuffer, double terrainNoise,
                                           Operation<Void> original) {
        ThreadSeaLevel.enter(chunkGenerator.getWorld());
        try {
            original.call(chunkGenerator, chunkX, chunkZ, primer, x, z, soulSandNoise, gravelNoise, depthBuffer,
                    terrainNoise);
        } finally {
            ThreadSeaLevel.exit();
        }
    }

    @Redirect(method = "buildSurface",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_181544_b(I)V"))
    private void chunksteward$setSeaLevel(World world, int level) {
        ThreadSeaLevel.set(world, level);
    }
}
