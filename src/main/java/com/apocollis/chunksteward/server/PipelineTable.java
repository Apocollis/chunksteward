package com.apocollis.chunksteward.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jobs and the ready map. A dimension epoch invalidates jobs from before an unload or a session reset. Publishing a
 * worker result fails when the server has already stolen the claim, or when the epoch moved.
 */
final class PipelineTable {
    private final ConcurrentHashMap<PipelineKey, PipelineJob> jobs = new ConcurrentHashMap<PipelineKey, PipelineJob>();
    private final ConcurrentHashMap<PipelineKey, PipelineJob> ready = new ConcurrentHashMap<PipelineKey, PipelineJob>();
    private final ConcurrentHashMap<Integer, Integer> epochs = new ConcurrentHashMap<Integer, Integer>();

    final AtomicLong stolen = new AtomicLong();
    final AtomicLong installed = new AtomicLong();
    final AtomicLong discarded = new AtomicLong();

    /** Why a job's result or place in the queue was thrown away; {@link #discardLabel} names them. */
    static final int D_QUEUED = 0;
    static final int D_EPOCH = 1;
    static final int D_DIMENSION = 2;
    static final int D_INVALID = 3;
    static final int D_BIOME = 4;
    static final int D_LOADED = 5;
    static final int D_IDLE = 6;
    static final int D_EVICTED = 7;
    private static final String[] DISCARD_LABELS = {"queued job dropped by the ranking", "late result after a reset",
            "dimension reset", "result no longer valid", "biome mismatch", "chunk already loaded", "result nobody waited for",
            "preload result evicted for a player"};
    final java.util.concurrent.atomic.AtomicLongArray discardReasons = new java.util.concurrent.atomic.AtomicLongArray(8);

    void discard(int reason) {
        discarded.incrementAndGet();
        discardReasons.incrementAndGet(reason);
    }

