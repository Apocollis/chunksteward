package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureHooks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A feature job that writes a section directly skips {@code Chunk.setBlockState} and would change a live chunk in the
 * job's window. Abort; the job runs inline, where no feature scope is set and the same write is allowed. Terrain
 * workers have no feature scope, so their own chunk still builds.
 */
@Mixin(ExtendedBlockStorage.class)
public abstract class MixinExtendedBlockStorage {

    @Inject(method = "set(IIILnet/minecraft/block/state/IBlockState;)V", at = @At("HEAD"))
    private void chunksteward$featureSectionWrite(int x, int y, int z, IBlockState state, CallbackInfo ci) {
        FeatureHooks.chunkWrite();
    }
}
