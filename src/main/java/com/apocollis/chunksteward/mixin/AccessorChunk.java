package com.apocollis.chunksteward.mixin;

import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chunk fields a worker-computed light install has to set: the rain height cache and the lowest column height. */
@Mixin(Chunk.class)
public interface AccessorChunk {
    @Accessor("precipitationHeightMap")
    int[] chunksteward$precipitationHeightMap();

    @Accessor("heightMapMinimum")
    void chunksteward$setHeightMapMinimum(int minimum);
}
