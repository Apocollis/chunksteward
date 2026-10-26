package com.apocollis.chunksteward.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Factor and allowance, on values that are exact in double so the expected results are not rounded. */
class AdmissionFactorTest {

    @Test
    void rawFactorEndsAndMidpoint() {
        assertEquals(1.0, AdmissionFactor.raw(20.0, 19.0, 10.0));
        assertEquals(1.0, AdmissionFactor.raw(19.0, 19.0, 10.0));
        assertEquals(0.0, AdmissionFactor.raw(10.0, 19.0, 10.0));
        assertEquals(0.0, AdmissionFactor.raw(9.0, 19.0, 10.0));
        // (14.5 - 10) / (19 - 10) = 0.5
        assertEquals(0.5, AdmissionFactor.raw(14.5, 19.0, 10.0));
    }

    @Test
    void clampSitsBelowTpsFull() {
        // 19 is not 0.1 under 19, so the effective clamp is 18.9. Well below that is 0; at tpsFull is 1.
        assertEquals(0.0, AdmissionFactor.raw(10.0, 19.0, 19.0));
        assertEquals(1.0, AdmissionFactor.raw(19.0, 19.0, 19.0));
    }

    @Test
    void smoothFallsFastAndRisesSlowly() {
        assertEquals(0.85, AdmissionFactor.smooth(1.0, 0.0));
        assertEquals(0.02, AdmissionFactor.smooth(0.0, 1.0));
        assertEquals(0.5, AdmissionFactor.smooth(0.5, 0.5));
    }

    @Test
    void allowanceIsTodaysHeadroomPlusTheBoost() {
        // 40 - 20 + 0 * 60
        assertEquals(20.0, AdmissionFactor.allowance(40.0, 20.0, 0.0, 60.0));
        // 40 - 20 + 1 * 60
        assertEquals(80.0, AdmissionFactor.allowance(40.0, 20.0, 1.0, 60.0));
        // 40 - 20 + 0.5 * 60
        assertEquals(50.0, AdmissionFactor.allowance(40.0, 20.0, 0.5, 60.0));
    }
}
