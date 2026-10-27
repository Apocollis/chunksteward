package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.mixin.AccessorChunkGeneratorEnd;
import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.structure.MapGenEndCity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nether API's end generator overrides {@code generateChunk}. End Expansion biomes are inside this class. Its
 * {@code recreateStructures} also drops vanilla's end city call, so the cities are recorded here the way
 * {@code ChunkGeneratorEnd} does it.
 */
@Mixin(targets = "git.jbredwards.nether_api.mod.common.world.gen.ChunkGeneratorTheEnd", remap = false)
public abstract class MixinChunkGeneratorTheEnd {

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }

    @Inject(method = "func_180514_a(Lnet/minecraft/world/chunk/Chunk;II)V", at = @At("RETURN"), remap = false)
    private void chunksteward$cities(Chunk chunk, int x, int z, CallbackInfo ci) {
        AccessorChunkGeneratorEnd end = (AccessorChunkGeneratorEnd) (Object) this;
        MapGenEndCity cities = end.chunksteward$cities();
        if (cities != null && end.chunksteward$mapFeatures()) {
            cities.generate(end.chunksteward$world(), x, z, null);
        }
    }
}
