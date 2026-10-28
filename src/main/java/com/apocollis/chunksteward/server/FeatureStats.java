package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counters for staged populate, shown in the status log: how many jobs went to a worker, how many fell back to the
 * server thread and why, how often a populate could not be staged at all, how long jobs ran and waited, and which mod
 * class was behind the aborts. Counted per status interval and since the session started.
 */
public final class FeatureStats {
    static final int SUBMITTED = 0;
    static final int WORKER_OK = 1;
    static final int APPLIED = 2;
    static final int INLINE_TILE = 3;
    static final int INLINE_WINDOW = 4;
    static final int INLINE_UNSUPPORTED = 5;
    static final int INLINE_TIMEOUT = 6;
    static final int INLINE_ERROR = 7;
    static final int DECLINED_QUEUE = 8;
    static final int DECLINED_UNLOADED = 9;
    static final int DECLINED_BUSY = 10;
    /** A mod-generator segment that ran on a worker and was applied. */
    static final int GEN_STAGED = 11;
    /** A mod-generator segment that ran on the server thread (demoted or server-only generators, or a fallback). */
    static final int GEN_INLINE = 12;
    static final int GEN_DEMOTED = 13;
    /** A second populate of a chunk object that already populated, blocked. */
    static final int REPEAT_BLOCKED = 14;
    private static final int COUNTERS = 15;

    private static final AtomicLongArray INTERVAL = new AtomicLongArray(COUNTERS);
    private static final AtomicLongArray TOTAL = new AtomicLongArray(COUNTERS);
    private static final AtomicLong WORK_NANOS = new AtomicLong();
    private static final AtomicLong WORK_JOBS = new AtomicLong();
    private static final AtomicLong WAIT_NANOS = new AtomicLong();
    private static final AtomicLong WAIT_JOBS = new AtomicLong();
    private static final Map<String, long[]> CULPRITS = new HashMap<String, long[]>();
    private static final Map<String, long[]> GEN_WORK = new HashMap<String, long[]>();
    private static final Set<String> SEEN = new HashSet<String>();

    private FeatureStats() {
    }

    static void count(int id) {
        INTERVAL.incrementAndGet(id);
        TOTAL.incrementAndGet(id);
    }

    static long total(int id) {
        return TOTAL.get(id);
    }

    static void work(long nanos) {
        WORK_NANOS.addAndGet(nanos);
        WORK_JOBS.incrementAndGet();
    }

    static void waited(long nanos) {
        WAIT_NANOS.addAndGet(nanos);
        WAIT_JOBS.incrementAndGet();
    }

    /** A mod class that caused an abort or an error; reported by count. */
    static void culprit(String name) {
        if (name == null) {
            return;
        }
        synchronized (CULPRITS) {
            long[] cell = CULPRITS.get(name);
            if (cell == null) {
                CULPRITS.put(name, cell = new long[1]);
            }
            cell[0]++;
        }
    }

    /** Worker time a mod generator class used this interval. */
    static void generatorWork(String name, long nanos) {
        synchronized (GEN_WORK) {
            long[] cell = GEN_WORK.get(name);
            if (cell == null) {
                GEN_WORK.put(name, cell = new long[2]);
            }
            cell[0] += nanos;
            cell[1]++;
        }
    }

    /** True the first time this key is seen in the session; used to log each distinct problem once. */
    static boolean firstSeen(String key) {
        synchronized (SEEN) {
            return SEEN.size() < 80 && SEEN.add(key);
        }
    }

    /** The counter a fallback reason belongs to. */
    static int categoryOf(String reason) {
        if (reason == null) {
            return INLINE_ERROR;
        }
        if (reason.startsWith("tile entity")) {
            return INLINE_TILE;
        }
        if (reason.startsWith("read outside") || reason.startsWith("write outside")) {
            return INLINE_WINDOW;
        }
        if (reason.startsWith("unsupported") || reason.startsWith("direct chunk write")) {
            return INLINE_UNSUPPORTED;
        }
        return INLINE_ERROR;
    }

