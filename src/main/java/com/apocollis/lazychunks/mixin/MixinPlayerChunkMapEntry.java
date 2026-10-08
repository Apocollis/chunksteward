package com.apocollis.lazychunks.mixin;

import com.apocollis.lazychunks.server.GenMetrics;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes when an entry is created, for the wait-before-load metric (only while status logging is on). */
@Mixin(PlayerChunkMapEntry.class)
public abstract class MixinPlayerChunkMapEntry {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lazychunks$created(PlayerChunkMap map, int chunkX, int chunkZ, CallbackInfo ci) {
        GenMetrics.onEntryCreated((PlayerChunkMapEntry) (Object) this);
    }
}
