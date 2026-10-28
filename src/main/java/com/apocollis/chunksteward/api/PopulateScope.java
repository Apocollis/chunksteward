package com.apocollis.chunksteward.api;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Brackets populate work that Chunk Wizard finishes later than the populate call itself. With staged populate, the
 * server thread applies a chunk's decorations and runs the rest of its populate after the call returned; a mod that
 * recorded or deferred work inside the populate call (light, for one) opens its scope again for that part.
 * Both methods run on the server thread, {@code end} after {@code begin} for the same chunk, never nested for one chunk.
 *
 * <p>Register with {@link ChunkStewardAPI#registerPopulateScope}. (Version 4; {@link #lightInstalled} is version 5.)
 */
public interface PopulateScope {
    /** Staged work for {@code chunk} starts: the chunk is already marked terrain populated. */
    void begin(World world, Chunk chunk);

    /** That work is done. */
    void end(World world, Chunk chunk);

    /**
     * Chunk Wizard installed this chunk's light and column heights, computed on a terrain worker, after the chunk's
     * staged block writes were applied. Light work a mod recorded or deferred for the chunk (relight columns, light checks,
     * the opening full check) is obsolete and should be dropped. Called between {@link #begin} and {@link #end}. Optional:
     * the default does nothing. (Version 5.)
     */
    default void lightInstalled(World world, Chunk chunk) {
    }
}
