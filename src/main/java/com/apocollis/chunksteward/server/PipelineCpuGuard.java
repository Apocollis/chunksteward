package com.apocollis.chunksteward.server;

/**
 * Steps the active worker cap down while the host looks saturated, and back up when it looks clear. One step per
 * resume interval. At cap 0 a signal that is still hot 10 s later restores the cap and mutes the guard for 5 minutes,
 * so a false signal does not leave workers off. A cut that leaves the host hot does not restore.
 */
final class PipelineCpuGuard {
    static final long ZERO_HOLD_MS = 10_000L;
    static final long MUTE_MS = 5L * 60L * 1000L;

    private int ceiling = 1;
    private int cap = 1;
    private long lastStepMs = Long.MIN_VALUE;
    private long zeroSinceMs = -1L;
    private long hotSinceMs = -1L;
    private long muteUntilMs = Long.MIN_VALUE;

    void reset(int workers) {
        ceiling = Math.max(1, workers);
        cap = ceiling;
        lastStepMs = Long.MIN_VALUE;
        zeroSinceMs = -1L;
        hotSinceMs = -1L;
        muteUntilMs = Long.MIN_VALUE;
    }

    int cap() {
        return cap;
    }

    /**
     * Once a second. {@code hot} is past the line; {@code clear} is comfortably under it. A sample in between does
     * nothing. The signal must stay hot for {@code sustainMs} before the first cut. {@code stepMs} is the minimum
     * time between steps.
     */
    void tick(long nowMs, boolean hot, boolean clear, long stepMs, long sustainMs) {
        long step = Math.max(1L, stepMs);
        long sustain = Math.max(1L, sustainMs);
        if (nowMs < muteUntilMs) {
            return;
        }
        if (hot) {
            if (hotSinceMs < 0L) {
                hotSinceMs = nowMs;
            }
            if (cap == 0) {
                if (zeroSinceMs < 0L) {
                    zeroSinceMs = nowMs;
                }
                if (nowMs - zeroSinceMs >= ZERO_HOLD_MS) {
                    cap = ceiling;
                    zeroSinceMs = -1L;
                    hotSinceMs = -1L;
                    muteUntilMs = nowMs + MUTE_MS;
                    lastStepMs = nowMs;
                }
                return;
            }
            if (nowMs - hotSinceMs < sustain) {
                return;
            }
            if (lastStepMs != Long.MIN_VALUE && nowMs - lastStepMs < step) {
                return;
            }
            cap--;
            lastStepMs = nowMs;
            zeroSinceMs = cap == 0 ? nowMs : -1L;
            return;
        }
        hotSinceMs = -1L;
        zeroSinceMs = -1L;
        if (clear && cap < ceiling && (lastStepMs == Long.MIN_VALUE || nowMs - lastStepMs >= step)) {
            cap++;
            lastStepMs = nowMs;
        }
    }
}