    /**
     * The first stack frame outside Minecraft, Forge, Chunk Steward and the JDK: the mod code that made the call the
     * worker could not allow. Null when every frame is one of those.
     */
    static String culpritOf(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("net.minecraft.") || cls.startsWith("net.minecraftforge.")
                    || cls.startsWith("com.apocollis.chunksteward.") || cls.startsWith("java.")
                    || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("org.spongepowered.")
                    || cls.startsWith("com.google.")) {
                continue;
            }
            int dollar = cls.indexOf('$');
            return dollar > 0 ? cls.substring(0, dollar) : cls;
        }
        return null;
    }

    /** One status-log fragment for the interval that ends now, and a reset of the interval counters. */
    public static String reportAndReset() {
        if (!ChunkStewardConfig.features.enabled) {
            return "staged populate off";
        }
        long[] n = new long[COUNTERS];
        for (int i = 0; i < COUNTERS; i++) {
            n[i] = INTERVAL.getAndSet(i, 0L);
        }
        long workJobs = WORK_JOBS.getAndSet(0L);
        long workNanos = WORK_NANOS.getAndSet(0L);
        long waitJobs = WAIT_JOBS.getAndSet(0L);
        long waitNanos = WAIT_NANOS.getAndSet(0L);
        long inline = n[INLINE_TILE] + n[INLINE_WINDOW] + n[INLINE_UNSUPPORTED] + n[INLINE_TIMEOUT] + n[INLINE_ERROR];
        long declined = n[DECLINED_QUEUE] + n[DECLINED_UNLOADED] + n[DECLINED_BUSY];
        StringBuilder out = new StringBuilder("staged populate: queued ").append(FeatureLane.queued())
                .append(", submitted ").append(n[SUBMITTED])
                .append(", on worker ").append(n[WORKER_OK])
                .append(", applied ").append(n[APPLIED])
                .append(", inline ").append(inline)
                .append(" (tile entity ").append(n[INLINE_TILE])
                .append(", outside window ").append(n[INLINE_WINDOW])
                .append(", unsupported ").append(n[INLINE_UNSUPPORTED])
                .append(", timeout ").append(n[INLINE_TIMEOUT])
                .append(", error ").append(n[INLINE_ERROR])
                .append("), repeat populates blocked ").append(n[REPEAT_BLOCKED])
                .append(", not staged ").append(declined)
                .append(" (queue full ").append(n[DECLINED_QUEUE])
                .append(", chunk not loaded ").append(n[DECLINED_UNLOADED])
                .append(", busy ").append(n[DECLINED_BUSY]).append(')');
        if (ChunkStewardConfig.features.modGenerators) {
            out.append("; mod generators staged ").append(n[GEN_STAGED])
                    .append(", inline ").append(n[GEN_INLINE])
                    .append(", demoted ").append(n[GEN_DEMOTED])
                    .append(" (").append(GeneratorPolicy.demotedCount()).append(" in session)");
        }
        out.append("; worker avg ").append(avgMs(workNanos, workJobs))
                .append(" ms over ").append(workJobs)
                .append(", wait avg ").append(avgMs(waitNanos, waitJobs)).append(" ms over ").append(waitJobs)
                .append("; session: submitted ").append(TOTAL.get(SUBMITTED))
                .append(", on worker ").append(TOTAL.get(WORKER_OK))
                .append(", inline ").append(TOTAL.get(INLINE_TILE) + TOTAL.get(INLINE_WINDOW)
                        + TOTAL.get(INLINE_UNSUPPORTED) + TOTAL.get(INLINE_TIMEOUT) + TOTAL.get(INLINE_ERROR));
        String culprits = top(CULPRITS, 3, false);
        if (!culprits.isEmpty()) {
            out.append("; aborts by class (session) ").append(culprits);
        }
        String generators = top(GEN_WORK, 3, true);
        if (!generators.isEmpty()) {
            out.append("; worker time by generator ").append(generators);
        }
        synchronized (GEN_WORK) {
            GEN_WORK.clear();
        }
        return out.toString();
    }

    private static String avgMs(long nanos, long count) {
        return count == 0 ? "-" : String.format("%.1f", nanos / 1.0e6 / count);
    }

    /** The {@code limit} largest entries of a map of long cells, as "name x count" or "name ms". */
    private static String top(final Map<String, long[]> map, int limit, final boolean millis) {
        List<Map.Entry<String, long[]>> rows;
        synchronized (map) {
            rows = new ArrayList<Map.Entry<String, long[]>>(map.size());
            for (Map.Entry<String, long[]> entry : map.entrySet()) {
                rows.add(new java.util.AbstractMap.SimpleEntry<String, long[]>(entry.getKey(), entry.getValue().clone()));
            }
        }
        Collections.sort(rows, new java.util.Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            if (i > 0) {
                out.append(", ");
            }
            Map.Entry<String, long[]> row = rows.get(i);
            out.append(shortName(row.getKey())).append(' ');
            if (millis) {
                out.append(String.format("%.0f ms/%d", row.getValue()[0] / 1.0e6, row.getValue()[1]));
            } else {
                out.append('x').append(row.getValue()[0]);
            }
        }
        return out.toString();
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        int second = dot > 0 ? className.lastIndexOf('.', dot - 1) : -1;
        return second < 0 ? simple : className.substring(second + 1);
    }
}
