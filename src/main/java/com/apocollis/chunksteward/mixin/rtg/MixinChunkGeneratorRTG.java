package com.apocollis.chunksteward.mixin.rtg;

import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Installs a worker-built RTG chunk. Queued with the other RTG-Plus mixins. */
@Mixin(targets = "rtg.world.gen.ChunkGeneratorRTG", remap = false)
public abstract class MixinChunkGeneratorRTG {

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }
}
