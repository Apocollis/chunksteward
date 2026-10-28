package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.AccessorPlayerChunkMap;
import com.apocollis.chunksteward.mixin.AccessorPlayerChunkMapEntry;
import com.sun.management.OperatingSystemMXBean;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Loader;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Terrain pipeline. A listed generator runs off the server thread in every dimension that uses that class.
 * An empty {@code allowedGenerators} list starts nothing.
 */
public final class TerrainPipeline {
    private static final Object LOCK = new Object();
    private static final Thread[] NO_THREADS = new Thread[0];
    private static final PipelineJob[] NO_JOBS = new PipelineJob[0];

    private static final PipelineTable TABLE = new PipelineTable();
    private static final PipelineCpuGuard GUARD = new PipelineCpuGuard();
    private static final ConcurrentHashMap<PipelineKey, Chunk> CHUNKS = new ConcurrentHashMap<PipelineKey, Chunk>();
    private static final Set<String> DISALLOWED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Integer> FAILURES = new ConcurrentHashMap<String, Integer>();
    private static final List<ConcurrentHashMap<Integer, IChunkGenerator>> GENERATORS = new ArrayList<ConcurrentHashMap<Integer, IChunkGenerator>>();
    private static final List<ConcurrentHashMap<Integer, BiomeProvider>> BIOMES = new ArrayList<ConcurrentHashMap<Integer, BiomeProvider>>();

    private static volatile boolean killed;
    private static volatile boolean run;
    private static volatile boolean loliBlocked;
    private static volatile int pausedDimension = Integer.MIN_VALUE;
    private static volatile PipelineJob[] snapshot = NO_JOBS;
    private static final ConcurrentLinkedDeque<PipelineKey> SPECULATIVE_READY = new ConcurrentLinkedDeque<PipelineKey>();
    private static double installEma;
    private static boolean installPrimed;
    private static final AtomicInteger CURSOR = new AtomicInteger();
    private static Thread[] threads = NO_THREADS;
    private static boolean[] retire = new boolean[0];
    private static boolean loggedUnsupported;

    private static final double RATIO_ALPHA = 0.02;

    private static boolean sampling;
    private static long wallStart;
    private static long cpuStart;
    private static long wallAccum;
    private static double ratioEma;
    private static boolean ratioPrimed;
    private static boolean threadCpu;
    private static boolean systemCpu;
    private static boolean signalLogged;
    private static ThreadMXBean threadBean;
    private static OperatingSystemMXBean osBean;

    private TerrainPipeline() {
    }

    /** True while workers may take terrain for a listed generator and the cap is above 0. */
    static boolean isPipelineActive() {
        return run && !killed && !loliBlocked && GUARD.cap() > 0;
    }

    /**
     * Staged populate may use the workers for this world: the pipeline runs, the worker cap is above 0, the world is
     * served and its generator has a copy on every worker.
     */
    static boolean featuresAvailable(WorldServer world) {
        if (world == null || !isPipelineActive() || !serving(world)) {
            return false;
        }
        int dimension = world.provider.getDimension();
        int count = Math.min(GUARD.cap(), GENERATORS.size());
        if (count <= 0) {
            return false;
        }
        for (int slot = 0; slot < count; slot++) {
            if (!GENERATORS.get(slot).containsKey(dimension)) {
                return false;
            }
        }
        return true;
    }

    /** The generator copy worker {@code slot} runs for {@code dimension}, or null. */
    static IChunkGenerator workerGenerator(int slot, int dimension) {
        return slot >= 0 && slot < GENERATORS.size() ? GENERATORS.get(slot).get(dimension) : null;
    }

    /** The biome provider copy worker {@code slot} uses for {@code dimension}, or null. */
    static BiomeProvider workerBiome(int slot, int dimension) {
        return slot >= 0 && slot < BIOMES.size() ? BIOMES.get(slot).get(dimension) : null;
    }

    /** Wakes parked workers, so a staged job does not wait for the next poll. */
    static void wakeWorkers() {
        for (Thread thread : threads) {
            LockSupport.unpark(thread);
        }
    }

