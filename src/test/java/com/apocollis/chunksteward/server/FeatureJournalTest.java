package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureJournalTest {

    @Test
    void packRoundTripsLikeBlockPos() {
        int[][] samples = {{0, 0, 0}, {-1, 5, -1}, {15, 255, 15}, {-30000000, 0, 29999999}, {123456, 64, -654321}};
        for (int[] s : samples) {
            long pos = FeatureJournal.pack(s[0], s[1], s[2]);
            assertEquals(s[0], FeatureJournal.unpackX(pos));
            assertEquals(s[1], FeatureJournal.unpackY(pos));
            assertEquals(s[2], FeatureJournal.unpackZ(pos));
        }
    }

    /** Values taken from {@code new BlockPos(x, y, z).toLong()} in 1.12.2: a journal replays through {@code fromLong}. */
    @Test
    void packMatchesBlockPosToLong() {
        long[][] real = {
                {0, 0, 0, 0L},
                {1, 0, 0, 274877906944L},
                {0, 1, 0, 67108864L},
                {0, 0, 1, 1L},
                {-1, 5, -1, -274475253761L},
                {15, 255, 15, 4140281364495L},
                {-30000000, 0, 29999999, -8246337208290000001L},
                {123456, 64, -654321, 33935331241100303L},
                {29999999, 255, -30000000, 8246336950591962240L},
        };
        for (long[] r : real) {
            assertEquals(r[3], FeatureJournal.pack((int) r[0], (int) r[1], (int) r[2]), r[0] + "," + r[1] + "," + r[2]);
            assertEquals((int) r[0], FeatureJournal.unpackX(r[3]));
            assertEquals((int) r[1], FeatureJournal.unpackY(r[3]));
            assertEquals((int) r[2], FeatureJournal.unpackZ(r[3]));
        }
    }

    @Test
    void readsSeeEarlierWritesAndLastWriteWins() {
        FeatureJournal<String> journal = new FeatureJournal<>();
        long pos = FeatureJournal.pack(3, 70, -4);
        assertNull(journal.overlay(pos));
        journal.write(pos, "log", 2);
        journal.write(pos, "leaves", 2);
        assertEquals("leaves", journal.overlay(pos));
        assertEquals(2, journal.size());
        assertEquals("log", journal.writes().get(0).state);
        assertEquals("leaves", journal.writes().get(1).state);
    }

    @Test
    void writesKeepOrderAndFlags() {
        FeatureJournal<String> journal = new FeatureJournal<>();
        journal.write(FeatureJournal.pack(1, 1, 1), "a", 3);
        journal.write(FeatureJournal.pack(2, 2, 2), "b", 2);
        journal.write(FeatureJournal.pack(1, 1, 1), "c", 18);
        assertEquals(3, journal.writes().get(0).flags);
        assertEquals(2, journal.writes().get(1).flags);
        assertEquals(18, journal.writes().get(2).flags);
    }

    @Test
    void lightChecksAreKeptOnceInFirstAskedOrder() {
        FeatureJournal<String> journal = new FeatureJournal<>();
        long a = FeatureJournal.pack(0, 80, 0);
        long b = FeatureJournal.pack(1, 80, 0);
        journal.check(a);
        journal.check(b);
        journal.check(a);
        assertArrayEquals(new long[] {a, b}, journal.checks());
        assertTrue(!journal.isEmpty());
    }

    /** A column as a map of y to light opacity; everything not listed is 0. */
    private static ColumnHeights.Opacity column(Map<Integer, Integer> blocks) {
        return (x, y, z) -> blocks.getOrDefault(y, 0);
    }

    @Test
    void opaqueBlockAboveTheHeightRaisesIt() {
        Map<Integer, Integer> blocks = new HashMap<>();
        for (int y = 0; y < 64; y++) blocks.put(y, 15); // ground up to y 63, height 64
        blocks.put(64, 1);                              // a log on top
        assertEquals(65, ColumnHeights.afterWrite(64, 0, 64, 0, 1, column(blocks)));
    }

    @Test
    void opaqueBlockBelowTheHeightLeavesItAlone() {
        Map<Integer, Integer> blocks = new HashMap<>();
        for (int y = 0; y < 64; y++) blocks.put(y, 15);
        blocks.put(70, 1);
        blocks.put(80, 1); // canopy already at height 81
        assertEquals(81, ColumnHeights.afterWrite(81, 0, 70, 0, 1, column(blocks)));
    }

    @Test
    void transparentWriteAtTheTopLowersTheHeightToTheNextOpaqueBlock() {
        Map<Integer, Integer> blocks = new HashMap<>();
        for (int y = 0; y < 64; y++) blocks.put(y, 15);
        blocks.put(70, 1); // leaf at y 70 (height 71); the block at 70 is removed below
        blocks.remove(70);
        assertEquals(64, ColumnHeights.afterWrite(71, 0, 70, 0, 0, column(blocks)));
    }

    @Test
    void transparentWriteBelowTheTopKeepsTheHeight() {
        Map<Integer, Integer> blocks = new HashMap<>();
        for (int y = 0; y < 64; y++) blocks.put(y, 15);
        blocks.put(80, 1);
        assertEquals(81, ColumnHeights.afterWrite(81, 0, 70, 0, 0, column(blocks)));
    }

    @Test
    void emptyColumnStopsAtTheFloor() {
        assertEquals(ColumnHeights.FLOOR, ColumnHeights.afterWrite(5, 0, 4, 0, 0, column(new HashMap<>())));
    }

    @Test
    void longMapSurvivesGrowthAndCollisions() {
        FeatureJournal.LongMap<Integer> map = new FeatureJournal.LongMap<>();
        for (int i = 0; i < 5000; i++) {
            assertNull(map.put(FeatureJournal.pack(i, i % 256, -i), i));
        }
        assertEquals(5000, map.size());
        for (int i = 0; i < 5000; i++) {
            assertEquals(i, map.get(FeatureJournal.pack(i, i % 256, -i)));
        }
        assertEquals(7, map.put(FeatureJournal.pack(7, 7, -7), 99));
        assertEquals(99, map.get(FeatureJournal.pack(7, 7, -7)));
        assertEquals(5000, map.size());
        assertNull(map.get(FeatureJournal.pack(1, 2, 3)));
    }
}
