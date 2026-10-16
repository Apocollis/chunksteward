package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;

final class Dimensions {
    private Dimensions() {
    }

    static boolean applies(int dimension) {
        int[] list = ChunkStewardConfig.dimensions.list;
        boolean listed = false;
        for (int id : list) {
            if (id == dimension) {
                listed = true;
                break;
            }
        }
        return ChunkStewardConfig.dimensions.whitelist ? listed : !listed;
    }
}