    static int queueSize() {
        return TABLE.count(PipelineJob.QUEUED)
                + TABLE.count(PipelineJob.CLAIMED_WORKER)
                + TABLE.count(PipelineJob.CLAIMED_MAIN)
                + TABLE.readyCount();
    }

    static double averageInstallMs() {
        return installPrimed ? installEma : 0.0;
    }

    static boolean chunkReady(int dimension, int x, int z) {
        WorldServer world = DimensionManager.getWorld(dimension);
        return world != null && ready(world, x, z);
    }

    /**
     * Preload and stage 2 should not generate this chunk on the server thread. A finished result still installs
     * through {@code provideChunk}. False when speculative production is off or this world is not being served.
     */
    static boolean deferSpeculative(World world, int x, int z) {
        if (!ChunkStewardConfig.pipeline.speculative || !serving(world)) {
            return false;
        }
        return !ready(world, x, z);
    }

    static PipelineTable table() {
        return TABLE;
    }

    static void reset() {
        synchronized (LOCK) {
            killed = false;
            stopWorkers();
            TABLE.clear();
            CHUNKS.clear();
            SPECULATIVE_READY.clear();
            installEma = 0.0;
            installPrimed = false;
            GENERATORS.clear();
            BIOMES.clear();
            DISALLOWED.clear();
            FAILURES.clear();
            snapshot = NO_JOBS;
            CURSOR.set(0);
            pausedDimension = Integer.MIN_VALUE;
            PipelinePasses.reset();
            GUARD.reset(1);
            sampling = false;
            wallAccum = 0L;
            ratioEma = 0.0;
            ratioPrimed = false;
        }
    }

    static void pause(int dimension) {
        pausedDimension = dimension;
    }

    /** True while a verify holds this dimension. Stage 2 and preload skip it so the server thread is not a third writer. */
    static boolean isPaused(int dimension) {
        return pausedDimension == dimension;
    }

    static void resume(int dimension) {
        if (pausedDimension == dimension) {
            pausedDimension = Integer.MIN_VALUE;
        }
    }

    static void allow(String className) {
        DISALLOWED.remove(className);
    }

    static void disallow(String className) {
        DISALLOWED.add(className);
    }

    public static void onDimensionUnloaded(int dimension) {
        FeatureLane.drain(dimension);
        synchronized (LOCK) {
            TABLE.bumpEpoch(dimension);
            dropChunks(dimension);
            for (ConcurrentHashMap<Integer, IChunkGenerator> generators : GENERATORS) {
                generators.remove(dimension);
            }
            for (ConcurrentHashMap<Integer, BiomeProvider> biomes : BIOMES) {
                biomes.remove(dimension);
            }
        }
    }

    /** Session kill switch. The config switch is unchanged. */
    static void setKilled(boolean value) {
        synchronized (LOCK) {
            killed = value;
            if (value) {
                stopWorkers();
            } else {
                startIfEngaged();
            }
        }
    }

    static boolean killed() {
        return killed;
    }

    /** Called from the server thread at tick start. Does nothing while the pipeline is idle. */
    static void onTickStart() {
        synchronized (LOCK) {
            if (!engaged()) {
                if (run) {
                    stopWorkers();
                }
                sampling = false;
                return;
            }
            startIfEngaged();
            if (run) {
                ensureGenerators();
                rank();
                watchdog();
                replaceRetired();
            }
            if (!run || loliBlocked || TickMonitor.savePaused()) {
                sampling = false;
                return;
            }
            sampling = true;
            wallStart = System.nanoTime();
            cpuStart = threadCpu ? threadBean.getCurrentThreadCpuTime() : 0L;
        }
    }

    /** Called from the server thread at tick end, before the status log. */
    static void onTickEnd() {
        synchronized (LOCK) {
            if (!sampling) {
                return;
            }
            sampling = false;
            long wall = System.nanoTime() - wallStart;
            if (wall <= 0L) {
                return;
            }
            wallAccum += wall;
            if (threadCpu) {
                long cpu = threadBean.getCurrentThreadCpuTime() - cpuStart;
                smooth(cpu <= 0L ? 0.0 : cpu / (double) wall);
            }
            if (wallAccum < 1_000_000_000L) {
                return;
            }
            if (!threadCpu && systemCpu) {
                double load = osBean.getCpuLoad();
                if (load >= 0.0) {
                    smooth(load);
                }
            }
            stepGuard();
            wallAccum = 0L;
        }
    }

