package com.apocollis.chunksteward.light;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LightEngineTest {

    /** The vanilla rule applied to every cell until nothing changes: slow, obviously the fixed point. */
    private static void reference(LightEngine e, byte[] sky, byte[] block) {
        int[] heights = new int[e.sx * e.sz];
        for (int z = 0; z < e.sz; z++) {
            for (int x = 0; x < e.sx; x++) {
                heights[z * e.sx + x] = e.height(x, z);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int y = 0; y < e.sy; y++) {
                for (int z = 0; z < e.sz; z++) {
                    for (int x = 0; x < e.sx; x++) {
                        int i = e.index(x, y, z);
                        if (e.fixed[i]) {
                            continue;
                        }
                        int s = value(e, x, y, z, sky, false, y >= heights[z * e.sx + x] ? 15 : 0);
                        int b = value(e, x, y, z, block, true, e.emission[i]);
                        if (s != sky[i]) {
                            sky[i] = (byte) s;
                            changed = true;
                        }
                        if (b != block[i]) {
                            block[i] = (byte) b;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private static int value(LightEngine e, int x, int y, int z, byte[] light, boolean emitters, int source) {
        int i = e.index(x, y, z);
        int op = e.opacity[i];
        if (op >= 15 && emitters && e.emission[i] > 0) {
            op = 1;
        }
        if (op < 1) {
            op = 1;
        }
        if (op >= 15) {
            return 0;
        }
        int best = source;
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            int nx = x + d[0];
            int ny = y + d[1];
            int nz = z + d[2];
            int neighbour;
            if (nx < 0 || ny < 0 || nz < 0 || nx >= e.sx || ny >= e.sy || nz >= e.sz) {
                neighbour = !emitters && e.skyAbove && ny == e.sy ? 15 : 0;
            } else {
                neighbour = light[e.index(nx, ny, nz)];
            }
            best = Math.max(best, neighbour - op);
        }
        return best;
    }

    private static LightEngine random(long seed, int sx, int sy, int sz, boolean ring) {
        Random r = new Random(seed);
        LightEngine e = new LightEngine(sx, sy, sz);
        int[] opacities = {0, 0, 0, 1, 1, 3, 15, 15};
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = e.index(x, y, z);
                    e.opacity[i] = (byte) opacities[r.nextInt(opacities.length)];
                    if (r.nextInt(40) == 0) {
                        e.emission[i] = (byte) (1 + r.nextInt(15));
                    }
                    if (ring && (x == 0 || z == 0 || x == sx - 1 || z == sz - 1)) {
                        e.fixed[i] = true;
                        e.sky[i] = (byte) r.nextInt(16);
                        e.block[i] = (byte) r.nextInt(16);
                    }
                }
            }
        }
        return e;
    }

    @Test
    void matchesTheBruteForceFixedPoint() {
        for (long seed = 1; seed <= 40; seed++) {
            LightEngine e = random(seed, 7, 12, 6, false);
            byte[] sky = e.sky.clone();
            byte[] block = e.block.clone();
            reference(e, sky, block);
            e.compute();
            assertArrayEquals(sky, e.sky, "sky, seed " + seed);
            assertArrayEquals(block, e.block, "block, seed " + seed);
        }
    }

    @Test
    void fixedCellsKeepTheirLightAndSpreadIt() {
        for (long seed = 100; seed <= 130; seed++) {
            LightEngine e = random(seed, 8, 10, 8, true);
            byte[] sky = e.sky.clone();
            byte[] block = e.block.clone();
            byte[] fixedSky = e.sky.clone();
            reference(e, sky, block);
            e.compute();
            assertArrayEquals(sky, e.sky, "sky, seed " + seed);
            assertArrayEquals(block, e.block, "block, seed " + seed);
            for (int i = 0; i < e.fixed.length; i++) {
                if (e.fixed[i]) {
                    assertEquals(fixedSky[i], e.sky[i], "fixed cell changed");
                }
            }
        }
    }

    @Test
    void openSkyIsFifteenAndShadeFallsOffByOnePerCell() {
        LightEngine e = new LightEngine(5, 8, 5);
        // A solid roof at y = 5 over the left half; open to the sky over the right half.
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 3; x++) {
                e.opacity[e.index(x, 5, z)] = 15;
            }
        }
        e.compute();
        assertEquals(15, e.sky[e.index(4, 0, 2)]);
        assertEquals(15, e.sky[e.index(2, 6, 2)]);
        // Under the roof, at the open edge, light is 15 - 1 per cell sideways.
        assertEquals(14, e.sky[e.index(2, 4, 2)]);
        assertEquals(13, e.sky[e.index(1, 4, 2)]);
        assertEquals(12, e.sky[e.index(0, 4, 2)]);
    }

    @Test
    void torchLightsItsSurroundings() {
        LightEngine e = new LightEngine(9, 9, 9);
        e.skyAbove = false;
        e.emission[e.index(4, 4, 4)] = 14;
        e.compute();
        assertEquals(14, e.block[e.index(4, 4, 4)]);
        assertEquals(13, e.block[e.index(5, 4, 4)]);
        assertEquals(11, e.block[e.index(4, 4, 7)]);
    }

    @Test
    void anOpaqueNonEmittingCellStaysDark() {
        LightEngine e = new LightEngine(3, 3, 3);
        e.skyAbove = false;
        e.emission[e.index(0, 1, 1)] = 15;
        e.opacity[e.index(1, 1, 1)] = 15;
        e.compute();
        assertEquals(0, e.block[e.index(1, 1, 1)]);
        // The wall does not stop light going round it: (0,1,1) -> (0,0,1) -> (1,0,1) -> (2,0,1) -> (2,1,1).
        assertEquals(11, e.block[e.index(2, 1, 1)]);
    }
}
