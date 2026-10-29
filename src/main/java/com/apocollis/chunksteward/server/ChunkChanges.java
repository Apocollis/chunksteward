package com.apocollis.chunksteward.server;

/** Mixed into {@code Chunk}: a counter of block changes, so a worker's work on a loaded chunk can be checked. */
public interface ChunkChanges {
    int chunksteward$changes();
}
