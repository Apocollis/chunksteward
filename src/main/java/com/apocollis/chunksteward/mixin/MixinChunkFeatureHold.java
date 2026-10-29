package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureHooks;
import com.apocollis.chunksteward.server.FeatureLane;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A chunk whose staged populate work has not been applied is not populated as far as clients are concerned:
 * {@code PlayerChunkMapEntry.sendToPlayers} waits for {@code isPopulated}, so the chunk is sent once, finished.
 * One int read while no staged work is queued.
 */
@Mixin(value = Chunk.class, priority = 500)
public abstract class MixinChunkFeatureHold implements com.apocollis.chunksteward.server.ChunkChanges {

    /** Block changes to this chunk since it loaded; the light audit compares it before and after a worker's pass. */
    @org.spongepowered.asm.mixin.Unique
    private volatile int chunksteward$changes;

    @Override
    public int chunksteward$changes() {
        return chunksteward$changes;
    }

    /** Priority 500 so this runs before Depths Update's replacement of the method. */
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)"
            + "Lnet/minecraft/block/state/IBlockState;", at = @At("HEAD"))
    private void chunksteward$featureDirectWrite(BlockPos pos, IBlockState state,
                                                 CallbackInfoReturnable<IBlockState> cir) {
        FeatureHooks.chunkWrite();
        chunksteward$changes++;
    }

    @Inject(method = "isPopulated()Z", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureHold(CallbackInfoReturnable<Boolean> cir) {
        if (FeatureLane.queued() == 0) {
            return;
        }
        Chunk self = (Chunk) (Object) this;
        if (FeatureLane.holds(self.getWorld().provider.getDimension(), self.x, self.z)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
