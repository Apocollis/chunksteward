package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureHooks;
import com.apocollis.chunksteward.server.FeatureLane;
import com.apocollis.chunksteward.server.GenMetrics;
import com.apocollis.chunksteward.server.RandomTickGuard;
import com.apocollis.chunksteward.server.StageGate;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@code provideChunk} ({@code func_186025_d}):
 *
 * <ul>
 * <li>HEAD: while random block ticks run, a chunk that is not loaded is answered with an empty chunk
 * ({@link RandomTickGuard}) instead of being generated.</li>
 * <li>Around the {@code IChunkGenerator.generateChunk} ({@code func_185932_a}) call: the terrain stage timing, and
 * {@link StageGate#onGenerate} (known-generated cache, cascade detection when a populate is running).</li>
 * </ul>
 * Populate and light that follow are timed on {@code Chunk}.
 */
@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$randomTickGuard(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        // Outside updateBlocks this is one boolean read. intercept is too large to inline, and lighting calls
        // provideChunk once per light read.
        if (!RandomTickGuard.isActive()) {
            return;
        }
        Chunk empty = RandomTickGuard.intercept((ChunkProviderServer) (Object) this, x, z);
        if (empty != null) {
            cir.setReturnValue(empty);
        }
    }

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/IChunkGenerator;generateChunk(II)Lnet/minecraft/world/chunk/Chunk;"))
    private void chunksteward$terrainStart(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        StageGate.onGenerate(((ChunkProviderServer) (Object) this).world.provider.getDimension(), x, z);
        GenMetrics.enter(GenMetrics.TERRAIN);
    }

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/IChunkGenerator;generateChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            shift = At.Shift.AFTER))
    private void chunksteward$terrainEnd(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        GenMetrics.exit(GenMetrics.TERRAIN);
    }

    /**
     * A feature job on a worker reads chunks from its own window, never from the shared chunk map, and never loads or
     * generates one. A mod generator that asks the chunk provider is answered the same way (or aborts the job).
     */
    @Inject(method = "getLoadedChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureLoaded(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = FeatureHooks.providerChunk(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "loadChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureLoad(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = FeatureHooks.providerChunk(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureProvide(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = FeatureHooks.providerChunk(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    /** A chunk with staged populate work that is not applied yet stays loaded. */
    @Inject(method = "queueUnload(Lnet/minecraft/world/chunk/Chunk;)V", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureHoldUnload(Chunk chunk, CallbackInfo ci) {
        if (FeatureLane.queued() != 0
                && FeatureLane.holds(((ChunkProviderServer) (Object) this).world.provider.getDimension(), chunk.x, chunk.z)) {
            ci.cancel();
        }
    }
}
