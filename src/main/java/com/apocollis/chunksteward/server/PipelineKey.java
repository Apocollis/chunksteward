package com.apocollis.chunksteward.server;

final class PipelineKey {
    final int dimension;
    final int x;
    final int z;

    PipelineKey(int dimension, int x, int z) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PipelineKey)) {
            return false;
        }
        PipelineKey key = (PipelineKey) other;
        return dimension == key.dimension && x == key.x && z == key.z;
    }

    @Override
    public int hashCode() {
        int hash = dimension;
        hash = hash * 31 + x;
        hash = hash * 31 + z;
        return hash;
    }
}
