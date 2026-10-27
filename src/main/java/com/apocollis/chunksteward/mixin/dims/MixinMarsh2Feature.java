package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.feature.Marsh2Feature;

/**
 * Marsh2 overrides {@code generateNoise} and names the inherited fields with itself as owner, so
 * {@link MixinMarsh1Feature} does not see these accesses. Same slots; the parent's {@code replaceStackBlocks} reads them.
 */
@Mixin(value = Marsh2Feature.class, remap = false)
public abstract class MixinMarsh2Feature {
    private static final String ISLAND = "Lthebetweenlands/common/world/gen/biome/feature/Marsh2Feature;islandNoise:[D";
    private static final String FUZZ = "Lthebetweenlands/common/world/gen/biome/feature/Marsh2Feature;fuzzNoise:[D";

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = ISLAND, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getIsland(Marsh2Feature self) {
        return ThreadNoise.of(self).first;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = ISLAND, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putIsland(Marsh2Feature self, double[] value) {
        ThreadNoise.of(self).first = value;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = FUZZ, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getFuzz(Marsh2Feature self) {
        return ThreadNoise.of(self).second;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = FUZZ, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putFuzz(Marsh2Feature self, double[] value) {
        ThreadNoise.of(self).second = value;
    }
}
