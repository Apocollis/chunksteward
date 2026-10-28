package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureRandomTest {

    private static void assertSameSequence(Random a, Random b) {
        for (int i = 0; i < 64; i++) {
            assertEquals(a.nextInt(), b.nextInt());
            assertEquals(a.nextLong(), b.nextLong());
            assertEquals(a.nextDouble(), b.nextDouble());
            assertEquals(a.nextInt(100), b.nextInt(100));
        }
    }

    @Test
    void copyContinuesTheSameSequenceAndLeavesTheSourceAlone() {
        Random source = new Random(12345L);
        for (int i = 0; i < 17; i++) source.nextInt();
        Random copy = FeatureRandom.copy(source);
        assertNotSame(source, copy);
        assertSameSequence(source, copy);
    }

    @Test
    void stateRoundTripsThroughSetSeed() {
        Random source = new Random(987654321L);
        for (int i = 0; i < 1000; i++) source.nextLong();
        Random other = new Random(1L);
        FeatureRandom.setState(other, FeatureRandom.state(source));
        assertSameSequence(source, other);
    }

    @Test
    void transferMovesAJobsFinalStateBackToTheGeneratorRandom() {
        Random generator = new Random(42L);
        Random job = FeatureRandom.copy(generator);
        for (int i = 0; i < 333; i++) job.nextInt(1000); // the decoration draws
        FeatureRandom.transfer(job, generator);
        assertSameSequence(job, generator);
    }

    @Test
    void stateMatchesTheScrambledSeed() {
        long seed = 0xCAFEBABEL;
        Random random = new Random(seed);
        assertEquals((seed ^ 0x5DEECE66DL) & ((1L << 48) - 1L), FeatureRandom.state(random));
    }

    @Test
    void reportsAPendingGaussian() {
        Random random = new Random(7L);
        assertFalse(FeatureRandom.hasGaussian(random));
        random.nextGaussian(); // generates a pair and caches one
        assertTrue(FeatureRandom.hasGaussian(random));
    }
}
