package com.apocollis.chunksteward.api;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Brackets populate work that Chunk Steward finishes later than the populate call itself. With staged populate, the
 * server thread applies a chunk's decorations and runs the rest of its populate after the call returned; a mod that
 * recorded or deferred work inside the populate call (light, for one) opens its scope again for that part.
 * Both methods run on the server thread, {@code end} after {@code begin} for the same chunk, never nested for one chunk.
 *
 * <p>Register with {@link ChunkStewardAPI#registerPopulateScope}. (Version 4.)
 */
public interface PopulateScope {
    /** Staged work for {@code chunk} starts: the chunk is already marked terrain populated. */
    void begin(World world, Chunk chunk);

    /** That work is done. */
    void end(World world, Chunk chunk);
}
