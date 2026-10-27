package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.feature.Marsh1Feature;

/** Per-thread noise buffers, see {@link MixinBiomeGenerator}. {@link MixinMarsh2Feature} fills the same slots. */
@Mixin(value = Marsh1Feature.class, remap = false)
public abstract class MixinMarsh1Feature {
    private static final String ISLAND = "Lthebetweenlands/common/world/gen/biome/feature/Marsh1Feature;islandNoise:[D";
    private static final String FUZZ = "Lthebetweenlands/common/world/gen/biome/feature/Marsh1Feature;fuzzNoise:[D";

    @Redirect(method = {"generateNoise", "replaceStackBlocks"},
            at = @At(value = "FIELD", target = ISLAND, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getIsland(Marsh1Feature self) {
        return ThreadNoise.of(self).first;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = ISLAND, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putIsland(Marsh1Feature self, double[] value) {
        ThreadNoise.of(self).first = value;
    }

    @Redirect(method = {"generateNoise", "replaceStackBlocks"},
            at = @At(value = "FIELD", target = FUZZ, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getFuzz(Marsh1Feature self) {
        return ThreadNoise.of(self).second;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = FUZZ, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putFuzz(Marsh1Feature self, double[] value) {
        ThreadNoise.of(self).second = value;
    }
}
