package com.apocollis.chunksteward.server;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counters for the terrain pipeline, reported in the status log as one {@code pipeline:} line per interval: how long
 * each worker spent on terrain and on staged populate, how long a terrain job waited to be claimed, ran, and then sat
 * ready before the server thread installed it. Workers write, the server thread reads and resets.
 */
final class PipelineStats {
    private static final int SLOTS = 8;
    private static final AtomicLongArray TERRAIN_NANOS = new AtomicLongArray(SLOTS);
    private static final AtomicLongArray TERRAIN_JOBS = new AtomicLongArray(SLOTS);
    private static final AtomicLongArray FEATURE_NANOS = new AtomicLongArray(SLOTS);
    private static final AtomicLongArray FEATURE_JOBS = new AtomicLongArray(SLOTS);
    private static final AtomicLong GENERATE_MAX = new AtomicLong();
    private static final AtomicLong DEMAND_JOBS = new AtomicLong();
    private static final AtomicLong CLAIM_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong CLAIM_WAIT_JOBS = new AtomicLong();
    private static final AtomicLong CLAIM_WAIT_MAX = new AtomicLong();
    private static final AtomicLong READY_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong READY_WAIT_JOBS = new AtomicLong();
    private static final AtomicLong READY_WAIT_MAX = new AtomicLong();
    private static final AtomicLong MAIN_GENERATED = new AtomicLong();
    private static final AtomicLong SNAPSHOTS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_MISSES = new AtomicLong();
    static final int PROVIDE_INSTALLED = 0;
    static final int PROVIDE_URGENT = 1;
    static final int PROVIDE_STARVED = 2;
    static final int PROVIDE_NOT_FINISHED = 3;
    static final int PROVIDE_HEADROOM = 4;
    static final int PROVIDE_SAVING = 5;
    static final int PROVIDE_LOADING = 6;
    private static final AtomicLongArray PROVIDE = new AtomicLongArray(8);
    private static final AtomicLong BIOME_CHECKED = new AtomicLong();
    private static final AtomicLong BIOME_MISMATCH = new AtomicLong();
    private static final AtomicLong BIOME_MISMATCH_TOTAL = new AtomicLong();
    private static long lastReport = System.nanoTime();
    private static long lastStolen;
    private static long lastInstalled;
    private static long lastDiscarded;
    private static long lastFailed;

    private PipelineStats() {
    }

    private static int slot(int slot) {
        return Math.max(0, Math.min(SLOTS - 1, slot));
    }

    /** A worker claimed a terrain job: how long it waited since it was queued. */
    static void claimed(PipelineJob job) {
        long wait = job.claimedNanos - job.createdNanos;
        CLAIM_WAIT_NANOS.addAndGet(wait);
        CLAIM_WAIT_JOBS.incrementAndGet();
        max(CLAIM_WAIT_MAX, wait);
        PipelineTrace.event("claim", job.dimension, job.x, job.z,
                "slot " + job.workerSlot + " queued " + wait / 1_000_000L + "ms" + (job.speculative ? " speculative" : ""));
    }

    /** A worker finished (or failed) a terrain job. */
    static void generated(int slot, PipelineJob job, long nanos, boolean ok) {
        int s = slot(slot);
        TERRAIN_NANOS.addAndGet(s, nanos);
        TERRAIN_JOBS.incrementAndGet(s);
        max(GENERATE_MAX, nanos);
        if (!job.speculative) {
            DEMAND_JOBS.incrementAndGet();
        }
        PipelineTrace.event(ok ? "done" : "fail", job.dimension, job.x, job.z,
                "slot " + slot + " " + nanos / 1_000_000L + "ms");
    }

    /** A worker ran a staged populate job. */
    static void feature(int slot, FeatureJob job, long nanos) {
        int s = slot(slot);
        FEATURE_NANOS.addAndGet(s, nanos);
        FEATURE_JOBS.incrementAndGet(s);
    }

    /** The server thread installed a worker chunk: how long it had been ready. */
    static void installed(PipelineJob job) {
        long wait = job.doneNanos == 0L ? 0L : System.nanoTime() - job.doneNanos;
        READY_WAIT_NANOS.addAndGet(wait);
        READY_WAIT_JOBS.incrementAndGet();
        max(READY_WAIT_MAX, wait);
        PipelineTrace.event("install", job.dimension, job.x, job.z, "ready " + wait / 1_000_000L + "ms");
    }

    /** The server thread took a biome snapshot for a queued chunk. */
    static void snapshotTaken(long nanos) {
        SNAPSHOTS.incrementAndGet();
        SNAPSHOT_NANOS.addAndGet(nanos);
    }

    /** A worker asked its snapshot for a biome outside it (or a layer it does not hold). Any thread. */
    static void snapshotMiss() {
        SNAPSHOT_MISSES.incrementAndGet();
    }

    /** What the player-chunk provision did with a chunk it was asked about (any thread that calls provide: the server's). */
    static void provide(int kind) {
        PROVIDE.incrementAndGet(kind);
    }

    static void biomeChecked() {
        BIOME_CHECKED.incrementAndGet();
    }

    /** A worker chunk's biomes disagreed with the server provider's: it was discarded. */
    static void biomeMismatch(PipelineJob job, short[] server) {
        BIOME_MISMATCH.incrementAndGet();
        BIOME_MISMATCH_TOTAL.incrementAndGet();
        BiomeCheck.report(job.dimension, job.x, job.z, job.workerSlot, job.biomeSamples, server);
        PipelineTrace.event("biome_mismatch", job.dimension, job.x, job.z, "slot " + job.workerSlot + " points "
                + BiomeCheck.differing(job.biomeSamples, server));
    }

