package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Beneath. Its biome provider is a single biome, copied without this class. */
@Mixin(targets = "com.shinoow.beneath.common.world.gen.ChunkProviderDeepDank", remap = false)
public abstract class MixinChunkProviderDeepDank {

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }
}
