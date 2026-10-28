package com.apocollis.chunksteward.server;

/** Comparison of two biome sample arrays; no Minecraft types, so it is unit tested. */
final class BiomeSamples {
    private BiomeSamples() {
    }

    /** Index of the first sample point where the two answers differ, or -1 when they agree (or one is missing). */
    static int firstDifference(short[] worker, short[] server) {
        if (worker == null || server == null || worker.length != server.length) {
            return -1;
        }
        for (int i = 0; i < worker.length; i++) {
            if (worker[i] != server[i]) {
                return i;
            }
        }
        return -1;
    }

    static int differing(short[] worker, short[] server) {
        int count = 0;
        for (int i = 0; i < worker.length; i++) {
            if (worker[i] != server[i]) {
                count++;
            }
        }
        return count;
    }
}