    /** The server thread generated a chunk itself (not claimed by a worker, or stolen back). */
    static void mainGenerated(int dimension, int x, int z, String why) {
        MAIN_GENERATED.incrementAndGet();
        PipelineTrace.event("server", dimension, x, z, why);
    }

    private static void max(AtomicLong cell, long value) {
        long seen;
        while (value > (seen = cell.get()) && !cell.compareAndSet(seen, value)) {
            // retry
        }
    }

    private static String ms2(long nanos, long count) {
        return count == 0 ? "-" : String.format("%.1f", nanos / 1.0e6 / count);
    }

    private static String ms(long nanos, long count) {
        return count == 0 ? "-" : String.format("%.0f", nanos / 1.0e6 / count);
    }

    /** One status fragment for the interval that ends now; {@code table} supplies the counters it keeps. */
    static String reportAndReset(PipelineTable table, int workers, int cap, int queued, int inFlight, int ready,
                                 int slots) {
        long now = System.nanoTime();
        double interval = Math.max(1L, now - lastReport);
        lastReport = now;
        StringBuilder out = new StringBuilder("pipeline: workers ").append(workers).append(" (cap ").append(cap)
                .append("), queued ").append(queued).append(", in flight ").append(inFlight).append(", ready ")
                .append(ready);
        long jobs = 0;
        long nanos = 0;
        StringBuilder busy = new StringBuilder();
        for (int s = 0; s < Math.min(slots, SLOTS); s++) {
            long t = TERRAIN_NANOS.getAndSet(s, 0L);
            long f = FEATURE_NANOS.getAndSet(s, 0L);
            long tj = TERRAIN_JOBS.getAndSet(s, 0L);
            FEATURE_JOBS.getAndSet(s, 0L);
            jobs += tj;
            nanos += t;
            if (busy.length() > 0) {
                busy.append(" | ");
            }
            busy.append('#').append(s).append(" terrain ").append(Math.round(100.0 * t / interval))
                    .append("% staged ").append(Math.round(100.0 * f / interval)).append('%');
        }
        out.append("; terrain jobs ").append(jobs).append(" (").append(DEMAND_JOBS.getAndSet(0L)).append(" for players)")
                .append(", worker avg ").append(ms(nanos, jobs)).append(" ms max ")
                .append(GENERATE_MAX.getAndSet(0L) / 1_000_000L).append(" ms")
                .append("; busy ").append(busy.length() == 0 ? "-" : busy.toString());
        long claimJobs = CLAIM_WAIT_JOBS.getAndSet(0L);
        long readyJobs = READY_WAIT_JOBS.getAndSet(0L);
        out.append("; waited to start avg ").append(ms(CLAIM_WAIT_NANOS.getAndSet(0L), claimJobs)).append(" ms max ")
                .append(CLAIM_WAIT_MAX.getAndSet(0L) / 1_000_000L).append(" ms")
                .append(", ready before install avg ").append(ms(READY_WAIT_NANOS.getAndSet(0L), readyJobs))
                .append(" ms max ").append(READY_WAIT_MAX.getAndSet(0L) / 1_000_000L).append(" ms");
        long stolen = table.stolen.get();
        long installed = table.installed.get();
        long discarded = table.discarded.get();
        long failed = table.failed.get();
        out.append("; installed ").append(installed - lastInstalled).append(", stolen back ").append(stolen - lastStolen)
                .append(", discarded ").append(discarded - lastDiscarded).append(", failed ").append(failed - lastFailed)
                .append(", generated on server thread ").append(MAIN_GENERATED.getAndSet(0L));
        lastInstalled = installed;
        lastStolen = stolen;
        lastDiscarded = discarded;
        lastFailed = failed;
        out.append("; player chunks: installed finished ").append(PROVIDE.getAndSet(PROVIDE_INSTALLED, 0L))
                .append(", urgent ").append(PROVIDE.getAndSet(PROVIDE_URGENT, 0L))
                .append(", starved ").append(PROVIDE.getAndSet(PROVIDE_STARVED, 0L))
                .append("; deferred not finished ").append(PROVIDE.getAndSet(PROVIDE_NOT_FINISHED, 0L))
                .append(", headroom ").append(PROVIDE.getAndSet(PROVIDE_HEADROOM, 0L))
                .append(", saving ").append(PROVIDE.getAndSet(PROVIDE_SAVING, 0L))
                .append(", loading ").append(PROVIDE.getAndSet(PROVIDE_LOADING, 0L));
        long snapshots = SNAPSHOTS.getAndSet(0L);
        out.append("; biome snapshots ").append(snapshots).append(" (avg ").append(ms2(SNAPSHOT_NANOS.getAndSet(0L), snapshots))
                .append(" ms on the server thread, ").append(SNAPSHOT_MISSES.getAndSet(0L)).append(" lookups outside one)");
        out.append("; biome check ").append(BIOME_CHECKED.getAndSet(0L)).append(" chunks, ")
                .append(BIOME_MISMATCH.getAndSet(0L)).append(" mismatched (").append(BIOME_MISMATCH_TOTAL.get())
                .append(" in session)");
        if (PipelineTrace.enabled()) {
            out.append("; trace on, dropped ").append(PipelineTrace.dropped());
        }
        return out.toString();
    }
}