    private static void smooth(double sample) {
        if (!ratioPrimed) {
            ratioEma = sample;
            ratioPrimed = true;
        } else {
            ratioEma += RATIO_ALPHA * (sample - ratioEma);
        }
    }

    static String summary() {
        ChunkStewardConfig.Pipeline pipeline = ChunkStewardConfig.pipeline;
        if (loliBlocked) {
            return "pipeline off (LoliASM makeEventsSingletons)";
        }
        if (!pipeline.enabled) {
            return "pipeline off (config)";
        }
        if (killed) {
            return "pipeline off this session";
        }
        if (!PipelineGate.hasGenerator(pipeline.allowedGenerators)) {
            return "pipeline idle (no generators listed)";
        }
        return "pipeline workers " + threads.length + ", cap " + GUARD.cap()
                + ", queued " + TABLE.count(PipelineJob.QUEUED)
                + ", in flight " + (TABLE.count(PipelineJob.CLAIMED_WORKER) + TABLE.count(PipelineJob.CLAIMED_MAIN))
                + ", ready " + TABLE.readyCount()
                + ", stolen " + TABLE.stolen.get()
                + ", installed " + TABLE.installed.get()
                + ", discarded " + TABLE.discarded.get()
                + ", failed " + TABLE.failed.get()
                + "; verify " + PipelineVerify.status();
    }

    /** The status-log fragment for the pipeline's interval, or null when the pipeline is not running. */
    static String reportAndReset() {
        if (!run || killed || loliBlocked) {
            return null;
        }
        return PipelineStats.reportAndReset(TABLE, threads.length, GUARD.cap(), TABLE.count(PipelineJob.QUEUED),
                TABLE.count(PipelineJob.CLAIMED_WORKER) + TABLE.count(PipelineJob.CLAIMED_MAIN), TABLE.readyCount(),
                threads.length);
    }

    private static boolean engaged() {
        ChunkStewardConfig.Pipeline pipeline = ChunkStewardConfig.pipeline;
        return PipelineGate.engages(pipeline.enabled, killed, PipelineGate.hasGenerator(pipeline.allowedGenerators));
    }

    private static void startIfEngaged() {
        if (run || !engaged()) {
            return;
        }
        checkLoli();
        if (loliBlocked) {
            return;
        }
        prepareSignal();
        int count = PipelineGate.resolveWorkers(ChunkStewardConfig.pipeline.workers,
                Runtime.getRuntime().availableProcessors());
        GUARD.reset(count);
        GENERATORS.clear();
        BIOMES.clear();
        for (int slot = 0; slot < count; slot++) {
            GENERATORS.add(new ConcurrentHashMap<Integer, IChunkGenerator>());
            BIOMES.add(new ConcurrentHashMap<Integer, BiomeProvider>());
        }
        retire = new boolean[count];
        run = true;
        threads = new Thread[count];
        for (int slot = 0; slot < count; slot++) {
            threads[slot] = startWorker(slot);
        }
        ChunkSteward.LOGGER.info("pipeline workers started ({}); a listed generator runs off the server thread", count);
    }

    private static void stopWorkers() {
        run = false;
        Thread[] running = threads;
        threads = NO_THREADS;
        for (Thread thread : running) {
            LockSupport.unpark(thread);
        }
        long deadline = System.nanoTime() + 2_000_000_000L;
        for (Thread thread : running) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs <= 0L) {
                break;
            }
            try {
                thread.join(leftMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        sampling = false;
    }

    private static void prepareSignal() {
        String mode = ChunkStewardConfig.pipeline.cpuGuard;
        if (mode == null) {
            mode = "thread";
        }
        threadCpu = false;
        systemCpu = false;
        if ("off".equalsIgnoreCase(mode)) {
            return;
        }
        if (!"system".equalsIgnoreCase(mode)) {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean.isCurrentThreadCpuTimeSupported()) {
                if (!bean.isThreadCpuTimeEnabled()) {
                    bean.setThreadCpuTimeEnabled(true);
                }
                threadBean = bean;
                threadCpu = true;
                return;
            }
        }
        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
        if (base instanceof OperatingSystemMXBean) {
            osBean = (OperatingSystemMXBean) base;
            systemCpu = osBean.getCpuLoad() >= 0.0;
        }
        if (!systemCpu && !signalLogged) {
            signalLogged = true;
            ChunkSteward.LOGGER.info("pipeline cpu guard has no usable signal; it stays off");
        }
    }

