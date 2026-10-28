package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiomeCheckTest {

    @Test
    void agreeingSamplesHaveNoDifference() {
        short[] a = new short[64];
        short[] b = new short[64];
        assertEquals(-1, BiomeCheck.firstDifference(a, b));
        assertEquals(0, BiomeCheck.differing(a, b));
    }

    @Test
    void firstDifferenceAndCountAreReported() {
        short[] a = new short[64];
        short[] b = new short[64];
        b[9] = 5;
        b[40] = 7;
        assertEquals(9, BiomeCheck.firstDifference(a, b));
        assertEquals(2, BiomeCheck.differing(a, b));
    }

    @Test
    void missingOrMismatchedSamplesAreNotADifference() {
        assertEquals(-1, BiomeCheck.firstDifference(null, new short[64]));
        assertEquals(-1, BiomeCheck.firstDifference(new short[64], null));
        assertEquals(-1, BiomeCheck.firstDifference(new short[64], new short[32]));
    }
}
