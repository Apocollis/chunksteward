package com.apocollis.chunksteward.mixin.rtg;

import com.apocollis.chunksteward.server.PipelineMarker;
import com.apocollis.chunksteward.server.ThreadRtg;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rtg.api.util.noise.SpacedCellularNoise;

/**
 * The five cellular noises share one unsynchronized cache. A worker or verify thread reads and fills its own map.
 * The server thread keeps the map on the noise instance.
 */
@Mixin(value = SpacedCellularNoise.class, remap = false)
public abstract class MixinSpacedCellularNoise {

    @Shadow
    private Map<Point, Point2D.Double[]> cache;

    @Redirect(method = {"areaPoints", "generatedAreaPoints"}, at = @At(value = "FIELD",
            target = "Lrtg/api/util/noise/SpacedCellularNoise;cache:Ljava/util/Map;",
            opcode = Opcodes.GETFIELD), remap = false)
    private Map<Point, Point2D.Double[]> chunksteward$cache(SpacedCellularNoise self) {
        if (!PipelineMarker.worker() && !PipelineMarker.verify()) {
            return cache;
        }
        @SuppressWarnings("unchecked")
        Map<Point, Point2D.Double[]> threadCache = (Map<Point, Point2D.Double[]>) ThreadRtg.cellular(self);
        return threadCache;
    }
}
