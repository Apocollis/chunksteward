package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureWindowTest {

    @Test
    void readWindowIsTheFourByFourRtgRequires() {
        assertEquals(16, FeatureWindow.readCount());
        int count = 0;
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                if (FeatureWindow.inRead(5, -3, x, z)) {
                    count++;
                    assertTrue(x >= 4 && x <= 7 && z >= -4 && z <= -1, x + "," + z);
                }
            }
        }
        assertEquals(16, count);
    }

    @Test
    void writeSetIsTheTwoByTwoFromTheCentreChunk() {
        assertTrue(FeatureWindow.inWrite(5, -3, 5, -3));
        assertTrue(FeatureWindow.inWrite(5, -3, 6, -2));
        assertFalse(FeatureWindow.inWrite(5, -3, 4, -3));
        assertFalse(FeatureWindow.inWrite(5, -3, 7, -3));
        assertFalse(FeatureWindow.inWrite(5, -3, 5, -1));
    }

    @Test
    void writeSetIsInsideTheReadWindow() {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (FeatureWindow.inWrite(0, 0, x, z)) {
                    assertTrue(FeatureWindow.inRead(0, 0, x, z));
                }
            }
        }
    }

    @Test
    void windowsConflictExactlyWhenTheyOverlap() {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                boolean overlap = false;
                for (int x = -10; x <= 10 && !overlap; x++) {
                    for (int z = -10; z <= 10 && !overlap; z++) {
                        overlap = FeatureWindow.inRead(0, 0, x, z) && FeatureWindow.inRead(dx, dz, x, z);
                    }
                }
                assertEquals(overlap, FeatureWindow.conflict(0, 0, dx, dz), dx + "," + dz);
            }
        }
    }

    @Test
    void conflictIsSymmetricAndIncludesTheSameChunk() {
        assertTrue(FeatureWindow.conflict(2, 2, 2, 2));
        assertEquals(FeatureWindow.conflict(0, 0, 3, -3), FeatureWindow.conflict(3, -3, 0, 0));
        assertTrue(FeatureWindow.conflict(0, 0, 3, 3));
        assertFalse(FeatureWindow.conflict(0, 0, 4, 0));
    }

    @Test
    void indexCoversTheWindowRowByRow() {
        boolean[] seen = new boolean[16];
        for (int x = 4; x <= 7; x++) {
            for (int z = -4; z <= -1; z++) {
                int i = FeatureWindow.index(5, -3, x, z);
                assertTrue(i >= 0 && i < 16 && !seen[i]);
                seen[i] = true;
            }
        }
        assertEquals(0, FeatureWindow.index(5, -3, 4, -4));
        assertEquals(5, FeatureWindow.index(5, -3, 5, -3));
        assertEquals(15, FeatureWindow.index(5, -3, 7, -1));
    }
}
