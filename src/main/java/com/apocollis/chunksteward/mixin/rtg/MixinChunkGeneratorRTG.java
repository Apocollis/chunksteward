package com.apocollis.chunksteward.mixin.rtg;

import com.apocollis.chunksteward.server.PipelineMarker;
import com.apocollis.chunksteward.server.TerrainPipeline;
import com.apocollis.chunksteward.server.ThreadRtg;
import java.util.Random;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Installs a worker-built RTG chunk, and publishes this copy's random for the generation call. */
@Mixin(targets = "rtg.world.gen.ChunkGeneratorRTG", remap = false)
public abstract class MixinChunkGeneratorRTG {

    @Shadow
    private Random rand;

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), remap = false)
    private void chunksteward$rtgRandom(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (PipelineMarker.worker() || PipelineMarker.verify()) {
            ThreadRtg.generator(rand);
        }
    }

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"), remap = false)
    private void chunksteward$rtgRandomClear(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        ThreadRtg.clearGenerator();
    }

    @Inject(method = "func_180514_a(Lnet/minecraft/world/chunk/Chunk;II)V", at = @At("HEAD"), remap = false)
    private void chunksteward$rtgRandomStructures(Chunk chunk, int x, int z, CallbackInfo ci) {
        if (PipelineMarker.worker() || PipelineMarker.verify()) {
            ThreadRtg.generator(rand);
        }
    }

    @Inject(method = "func_180514_a(Lnet/minecraft/world/chunk/Chunk;II)V", at = @At("RETURN"), remap = false)
    private void chunksteward$rtgRandomStructuresClear(Chunk chunk, int x, int z, CallbackInfo ci) {
        ThreadRtg.clearGenerator();
    }
}
