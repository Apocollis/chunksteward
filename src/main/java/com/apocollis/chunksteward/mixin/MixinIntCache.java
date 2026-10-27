package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.ThreadIntCache;
import net.minecraft.world.gen.layer.IntCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * One {@code IntCache} pool per thread, so pipeline workers, verify legs and the server thread do not recycle each
 * other's arrays. The replacements are not {@code synchronized}: a class lock would run biome sampling one thread at a
 * time.
 */
@Mixin(IntCache.class)
public abstract class MixinIntCache {

    /**
     * @author chunksteward
     * @reason per-thread pool
     */
    @Overwrite
    public static int[] getIntCache(int size) {
        return ThreadIntCache.get(size);
    }

    /**
     * @author chunksteward
     * @reason per-thread pool
     */
    @Overwrite
    public static void resetIntCache() {
        ThreadIntCache.reset();
    }

    /**
     * @author chunksteward
     * @reason per-thread pool; reports the calling thread's lists
     */
    @Overwrite
    public static String getCacheSizes() {
        return ThreadIntCache.sizes();
    }
}
