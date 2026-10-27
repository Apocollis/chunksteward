package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.locks.LockSupport;

/**
 * Terrain pipeline infrastructure. This build does not generate chunks. Workers start only when {@code pipeline.enabled}
 * is on, the session kill switch is clear, and {@code allowedGenerators} lists a class. With the default empty list
 * there are no threads, no samples, and no change to loading.
 */
final class TerrainPipeline {
    private static final Object LOCK = new Object();
    private static final Thread[] NO_THREADS = new Thread[0];
    private static final int[] NO_RANKS = new int[0];

    private static final PipelineTable TABLE = new PipelineTable();
    private static final PipelineCpuGuard GUARD = new PipelineCpuGuard();

    private static volatile boolean killed;
    private static volatile boolean run;
    private static volatile int[] ranks = NO_RANKS;
    private static Thread[] threads = NO_THREADS;

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

    /**
     * True only while workers are producing terrain. This build never generates, so this stays false even when stub
     * workers are parked.
     */
    static boolean isPipelineActive() {
        return false;
    }

    static PipelineTable table() {
        return TABLE;
    }

    static void reset() {
        synchronized (LOCK) {
            killed = false;
            stopWorkers();
            TABLE.clear();
            ranks = NO_RANKS;
            GUARD.reset(1);
            sampling = false;
            wallAccum = 0L;
            ratioEma = 0.0;
            ratioPrimed = false;
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
            if (!run || TickMonitor.savePaused()) {
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

    /** Ranked indexes, highest score first, capped at {@code maxInFlight}. Nothing publishes scores in this build. */
    static int[] publishScores(int[] scores) {
        int[] ranked = PipelineRank.top(scores, Math.max(1, ChunkStewardConfig.pipeline.maxInFlight));
        ranks = ranked;
        return ranked;
    }

    static int[] ranks() {
        return ranks;
    }

    static String summary() {
        ChunkStewardConfig.Pipeline pipeline = ChunkStewardConfig.pipeline;
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
                + ", generation not started, queued " + TABLE.count(PipelineJob.QUEUED)
                + ", in flight " + (TABLE.count(PipelineJob.CLAIMED_WORKER) + TABLE.count(PipelineJob.CLAIMED_MAIN))
                + ", ready " + TABLE.readyCount()
                + ", stolen " + TABLE.stolen.get()
                + ", installed " + TABLE.installed.get()
                + ", discarded " + TABLE.discarded.get()
                + ", failed " + TABLE.failed.get();
    }

    private static boolean engaged() {
        ChunkStewardConfig.Pipeline pipeline = ChunkStewardConfig.pipeline;
        return PipelineGate.engages(pipeline.enabled, killed, PipelineGate.hasGenerator(pipeline.allowedGenerators));
    }

    private static void startIfEngaged() {
        if (run || !engaged()) {
            return;
        }
        prepareSignal();
        int count = PipelineGate.resolveWorkers(ChunkStewardConfig.pipeline.workers,
                Runtime.getRuntime().availableProcessors());
        GUARD.reset(count);
        run = true;
        Thread[] started = new Thread[count];
        ClassLoader loader = ChunkSteward.class.getClassLoader();
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(new Worker(), "chunksteward-terrain-" + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setContextClassLoader(loader);
            started[i] = thread;
        }
        threads = started;
        for (Thread thread : started) {
            thread.start();
        }
        ChunkSteward.LOGGER.info("pipeline workers started ({}); this build does not generate off-thread", count);
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

    /** Parks until the session stops it. It does not call a generator. */
    private static final class Worker implements Runnable {
        @Override
        public void run() {
            while (run) {
                LockSupport.parkNanos(50_000_000L);
            }
        }
    }
}
