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
    final AtomicLong failed = new AtomicLong();

    void clear() {
        jobs.clear();
        ready.clear();
        epochs.clear();
        stolen.set(0L);
        installed.set(0L);
        discarded.set(0L);
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
                discarded.incrementAndGet();
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

    PipelineJob readyAt(int dimension, int x, int z) {
        return ready.get(new PipelineKey(dimension, x, z));
    }

    /** Removes a ready result. Counts as installed when the caller kept it. */
    PipelineJob takeReady(int dimension, int x, int z) {
        PipelineJob job = ready.remove(new PipelineKey(dimension, x, z));
        if (job != null) {
            installed.incrementAndGet();
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
                discarded.incrementAndGet();
            }
            ready.remove(key);
        }
    }
}
