package com.apocollis.chunksteward.mixin.rtg;

import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rtg.api.world.RTGWorld;
import rtg.api.world.surface.SurfaceBase;
import rtg.api.world.terrain.TerrainBase;

/**
 * Terrain and surface singletons write {@code groundNoise} and {@code mixCheck} inside these calls. One thread
 * finishes a call on an instance before another starts it. The lock is the instance, so a single thread is unchanged.
 */
@Mixin(targets = "rtg.api.world.biome.RealisticBiomeBase", remap = false)
public abstract class MixinRealisticBiomeBase {
    private static final String NOISE = "Lrtg/api/world/terrain/TerrainBase;generateNoise(Lrtg/api/world/RTGWorld;IIFF)F";
    private static final String PAINT = "Lrtg/api/world/surface/SurfaceBase;paintTerrain(Lnet/minecraft/world/chunk/ChunkPrimer;IIIIILrtg/api/world/RTGWorld;[FF[Lnet/minecraft/world/biome/Biome;)V";
    private static final String REPLACE = "rReplace(Lnet/minecraft/world/chunk/ChunkPrimer;IIIIILrtg/api/world/RTGWorld;[FF[Lnet/minecraft/world/biome/Biome;)V";
    private static final String RIVER = "rReplaceWithRiver(Lnet/minecraft/world/chunk/ChunkPrimer;IIIIILrtg/api/world/RTGWorld;[FF[Lnet/minecraft/world/biome/Biome;)V";

    @Redirect(method = "newrNoise(Lrtg/api/world/RTGWorld;IIFF)F", at = @At(value = "INVOKE", target = NOISE), remap = false)
    private float chunksteward$noise(TerrainBase terrain, RTGWorld world, int x, int z, float border, float river) {
        synchronized (terrain) {
            return terrain.generateNoise(world, x, z, border, river);
        }
    }

    @Redirect(method = {REPLACE, RIVER}, at = @At(value = "INVOKE", target = PAINT), remap = false)
    private void chunksteward$paint(SurfaceBase surface, ChunkPrimer primer, int i, int j, int x, int y, int depth,
                                    RTGWorld world, float[] noise, float river, Biome[] base) {
        synchronized (surface) {
            surface.paintTerrain(primer, i, j, x, y, depth, world, noise, river, base);
        }
    }
}
