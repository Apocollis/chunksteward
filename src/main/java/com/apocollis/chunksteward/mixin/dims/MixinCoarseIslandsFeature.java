package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.feature.CoarseIslandsFeature;

/** Per-thread noise buffers, see {@link MixinBiomeGenerator}. Read through the two getters. */
@Mixin(value = CoarseIslandsFeature.class, remap = false)
public abstract class MixinCoarseIslandsFeature {
    private static final String ISLAND =
            "Lthebetweenlands/common/world/gen/biome/feature/CoarseIslandsFeature;islandNoise:[D";
    private static final String CRAG =
            "Lthebetweenlands/common/world/gen/biome/feature/CoarseIslandsFeature;cragNoise:[D";

    @Redirect(method = {"generateNoise", "getIslandNoiseAt"},
            at = @At(value = "FIELD", target = ISLAND, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getIsland(CoarseIslandsFeature self) {
        return ThreadNoise.of(self).first;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = ISLAND, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putIsland(CoarseIslandsFeature self, double[] value) {
        ThreadNoise.of(self).first = value;
    }

    @Redirect(method = {"generateNoise", "getCragrockNoiseAt"},
            at = @At(value = "FIELD", target = CRAG, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getCrag(CoarseIslandsFeature self) {
        return ThreadNoise.of(self).second;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = CRAG, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putCrag(CoarseIslandsFeature self, double[] value) {
        ThreadNoise.of(self).second = value;
    }
}
