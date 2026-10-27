package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.ThreadSurfaceBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.biome.Biome;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reads the top and filler block for {@code genTwilightBiomeTerrain} from {@link ThreadSurfaceBlocks} when this thread
 * is inside a highlands column ({@link MixinTFBiomeHighlands}), and from the biome's own fields otherwise.
 */
@Mixin(targets = "twilightforest.biomes.TFBiomeBase", remap = false)
public abstract class MixinTFBiomeBase {
    private static final String TOP =
            "Ltwilightforest/biomes/TFBiomeBase;field_76752_A:Lnet/minecraft/block/state/IBlockState;";
    private static final String FILLER =
            "Ltwilightforest/biomes/TFBiomeBase;field_76753_B:Lnet/minecraft/block/state/IBlockState;";
    private static final String METHOD =
            "genTwilightBiomeTerrain(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/world/chunk/ChunkPrimer;IID)V";

    @Redirect(method = METHOD, at = @At(value = "FIELD", target = TOP, opcode = Opcodes.GETFIELD))
    private IBlockState chunksteward$getTop(Biome self) {
        return ThreadSurfaceBlocks.top(self.topBlock);
    }

    @Redirect(method = METHOD, at = @At(value = "FIELD", target = FILLER, opcode = Opcodes.GETFIELD))
    private IBlockState chunksteward$getFiller(Biome self) {
        return ThreadSurfaceBlocks.filler(self.fillerBlock);
    }
}
