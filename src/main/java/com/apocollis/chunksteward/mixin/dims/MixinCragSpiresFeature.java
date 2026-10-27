package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.feature.CragSpiresFeature;

/** Per-thread noise buffer, see {@link MixinBiomeGenerator}. */
@Mixin(value = CragSpiresFeature.class, remap = false)
public abstract class MixinCragSpiresFeature {
    private static final String NOISE = "Lthebetweenlands/common/world/gen/biome/feature/CragSpiresFeature;spireNoise:[D";

    @Redirect(method = {"generateNoise", "replaceStackBlocks"},
            at = @At(value = "FIELD", target = NOISE, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$get(CragSpiresFeature self) {
        return ThreadNoise.of(self).first;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = NOISE, opcode = Opcodes.PUTFIELD))
    private void chunksteward$put(CragSpiresFeature self, double[] value) {
        ThreadNoise.of(self).first = value;
    }
}
