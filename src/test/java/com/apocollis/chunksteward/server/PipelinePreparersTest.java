package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelinePreparersTest {

    /** Returns {@code context} from prepare and {@code valid} from valid; either can throw. */
    static class Fake implements PreparerCore.Slot {
        Object context = "snapshot";
        boolean valid = true;
        boolean throwPrepare;
        boolean throwValid;

        @Override
        public String name() {
            return "test." + getClass().getSimpleName();
        }

        @Override
        public Object prepare() {
            if (throwPrepare) {
                throw new IllegalStateException("prepare");
            }
            return context;
        }

        @Override
        public boolean valid(Object context) {
            if (throwValid) {
                throw new IllegalStateException("valid");
            }
            return valid;
        }
    }

    static class Other extends Fake {
    }

    private final List<String> reports = new ArrayList<String>();

    private final PreparerCore.Reporter reporter = new PreparerCore.Reporter() {
        @Override
        public void prepareThrew(String name, Throwable error) {
            reports.add("prepare " + name);
        }

        @Override
        public void validThrew(String name, Throwable error) {
            reports.add("valid " + name);
        }

        @Override
        public void firstDiscard(String name) {
            reports.add("first " + name);
        }
    };

    @BeforeEach
    void reset() {
        PreparerCore.resetForTest();
        reports.clear();
    }

    private boolean valid(List<PreparerCore.Slot> list, PreparerCore.Prepared prepared) {
        return PreparerCore.valid(list, prepared.contexts, prepared.failed, reporter);
    }

    @Test
    void nullSlotSitsOutAndTheChunkInstalls() {
        Fake preparer = new Fake();
        preparer.context = null;
        preparer.valid = false;
        List<PreparerCore.Slot> list = Arrays.<PreparerCore.Slot>asList(preparer);
        PreparerCore.Prepared prepared = PreparerCore.prepare(list, reporter);
        assertNull(prepared.contexts[0]);
        assertEquals(0L, prepared.failed);
        assertTrue(valid(list, prepared));
        assertEquals("", PreparerCore.discardSummary(null));
        assertTrue(reports.isEmpty());
    }

    @Test
    void falseFromIsStillValidDiscardsCountsTheClassAndWarnsOnce() {
        Fake preparer = new Fake();
        preparer.valid = false;
        List<PreparerCore.Slot> list = Arrays.<PreparerCore.Slot>asList(preparer);
        PreparerCore.Prepared prepared = PreparerCore.prepare(list, reporter);
        assertFalse(valid(list, prepared));
        assertFalse(valid(list, prepared));
        assertEquals("preparer Fake 2", PreparerCore.discardSummary(null));
        assertEquals(Arrays.asList("first test.Fake"), reports);
    }

    @Test
    void throwingPrepareLeavesAGapAndDiscardsInsteadOfInstallingUnprepared() {
        Fake bad = new Fake();
        bad.throwPrepare = true;
        Fake good = new Fake();
        List<PreparerCore.Slot> list = Arrays.<PreparerCore.Slot>asList(bad, good);
        PreparerCore.Prepared prepared = PreparerCore.prepare(list, reporter);
        PreparerCore.prepare(list, reporter);
        assertNull(prepared.contexts[0]);
        assertEquals("snapshot", prepared.contexts[1]);
        assertEquals(1L, prepared.failed);
        assertFalse(valid(list, prepared));
        assertEquals("preparer Fake 1", PreparerCore.discardSummary(null));
        assertEquals(Arrays.asList("prepare test.Fake"), reports);
    }

    @Test
    void throwingIsStillValidDiscards() {
        Fake preparer = new Fake();
        preparer.throwValid = true;
        List<PreparerCore.Slot> list = Arrays.<PreparerCore.Slot>asList(preparer);
        PreparerCore.Prepared prepared = PreparerCore.prepare(list, reporter);
        assertFalse(valid(list, prepared));
        assertFalse(valid(list, prepared));
        assertEquals("preparer Fake 2", PreparerCore.discardSummary(null));
        assertEquals(Arrays.asList("valid test.Fake", "first test.Fake"), reports);
    }

    @Test
    void allValidInstalls() {
        List<PreparerCore.Slot> list = Arrays.<PreparerCore.Slot>asList(new Fake(), new Other());
        assertTrue(valid(list, PreparerCore.prepare(list, reporter)));
    }

    @Test
    void summaryAdvancesTheSnapshot() {
        Fake preparer = new Fake();
        preparer.valid = false;
        List<PreparerCore.Slot> list = Arrays.<PreparerCore.Slot>asList(preparer);
        PreparerCore.Prepared prepared = PreparerCore.prepare(list, reporter);
        Map<String, Long> since = new HashMap<String, Long>();
        valid(list, prepared);
        assertEquals("; preparer Fake 1", PreparerCore.statusSuffix(since));
        assertEquals("", PreparerCore.statusSuffix(since));
        valid(list, prepared);
        valid(list, prepared);
        assertEquals("; preparer Fake 2", PreparerCore.statusSuffix(since));
    }

    @Test
    void sharedSimpleNamesPrintInFull() {
        Map<String, Long> counts = new TreeMap<String, Long>();
        counts.put("a.mod.Preparer", 3L);
        counts.put("b.mod.Preparer", 4L);
        counts.put("c.mod.Unique", 5L);
        String text = PreparerCore.format(counts);
        assertTrue(text.startsWith("preparer "));
        assertTrue(text.contains("a.mod.Preparer 3"));
        assertTrue(text.contains("b.mod.Preparer 4"));
        assertTrue(text.contains("Unique 5"));
        assertFalse(text.contains("c.mod.Unique"));
    }

    @Test
    void emptyListInstallsWithNothingToCheck() {
        assertTrue(PreparerCore.valid(new ArrayList<PreparerCore.Slot>(), new Object[0], 0L, reporter));
    }
}
