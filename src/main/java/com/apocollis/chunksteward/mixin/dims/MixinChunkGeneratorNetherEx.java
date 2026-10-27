package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NetherEx overrides {@code generateChunk}, so the vanilla hell mixin never sees it. */
@Mixin(targets = "logictechcorp.netherex.world.generation.ChunkGeneratorNetherEx", remap = false)
public abstract class MixinChunkGeneratorNetherEx {

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }
}
