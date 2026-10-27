package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nether API's nether generator overrides {@code generateChunk}. */
@Mixin(targets = "git.jbredwards.nether_api.mod.common.world.gen.ChunkGeneratorNether", remap = false)
public abstract class MixinChunkGeneratorNether {

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }
}
