package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FeatureStatsTest {

    @Test
    void reasonsMapToCounters() {
        assertEquals(FeatureStats.INLINE_TILE, FeatureStats.categoryOf("tile entity block minecraft:chest at 1,2,3"));
        assertEquals(FeatureStats.INLINE_WINDOW, FeatureStats.categoryOf("read outside the window at chunk 1,2"));
        assertEquals(FeatureStats.INLINE_WINDOW, FeatureStats.categoryOf("write outside the window at 1,2,3"));
        assertEquals(FeatureStats.INLINE_UNSUPPORTED, FeatureStats.categoryOf("unsupported call: spawnEntity"));
        assertEquals(FeatureStats.INLINE_UNSUPPORTED, FeatureStats.categoryOf("direct chunk write"));
        assertEquals(FeatureStats.INLINE_ERROR, FeatureStats.categoryOf("error: java.lang.NullPointerException"));
        assertEquals(FeatureStats.INLINE_ERROR, FeatureStats.categoryOf(null));
    }

    @Test
    void culpritIsTheFirstModFrame() {
        StackTraceElement[] stack = {
                new StackTraceElement("net.minecraft.world.World", "setBlockState", "World.java", 1),
                new StackTraceElement("com.apocollis.chunksteward.server.FeatureHooks", "x", "F.java", 1),
                new StackTraceElement("java.util.ArrayList", "forEach", "A.java", 1),
                new StackTraceElement("soot.handler.GenerationHandler$Inner", "onGenerateOres", "G.java", 1),
                new StackTraceElement("net.minecraftforge.fml.common.eventhandler.EventBus", "post", "E.java", 1),
        };
        assertEquals("soot.handler.GenerationHandler", FeatureStats.culpritOf(stack));
        assertNull(FeatureStats.culpritOf(new StackTraceElement[] {stack[0], stack[1]}));
    }
}
