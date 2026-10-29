package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.SpawnerPause;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldEntitySpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code WorldEntitySpawner.findChunksForSpawning} skips spectators when it collects the chunks around each player.
 * A player {@link SpawnerPause} says is moving fast is reported as a spectator there, which is the only place the
 * method asks.
 */
@Mixin(WorldEntitySpawner.class)
public abstract class MixinWorldEntitySpawner {

    @Redirect(method = "findChunksForSpawning", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;isSpectator()Z"))
    private boolean chunksteward$skipFastPlayer(EntityPlayer player) {
        return player.isSpectator() || SpawnerPause.skip(player);
    }
}
