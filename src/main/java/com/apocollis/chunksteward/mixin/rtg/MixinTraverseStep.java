package com.apocollis.chunksteward.mixin.rtg;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rtg.api.world.RTGWorld;
import rtg.api.world.terrain.TerrainBase;

/**
 * Traverse canyon and crag cliffs call {@code stepSize.generateNoise} from their own noise. That instance is shared,
 * so the call is locked the same way as {@link MixinRealisticBiomeBase}.
 */
@Mixin(targets = {
        "rtg.world.biome.realistic.traverse.RealisticBiomeTRAVCanyon$TerrainRTGCanyon",
        "rtg.world.biome.realistic.traverse.RealisticBiomeTRAVCragCliffs$TerrainRTGCanyon"
}, remap = false)
public abstract class MixinTraverseStep {
    private static final String NOISE = "Lrtg/api/world/terrain/TerrainBase;generateNoise(Lrtg/api/world/RTGWorld;IIFF)F";

    @Redirect(method = "generateNoise(Lrtg/api/world/RTGWorld;IIFF)F", at = @At(value = "INVOKE", target = NOISE), remap = false)
    private float chunksteward$step(TerrainBase terrain, RTGWorld world, int x, int z, float border, float river) {
        synchronized (terrain) {
            return terrain.generateNoise(world, x, z, border, river);
        }
    }
}
