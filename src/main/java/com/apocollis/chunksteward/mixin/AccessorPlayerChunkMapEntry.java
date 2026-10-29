package com.apocollis.chunksteward.mixin;

import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Forge's async-load flag. The name is Forge's, not an MCP field, so it is not remapped. */
@Mixin(PlayerChunkMapEntry.class)
public interface AccessorPlayerChunkMapEntry {

    @Accessor(value = "loading", remap = false)
    boolean chunksteward$loading();

    @Accessor("changeMask")
    int chunksteward$changeMask();

    @Accessor("changeMask")
    void chunksteward$changeMask(int mask);

    @Accessor("changes")
    void chunksteward$changes(int changes);
}
