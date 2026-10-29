package com.apocollis.chunksteward.server;

/**
 * Light a worker computed for the 2x2 write window of a feature job, ready to install: per chunk and 16-block section,
 * the sky and block light as vanilla nibble arrays (2048 bytes, index {@code y << 8 | z << 4 | x}), the final height
 * of every column the job wrote to, and the cells just outside the window whose neighbour's light changed (the server
 * checks them the normal way, so light that spills past the window still settles).
 */
final class LightResult {
    final int chunkX;
    final int chunkZ;
    /** Block Y of the first section, a multiple of 16 (negative in a world extended below zero). */
    final int yLow;
    final int sections;
    /** {@code [chunk][section]}, chunk index {@code dz * 2 + dx}; null where the section was not computed. */
    final byte[][][] sky;
    final byte[][][] block;
    int[] heightX = new int[0];
    int[] heightZ = new int[0];
    int[] heightY = new int[0];
    long[] boundary = new long[0];
    /** {@code [chunk][z << 4 | x]}: vanilla's precipitation height of every column of the write window, or null. */
    int[][] rain;

    LightResult(int chunkX, int chunkZ, int yLow, int sections) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.yLow = yLow;
        this.sections = sections;
        this.sky = new byte[4][sections][];
        this.block = new byte[4][sections][];
    }
}
