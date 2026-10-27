package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadSurfaceBlocks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Twilight Forest's highlands biome picks a column's top and filler block by writing them into the biome's own
 * {@code topBlock} / {@code fillerBlock}, one biome object shared by every thread, then paints the column from those
 * fields in {@code TFBiomeBase.genTwilightBiomeTerrain}. The writes go to {@link ThreadSurfaceBlocks} for the length of
 * the call instead, and {@link MixinTFBiomeBase} reads them from there. The shared fields stay at their defaults,
 * which is what the cave and ravine carvers read from them.
 */
@Mixin(targets = "twilightforest.biomes.TFBiomeHighlands", remap = false)
public abstract class MixinTFBiomeHighlands {
    private static final String TOP =
            "Ltwilightforest/biomes/TFBiomeHighlands;field_76752_A:Lnet/minecraft/block/state/IBlockState;";
    private static final String FILLER =
            "Ltwilightforest/biomes/TFBiomeHighlands;field_76753_B:Lnet/minecraft/block/state/IBlockState;";

    @WrapMethod(method = "func_180622_a(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/world/chunk/ChunkPrimer;IID)V")
    private void chunksteward$column(World world, Random rand, ChunkPrimer primer, int x, int z, double noiseVal,
                                     Operation<Void> original) {
        ThreadSurfaceBlocks.enter();
        try {
            original.call(world, rand, primer, x, z, noiseVal);
        } finally {
            ThreadSurfaceBlocks.exit();
        }
    }

    @Redirect(method = "func_180622_a(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/world/chunk/ChunkPrimer;IID)V",
            at = @At(value = "FIELD", target = TOP, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putTop(Biome self, IBlockState state) {
        ThreadSurfaceBlocks.setTop(state);
    }

    @Redirect(method = "func_180622_a(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/world/chunk/ChunkPrimer;IID)V",
            at = @At(value = "FIELD", target = FILLER, opcode = Opcodes.PUTFIELD))
    private void chunksteward$putFiller(Biome self, IBlockState state) {
        ThreadSurfaceBlocks.setFiller(state);
    }
}