    /** "queued dropped 12, evicted preload 3": the non-zero reasons, as a count since {@code since} (null = total). */
    String discardSummary(long[] since) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < discardReasons.length(); i++) {
            long n = discardReasons.get(i) - (since == null ? 0L : since[i]);
            if (since != null) {
                since[i] = discardReasons.get(i);
            }
            if (n > 0) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(DISCARD_LABELS[i]).append(' ').append(n);
            }
        }
        return out.length() == 0 ? "-" : out.toString();
    }
    final AtomicLong failed = new AtomicLong();

    void clear() {
        jobs.clear();
        ready.clear();
        epochs.clear();
        stolen.set(0L);
        installed.set(0L);
        discarded.set(0L);
        for (int i = 0; i < discardReasons.length(); i++) {
            discardReasons.set(i, 0L);
        }
        failed.set(0L);
    }

    int epoch(int dimension) {
        Integer epoch = epochs.get(dimension);
        return epoch == null ? 0 : epoch;
    }

    /** Drops jobs and ready results for one dimension. */
    void bumpEpoch(int dimension) {
        epochs.put(dimension, epoch(dimension) + 1);
        dropDimension(dimension);
    }

    PipelineJob submit(int dimension, int x, int z) {
        int epoch = epoch(dimension);
        PipelineKey key = new PipelineKey(dimension, x, z);
        PipelineJob created = new PipelineJob(dimension, x, z, epoch);
        while (true) {
            PipelineJob existing = jobs.get(key);
            if (existing == null) {
                if (jobs.putIfAbsent(key, created) == null) {
                    return created;
                }
                continue;
            }
            int state = existing.state.get();
            // A finished job whose result still waits in the ready map is the answer: replacing it would throw the
            // chunk away and generate it again every tick the server has not installed it yet.
            boolean waiting = state == PipelineJob.DONE && existing.epoch == epoch && ready.get(key) == existing;
            if (waiting) {
                return existing;
            }
            if (existing.epoch != epoch || state == PipelineJob.DONE || state == PipelineJob.CANCELLED) {
                if (jobs.replace(key, existing, created)) {
                    ready.remove(key, existing);
                    return created;
                }
                continue;
            }
            return existing;
        }
    }

    boolean claimWorker(PipelineJob job) {
        return job != null && job.epoch == epoch(job.dimension) && job.claimWorker();
    }

    boolean claimMain(PipelineJob job) {
        return job != null && job.epoch == epoch(job.dimension) && job.claimMain();
    }

    /** Puts a worker result in the ready map. A late publish after a steal or an epoch change does not. */
    boolean publish(PipelineJob job) {
        if (job == null || !job.publish()) {
            return false;
        }
        PipelineKey key = new PipelineKey(job.dimension, job.x, job.z);
        ready.put(key, job);
        if (job.epoch != epoch(job.dimension)) {
            if (ready.remove(key, job)) {
                discard(D_EPOCH);
            }
            job.state.set(PipelineJob.CANCELLED);
            return false;
        }
        return true;
    }

    boolean steal(PipelineJob job) {
        if (job == null || !job.steal()) {
            return false;
        }
        stolen.incrementAndGet();
        return true;
    }

    void fail(PipelineJob job) {
        if (job == null) {
            return;
        }
        job.state.set(PipelineJob.CANCELLED);
        failed.incrementAndGet();
    }

    PipelineJob jobAt(int dimension, int x, int z) {
        return jobs.get(new PipelineKey(dimension, x, z));
    }

    PipelineJob readyAt(int dimension, int x, int z) {
        return ready.get(new PipelineKey(dimension, x, z));
    }

    /** Drops queued jobs that fell out of the ranked window. A claimed job is left to finish. */
    void dropQueuedExcept(java.util.Set<PipelineKey> keep) {
        List<PipelineKey> drop = new ArrayList<PipelineKey>();
        for (java.util.Map.Entry<PipelineKey, PipelineJob> entry : jobs.entrySet()) {
            if (entry.getValue().state.get() == PipelineJob.QUEUED && !keep.contains(entry.getKey())) {
                drop.add(entry.getKey());
            }
        }
        for (PipelineKey key : drop) {
            PipelineJob job = jobs.get(key);
            if (job != null && job.state.compareAndSet(PipelineJob.QUEUED, PipelineJob.CANCELLED)) {
                jobs.remove(key, job);
                discard(D_QUEUED);
            }
        }
    }

    List<PipelineJob> claimedByWorkers() {
        List<PipelineJob> claimed = new ArrayList<PipelineJob>();
        for (PipelineJob job : jobs.values()) {
            if (job.state.get() == PipelineJob.CLAIMED_WORKER) {
                claimed.add(job);
            }
        }
        return claimed;
    }

    /** Drops a ready result without counting it as installed. */
    PipelineJob discardReady(int dimension, int x, int z, int reason) {
        PipelineKey key = new PipelineKey(dimension, x, z);
        PipelineJob job = ready.remove(key);
        if (job == null) {
            return null;
        }
        jobs.remove(key, job);
        discard(reason);
        return job;
    }

    /** Removes a ready result. Counts as installed when the caller kept it. */
    PipelineJob takeReady(int dimension, int x, int z) {
        PipelineKey key = new PipelineKey(dimension, x, z);
        PipelineJob job = ready.remove(key);
        if (job != null) {
            installed.incrementAndGet();
            jobs.remove(key, job);
        }
        return job;
    }

    int count(int state) {
        int n = 0;
        for (PipelineJob job : jobs.values()) {
            if (job.state.get() == state) {
                n++;
            }
        }
        return n;
    }

    /**
     * Drops entries nothing waits on any more: cancelled and finished jobs whose result is not in the ready map, and
     * jobs the server thread claimed (it generates those in the same call, so one this old is done). Without this the
     * table grew by one entry for every chunk the server thread generated, and each kept its biome snapshot.
     */
    int sweep(long maxAgeNanos) {
        long now = System.nanoTime();
        int removed = 0;
        for (java.util.Map.Entry<PipelineKey, PipelineJob> entry : jobs.entrySet()) {
            PipelineJob job = entry.getValue();
            int state = job.state.get();
            boolean stale = state == PipelineJob.CANCELLED
                    || (state == PipelineJob.DONE && ready.get(entry.getKey()) != job)
                    || (state == PipelineJob.CLAIMED_MAIN && now - job.createdNanos > maxAgeNanos);
            if (stale && jobs.remove(entry.getKey(), job)) {
                job.biomeSnapshot = null;
                removed++;
            }
        }
        return removed;
    }

    /** The finished jobs whose chunks have not been installed. */
    List<PipelineJob> readyJobs() {
        return new ArrayList<PipelineJob>(ready.values());
    }

    int readyCount() {
        return ready.size();
    }

    private void dropDimension(int dimension) {
        List<PipelineKey> drop = new ArrayList<PipelineKey>();
        for (PipelineKey key : jobs.keySet()) {
            if (key.dimension == dimension) {
                drop.add(key);
            }
        }
        for (PipelineKey key : drop) {
            PipelineJob job = jobs.remove(key);
            if (job != null && job.state.get() != PipelineJob.DONE && job.state.get() != PipelineJob.CANCELLED) {
                job.state.set(PipelineJob.CANCELLED);
                discard(D_DIMENSION);
            }
            ready.remove(key);
        }
    }
}
