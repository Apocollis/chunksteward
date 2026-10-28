package com.apocollis.chunksteward.api;

import net.minecraft.world.World;

/**
 * A snapshot taken on the server thread when a terrain job is submitted, and read on the worker that generates it.
 * The object returned from {@link #prepare} must be safe to read on another thread: no live world, no shared maps.
 */
public interface JobPreparer {
    /**
     * Server thread, once, when the chunk position is first submitted. Return an immutable snapshot, or null to sit
     * this job out.
     */
    Object prepare(World world, int chunkX, int chunkZ);

    /**
     * Server thread, when a finished worker chunk is about to be installed. False discards that chunk and the server
     * thread generates it. {@code context} is the object {@link #prepare} returned for this position.
     */
    boolean isStillValid(Object context, World world, int chunkX, int chunkZ);
}
