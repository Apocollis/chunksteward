package com.apocollis.chunksteward.server;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * A chunk saved while its staged populate work is not applied (autosave, a crash after) is written as not populated,
 * so loading it later populates it again instead of keeping a chunk without its decorations. A clean stop applies the
 * work first ({@link FeatureLane#drainAll()}). Done from Forge's save event so no vanilla class needs a mixin.
 */
@Mod.EventBusSubscriber
public final class FeatureSaveHold {
    private FeatureSaveHold() {
    }

    @SubscribeEvent
    public static void onSave(ChunkDataEvent.Save event) {
        if (FeatureLane.queued() == 0 || !(event.getWorld() instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (!FeatureLane.holds(event.getWorld().provider.getDimension(), chunk.x, chunk.z)) {
            return;
        }
        NBTTagCompound level = event.getData().getCompoundTag("Level");
        level.setBoolean("TerrainPopulated", false);
        level.setBoolean("LightPopulated", false);
    }
}
