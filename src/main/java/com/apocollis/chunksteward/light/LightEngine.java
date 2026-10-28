package com.apocollis.chunksteward.light;

import java.util.Arrays;

/**
 * Sky and block light for a box of cells, computed in one pass from the cells' light opacity and emission, with no
 * Minecraft types so a terrain worker can run it on data it copied and a unit test can check it against a brute-force
 * reference.
 *
 * <p>It computes the light vanilla 1.12.2's {@code World.checkLightFor} settles on. Vanilla gives a cell the largest of
 * its own source and the brightest neighbour minus the cell's opacity ({@code computeLightValue}):
 * <pre>
 *   opacity' = opacity;  if (opacity' &gt;= 15 &amp;&amp; emission &gt; 0) opacity' = 1;  if (opacity' &lt; 1) opacity' = 1;
 *   value    = opacity' &gt;= 15 ? 0 : max(source, max over the 6 neighbours of (light(n) - opacity'))
 *   source   = sky light: 15 when the cell is at or above its column's height, else 0; block light: the emission
 * </pre>
 * That has one largest fixed point, which a flood from the brightest cells down finds ({@code compute}); vanilla's
 * incremental updates converge to it. A column's height is one above its highest cell with opacity above zero, the
 * vanilla height map.
 *
 * <p>Cells may be {@link #fixed}: their light is an input (the ring of already-lit neighbour cells around the region) and
 * never changes, but it spreads into the cells around it. Layout: {@code index(x, y, z) = (y * sz + z) * sx + x}, with
 * {@code y} counted from the bottom of the box. Not thread safe: one engine, one thread.
 */
public final class LightEngine {
    public final int sx;
    public final int sy;
    public final int sz;
    /** Light opacity per cell, 0 to 15 (what {@code IBlockState.getLightOpacity} answers). Input. */
    public final byte[] opacity;
    /** Block light emitted per cell, 0 to 15. Input. */
    public final byte[] emission;
    /** True for cells whose {@link #sky} and {@link #block} values are given and kept. Input. */
    public final boolean[] fixed;
    /** Sky light per cell: input for fixed cells, output for the others. */
    public final byte[] sky;
    /** Block light per cell: input for fixed cells, output for the others. */
    public final byte[] block;
    /** True when the cells above the top of the box are open sky (the box reaches the top of the world). */
    public boolean skyAbove = true;

    private int[][] bucket = new int[16][];
    private final int[] bucketSize = new int[16];

    public LightEngine(int sx, int sy, int sz) {
        if (sx < 1 || sy < 1 || sz < 1) {
            throw new IllegalArgumentException("empty box");
        }
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        int cells = sx * sy * sz;
        this.opacity = new byte[cells];
        this.emission = new byte[cells];
        this.fixed = new boolean[cells];
        this.sky = new byte[cells];
        this.block = new byte[cells];
        for (int level = 0; level < 16; level++) {
            bucket[level] = new int[256];
        }
    }

    /**
     * Sets a cell's opacity from what {@code IBlockState.getLightOpacity} answers. Minecraft answers 255 for an opaque
     * block, not 15, so the value is clamped to 0..15; a byte cast would wrap 255 to -1 and read the cell as open air.
     */
    public void setOpacity(int cell, int raw) {
        opacity[cell] = (byte) Math.max(0, Math.min(15, raw));
    }

    /** Sets a cell's emitted block light, clamped to 0..15. */
    public void setEmission(int cell, int raw) {
        emission[cell] = (byte) Math.max(0, Math.min(15, raw));
    }

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    /** One above the highest cell of the column with opacity above zero, or 0 for an empty column. */
    public int height(int x, int z) {
        for (int y = sy - 1; y >= 0; y--) {
            if (opacity[index(x, y, z)] > 0) {
                return y + 1;
            }
        }
        return 0;
    }

    /** Fills {@link #sky} and {@link #block} for every cell that is not fixed. */
    public void compute() {
        computeSky();
        computeBlock();
    }

    /** Sky light for every cell that is not fixed. */
    public void computeSky() {
        int[] heights = new int[sx * sz];
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                heights[z * sx + x] = height(x, z);
            }
        }
        clear();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = index(x, y, z);
                    if (fixed[i]) {
                        push(sky[i], i);
                        continue;
                    }
                    int value = 0;
                    if (y >= heights[z * sx + x]) {
                        value = 15;
                    } else if (skyAbove && y == sy - 1 && opacity[i] < 15) {
                        // Open sky above the box shines into the top cell.
                        value = 15 - Math.max(1, opacity[i]);
                    }
                    sky[i] = (byte) value;
                    push(value, i);
                }
            }
        }
        flood(sky, false);
    }

    /** Block light for every cell that is not fixed. */
    public void computeBlock() {
        clear();
        for (int i = 0; i < block.length; i++) {
            if (fixed[i]) {
                push(block[i], i);
                continue;
            }
            int value = emission[i];
            block[i] = (byte) value;
            push(value, i);
        }
        flood(block, true);
    }

    private void clear() {
        Arrays.fill(bucketSize, 0);
    }

    private void push(int level, int cell) {
        if (level < 2) {
            return;
        }
        int size = bucketSize[level];
        if (size == bucket[level].length) {
            bucket[level] = Arrays.copyOf(bucket[level], size * 2);
        }
        bucket[level][size] = cell;
        bucketSize[level] = size + 1;
    }

    /** Spreads light from the brightest cells down; a cell is re-queued each time it is raised. */
    private void flood(byte[] light, boolean emitters) {
        int plane = sx * sz;
        for (int level = 15; level >= 2; level--) {
            while (bucketSize[level] > 0) {
                int cell = bucket[level][--bucketSize[level]];
                if (light[cell] != level) {
                    continue;
                }
                int y = cell / plane;
                int rest = cell - y * plane;
                int z = rest / sx;
                int x = rest - z * sx;
                if (x > 0) {
                    reach(light, cell - 1, level, emitters);
                }
                if (x < sx - 1) {
                    reach(light, cell + 1, level, emitters);
                }
                if (z > 0) {
                    reach(light, cell - sx, level, emitters);
                }
                if (z < sz - 1) {
                    reach(light, cell + sx, level, emitters);
                }
                if (y > 0) {
                    reach(light, cell - plane, level, emitters);
                }
                if (y < sy - 1) {
                    reach(light, cell + plane, level, emitters);
                }
            }
        }
    }

    private void reach(byte[] light, int cell, int level, boolean emitters) {
        if (fixed[cell]) {
            return;
        }
        int op = opacity[cell];
        if (op >= 15 && emitters && emission[cell] > 0) {
            op = 1;
        }
        if (op < 1) {
            op = 1;
        }
        if (op >= 15) {
            return;
        }
        int next = level - op;
        if (next > light[cell]) {
            light[cell] = (byte) next;
            push(next, cell);
        }
    }
}
