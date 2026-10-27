package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadNoise;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import thebetweenlands.common.world.gen.biome.generator.BiomeGenerator;

/**
 * Each Betweenlands biome owns one {@code BiomeGenerator}, shared by every thread. Its surface noise buffer and the
 * "noise generated for this chunk" flag live in {@link ThreadNoise} per thread instead. The noise generator itself
 * stays shared; each thread's first {@code initializeGenerators} runs under the generator's lock so it sees it built.
 *
 * <p>Only the reads of the buffer fields are redirected. Mixin cannot redirect a {@code PUTFIELD} of an array field
 * ("Unsupported opcode PUTFIELD for array access"), and it drops the whole mixin class when it tries. The assignment in
 * {@code generateNoise} stays: the redirected read hands {@code getRegion} this thread's array, which it fills and
 * returns, so the shared field is only ever set to a reference nobody reads.
 */
@Mixin(value = BiomeGenerator.class, remap = false)
public abstract class MixinBiomeGenerator {

    @WrapMethod(method = "initializeGenerators")
    private void chunksteward$initOnce(long seed, Operation<Void> original) {
        ThreadNoise.Slot slot = ThreadNoise.of(this);
        if (slot.initialized) {
            return;
        }
        synchronized (this) {
            original.call(seed);
        }
        slot.initialized = true;
    }

    @Redirect(method = {"generateNoise", "replaceBiomeBlocks"}, at = @At(value = "FIELD",
            target = "Lthebetweenlands/common/world/gen/biome/generator/BiomeGenerator;baseBlockLayerVariationNoise:[D",
            opcode = Opcodes.GETFIELD))
    private double[] chunksteward$getNoise(BiomeGenerator self) {
        return ThreadNoise.of(self).first;
    }

    @Redirect(method = "generateNoise", at = @At(value = "FIELD",
            target = "Lthebetweenlands/common/world/gen/biome/generator/BiomeGenerator;noiseGenerated:Z",
            opcode = Opcodes.GETFIELD))
    private boolean chunksteward$getGenerated(BiomeGenerator self) {
        return ThreadNoise.of(self).generated;
    }

    @Redirect(method = {"generateNoise", "resetNoise"}, at = @At(value = "FIELD",
            target = "Lthebetweenlands/common/world/gen/biome/generator/BiomeGenerator;noiseGenerated:Z",
            opcode = Opcodes.PUTFIELD))
    private void chunksteward$putGenerated(BiomeGenerator self, boolean value) {
        ThreadNoise.of(self).generated = value;
    }
}
