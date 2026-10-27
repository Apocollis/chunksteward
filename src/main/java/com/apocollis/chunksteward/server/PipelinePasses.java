package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Dimensions where a generator class has passed a 10,000-chunk verify. Stored in the overworld save. */
final class PipelinePasses {
    private static final Set<String> PASSES = Collections.synchronizedSet(new HashSet<String>());
    private static boolean loaded;

    private PipelinePasses() {
    }

    static void reset() {
        PASSES.clear();
        loaded = false;
    }

    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    PASSES.add(trimmed);
                }
            }
        } catch (IOException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not read verify passes", failed);
        }
    }

    static boolean passed(int dimension, String className) {
        ensureLoaded();
        return PASSES.contains(key(dimension, className));
    }

    static boolean any() {
        ensureLoaded();
        return !PASSES.isEmpty();
    }

    static void grant(int dimension, String className) {
        ensureLoaded();
        PASSES.add(key(dimension, className));
        write();
    }

    static void revoke(int dimension, String className) {
        ensureLoaded();
        PASSES.remove(key(dimension, className));
        write();
    }

    static String key(int dimension, String className) {
        return dimension + "\t" + className;
    }

    private static void write() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, PASSES, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not write verify passes", failed);
        }
    }

    private static Path file() {
        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            return null;
        }
        return overworld.getSaveHandler().getWorldDirectory().toPath().resolve("chunksteward-pipeline-passes.txt");
    }
}
