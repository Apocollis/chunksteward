package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTest {
    private static final long STEP = 10_000L;
    private static final long SUSTAIN = 3_000L;

    @Test
    void workerCountReservesTwoProcessors() {
        assertEquals(1, PipelineGate.workerCount(1));
        assertEquals(1, PipelineGate.workerCount(2));
        assertEquals(1, PipelineGate.workerCount(3));
        assertEquals(2, PipelineGate.workerCount(4));
        assertEquals(2, PipelineGate.workerCount(16));
        assertEquals(2, PipelineGate.resolveWorkers(0, 8));
        assertEquals(1, PipelineGate.resolveWorkers(1, 8));
        assertEquals(2, PipelineGate.resolveWorkers(9, 8));
    }

    @Test
    void emptyWhitelistDoesNotEngage() {
        assertFalse(PipelineGate.hasGenerator(null));
        assertFalse(PipelineGate.hasGenerator(new String[0]));
        assertFalse(PipelineGate.hasGenerator(new String[]{"", "  "}));
        assertTrue(PipelineGate.hasGenerator(new String[]{"rtg.world.ChunkGeneratorRTG"}));
        assertFalse(PipelineGate.engages(true, false, false));
        assertFalse(PipelineGate.engages(false, false, true));
        assertFalse(PipelineGate.engages(true, true, true));
        assertTrue(PipelineGate.engages(true, false, true));
    }

    @Test
    void rankKeepsTheHighestScores() {
        assertArrayEquals(new int[]{2, 0}, PipelineRank.top(new int[]{5, 1, 9}, 2));
        assertArrayEquals(new int[]{0, 1}, PipelineRank.top(new int[]{4, 4, 1}, 2));
        assertEquals(0, PipelineRank.top(new int[]{1}, 0).length);
    }

    @Test
    void claimPublishAndSteal() {
        PipelineTable table = new PipelineTable();
        PipelineJob job = table.submit(0, 3, 4);
        assertTrue(table.claimWorker(job));
        assertFalse(table.claimMain(job));
        assertTrue(table.publish(job));
        assertSame(job, table.readyAt(0, 3, 4));

        PipelineJob stolen = table.submit(0, 1, 1);
        assertTrue(table.claimWorker(stolen));
        assertTrue(table.steal(stolen));
        assertFalse(table.publish(stolen));
        assertEquals(1L, table.stolen.get());
        assertEquals(null, table.readyAt(0, 1, 1));
    }

    @Test
    void mainClaimBlocksTheWorker() {
        PipelineTable table = new PipelineTable();
        PipelineJob job = table.submit(0, 8, 8);
        assertTrue(table.claimMain(job));
        assertFalse(table.claimWorker(job));
    }

    @Test
    void epochDropsAQueuedJob() {
        PipelineTable table = new PipelineTable();
        PipelineJob job = table.submit(2, 5, 6);
        table.bumpEpoch(2);
        assertFalse(table.claimWorker(job));
        assertEquals(1L, table.discarded.get());
        assertEquals(PipelineJob.QUEUED, table.submit(2, 5, 6).state.get());
        assertEquals(1, table.epoch(2));
    }

    @Test
    void latePublishAfterEpochIsDroppedOnce() {
        PipelineTable table = new PipelineTable();
        PipelineJob job = table.submit(1, 2, 2);
        assertTrue(table.claimWorker(job));
        table.bumpEpoch(1);
        assertFalse(table.publish(job));
        assertEquals(1L, table.discarded.get());
        assertEquals(null, table.readyAt(1, 2, 2));
    }

    @Test
    void guardStepsDownAndDoesNotRestoreOnTheFirstCut() {
        PipelineCpuGuard guard = new PipelineCpuGuard();
        guard.reset(2);
        guard.tick(0L, true, false, STEP, SUSTAIN);
        assertEquals(2, guard.cap());
        guard.tick(SUSTAIN, true, false, STEP, SUSTAIN);
        assertEquals(1, guard.cap());
        guard.tick(SUSTAIN + STEP, true, false, STEP, SUSTAIN);
        assertEquals(0, guard.cap());
    }

    @Test
    void guardRestoresOnlyAfterCapStaysZero() {
        PipelineCpuGuard guard = new PipelineCpuGuard();
        guard.reset(2);
        guard.tick(0L, true, false, STEP, SUSTAIN);
        guard.tick(SUSTAIN, true, false, STEP, SUSTAIN);
        guard.tick(SUSTAIN + STEP, true, false, STEP, SUSTAIN);
        long atZero = SUSTAIN + STEP;
        guard.tick(atZero + PipelineCpuGuard.ZERO_HOLD_MS - 1, true, false, STEP, SUSTAIN);
        assertEquals(0, guard.cap());
        guard.tick(atZero + PipelineCpuGuard.ZERO_HOLD_MS, true, false, STEP, SUSTAIN);
        assertEquals(2, guard.cap());
        guard.tick(atZero + PipelineCpuGuard.ZERO_HOLD_MS + 1_000L, true, false, STEP, SUSTAIN);
        assertEquals(2, guard.cap());
    }

    @Test
    void guardRaisesTheCapWhenTheSignalClears() {
        PipelineCpuGuard guard = new PipelineCpuGuard();
        guard.reset(2);
        guard.tick(0L, true, false, STEP, SUSTAIN);
        guard.tick(SUSTAIN, true, false, STEP, SUSTAIN);
        assertEquals(1, guard.cap());
        guard.tick(SUSTAIN + STEP, false, true, STEP, SUSTAIN);
        assertEquals(2, guard.cap());
    }
}
