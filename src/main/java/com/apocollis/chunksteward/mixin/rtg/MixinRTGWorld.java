package com.apocollis.chunksteward.mixin.rtg;

import com.apocollis.chunksteward.server.PipelineMarker;
import com.apocollis.chunksteward.server.ThreadRtg;
import java.util.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code RTGWorld.rand()} is the first generator's {@code Random}. A worker or verify thread uses the random published
 * for the generator copy that is running. Any other thread still gets the shared one.
 */
@Mixin(targets = "rtg.api.world.RTGWorld", remap = false)
public abstract class MixinRTGWorld {

    @Inject(method = "rand()Ljava/util/Random;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$rand(CallbackInfoReturnable<Random> cir) {
        if (!PipelineMarker.worker() && !PipelineMarker.verify()) {
            return;
        }
        Random random = ThreadRtg.generator();
        if (random != null) {
            cir.setReturnValue(random);
        }
    }
}
