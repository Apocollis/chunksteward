package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureHooksTest {

    private static void noAbort(String key) {
        throw new AssertionError("must not abort: " + key);
    }

    @Test
    void featureWorkerAbortsAndDoesNotSchedule() {
        AtomicInteger scheduled = new AtomicInteger();
        List<String> aborted = new ArrayList<String>();
        boolean cancel = FeatureHooks.guardServerThread(true, false, "test.scope", scheduled::incrementAndGet,
                aborted::add, s -> { });
        assertTrue(cancel);
        assertEquals(0, scheduled.get());
        assertEquals(1, aborted.size());
        assertEquals("test.scope", aborted.get(0));
    }

    @Test
    void serverThreadProceedsWithoutSchedulingOrWarning() {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger warned = new AtomicInteger();
        boolean cancel = FeatureHooks.guardServerThread(false, true, "test.server",
                scheduled::incrementAndGet, FeatureHooksTest::noAbort, s -> warned.incrementAndGet());
        assertFalse(cancel);
        assertEquals(0, scheduled.get());
        assertEquals(0, warned.get());
    }

    @Test
    void offThreadReschedulesEveryCallButWarnsOncePerKey() {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger warned = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertTrue(FeatureHooks.guardServerThread(false, false, "test.off", scheduled::incrementAndGet,
                    FeatureHooksTest::noAbort, s -> warned.incrementAndGet()));
        }
        assertEquals(3, scheduled.get());
        assertEquals(1, warned.get());
    }
}
