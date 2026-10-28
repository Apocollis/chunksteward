package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.block.BlockFalling;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Staged populate. A chunk's populate reaches a stage (RTG's decorations), and instead of running it the populate hands
 * it here as a {@link FeatureJob}. A terrain worker runs the job when no terrain job is waiting, one job at a time,
 * against {@link FeatureScope}: nothing in the loaded chunks changes while it runs. The server thread then, at tick
 * end and in the order the jobs were created, applies what the job recorded through the real {@code World.setBlockState}
 * and runs the rest of that chunk's populate. A job that aborts (a read or write outside its window, a tile entity, an
 * entity, a scheduled tick) is run inline on the server thread instead, with the same result as an unstaged populate.
 *
 * <p>Until a job is applied its chunk is held: not populated for the client, not unloaded, saved as not populated,
 * and no populate whose window overlaps its window starts ({@link #conflicts}). That keeps the order of an unstaged
 * populate, where each chunk's populate finishes before the next one starts.
 *
 * <p>Off by default ({@code features.enabled}). All state changes happen under {@link #LOCK}; the server thread alone
 * applies jobs.
 */
public final class FeatureLane {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<FeatureJob> QUEUE = new ArrayDeque<FeatureJob>();
    /** Mutual exclusion for the decoration objects, which are shared and keep per-call state. */
    private static final ReentrantLock DECO = new ReentrantLock();
    private static FeatureJob running;
    private static long nextSeq;
    private static volatile int held;

    private FeatureLane() {
    }

    // ---- server thread: submit and decide ---------------------------------------------------------------------

    /** True when staged populate is switched on and the pipeline can run it for this world. */
    public static boolean available(WorldServer world) {
        return ChunkStewardConfig.features.enabled && TerrainPipeline.featuresAvailable(world);
    }

    /**
     * Server thread. Hands the stages to a worker and returns the job, or returns null when the caller must run them
     * inline now: staged populate is off or unavailable, the queue is full, a chunk of the read window is not loaded,
     * or this chunk already has a job.
     */
    public static FeatureJob submit(WorldServer world, int x, int z, List<FeatureStage> stages, Runnable finish) {
        if (!available(world) || Thread.currentThread() != TickMonitor.serverThread) {
            return null;
        }
        if (PipelineMarker.worker() || PipelineMarker.verify()) {
            return null;
        }
        ChunkProviderServer provider = world.getChunkProvider();
        Chunk[] window = new Chunk[FeatureWindow.readCount()];
        for (int dx = FeatureWindow.READ_LOW; dx <= FeatureWindow.READ_HIGH; dx++) {
            for (int dz = FeatureWindow.READ_LOW; dz <= FeatureWindow.READ_HIGH; dz++) {
                Chunk chunk = provider.getLoadedChunk(x + dx, z + dz);
                if (chunk == null) {
                    FeatureStats.count(FeatureStats.DECLINED_UNLOADED);
                    return null;
                }
                window[FeatureWindow.index(x, z, x + dx, z + dz)] = chunk;
            }
        }
        settleHeights(window);
        FeatureJob job;
        synchronized (LOCK) {
            if (QUEUE.size() >= ChunkStewardConfig.features.maxQueued) {
                FeatureStats.count(FeatureStats.DECLINED_QUEUE);
                return null;
            }
            int dimension = world.provider.getDimension();
            for (FeatureJob other : QUEUE) {
                if (other.holds(dimension, x, z)) {
                    FeatureStats.count(FeatureStats.DECLINED_BUSY);
                    return null;
                }
            }
            job = new FeatureJob(world, x, z, nextSeq++, window, stages, finish);
            QUEUE.addLast(job);
            held = QUEUE.size();
        }
        FeatureStats.count(FeatureStats.SUBMITTED);
        TerrainPipeline.wakeWorkers();
        return job;
    }

    /**
     * Reads every column height of the window once, on the server thread. A mod that defers light work (Arcana Quest
     * Tweaks) replays a column's pending work when its height is read, so after this the height maps the worker reads
     * are final and the worker never triggers a replay.
     */
    private static void settleHeights(Chunk[] window) {
        for (Chunk chunk : window) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    chunk.getHeightValue(x, z);
                }
            }
        }
    }

    /** The lock the decoration objects need: a worker holds it for a job, inline decorations take it while staging is on. */
    public static ReentrantLock decoLock() {
        return DECO;
    }

    /** A populate of chunk (x, z) must wait: its window overlaps a chunk whose staged work is not applied yet. */
    public static boolean conflicts(int dimension, int x, int z) {
        if (held == 0) {
            return false;
        }
        synchronized (LOCK) {
            for (FeatureJob job : QUEUE) {
                if (job.dimension == dimension && FeatureWindow.conflict(job.x, job.z, x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The chunk's staged work is not applied yet: it must not be sent, unloaded or saved as populated. */
    public static boolean holds(int dimension, int x, int z) {
        if (held == 0) {
            return false;
        }
        synchronized (LOCK) {
            for (FeatureJob job : QUEUE) {
                if (job.holds(dimension, x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int queued() {
        return held;
    }

    // ---- worker ----------------------------------------------------------------------------------------------

    /** True when a worker has a job to take. Lets the worker loop sleep shorter. */
    static boolean hasQueued() {
        if (held == 0) {
            return false;
        }
        synchronized (LOCK) {
            if (running != null) {
                return false;
            }
            for (FeatureJob job : QUEUE) {
                if (job.state.get() == FeatureJob.QUEUED) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Worker thread, when no terrain job is waiting: the first queued job, or null. One job runs at a time. */
    static FeatureJob takeForWorker() {
        if (held == 0) {
            return null;
        }
        synchronized (LOCK) {
            if (running != null) {
                return null;
            }
            for (FeatureJob job : QUEUE) {
                if (job.state.compareAndSet(FeatureJob.QUEUED, FeatureJob.RUNNING)) {
                    running = job;
                    return job;
                }
            }
        }
        return null;
    }

    /** Worker thread: runs the job's stages against a scope, then publishes the outcome. */
    static void execute(FeatureJob job, int slot) {
        IChunkGenerator generator = TerrainPipeline.workerGenerator(slot, job.dimension);
        BiomeProvider biome = TerrainPipeline.workerBiome(slot, job.dimension);
        String failure = null;
        FeatureScope scope = null;
        if (generator == null || biome == null) {
            failure = "no worker generator for dimension " + job.dimension;
        } else {
            scope = new FeatureScope(job.world, job.x, job.z, job.window);
            job.scope = scope;
            PipelineMarker.set(true, false);
            PipelineMarker.biome(biome, job.dimension);
            boolean decoLock = needsDecorationLock(job);
            if (decoLock) {
                DECO.lock();
            }
            long started = System.nanoTime();
            try {
                scope.begin();
                int finished = 0;
                for (FeatureStage stage : job.stages) {
                    stage.run(generator);
                    if (scope.abortReason() != null) {
                        break;
                    }
                    // A later stage that aborts leaves these stages' writes valid: the server applies them and runs
                    // only the stages after them inline.
                    job.doneWrites = scope.journal.size();
                    job.doneChecks = scope.journal.checkCount();
                    job.doneStages = ++finished;
                }
                failure = scope.abortReason();
            } catch (FeatureAbort abort) {
                failure = abort.getMessage();
            } catch (Throwable error) {
                failure = scope.abortReason() != null ? scope.abortReason() : "error: " + error;
                if (scope.abortReason() == null) {
                    job.culprit = FeatureStats.culpritOf(error.getStackTrace());
                }
            } finally {
                job.workNanos = System.nanoTime() - started;
                scope.end();
                if (decoLock) {
                    DECO.unlock();
                }
                PipelineMarker.clear();
            }
            if (failure != null && scope.culprit() != null) {
                job.culprit = scope.culprit();
            }
        }
        job.failure = failure;
        if (failure == null) {
            FeatureStats.count(FeatureStats.WORKER_OK);
        }
        job.state.compareAndSet(FeatureJob.RUNNING, failure == null ? FeatureJob.DONE : FeatureJob.FAILED);
        synchronized (LOCK) {
            if (running == job) {
                running = null;
            }
        }
    }

    private static boolean needsDecorationLock(FeatureJob job) {
        for (FeatureStage stage : job.stages) {
            if (stage.needsDecorationLock()) {
                return true;
            }
        }
        return false;
    }

    // ---- server thread: apply ----------------------------------------------------------------------------------

    /** Tick start: give up on jobs that cannot finish (no workers, or waiting too long). */
    static void onTickStart() {
        if (held == 0) {
            return;
        }
        long limit = ChunkStewardConfig.features.maxWaitSeconds * 1_000_000_000L;
        long now = System.nanoTime();
        synchronized (LOCK) {
            for (FeatureJob job : QUEUE) {
                int state = job.state.get();
                if (state != FeatureJob.QUEUED && state != FeatureJob.RUNNING) {
                    continue;
                }
                if (!TerrainPipeline.featuresAvailable(job.world) || now - job.createdNanos > limit) {
                    abandon(job);
                }
            }
        }
    }

    /** Tick end: apply finished jobs in creation order, within {@code features.promoteMs} (at least one). */
    static void onTickEnd() {
        if (held == 0) {
            return;
        }
        long deadline = System.nanoTime() + ChunkStewardConfig.features.promoteMs * 1_000_000L;
        boolean first = true;
        while (true) {
            FeatureJob job;
            synchronized (LOCK) {
                job = QUEUE.peekFirst();
                if (job == null || !isFinished(job)) {
                    return;
                }
                if (!first && System.nanoTime() > deadline) {
                    return;
                }
                QUEUE.pollFirst();
                held = QUEUE.size();
            }
            first = false;
            apply(job);
        }
    }

    /** Server thread: finish every job now, inline where a worker has not produced a result. Server stop, dimension unload. */
    public static void drainAll() {
        drain(Integer.MIN_VALUE, true);
    }

    /** As {@link #drainAll()} for one dimension. */
    public static void drain(int dimension) {
        drain(dimension, false);
    }

    private static void drain(int dimension, boolean everything) {
        while (true) {
            FeatureJob job;
            synchronized (LOCK) {
                job = null;
                for (Iterator<FeatureJob> it = QUEUE.iterator(); it.hasNext(); ) {
                    FeatureJob candidate = it.next();
                    if (everything || candidate.dimension == dimension) {
                        job = candidate;
                        break;
                    }
                }
                if (job == null) {
                    return;
                }
                if (!isFinished(job)) {
                    abandon(job);
                }
                QUEUE.remove(job);
                held = QUEUE.size();
            }
            apply(job);
        }
    }

    private static boolean isFinished(FeatureJob job) {
        int state = job.state.get();
        return state == FeatureJob.DONE || state == FeatureJob.FAILED || state == FeatureJob.ABANDONED;
    }

    private static void abandon(FeatureJob job) {
        while (true) {
            int state = job.state.get();
            if (state != FeatureJob.QUEUED && state != FeatureJob.RUNNING) {
                return;
            }
            if (job.state.compareAndSet(state, FeatureJob.ABANDONED)) {
                return;
            }
        }
    }

    /** Applies one finished job: the recorded writes (or the stages inline), then the rest of the chunk's populate. */
    private static void apply(FeatureJob job) {
        Chunk center = job.center();
        int state = job.state.get();
        boolean fallBack = state != FeatureJob.DONE;
        // A job the worker finished (failed) keeps the stages that completed; an abandoned job may still be running.
        int from = state == FeatureJob.DONE ? job.stages.size()
                : (state == FeatureJob.FAILED && job.scope != null ? job.doneStages : 0);
        FeatureStats.waited(System.nanoTime() - job.createdNanos);
        if (job.workNanos > 0L) {
            FeatureStats.work(job.workNanos);
        }
        if (fallBack) {
            noteAbort(job);
        }
        for (int i = 0; i < from; i++) {
            if (job.stages.get(i) instanceof GeneratorStage) {
                FeatureStats.count(FeatureStats.GEN_STAGED);
            }
        }
        boolean previousFalling = BlockFalling.fallInstantly;
        BlockFalling.fallInstantly = true;
        PopulateScopes.begin(job.world, center);
        try {
            if (from > 0) {
                replay(job, state == FeatureJob.DONE ? Integer.MAX_VALUE : job.doneWrites,
                        state == FeatureJob.DONE ? Integer.MAX_VALUE : job.doneChecks);
            }
            if (from < job.stages.size()) {
                boolean decoLock = needsDecorationLock(job);
                if (decoLock) {
                    DECO.lock();
                }
                try {
                    for (int i = from; i < job.stages.size(); i++) {
                        job.stages.get(i).runInline();
                    }
                } finally {
                    if (decoLock) {
                        DECO.unlock();
                    }
                }
            }
            job.finish.run();
            FeatureStats.count(FeatureStats.APPLIED);
        } catch (Throwable error) {
            ChunkSteward.LOGGER.error("staged populate failed at chunk {},{} in dimension {}",
                    job.x, job.z, job.dimension, error);
        } finally {
            PopulateScopes.end(job.world, center);
            BlockFalling.fallInstantly = previousFalling;
        }
    }

    /** Replays the first {@code writes} writes and {@code checks} light checks the worker recorded. */
    private static void replay(FeatureJob job, int writes, int checks) {
        FeatureJournal<net.minecraft.block.state.IBlockState> journal = job.scope.journal;
        WorldServer world = job.world;
        int count = 0;
        for (FeatureJournal.Write<net.minecraft.block.state.IBlockState> write : journal.writes()) {
            if (count++ >= writes) {
                break;
            }
            world.setBlockState(BlockPos.fromLong(write.pos), write.state, write.flags);
        }
        for (long pos : journal.checks(checks)) {
            world.checkLight(BlockPos.fromLong(pos));
        }
    }

    /** Counts why the job fell back, strikes the generator behind it, and logs each distinct reason once. */
    private static void noteAbort(FeatureJob job) {
        String reason = job.failure != null ? job.failure
                : (job.state.get() == FeatureJob.ABANDONED ? "gave up waiting" : "no result");
        int category = job.failure == null ? FeatureStats.INLINE_TIMEOUT : FeatureStats.categoryOf(job.failure);
        FeatureStats.count(category);
        FeatureStats.culprit(job.culprit);
        FeatureScope scope = job.scope;
        if (scope != null && category != FeatureStats.INLINE_ERROR && scope.culpritGenerator() != null) {
            GeneratorPolicy.strike(scope.culpritGenerator(), reason);
        }
        if (ChunkStewardConfig.features.logAborts && FeatureStats.firstSeen(category + "/" + job.culprit)) {
            ChunkSteward.LOGGER.info("staged populate at chunk {},{} ran inline: {}{}", job.x, job.z, reason,
                    job.culprit != null ? " (caused by " + job.culprit + ")" : "");
        }
    }

    static String summary() {
        if (!ChunkStewardConfig.features.enabled) {
            return "staged populate off";
        }
        return "staged populate queued " + held + ", submitted " + FeatureStats.total(FeatureStats.SUBMITTED)
                + ", applied " + FeatureStats.total(FeatureStats.APPLIED);
    }

    /** For tests and status: a snapshot of the queue positions. */
    static List<int[]> snapshot() {
        List<int[]> out = new ArrayList<int[]>();
        synchronized (LOCK) {
            for (FeatureJob job : QUEUE) {
                out.add(new int[] {job.dimension, job.x, job.z, job.state.get()});
            }
        }
        return out;
    }
}
