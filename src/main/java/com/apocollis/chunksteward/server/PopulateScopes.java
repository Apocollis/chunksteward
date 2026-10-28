package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.api.PopulateScope;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.CopyOnWriteArrayList;

/** The scopes mods registered through {@code ChunkStewardAPI.registerPopulateScope}. */
final class PopulateScopes {
    private static final CopyOnWriteArrayList<PopulateScope> LIST = new CopyOnWriteArrayList<PopulateScope>();

    private PopulateScopes() {
    }

    static void register(PopulateScope scope) {
        if (scope != null && !LIST.contains(scope)) {
            LIST.add(scope);
        }
    }

    static void begin(World world, Chunk chunk) {
        for (PopulateScope scope : LIST) {
            try {
                scope.begin(world, chunk);
            } catch (Throwable failed) {
                ChunkSteward.LOGGER.warn("populate scope begin failed", failed);
            }
        }
    }

    /** Ends in reverse order of {@link #begin}. */
    static void end(World world, Chunk chunk) {
        for (int i = LIST.size() - 1; i >= 0; i--) {
            try {
                LIST.get(i).end(world, chunk);
            } catch (Throwable failed) {
                ChunkSteward.LOGGER.warn("populate scope end failed", failed);
            }
        }
    }
}
