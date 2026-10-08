package com.apocollis.lazychunks.mixin;

import com.apocollis.lazychunks.server.ChunkThrottle;
import com.apocollis.lazychunks.server.DirectionalOrder;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
    private boolean lazychunks$provide(PlayerChunkMapEntry entry, boolean canGenerate) {
        return ChunkThrottle.provide((PlayerChunkMap) (Object) this, entry, canGenerate);
    }

    @Redirect(method = "tick()V", at = @At(value = "INVOKE",
            target = "Ljava/util/Collections;sort(Ljava/util/List;Ljava/util/Comparator;)V", ordinal = 0,
            remap = false))
    private void lazychunks$sortMissing(List<PlayerChunkMapEntry> list,
                                        Comparator<? super PlayerChunkMapEntry> vanilla) {
        DirectionalOrder.sort(list, vanilla);
    }
}
