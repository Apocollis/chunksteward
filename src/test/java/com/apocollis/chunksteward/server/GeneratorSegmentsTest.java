package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorSegmentsTest {

    private static List<Boolean> flags(boolean... values) {
        Boolean[] boxed = new Boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return Arrays.asList(boxed);
    }

    @Test
    void runsAreMaximalGroupsOfEqualEligibility() {
        assertEquals(flags(true, false, true), GeneratorSegments.runs(flags(true, true, false, false, false, true)));
        assertEquals(flags(false), GeneratorSegments.runs(flags(false, false)));
        assertEquals(Collections.<Boolean>emptyList(), GeneratorSegments.runs(Collections.<Boolean>emptyList()));
    }

    @Test
    void runOfFollowsTheBoundaries() {
        List<Boolean> order = flags(true, true, false, true, true, false);
        int[] expected = {0, 0, 1, 2, 2, 3};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], GeneratorSegments.runOf(order, i), "index " + i);
        }
        assertEquals(GeneratorSegments.runs(order).size() - 1, GeneratorSegments.runOf(order, order.size() - 1));
    }

    @Test
    void entriesMatchByNameOrPackagePrefix() {
        String[] entries = {"thaumcraft.", "a.b.Exact", " ", null};
        assertTrue(GeneratorSegments.matches(entries, "thaumcraft.common.world.ThaumcraftWorldGenerator"));
        assertTrue(GeneratorSegments.matches(entries, "a.b.Exact"));
        assertFalse(GeneratorSegments.matches(entries, "a.b.ExactNot"));
        assertFalse(GeneratorSegments.matches(entries, "thaumcraftx.Other"));
        assertFalse(GeneratorSegments.matches(null, "a.b.Exact"));
    }
}
