package com.apocollis.lazychunks.mixin;

import com.apocollis.lazychunks.server.TickMonitor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets one server tick (work time only, not the sleep between ticks) and notes every world save.
 * {@code tick()} is {@code func_71217_p}; {@code saveAllWorlds} is {@code func_71267_a}.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void lazychunks$tickStart(CallbackInfo ci) {
        TickMonitor.onTickStart(((MinecraftServer) (Object) this).getTickCounter());
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void lazychunks$tickEnd(CallbackInfo ci) {
        TickMonitor.onTickEnd();
    }

    @Inject(method = "saveAllWorlds(Z)V", at = @At("HEAD"))
    private void lazychunks$saveStart(boolean isSilent, CallbackInfo ci) {
        TickMonitor.onSave();
    }
}
