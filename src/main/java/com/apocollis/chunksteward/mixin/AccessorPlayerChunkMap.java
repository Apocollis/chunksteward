package com.apocollis.chunksteward.mixin;

import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * {@code sortMissingChunks} ({@code field_187312_l}) is vanilla's flag to re-sort the entries without a chunk on the
 * next tick divisible by 4; {@code entriesWithoutChunks} ({@code field_187311_h}) is that list.
 */
@Mixin(PlayerChunkMap.class)
public interface AccessorPlayerChunkMap {

    @Accessor("sortMissingChunks")
    void chunksteward$setSortMissingChunks(boolean sort);

    @Accessor("entriesWithoutChunks")
    List<PlayerChunkMapEntry> chunksteward$getEntriesWithoutChunks();

    /** The view distance in chunks ({@code field_72698_e}). */
    @Accessor("playerViewRadius")
    int chunksteward$getViewRadius();
}
