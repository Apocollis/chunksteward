package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.ChunkThrottle;
import com.apocollis.chunksteward.server.DirectionalOrder;
import com.apocollis.chunksteward.server.FeatureHooks;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.List;

/**
 * Hooks {@code PlayerChunkMap.tick} ({@code func_72693_b}).
 *
 * <p>Per-tick chunk provision: only the loop over entries without a chunk calls
 * {@code PlayerChunkMapEntry.providePlayerChunk} ({@code func_187268_a}); a {@code false} return leaves the entry
 * queued for a later tick. Vanilla's own 50 ms and 49 chunk limits stay.
 *
 * <p>The first {@code Collections.sort} in the method sorts {@code entriesWithoutChunks} with {@code PlayerChunkMap$4}
 * (closest player distance); {@link DirectionalOrder} replaces that order. The second sort ({@code $5}) is untouched.
 */
@Mixin(PlayerChunkMap.class)
public abstract class MixinPlayerChunkMap {

    @Redirect(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/PlayerChunkMapEntry;providePlayerChunk(Z)Z"))
    private boolean chunksteward$provide(PlayerChunkMapEntry entry, boolean canGenerate) {
        return ChunkThrottle.provide((PlayerChunkMap) (Object) this, entry, canGenerate);
    }

    @Redirect(method = "tick()V", at = @At(value = "INVOKE",
            target = "Ljava/util/Collections;sort(Ljava/util/List;Ljava/util/Comparator;)V", ordinal = 0,
            remap = false))
    private void chunksteward$sortMissing(List<PlayerChunkMapEntry> list,
                                        Comparator<? super PlayerChunkMapEntry> vanilla) {
        DirectionalOrder.sort(list, vanilla);
    }

    /** A worker or other thread here would race the server thread's iteration of {@code dirtyEntries}. */
    @Inject(method = "markBlockForUpdate(Lnet/minecraft/util/math/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void chunksteward$markGuard(BlockPos pos, CallbackInfo ci) {
        if (FeatureHooks.guardMarkBlock((PlayerChunkMap) (Object) this, pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "entryChanged(Lnet/minecraft/server/management/PlayerChunkMapEntry;)V", at = @At("HEAD"),
            cancellable = true)
    private void chunksteward$entryChangedGuard(PlayerChunkMapEntry entry, CallbackInfo ci) {
        if (FeatureHooks.guardEntryChanged((PlayerChunkMap) (Object) this, entry)) {
            ci.cancel();
        }
    }
}
