package com.apocollis.chunksteward.mixin;

import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkGeneratorHell;
import net.minecraft.world.gen.structure.MapGenNetherBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkGeneratorHell.class)
public interface AccessorChunkGeneratorHell {

    @Accessor("world")
    World chunksteward$world();

    @Accessor("generateStructures")
    boolean chunksteward$structures();

    @Accessor("genNetherBridge")
    MapGenNetherBridge chunksteward$bridge();
}
