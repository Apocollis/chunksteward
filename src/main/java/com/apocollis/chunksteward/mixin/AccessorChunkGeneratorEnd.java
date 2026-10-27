package com.apocollis.chunksteward.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkGeneratorEnd;
import net.minecraft.world.gen.structure.MapGenEndCity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkGeneratorEnd.class)
public interface AccessorChunkGeneratorEnd {

    @Accessor("world")
    World chunksteward$world();

    @Accessor("mapFeaturesEnabled")
    boolean chunksteward$mapFeatures();

    @Accessor("spawnPoint")
    BlockPos chunksteward$spawn();

    @Accessor("endCityGen")
    MapGenEndCity chunksteward$cities();
}
