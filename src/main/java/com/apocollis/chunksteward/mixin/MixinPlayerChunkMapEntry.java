package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureHooks;
import com.apocollis.chunksteward.server.GenMetrics;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes when an entry is created, for the wait-before-load metric (only while status logging is on), and keeps
 * {@code blockChanged} on the server thread. Priority 500 so the guard runs before Depths Update's inject.
 */
@Mixin(value = PlayerChunkMapEntry.class, priority = 500)
public abstract class MixinPlayerChunkMapEntry {

    /** Off the server thread this would mutate the entry and {@code dirtyEntries} while the tick reads them. */
    @Inject(method = "blockChanged(III)V", at = @At("HEAD"), cancellable = true)
    private void chunksteward$blockChangedGuard(int x, int y, int z, CallbackInfo ci) {
        PlayerChunkMapEntry self = (PlayerChunkMapEntry) (Object) this;
        PlayerChunkMap map = ((AccessorPlayerChunkMapEntry) self).chunksteward$map();
        if (FeatureHooks.guardBlockChanged(map, self, x, y, z)) {
            ci.cancel();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunksteward$created(PlayerChunkMap map, int chunkX, int chunkZ, CallbackInfo ci) {
        GenMetrics.onEntryCreated((PlayerChunkMapEntry) (Object) this);
    }
}
