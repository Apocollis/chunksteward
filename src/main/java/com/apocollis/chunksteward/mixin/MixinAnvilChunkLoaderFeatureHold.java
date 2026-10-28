package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureLane;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A chunk saved while its staged populate work is not applied (autosave, a crash after) is written as not populated,
 * so loading it later populates it again instead of keeping a chunk without its decorations. A clean stop applies the
 * work first ({@code FeatureLane.drainAll}).
 */
@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilChunkLoaderFeatureHold {

    @Inject(method = "writeChunkToNBT(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;)V",
            at = @At("RETURN"))
    private void chunksteward$saveHeldAsUnpopulated(Chunk chunk, World world, NBTTagCompound level, CallbackInfo ci) {
        if (FeatureLane.queued() != 0 && FeatureLane.holds(world.provider.getDimension(), chunk.x, chunk.z)) {
            level.setBoolean("TerrainPopulated", false);
            level.setBoolean("LightPopulated", false);
        }
    }
}
