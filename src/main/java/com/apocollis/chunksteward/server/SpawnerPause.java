package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.entity.player.EntityPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether the mob spawner treats a player as absent this tick. {@code WorldEntitySpawner.findChunksForSpawning}
 * skips spectators when it collects the chunks within 8 of each player; the mixin answers "spectator" for a player this
 * class says is moving fast, so chunks that only that player has in range are not visited (and the mob cap, which is
 * counted from the same chunks, shrinks with them). A slower player's chunks keep spawning.
 */
public final class SpawnerPause {
    private static final Map<UUID, Integer> LAST_FAST = new HashMap<UUID, Integer>();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static int skippedInterval;

    private SpawnerPause() {
    }

    /** True while this player should not be counted by the spawner. Server thread. */
    public static boolean skip(EntityPlayer player) {
        ChunkStewardConfig.Spawner cfg = ChunkStewardConfig.spawner;
        if (!cfg.pauseWhileFast || !applies(player.dimension)) {
            return false;
        }
        int now = TickMonitor.tick;
        UUID id = player.getUniqueID();
        if (PlayerMotion.speed(player) >= cfg.pauseSpeed) {
            LAST_FAST.put(id, now);
            skippedInterval++;
            return true;
        }
        Integer last = LAST_FAST.get(id);
        if (last == null) {
            return false;
        }
        if (now - last < cfg.resumeDelayTicks) {
            skippedInterval++;
            return true;
        }
        LAST_FAST.remove(id);
        return false;
    }

    private static boolean applies(int dimension) {
        int[] only = ChunkStewardConfig.spawner.dimensions;
        if (only == null || only.length == 0) {
            return true;
        }
        for (int id : only) {
            if (id == dimension) {
                return true;
            }
        }
        return false;
    }

    /** A status-log fragment, or empty when the pause is off. Resets the interval count. */
    static String reportAndReset() {
        if (!ChunkStewardConfig.spawner.pauseWhileFast) {
            return "";
        }
        int count = skippedInterval;
        skippedInterval = 0;
        SKIPPED.addAndGet(count);
        return "; spawner pause skipped " + count + " player-ticks (" + SKIPPED.get() + " in session)";
    }
}
