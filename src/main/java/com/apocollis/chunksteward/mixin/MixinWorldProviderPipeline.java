package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.PipelineMarker;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code World.getBiome} reaches the provider through {@code WorldProvider.getBiomeProvider()}, not
 * {@code World.getBiomeProvider()}. Workers and verify legs get their private provider here too, for the job's
 * dimension only. A provider subclass that overrides this method is not covered.
 */
@Mixin(WorldProvider.class)
public abstract class MixinWorldProviderPipeline {

    @Shadow
    protected World world;

    @Inject(method = "getBiomeProvider()Lnet/minecraft/world/biome/BiomeProvider;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$biome(CallbackInfoReturnable<BiomeProvider> cir) {
        BiomeProvider provider = PipelineMarker.biomeFor(world);
        if (provider != null) {
            cir.setReturnValue(provider);
        }
    }
}
