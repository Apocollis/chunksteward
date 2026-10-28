package com.apocollis.chunksteward.server;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tells Forge's {@code GameRegistry.generateWorld} loop (see {@code MixinGameRegistry}) which generators to run on this
 * thread. The loop is Forge's own, so the order and the per-generator random seeding stay exactly Forge's; a plan only
 * filters it. Three kinds:
 * <ul>
 * <li>probe: runs nothing, records which generators are eligible for a worker, in loop order;</li>
 * <li>worker or inline run of one segment: runs only the generators in that run of equal eligibility.</li>
 * </ul>
 * The eligibility list is a snapshot taken by the probe, so a generator demoted while a chunk is in progress does not
 * move the run boundaries.
 */
public final class GeneratorPlan {
    private static final ThreadLocal<GeneratorPlan> CURRENT = new ThreadLocal<GeneratorPlan>();
    private static final ThreadLocal<Class<?>> RUNNING = new ThreadLocal<Class<?>>();

    private final boolean probe;
    private final boolean worker;
    private final int segment;
    private final List<Boolean> eligible;
    private int index;

    private GeneratorPlan(boolean probe, boolean worker, int segment, List<Boolean> eligible) {
        this.probe = probe;
        this.worker = worker;
        this.segment = segment;
        this.eligible = eligible;
    }

    /** The generator class running on this thread inside a worker segment, or null. */
    static Class<?> running() {
        return RUNNING.get();
    }

    /** Eligibility of each generator in loop order, from a loop that runs none of them. Server thread. */
    static List<Boolean> probe(int x, int z, World world, IChunkGenerator generator) {
        List<Boolean> order = new ArrayList<Boolean>();
        GeneratorPlan plan = new GeneratorPlan(true, false, 0, order);
        CURRENT.set(plan);
        try {
            GameRegistry.generateWorld(x, z, world, generator, world.getChunkProvider());
        } finally {
            CURRENT.remove();
        }
        return order;
    }

    /** Runs one segment (a run of generators) on this thread: {@code worker} selects the worker flavour. */
    static void runSegment(List<Boolean> eligible, int segment, boolean worker, int x, int z, World world,
                           IChunkGenerator generator) {
        GeneratorPlan previous = CURRENT.get();
        CURRENT.set(new GeneratorPlan(false, worker, segment, eligible));
        try {
            GameRegistry.generateWorld(x, z, world, generator, world.getChunkProvider());
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * The body of the {@code generate} call inside {@code GameRegistry.generateWorld}. Without a plan the generator
     * simply runs (timed, and locked while staging is on); with one, only the plan's segment runs it.
     */
    public static void dispatch(IWorldGenerator generator, Random random, int chunkX, int chunkZ, World world,
                                IChunkGenerator chunkGenerator, IChunkProvider provider) {
        GeneratorPlan plan = CURRENT.get();
        Class<?> key = generator.getClass();
        if (plan == null) {
            if (!GeneratorPolicy.enabled()) {
                timed(key, generator, random, chunkX, chunkZ, world, chunkGenerator, provider);
                return;
            }
            ReentrantLock lock = GeneratorPolicy.lockFor(key);
            lock.lock();
            try {
                timed(key, generator, random, chunkX, chunkZ, world, chunkGenerator, provider);
            } finally {
                lock.unlock();
            }
            return;
        }
        int at = plan.index++;
        if (plan.probe) {
            plan.eligible.add(GeneratorPolicy.staged(key));
            return;
        }
        boolean inRange = at < plan.eligible.size();
        // A generator registered after the probe has no run of its own: the server thread runs it with the last run.
        boolean mine = inRange ? GeneratorSegments.runOf(plan.eligible, at) == plan.segment
                : !plan.worker && plan.segment == GeneratorSegments.runs(plan.eligible).size() - 1;
        if (!mine) {
            return;
        }
        ReentrantLock lock = GeneratorPolicy.lockFor(key);
        lock.lock();
        try {
            if (plan.worker) {
                RUNNING.set(key);
                long started = System.nanoTime();
                try {
                    generator.generate(random, chunkX, chunkZ, world, chunkGenerator, provider);
                } catch (FeatureAbort abort) {
                    throw abort;
                } catch (Throwable error) {
                    GeneratorPolicy.strike(key.getName(), String.valueOf(error));
                    throw error;
                } finally {
                    FeatureStats.generatorWork(key.getName(), System.nanoTime() - started);
                    RUNNING.remove();
                }
            } else {
                timed(key, generator, random, chunkX, chunkZ, world, chunkGenerator, provider);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void timed(Class<?> key, IWorldGenerator generator, Random random, int chunkX, int chunkZ,
                              World world, IChunkGenerator chunkGenerator, IChunkProvider provider) {
        if (!GenMetrics.active()) {
            generator.generate(random, chunkX, chunkZ, world, chunkGenerator, provider);
            return;
        }
        GenMetrics.enter(key);
        try {
            generator.generate(random, chunkX, chunkZ, world, chunkGenerator, provider);
        } finally {
            GenMetrics.exit(key);
        }
    }
}
