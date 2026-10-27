package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.feature.SiltBeachFeature;

/** Per-thread noise buffer, see {@link MixinBiomeGenerator}. */
@Mixin(value = SiltBeachFeature.class, remap = false)
public abstract class MixinSiltBeachFeature {
    private static final String NOISE = "Lthebetweenlands/common/world/gen/biome/feature/SiltBeachFeature;siltNoise:[D";

    @Redirect(method = {"generateNoise", "replaceStackBlocks"},
            at = @At(value = "FIELD", target = NOISE, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$get(SiltBeachFeature self) {
        return ThreadNoise.of(self).first;
    }
}
