package com.apocollis.chunksteward.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MapGenStructure.class)
public interface AccessorMapGenStructure {

    @Accessor("structureMap")
    Long2ObjectMap<StructureStart> chunksteward$structureMap();
}
