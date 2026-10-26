package com.apocollis.chunksteward.server;

/**
 * The admission factor and the widened headroom allowance. No world or config access, so the arithmetic can be tested
 * on its own.
 */
final class AdmissionFactor {
    private static final double FALL = 0.15;
    private static final double RISE = 0.02;

    private AdmissionFactor() {
    }

    /** 1 at or above {@code tpsFull}, 0 at or below the effective clamp, linear between. */
    static double raw(double tps, double tpsFull, double tpsClamp) {
        double clamp = tpsClamp <= tpsFull - 0.1 ? tpsClamp : tpsFull - 0.1;
        if (tps >= tpsFull) {
            return 1.0;
        }
        if (tps <= clamp) {
            return 0.0;
        }
        return (tps - clamp) / (tpsFull - clamp);
    }

    /** Falls at 15% of the gap per tick, rises at 2%. Equal inputs stay put. */
    static double smooth(double factor, double raw) {
        double rate = raw < factor ? FALL : RISE;
        return factor + rate * (raw - factor);
    }

    /** {@code targetTickMs - other} plus the boost scaled by the admission factor. */
    static double allowance(double targetTickMs, double otherEmaMs, double factor, double boostMs) {
        return (targetTickMs - otherEmaMs) + factor * boostMs;
    }
}
