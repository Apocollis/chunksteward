package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.PipelineMarker;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.storage.MapStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Workers and the verifier each get a biome provider. The verifier also gets a scratch structure save so a verify run
 * does not write {@code MapGenStructureData} into the world.
 */
@Mixin(World.class)
public abstract class MixinWorldPipeline {

    @Inject(method = "getBiomeProvider()Lnet/minecraft/world/biome/BiomeProvider;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$biome(CallbackInfoReturnable<BiomeProvider> cir) {
        if (!PipelineMarker.worker() && !PipelineMarker.verify()) {
            return;
        }
        BiomeProvider provider = PipelineMarker.biome();
        if (provider != null) {
            cir.setReturnValue(provider);
        }
    }

    @Inject(method = "getPerWorldStorage()Lnet/minecraft/world/storage/MapStorage;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$storage(CallbackInfoReturnable<MapStorage> cir) {
        if (PipelineMarker.verify()) {
            cir.setReturnValue(PipelineMarker.scratch());
        }
    }
}
