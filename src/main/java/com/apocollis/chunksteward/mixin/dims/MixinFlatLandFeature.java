package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.feature.FlatLandFeature;

/** Per-thread noise buffers, see {@link MixinBiomeGenerator}. */
@Mixin(value = FlatLandFeature.class, remap = false)
public abstract class MixinFlatLandFeature {
    private static final String LAND = "Lthebetweenlands/common/world/gen/biome/feature/FlatLandFeature;landNoise:[D";
    private static final String RIVER = "Lthebetweenlands/common/world/gen/biome/feature/FlatLandFeature;riverNoise:[D";

    @Redirect(method = {"generateNoise", "replaceStackBlocks"},
            at = @At(value = "FIELD", target = LAND, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getLand(FlatLandFeature self) {
        return ThreadNoise.of(self).first;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = LAND, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putLand(FlatLandFeature self, double[] value) {
        ThreadNoise.of(self).first = value;
    }

    @Redirect(method = {"generateNoise", "replaceStackBlocks"},
            at = @At(value = "FIELD", target = RIVER, opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getRiver(FlatLandFeature self) {
        return ThreadNoise.of(self).second;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD", target = RIVER, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putRiver(FlatLandFeature self, double[] value) {
        ThreadNoise.of(self).second = value;
    }
}
