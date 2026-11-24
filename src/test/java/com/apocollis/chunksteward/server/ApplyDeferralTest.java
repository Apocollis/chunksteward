package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyDeferralTest {
    private static final int CAP = ApplyDeferral.MAX_DEFERRALS;

    @Test
    void aBusyLockDefersUntilTheCap() {
        assertTrue(ApplyDeferral.shouldDefer(0, CAP));
        assertTrue(ApplyDeferral.shouldDefer(CAP - 1, CAP));
        assertFalse(ApplyDeferral.shouldDefer(CAP, CAP));
        assertFalse(ApplyDeferral.shouldDefer(CAP + 5, CAP));
    }

    /** Jobs at chunks (x[i], 0) in dimension 0; far apart means independent windows. */
    private static int pick(int[] x, boolean[] finished, boolean[] needsLock, int[] deferrals, boolean lockFree,
                            boolean[] deferred, boolean[] locked) {
        int n = x.length;
        return ApplyDeferral.pick(n, new int[n], x, new int[n], finished, needsLock, deferrals, CAP,
                () -> lockFree, deferred, locked);
    }

    @Test
    void theHeadGoesFirstWhenItCanRun() {
        boolean[] deferred = new boolean[2];
        boolean[] locked = new boolean[1];
        assertEquals(0, pick(new int[] {0, 10}, new boolean[] {true, true}, new boolean[] {false, false},
                new int[2], false, deferred, locked));
        assertFalse(locked[0]);
    }

    @Test
    void anIndependentJobAppliesAheadOfADeferredHead() {
        boolean[] deferred = new boolean[2];
        boolean[] locked = new boolean[1];
        int chosen = pick(new int[] {0, 10}, new boolean[] {true, true}, new boolean[] {true, false},
                new int[2], false, deferred, locked);
        assertEquals(1, chosen);
        assertArrayEquals(new boolean[] {true, false}, deferred);
        assertFalse(locked[0]);
    }

    @Test
    void anIndependentJobAppliesAheadOfAnUnfinishedHead() {
        boolean[] deferred = new boolean[2];
        assertEquals(1, pick(new int[] {0, 10}, new boolean[] {false, true}, new boolean[] {false, false},
                new int[2], false, deferred, new boolean[1]));
        assertArrayEquals(new boolean[] {false, false}, deferred);
    }

    @Test
    void anOverlappingJobKeepsItsPlaceBehindTheHead() {
        boolean[] deferred = new boolean[2];
        // Chunks 0 and 3 apart on x: 4-wide read windows overlap (distance 3).
        assertEquals(-1, pick(new int[] {0, 3}, new boolean[] {true, true}, new boolean[] {true, false},
                new int[2], false, deferred, new boolean[1]));
        assertTrue(deferred[0]);
        assertFalse(deferred[1]);
        // Four apart: independent.
        assertEquals(1, pick(new int[] {0, 4}, new boolean[] {true, true}, new boolean[] {true, false},
                new int[2], false, new boolean[2], new boolean[1]));
    }

    @Test
    void aFreeLockIsHandedToTheChosenJob() {
        boolean[] locked = new boolean[1];
        assertEquals(0, pick(new int[] {0}, new boolean[] {true}, new boolean[] {true}, new int[1], true,
                new boolean[1], locked));
        assertTrue(locked[0]);
    }

    @Test
    void aJobAtTheCapIsChosenSoTheServerBlocksForTheLock() {
        boolean[] deferred = new boolean[2];
        boolean[] locked = new boolean[1];
        assertEquals(0, pick(new int[] {0, 10}, new boolean[] {true, true}, new boolean[] {true, false},
                new int[] {CAP, 0}, false, deferred, locked));
        assertFalse(deferred[0]);
        assertFalse(locked[0]);
    }

    @Test
    void differentDimensionsNeverConflict() {
        assertEquals(1, ApplyDeferral.pick(2, new int[] {0, -1}, new int[] {0, 0}, new int[] {0, 0},
                new boolean[] {true, true}, new boolean[] {true, false}, new int[2], CAP, () -> false,
                new boolean[2], new boolean[1]));
    }

    @Test
    void nothingToApplyGivesMinusOne() {
        assertEquals(-1, pick(new int[] {0}, new boolean[] {false}, new boolean[] {false}, new int[1], false,
                new boolean[1], new boolean[1]));
        assertEquals(-1, pick(new int[0], new boolean[0], new boolean[0], new int[0], false, new boolean[0],
                new boolean[1]));
    }
}
