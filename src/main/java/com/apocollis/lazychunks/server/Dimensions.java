package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;

final class Dimensions {
    private Dimensions() {
    }

    static boolean applies(int dimension) {
        int[] list = LazyChunksConfig.dimensions.list;
        boolean listed = false;
        for (int id : list) {
            if (id == dimension) {
                listed = true;
                break;
            }
        }
        return LazyChunksConfig.dimensions.whitelist ? listed : !listed;
    }
}
