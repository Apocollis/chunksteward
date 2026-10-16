package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.GenMetrics;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times {@code MapGenBase.generate} ({@code func_186125_a}) by the runtime class: caves, ravines and the layout of
 * structures (villages, mineshafts, strongholds) during the terrain stage. Plain HEAD/RETURN injects only, so they
 * coexist with other mods' mixins on the same method.
 */
@Mixin(MapGenBase.class)
public abstract class MixinMapGenBase {

    @Inject(method = "generate(Lnet/minecraft/world/World;IILnet/minecraft/world/chunk/ChunkPrimer;)V",
            at = @At("HEAD"))
    private void chunksteward$mapGenStart(World world, int chunkX, int chunkZ, ChunkPrimer primer, CallbackInfo ci) {
        if (GenMetrics.active()) {
            GenMetrics.enter(GenMetrics.mapGenKey(((Object) this).getClass()));
        }
    }

    @Inject(method = "generate(Lnet/minecraft/world/World;IILnet/minecraft/world/chunk/ChunkPrimer;)V",
            at = @At("RETURN"))
    private void chunksteward$mapGenEnd(World world, int chunkX, int chunkZ, ChunkPrimer primer, CallbackInfo ci) {
        if (GenMetrics.active()) {
            GenMetrics.exit(GenMetrics.mapGenKey(((Object) this).getClass()));
        }
    }
}