    private static void stepGuard() {
        if (!threadCpu && !systemCpu) {
            return;
        }
        ChunkStewardConfig.Pipeline pipeline = ChunkStewardConfig.pipeline;
        if (!ratioPrimed) {
            return;
        }
        boolean hot;
        boolean clear;
        if (threadCpu) {
            hot = ratioEma < pipeline.cpuGuardRatio;
            clear = ratioEma >= pipeline.cpuGuardRatio + 0.05;
        } else {
            hot = ratioEma > pipeline.cpuGuardLoad;
            clear = ratioEma <= pipeline.cpuGuardLoad - 0.05;
        }
        GUARD.tick(System.currentTimeMillis(), hot, clear,
                pipeline.cpuGuardResumeSeconds * 1000L, pipeline.cpuGuardSeconds * 1000L);
    }

    /**
     * Called from a whitelisted generator's {@code generateChunk}. A worker or verify thread runs the body. On the server
     * thread a finished worker chunk is installed, and a queued chunk is claimed for synchronous generation.
     */
    public static Chunk takeGenerated(IChunkGenerator generator, int x, int z) {
        if (PipelineMarker.worker() || PipelineMarker.verify()) {
            return null;
        }
        World world = PipelineGenerators.worldOf(generator);
        if (world == null || !serving(world)) {
            return null;
        }
        int dimension = world.provider.getDimension();
        PipelineKey key = new PipelineKey(dimension, x, z);
        Chunk installed = install(world, generator, dimension, x, z, key);
        if (installed != null) {
            GenSource.record(dimension, x, z, GenSource.WORKER);
            return installed;
        }
        GenSource.record(dimension, x, z, GenSource.SERVER);
        PipelineJob job = TABLE.jobAt(dimension, x, z);
        PipelineStats.mainGenerated(dimension, x, z, job == null ? "no job queued"
                : (job.state.get() == PipelineJob.CLAIMED_WORKER ? "worker still running, stolen back"
                : (job.state.get() == PipelineJob.QUEUED ? "not started by a worker" : "job state " + job.state.get())));
        if (job != null && job.state.get() == PipelineJob.CLAIMED_WORKER) {
            long deadline = System.nanoTime() + ChunkStewardConfig.pipeline.stealWaitMs * 1_000_000L;
            while (job.state.get() == PipelineJob.CLAIMED_WORKER && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000L);
            }
            installed = install(world, generator, dimension, x, z, key);
            if (installed != null) {
                GenSource.record(dimension, x, z, GenSource.WORKER);
                return installed;
            }
            TABLE.steal(job);
            return null;
        }
        if (job == null || job.state.get() != PipelineJob.QUEUED) {
            job = TABLE.submit(dimension, x, z);
        }
        TABLE.claimMain(job);
        return null;
    }

    static boolean serving(World world) {
        if (!run || killed || loliBlocked || GUARD.cap() <= 0 || world == null) {
            return false;
        }
        int dimension = world.provider.getDimension();
        if (dimension == pausedDimension || !Dimensions.applies(dimension)) {
            return false;
        }
        IChunkGenerator generator = PipelineGenerators.of(world);
        if (!PipelineGenerators.supported(generator) || !PipelineGenerators.listed(generator)) {
            noteUnsupported(generator);
            return false;
        }
        return !DISALLOWED.contains(generator.getClass().getName());
    }

    static boolean ready(World world, int x, int z) {
        if (world == null) {
            return false;
        }
        int dimension = world.provider.getDimension();
        PipelineJob job = TABLE.readyAt(dimension, x, z);
        return job != null && job.epoch == TABLE.epoch(dimension) && job.state.get() == PipelineJob.DONE
                && CHUNKS.containsKey(new PipelineKey(dimension, x, z));
    }

    private static Chunk install(World world, IChunkGenerator generator, int dimension, int x, int z, PipelineKey key) {
        PipelineJob job = TABLE.readyAt(dimension, x, z);
        if (job == null || job.epoch != TABLE.epoch(dimension) || job.state.get() != PipelineJob.DONE) {
            return null;
        }
        Chunk chunk = CHUNKS.get(key);
        if (chunk == null) {
            return null;
        }
        if (job.context != null && !PipelinePreparers.valid(job.context, world, x, z)) {
            CHUNKS.remove(key, chunk);
            TABLE.discardReady(dimension, x, z);
            SPECULATIVE_READY.remove(key);
            return null;
        }
        if (job.biomeSamples != null && ChunkStewardConfig.pipeline.biomeCheck) {
            short[] server = BiomeCheck.sample(world.getBiomeProvider(), x, z);
            PipelineStats.biomeChecked();
            if (BiomeCheck.firstDifference(job.biomeSamples, server) >= 0) {
                PipelineStats.biomeMismatch(job, server);
                CHUNKS.remove(key, chunk);
                TABLE.discardReady(dimension, x, z);
                SPECULATIVE_READY.remove(key);
                return null;
            }
        }
        long started = System.nanoTime();
        try {
            generator.recreateStructures(chunk, x, z);
        } catch (RuntimeException failed) {
            CHUNKS.remove(key, chunk);
            ChunkSteward.LOGGER.warn("pipeline install failed at {}, {} in {}", x, z, dimension, failed);
            return null;
        }
        if (!CHUNKS.remove(key, chunk)) {
            return null;
        }
        TABLE.takeReady(dimension, x, z);
        SPECULATIVE_READY.remove(key);
        noteInstall(System.nanoTime() - started);
        PipelineStats.installed(job);
        return chunk;
    }

    private static void noteInstall(long nanos) {
        double ms = nanos / 1.0e6;
        if (!installPrimed) {
            installEma = ms;
            installPrimed = true;
        } else {
            installEma += 0.2 * (ms - installEma);
        }
    }

    private static void ensureGenerators() {
        PipelinePasses.ensureLoaded();
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (!serving(world) && !listedPassed(world)) {
                continue;
            }
            if (world.provider.getDimension() == pausedDimension) {
                continue;
            }
            IChunkGenerator main = PipelineGenerators.of(world);
            if (!PipelineGenerators.supported(main) || !PipelineGenerators.listed(main)) {
                noteUnsupported(main);
                continue;
            }
            int dimension = world.provider.getDimension();
            for (int slot = 0; slot < GENERATORS.size(); slot++) {
                if (GENERATORS.get(slot).containsKey(dimension)) {
                    continue;
                }
                PipelineMarker.constructing(true);
                BiomeProvider biome;
                IChunkGenerator copy;
                try {
                    biome = PipelineGenerators.copyBiome(world);
                    copy = PipelineGenerators.copy(world, main, biome);
                } finally {
                    PipelineMarker.constructing(false);
                }
                if (biome == null || copy == null) {
                    noteUnsupported(main);
                    break;
                }
                String different = BiomeCheck.compareProviders(world.getBiomeProvider(), biome);
                if (different != null && ChunkStewardConfig.pipeline.biomeSnapshot) {
                    ChunkSteward.LOGGER.warn("pipeline: worker {}'s own copy of the biome provider {} differs from the server's "
                            + "in dimension {} ({}); workers use biome snapshots from the server's provider instead",
                            slot, biome.getClass().getName(), dimension, different);
                } else if (different != null) {
                    ChunkSteward.LOGGER.error("pipeline off for dimension {}: the worker's copy of the biome provider {} does "
                            + "not give the server's biomes ({}). The generator stays on the server thread.", dimension,
                            biome.getClass().getName(), different);
                    noteUnsupported(main);
                    break;
                }
                if (different == null) {
                    ChunkSteward.LOGGER.info("pipeline: worker {} biome provider {} matches the server's in dimension {}", slot,
                            biome.getClass().getSimpleName(), dimension);
                }
                GENERATORS.get(slot).put(dimension, copy);
                BIOMES.get(slot).put(dimension, biome);
            }
        }
    }

    /** Listed and supported, including while the cap is 0 or verify has this dimension paused. */
    private static boolean listedPassed(World world) {
        IChunkGenerator generator = PipelineGenerators.of(world);
        return PipelineGenerators.supported(generator) && PipelineGenerators.listed(generator);
    }

    private static void noteUnsupported(IChunkGenerator generator) {
        if (loggedUnsupported || generator == null || PipelineGenerators.supported(generator)) {
            return;
        }
        if (!PipelineGenerators.listed(generator)) {
            return;
        }
        loggedUnsupported = true;
        ChunkSteward.LOGGER.info("pipeline is not generating {}; no copy for this generator",
                generator.getClass().getName());
    }

    private static void rank() {
        if (!run || GUARD.cap() <= 0) {
            snapshot = NO_JOBS;
            CURSOR.set(0);
            return;
        }
        List<Scored> found = new ArrayList<Scored>();
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (!serving(world)) {
                continue;
            }
            int dimension = world.provider.getDimension();
            List<PlayerChunkMapEntry> entries = ((AccessorPlayerChunkMap) world.getPlayerChunkMap())
                    .chunksteward$getEntriesWithoutChunks();
            for (PlayerChunkMapEntry entry : entries) {
                if (entry.getChunk() != null || ((AccessorPlayerChunkMapEntry) entry).chunksteward$loading()) {
                    continue;
                }
                double score = DirectionalOrder.demandScore(entry);
                if (ChunkThrottle.urgent(entry)) {
                    score -= 1.0e18;
                }
                found.add(new Scored(dimension, entry.getPos().x, entry.getPos().z, score));
            }
        }
        found.sort(Comparator.comparingDouble(scored -> scored.score));
        int limit = Math.max(1, ChunkStewardConfig.pipeline.maxInFlight);
        int players = Math.min(found.size(), limit);
        Set<PipelineKey> keep = new HashSet<PipelineKey>();
        List<PipelineJob> ranked = new ArrayList<PipelineJob>(limit);
        for (int i = 0; i < players; i++) {
            Scored scored = found.get(i);
            ranked.add(enqueue(scored.dimension, scored.x, scored.z, false));
            keep.add(new PipelineKey(scored.dimension, scored.x, scored.z));
        }
        if (ChunkStewardConfig.pipeline.speculative && players < limit) {
            List<int[]> extra = new ArrayList<int[]>();
            int room = limit - players;
            IdlePreload.collectSpeculative(extra, room);
            if (extra.size() < room) {
                Stage2Pregen.collectSpeculative(extra, room - extra.size());
            }
            int kept = PipelineRank.speculativeKept(players, extra.size(), limit);
            for (int i = 0; i < extra.size() && ranked.size() < players + kept; i++) {
                int[] target = extra.get(i);
                PipelineKey key = new PipelineKey(target[0], target[1], target[2]);
                if (!keep.add(key)) {
                    continue;
                }
                ranked.add(enqueue(target[0], target[1], target[2], true));
            }
        }
        TABLE.dropQueuedExcept(keep);
        snapshot = ranked.toArray(NO_JOBS);
        takeBiomeSnapshots(snapshot);
        CURSOR.set(0);
    }

    /** True while a ranked job still waits for its biome snapshot, so idle workers look again soon. */
    private static volatile boolean snapshotsPending;

    /**
     * Server thread. The ranked jobs in order get a {@link BiomeSnapshot} from the server's own biome provider, within
     * {@code pipeline.biomeSnapshotMs} (at least one per tick); a worker does not start a job that has none.
     */
    private static void takeBiomeSnapshots(PipelineJob[] ranked) {
        if (!ChunkStewardConfig.pipeline.biomeSnapshot) {
            snapshotsPending = false;
            return;
        }
        long deadline = System.nanoTime() + ChunkStewardConfig.pipeline.biomeSnapshotMs * 1_000_000L;
        int radius = ChunkStewardConfig.pipeline.biomeSnapshotRadius;
        boolean first = true;
        boolean pending = false;
        for (PipelineJob job : ranked) {
            if (job.biomeSnapshot != null || job.state.get() != PipelineJob.QUEUED) {
                continue;
            }
            if (!first && System.nanoTime() > deadline) {
                pending = true;
                break;
            }
            WorldServer world = DimensionManager.getWorld(job.dimension);
            if (world == null) {
                continue;
            }
            first = false;
            long started = System.nanoTime();
            try {
                job.biomeSnapshot = BiomeSnapshot.take(world.getBiomeProvider(), job.x, job.z, radius);
                PipelineStats.snapshotTaken(System.nanoTime() - started);
            } catch (RuntimeException failed) {
                ChunkSteward.LOGGER.warn("pipeline biome snapshot failed at {}, {} in {}", job.x, job.z, job.dimension, failed);
            }
        }
        snapshotsPending = pending;
    }

    private static PipelineJob enqueue(int dimension, int x, int z, boolean speculative) {
        PipelineJob job = TABLE.submit(dimension, x, z);
        job.speculative = speculative;
        if (!speculative) {
            SPECULATIVE_READY.remove(new PipelineKey(dimension, x, z));
        }
        if (job.context == null) {
            WorldServer world = DimensionManager.getWorld(dimension);
            if (world != null) {
                job.context = PipelinePreparers.prepare(world, x, z);
            }
        }
        return job;
    }

    /** Drops the oldest speculative ready chunk so a player job can take its place. */
    private static boolean evictOldestSpeculative() {
        for (int i = 0; i < SPECULATIVE_READY.size(); i++) {
            PipelineKey key = SPECULATIVE_READY.pollFirst();
            if (key == null) {
                return false;
            }
            PipelineJob job = TABLE.readyAt(key.dimension, key.x, key.z);
            if (job == null || !job.speculative) {
                continue;
            }
            CHUNKS.remove(key);
            TABLE.discardReady(key.dimension, key.x, key.z);
            return true;
        }
        return false;
    }

    private static void watchdog() {
        long limit = ChunkStewardConfig.pipeline.jobTimeoutSeconds * 1_000_000_000L;
        long now = System.nanoTime();
        for (PipelineJob job : TABLE.claimedByWorkers()) {
            if (job.claimedNanos == 0L || now - job.claimedNanos < limit) {
                continue;
            }
            if (!TABLE.steal(job)) {
                continue;
            }
            CHUNKS.remove(new PipelineKey(job.dimension, job.x, job.z));
            if (job.workerSlot >= 0 && job.workerSlot < retire.length) {
                retire[job.workerSlot] = true;
            }
            TABLE.failed.incrementAndGet();
            noteFailure(job);
        }
    }

    private static void noteFailure(PipelineJob job) {
        WorldServer world = DimensionManager.getWorld(job.dimension);
        IChunkGenerator generator = world == null ? null : PipelineGenerators.of(world);
        if (generator == null) {
            return;
        }
        String className = generator.getClass().getName();
        int count = FAILURES.merge(className, 1, Integer::sum);
        if (count >= ChunkStewardConfig.pipeline.maxFailures) {
            disallow(className);
            ChunkSteward.LOGGER.warn("pipeline disabled {} after {} failures", className, count);
        }
    }

    private static void replaceRetired() {
        for (int slot = 0; slot < threads.length; slot++) {
            if (!retire[slot] || threads[slot].isAlive()) {
                continue;
            }
            retire[slot] = false;
            threads[slot] = startWorker(slot);
        }
    }

    private static void dropChunks(int dimension) {
        for (PipelineKey key : new ArrayList<PipelineKey>(CHUNKS.keySet())) {
            if (key.dimension == dimension) {
                CHUNKS.remove(key);
            }
        }
    }

    private static void checkLoli() {
        if (loliBlocked) {
            return;
        }
        try {
            java.nio.file.Path cfg = Loader.instance().getConfigDir().toPath().resolve("loliasm.cfg");
            if (!Files.isRegularFile(cfg)) {
                return;
            }
            String text = new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8);
            if (text.contains("makeEventsSingletons=true")) {
                loliBlocked = true;
                ChunkSteward.LOGGER.warn("pipeline off: LoliASM makeEventsSingletons is true");
            }
        } catch (IOException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not read loliasm.cfg", failed);
        }
    }

    private static Thread startWorker(int slot) {
        Thread thread = new Thread(new Worker(slot), "chunksteward-terrain-" + slot);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.setContextClassLoader(ChunkSteward.class.getClassLoader());
        thread.start();
        return thread;
    }

    private static final class Scored {
        final int dimension;
        final int x;
        final int z;
        final double score;

        Scored(int dimension, int x, int z, double score) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
            this.score = score;
        }
    }

    private static final class Worker implements Runnable {
        private final int slot;

        Worker(int slot) {
            this.slot = slot;
        }

        @Override
        public void run() {
            while (run && (slot >= retire.length || !retire[slot])) {
                PipelineJob[] jobs = snapshot;
                int cap = GUARD.cap();
                if (slot >= cap || jobs.length == 0) {
                    if (slot < cap && runFeature()) {
                        continue;
                    }
                    LockSupport.parkNanos(FeatureLane.hasQueued() || snapshotsPending ? 1_000_000L : 50_000_000L);
                    continue;
                }
                int index = CURSOR.getAndIncrement();
                if (index < 0 || index >= jobs.length) {
                    // Every ranked terrain job is claimed: staged populate may use this worker.
                    if (runFeature()) {
                        continue;
                    }
                    LockSupport.parkNanos(FeatureLane.hasQueued() || snapshotsPending ? 1_000_000L : 50_000_000L);
                    continue;
                }
                PipelineJob job = jobs[index];
                if (job.biomeSnapshot == null && ChunkStewardConfig.pipeline.biomeSnapshot) {
                    // Its biomes are taken on the server thread at the start of the tick; the next ranking has them.
                    continue;
                }
                if (TABLE.readyCount() >= ChunkStewardConfig.pipeline.maxReady
                        && (job.speculative || !evictOldestSpeculative())) {
                    LockSupport.parkNanos(50_000_000L);
                    continue;
                }
                if (!TABLE.claimWorker(job)) {
                    continue;
                }
                job.claimedNanos = System.nanoTime();
                job.workerSlot = slot;
                PipelineStats.claimed(job);
                generate(job);
            }
        }

        /** Runs one staged populate job when one is waiting. */
        private boolean runFeature() {
            FeatureJob job = FeatureLane.takeForWorker();
            if (job == null) {
                return false;
            }
            long started = System.nanoTime();
            FeatureLane.execute(job, slot);
            PipelineStats.feature(slot, job, System.nanoTime() - started);
            return true;
        }

        private void generate(PipelineJob job) {
            IChunkGenerator generator = GENERATORS.get(slot).get(job.dimension);
            BiomeProvider biome = BIOMES.get(slot).get(job.dimension);
            WorldServer world = DimensionManager.getWorld(job.dimension);
            if (generator == null || biome == null || world == null) {
                TABLE.fail(job);
                noteFailure(job);
                return;
            }
            Chunk[] held = new Chunk[1];
            long started = System.nanoTime();
            boolean ok = false;
            PipelineMarker.set(true, false);
            BiomeProvider used = job.biomeSnapshot != null ? new SnapshotBiomeProvider(job.biomeSnapshot, biome) : biome;
            PipelineMarker.biome(used, job.dimension);
            PipelineMarker.context(job.context);
            try {
                BedrockShim.run(world, () -> held[0] = generator.generateChunk(job.x, job.z));
                if (held[0] == null) {
                    throw new IllegalStateException("generateChunk returned null");
                }
                PipelineKey key = new PipelineKey(job.dimension, job.x, job.z);
                CHUNKS.put(key, held[0]);
                job.doneNanos = System.nanoTime();
                if (ChunkStewardConfig.pipeline.biomeCheck && job.biomeSnapshot == null) {
                    job.biomeSamples = BiomeCheck.sample(used, job.x, job.z);
                }
                ok = true;
                if (!TABLE.publish(job)) {
                    CHUNKS.remove(key, held[0]);
                } else if (job.speculative) {
                    SPECULATIVE_READY.add(key);
                }
            } catch (Throwable failed) {
                TABLE.fail(job);
                noteFailure(job);
                ChunkSteward.LOGGER.warn("pipeline worker failed at {}, {} in {}", job.x, job.z, job.dimension, failed);
            } finally {
                PipelineMarker.clear();
                PipelineStats.generated(slot, job, System.nanoTime() - started, ok);
            }
        }
    }
}
