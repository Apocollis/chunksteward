package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.RandomTickGuard;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the random block tick pass, {@code WorldServer.updateBlocks} ({@code func_147456_g}), for
 * {@link RandomTickGuard}. Injects only at HEAD and RETURN (not a redirect of the {@code randomTick} call), so it cannot
 * clash with other mods that wrap that call.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Inject(method = "updateBlocks()V", at = @At("HEAD"))
    private void chunksteward$randomTicksBegin(CallbackInfo ci) {
        RandomTickGuard.begin();
    }

    @Inject(method = "updateBlocks()V", at = @At("RETURN"))
    private void chunksteward$randomTicksEnd(CallbackInfo ci) {
        RandomTickGuard.end();
    }
}
