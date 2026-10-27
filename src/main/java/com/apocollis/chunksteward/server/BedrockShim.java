package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * Depths Update suppresses bedrock at Y 0 to 4 with a thread-local window around {@code generateChunk}. A worker calls
 * the generator directly, so it opens the same window when that dimension is extended below Y 0.
 */
final class BedrockShim {
    private static final Method BEGIN;
    private static final Method END;
    private static final Method EXTENDED;
    private static final Method MIN_Y;
    private static boolean logged;

    static {
        Method begin = null;
        Method end = null;
        Method extended = null;
        Method minY = null;
        try {
            Class<?> filter = Class.forName("sayys.depthsupdate.core.BedrockFilter");
            Class<?> heights = Class.forName("sayys.depthsupdate.core.HeightManager");
            begin = filter.getMethod("begin");
            end = filter.getMethod("end");
            extended = heights.getMethod("isExtended", World.class);
            minY = heights.getMethod("getMinY", World.class);
        } catch (ClassNotFoundException absent) {
            // Depths Update is not loaded.
        } catch (ReflectiveOperationException broken) {
            ChunkSteward.LOGGER.warn("pipeline could not bind Depths Update's bedrock filter; workers will not open it");
        }
        BEGIN = begin;
        END = end;
        EXTENDED = extended;
        MIN_Y = minY;
    }

    private BedrockShim() {
    }

    static void run(World world, Runnable work) {
        boolean opened = begin(world);
        try {
            work.run();
        } finally {
            if (opened) {
                end();
            }
        }
    }

    private static boolean begin(World world) {
        if (BEGIN == null || EXTENDED == null || MIN_Y == null) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(EXTENDED.invoke(null, world))) {
                return false;
            }
            Object min = MIN_Y.invoke(null, world);
            if (!(min instanceof Integer) || (Integer) min >= 0) {
                return false;
            }
            BEGIN.invoke(null);
            return true;
        } catch (ReflectiveOperationException failed) {
            if (!logged) {
                logged = true;
                ChunkSteward.LOGGER.warn("pipeline bedrock filter failed to open", failed);
            }
            return false;
        }
    }

    private static void end() {
        try {
            END.invoke(null);
        } catch (ReflectiveOperationException failed) {
            if (!logged) {
                logged = true;
                ChunkSteward.LOGGER.warn("pipeline bedrock filter failed to close", failed);
            }
        }
    }
}
