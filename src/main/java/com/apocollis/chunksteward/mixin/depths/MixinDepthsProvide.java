package com.apocollis.chunksteward.mixin.depths;

import com.apocollis.chunksteward.depths.DepthsBridge;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Depths Update wraps the generator call in {@code ChunkProviderServer.provideChunk} with a handler that, after the
 * generator returns, fills the deep band, carves caves and rivers, and rebuilds the sky light map on the server
 * thread. A terrain worker that already did all of that (see {@code DepthsBridge}) hands the finished chunk over here, so
 * the handler must not run again: the fill would overwrite the carved caves below Y 0.
 *
 * <p>This is injected into the handler Depths merged into {@code ChunkProviderServer} (its name is mangled, so it is
 * matched by a pattern), with a higher priority number so it applies after Depths' own mixin. The config is not
 * required and its injectors are not required: if the injection does not apply, workers do not run the Depths pass
 * ({@code DepthsBridge} looks for this handler) and nothing changes.
 */
@Mixin(value = ChunkProviderServer.class, priority = 2000)
public abstract class MixinDepthsProvide {

    @Shadow
    @Final
    private WorldServer world;

    @Inject(method = "/.*depthsupdate\\$onGenerateChunk.*/", at = @At("HEAD"), cancellable = true, remap = false,
            require = 0)
    private void chunksteward$depthsSkip(IChunkGenerator generator, int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk finished = DepthsBridge.tryInstall(generator, this.world, x, z);
        if (finished != null) {
            cir.setReturnValue(finished);
        }
    }
}
