package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorSegmentsTest {

    @Test
    void entriesMatchByNameOrPackagePrefix() {
        String[] entries = {"thaumcraft.", "a.b.Exact", " ", null};
        assertTrue(GeneratorSegments.matches(entries, "thaumcraft.common.world.ThaumcraftWorldGenerator"));
        assertTrue(GeneratorSegments.matches(entries, "a.b.Exact"));
        assertFalse(GeneratorSegments.matches(entries, "a.b.ExactNot"));
        assertFalse(GeneratorSegments.matches(entries, "thaumcraftx.Other"));
        assertFalse(GeneratorSegments.matches(null, "a.b.Exact"));
        assertFalse(GeneratorSegments.matches(new String[0], "a.b.Exact"));
    }
}
