package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureHooks;
import com.apocollis.chunksteward.server.RandomTickGuard;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the random block tick pass, {@code WorldServer.updateBlocks} ({@code func_147456_g}), for
 * {@link RandomTickGuard}. Injects only at HEAD and RETURN (not a redirect of the {@code randomTick} call), so it cannot
 * clash with other mods that wrap that call.
 *
 * <p>The tick and block-event methods below are overridden here and do not call {@code super}, so a hook on
 * {@code World} never sees them. A feature job that enters one is aborted and run inline.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Inject(method = "scheduleUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;I)V",
            at = @At("HEAD"))
    private void chunksteward$featureSchedule(BlockPos pos, Block block, int delay, CallbackInfo ci) {
        FeatureHooks.unsupported("scheduleUpdate");
    }

    @Inject(method = "updateBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"))
    private void chunksteward$featureBlockTick(BlockPos pos, Block block, int delay, int priority, CallbackInfo ci) {
        FeatureHooks.unsupported("updateBlockTick");
    }

    @Inject(method = "scheduleBlockUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"))
    private void chunksteward$featureScheduleBlock(BlockPos pos, Block block, int delay, int priority, CallbackInfo ci) {
        FeatureHooks.unsupported("scheduleBlockUpdate");
    }

    @Inject(method = "addBlockEvent(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"))
    private void chunksteward$featureBlockEvent(BlockPos pos, Block block, int eventId, int param, CallbackInfo ci) {
        FeatureHooks.unsupported("addBlockEvent");
    }

    @Inject(method = "updateBlocks()V", at = @At("HEAD"))
    private void chunksteward$randomTicksBegin(CallbackInfo ci) {
        RandomTickGuard.begin();
    }

    @Inject(method = "updateBlocks()V", at = @At("RETURN"))
    private void chunksteward$randomTicksEnd(CallbackInfo ci) {
        RandomTickGuard.end();
    }
}
