package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.GenMetrics;
import com.apocollis.chunksteward.server.PipelineMarker;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.MapGenBase;
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
 *
 * <p>Also adds the {@code generate} override {@code MapGenStructure} does not have, so a pipeline worker skips structure
 * layout. The server thread still calls through to {@code MapGenBase}.
 */
@Mixin(MapGenStructure.class)
public abstract class MixinMapGenStructure extends MapGenBase {

    @Override
    public void generate(World world, int chunkX, int chunkZ, ChunkPrimer primer) {
        if (PipelineMarker.worker()) {
            return;
        }
        super.generate(world, chunkX, chunkZ, primer);
    }

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
