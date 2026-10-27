package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.PipelineMarker;
import com.apocollis.chunksteward.server.ThreadSeaLevel;
import java.util.Random;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.storage.MapStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Workers and the verifier each get a biome provider for the job's dimension. Workers and the verifier also get a
 * scratch structure save so pyramid layout and verify runs do not write {@code MapGenStructureData} into the world.
 * A sea level held by {@link ThreadSeaLevel} for this world wins over the shared field. {@code setRandomSeed} seeds a
 * per-thread {@code Random} instead of the shared {@code World.rand}, with the vanilla seed formula.
 */
@Mixin(World.class)
public abstract class MixinWorldPipeline {

    @Inject(method = "getBiomeProvider()Lnet/minecraft/world/biome/BiomeProvider;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$biome(CallbackInfoReturnable<BiomeProvider> cir) {
        BiomeProvider provider = PipelineMarker.biomeFor((World) (Object) this);
        if (provider != null) {
            cir.setReturnValue(provider);
        }
    }

    @Inject(method = "getPerWorldStorage()Lnet/minecraft/world/storage/MapStorage;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$storage(CallbackInfoReturnable<MapStorage> cir) {
        if (PipelineMarker.worker() || PipelineMarker.verify()) {
            cir.setReturnValue(PipelineMarker.scratch());
        }
    }

    @Inject(method = "getSeaLevel()I", at = @At("HEAD"), cancellable = true)
    private void chunksteward$seaLevel(CallbackInfoReturnable<Integer> cir) {
        World world = (World) (Object) this;
        if (ThreadSeaLevel.holds(world)) {
            cir.setReturnValue(ThreadSeaLevel.level(world));
        }
    }

    @Inject(method = "setRandomSeed(III)Ljava/util/Random;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$random(int x, int z, int salt, CallbackInfoReturnable<Random> cir) {
        if (PipelineMarker.worker() || PipelineMarker.verify()) {
            World world = (World) (Object) this;
            long seed = (long) x * 341873128712L + (long) z * 132897987541L + world.getWorldInfo().getSeed() + (long) salt;
            cir.setReturnValue(PipelineMarker.random(seed));
        }
    }
}
