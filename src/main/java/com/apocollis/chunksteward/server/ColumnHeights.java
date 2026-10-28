package com.apocollis.chunksteward.server;

/**
 * The height-map rule of {@code Chunk.setBlockState} and {@code Chunk.relightBlock}, on its own so a feature job can
 * keep the heights it would have seen without running the lighting code. The job never calls {@code relightBlock}
 * (it updates the shared light queue); it asks this class what the column height is after each write, and the real
 * {@code relightBlock} runs later on the server thread from the replayed writes.
 *
 * <p>A height is the y just above the highest block with light opacity above 0, as in the vanilla height map.
 */
final class ColumnHeights {

    /** The light opacity of the block at a position, with the job's own writes applied. */
    interface Opacity {
        int at(int x, int y, int z);
    }

    /**
     * Vanilla stops the scan at y 0. Depths Update lowers the world, but a column with no opaque block between the
     * surface and y 0 does not occur in generated terrain, so the scan never gets here.
     */
    static final int FLOOR = 0;

    private ColumnHeights() {
    }

    /**
     * The column height after a block with light opacity {@code newOpacity} was written at {@code y}, given the height
     * {@code heightNow} before the write and a view that already includes the write.
     */
    static int afterWrite(int heightNow, int x, int y, int z, int newOpacity, Opacity view) {
        if (newOpacity > 0) {
            // Chunk.setBlockState: relightBlock(x, y + 1, z) when y >= height.
            return y >= heightNow ? relight(heightNow, x, y + 1, z, view) : heightNow;
        }
        // relightBlock(x, y, z) when the block at height - 1 became transparent.
        return y == heightNow - 1 ? relight(heightNow, x, y, z, view) : heightNow;
    }

    /** {@code Chunk.relightBlock}'s height part: start at the larger of {@code y} and the old height, scan down. */
    static int relight(int heightNow, int x, int y, int z, Opacity view) {
        int j = y > heightNow ? y : heightNow;
        while (j > FLOOR && view.at(x, j - 1, z) == 0) {
            --j;
        }
        return j;
    }
}
