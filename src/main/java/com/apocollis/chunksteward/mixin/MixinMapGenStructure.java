package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.GenMetrics;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * Times {@code MapGenStructure.generateStructure} ({@code func_175794_a}), the structure paste that runs inside
 * populate, by the runtime class (vanilla villages, mineshafts, strongholds, and other mods' subclasses). Plain
 * HEAD/RETURN injects only.
 */
@Mixin(MapGenStructure.class)
public abstract class MixinMapGenStructure {

    @Inject(method = "generateStructure(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/util/math/ChunkPos;)Z",
            at = @At("HEAD"))
    private void chunksteward$pasteStart(World world, Random random, ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (GenMetrics.active()) {
            GenMetrics.enter(GenMetrics.structureKey(((Object) this).getClass()));
        }
    }

    @Inject(method = "generateStructure(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/util/math/ChunkPos;)Z",
            at = @At("RETURN"))
    private void chunksteward$pasteEnd(World world, Random random, ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (GenMetrics.active()) {
            GenMetrics.exit(GenMetrics.structureKey(((Object) this).getClass()));
        }
    }
}
