package com.apocollis.chunksteward.server;

/**
 * The chunks a feature job for chunk (cx, cz) may touch, taken from how RTG-Plus populates a chunk.
 *
 * <p>RTG populates (cx, cz) only when the 4x4 chunks (cx-1..cx+2, cz-1..cz+2) are generated, and decorates around
 * the chunk centre offset by 8 blocks, so trees land in the 2x2 chunks (cx..cx+1, cz..cz+1). The job may read the whole
 * 4x4 window and write only the 2x2. Both windows exist when the job is accepted; a read outside the window aborts the
 * job.
 *
 * <p>Two jobs, or a job and an inline populate, conflict when their windows overlap: one would read or write what the
 * other changes. They run one after the other in the order they started, which is the order an inline populate would
 * have given the world.
 */
final class FeatureWindow {
    /** Chunks either side of a chunk centre that a window covers: -1 on the low side, +2 on the high side. */
    static final int READ_LOW = -1;
    static final int READ_HIGH = 2;
    static final int WRITE_HIGH = 1;
    /** Two windows 4 wide overlap when their centres are at most 3 apart on both axes. */
    static final int CONFLICT_DISTANCE = READ_HIGH - READ_LOW;

    private FeatureWindow() {
    }

    static boolean inRead(int cx, int cz, int x, int z) {
        return x >= cx + READ_LOW && x <= cx + READ_HIGH && z >= cz + READ_LOW && z <= cz + READ_HIGH;
    }

    static boolean inWrite(int cx, int cz, int x, int z) {
        return x >= cx && x <= cx + WRITE_HIGH && z >= cz && z <= cz + WRITE_HIGH;
    }

    /** True when the read windows of the two chunks overlap, so their populates cannot run at the same time. */
    static boolean conflict(int ax, int az, int bx, int bz) {
        return Math.abs(ax - bx) <= CONFLICT_DISTANCE && Math.abs(az - bz) <= CONFLICT_DISTANCE;
    }

    /** Index of chunk (x, z) in a window row-major from (cx-1, cz-1); the caller checked {@link #inRead}. */
    static int index(int cx, int cz, int x, int z) {
        return (x - (cx + READ_LOW)) + (z - (cz + READ_LOW)) * (READ_HIGH - READ_LOW + 1);
    }

    /** Number of chunks in a read window. */
    static int readCount() {
        int width = READ_HIGH - READ_LOW + 1;
        return width * width;
    }
}
