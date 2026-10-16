package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.GenMetrics;
import com.apocollis.chunksteward.server.StageGate;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage gate and stage timing on {@code Chunk}.
 *
 * <ul>
 * <li>{@code populate(IChunkGenerator)} ({@code func_186034_a}, the single choke point for populate): HEAD asks
 * {@link StageGate} and cancels for terrain-only preload chunks; otherwise HEAD/RETURN time the populate stage.</li>
 * <li>{@code checkLight()} ({@code func_150809_p}): HEAD asks {@link StageGate} for retries on chunks that are
 * terrain-populated but not light-populated, and cancels deferred ones; HEAD/RETURN time the light stage.</li>
 * </ul>
 *
 * <p>The light gate sits on {@code checkLight()} itself, not on the call in {@code onTick}: Somnia's coremod rewrites
 * that call into {@code SomniaUtil.chunkLightCheck(Chunk)} (which Arcana Quest Tweaks overwrites with its own throttle),
 * so a redirect there finds nothing. Populate's own call happens while {@code isTerrainPopulated} is still false and
 * is never gated: generators read light while populating.
 */
@Mixin(Chunk.class)
public abstract class MixinChunk {

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("HEAD"), cancellable = true)
    private void chunksteward$populateHead(IChunkGenerator generator, CallbackInfo ci) {
        if (!StageGate.runNow(StageGate.Stage.POPULATE, (Chunk) (Object) this)) {
            ci.cancel();
            return;
        }
        StageGate.populateEnter((Chunk) (Object) this);
        GenMetrics.enter(GenMetrics.POPULATE);
    }

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("RETURN"))
    private void chunksteward$populateReturn(IChunkGenerator generator, CallbackInfo ci) {
        GenMetrics.exit(GenMetrics.POPULATE);
        StageGate.populateExit((Chunk) (Object) this);
    }

    @Inject(method = "checkLight()V", at = @At("HEAD"), cancellable = true)
    private void chunksteward$lightHead(CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        // Terrain populated but light not: the retry onTick makes (directly, or via Somnia's/Tweaks' chunkLightCheck).
        // Populate's own call runs before isTerrainPopulated is set, so it is never gated.
        if (self.isTerrainPopulated() && !self.isLightPopulated()
                && !StageGate.runNow(StageGate.Stage.LIGHT, self)) {
            ci.cancel();
            return;
        }
        GenMetrics.enter(GenMetrics.LIGHT);
    }

    @Inject(method = "checkLight()V", at = @At("RETURN"))
    private void chunksteward$lightReturn(CallbackInfo ci) {
        GenMetrics.exit(GenMetrics.LIGHT);
    }
}
