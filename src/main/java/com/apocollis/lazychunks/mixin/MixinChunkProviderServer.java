package com.apocollis.lazychunks.mixin;

import com.apocollis.lazychunks.server.GenMetrics;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the terrain stage: the {@code IChunkGenerator.generateChunk} ({@code func_185932_a}) call inside
 * {@code provideChunk} ({@code func_186025_d}). Populate and light that follow are timed on {@code Chunk}.
 */
@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/IChunkGenerator;generateChunk(II)Lnet/minecraft/world/chunk/Chunk;"))
    private void lazychunks$terrainStart(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        GenMetrics.enter(GenMetrics.TERRAIN);
    }

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/IChunkGenerator;generateChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            shift = At.Shift.AFTER))
    private void lazychunks$terrainEnd(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        GenMetrics.exit(GenMetrics.TERRAIN);
    }
}
