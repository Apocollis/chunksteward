package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands back pipeline chunks, and keeps the 16×16 surface biomes in the chunk. Pyramid layout (and Arcana Quest Tweaks'
 * ground sampling in it) calls {@code setBlocksInChunk}, which writes a 10×10 sample into the first 100 slots of the
 * same {@code biomesForGeneration} array before the chunk copies it.
 */
@Mixin(targets = "com.teammetallurgy.atum.world.ChunkGeneratorAtum", remap = false)
public abstract class MixinChunkGeneratorAtum {
    private static final String BIOMES =
            "Lcom/teammetallurgy/atum/world/ChunkGeneratorAtum;biomesForGeneration:[Lnet/minecraft/world/biome/Biome;";

    @Shadow
    private Biome[] biomesForGeneration;

    @Unique
    private Biome[] chunksteward$surfaceBiomes;

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
        }
    }

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "FIELD", target = BIOMES, opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER),
            remap = false)
    private void chunksteward$saveBiomes(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        chunksteward$surfaceBiomes = biomesForGeneration == null ? null : biomesForGeneration.clone();
    }

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "NEW", target = "net/minecraft/world/chunk/Chunk"), remap = false)
    private void chunksteward$restoreBiomes(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Biome[] saved = chunksteward$surfaceBiomes;
        chunksteward$surfaceBiomes = null;
        if (saved == null) {
            return;
        }
        if (biomesForGeneration != null && biomesForGeneration.length >= saved.length) {
            System.arraycopy(saved, 0, biomesForGeneration, 0, saved.length);
        } else {
            biomesForGeneration = saved;
        }
    }
}
