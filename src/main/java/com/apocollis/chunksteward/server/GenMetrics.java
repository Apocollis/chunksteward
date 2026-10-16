package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.server.management.PlayerChunkMapEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Status-log metrics: exclusive time per generation stage (terrain, populate, light) and per {@code IWorldGenerator}
 * class, plus how long a player chunk map entry waits before its chunk is provided.
 *
 * <p>Stages nest (populate can generate a neighbour, which runs terrain and light), so a small stack charges each
 * nanosecond to the innermost stage only. World generator frames sit on the same stack and are reported as part of
 * populate. Collection runs only on the server thread and only while {@code statusIntervalSeconds > 0}; otherwise
 * every hook is one field read and a thread compare.
 */
public final class GenMetrics {
    public static final Object TERRAIN = "terrain";
    public static final Object POPULATE = "populate";
    public static final Object LIGHT = "light";
    /** The chunk generator's own populate call: decoration and structure paste, but not mod world generators. */
    public static final Object GEN_POPULATE = "generator populate";

    /** A structure paste or terrain map-gen frame, keyed by the runtime class. */
    public static final class Key {
        final boolean structure;
        final String label;

        Key(boolean structure, String label) {
            this.structure = structure;
            this.label = label;
        }
    }

    private static final ClassValue<Key> STRUCTURE_KEYS = new ClassValue<Key>() {
        @Override
        protected Key computeValue(Class<?> type) {
            return new Key(true, type.getName());
        }
    };

    private static final ClassValue<Key> MAPGEN_KEYS = new ClassValue<Key>() {
        @Override
        protected Key computeValue(Class<?> type) {
            return new Key(false, type.getName());
        }
    };

    /** The frame key for {@code MapGenStructure.generateStructure} on this class (one object per class). */
    public static Object structureKey(Class<?> type) {
        return STRUCTURE_KEYS.get(type);
    }

    /** The frame key for {@code MapGenBase.generate} (caves, ravines, structure layout) on this class. */
    public static Object mapGenKey(Class<?> type) {
        return MAPGEN_KEYS.get(type);
    }

    private static final int MAX_DEPTH = 64;
    private static final Object[] STACK = new Object[MAX_DEPTH];
    private static int depth;
    private static long segmentStart;

    /** key -> {nanos, calls}. Keys are the stage constants or IWorldGenerator classes. */
    private static final Map<Object, long[]> ACCUM = new HashMap<>();
    private static final Map<PlayerChunkMapEntry, Long> CREATED = new WeakHashMap<>();
    /** Cascades (a chunk generated inside a populate) by the innermost running world generator: key -> {count}. */
    private static final Map<Object, long[]> CASCADES = new HashMap<>();

    private static long waitSamples;
    private static long waitNanosTotal;
    private static long waitNanosMax;

    private GenMetrics() {
    }

    public static boolean active() {
        return ChunkStewardConfig.logging.statusIntervalSeconds > 0 && Thread.currentThread() == TickMonitor.serverThread;
    }

    public static void enter(Object key) {
        if (!active()) {
            return;
        }
        long now = System.nanoTime();
        if (depth > 0) {
            add(STACK[depth - 1], now - segmentStart, 0);
        }
        if (depth == MAX_DEPTH) {
            depth = 0;
            return;
        }
        STACK[depth++] = key;
        segmentStart = now;
        add(key, 0L, 1);
    }

    public static void exit(Object key) {
        if (depth == 0 || Thread.currentThread() != TickMonitor.serverThread) {
            return;
        }
        long now = System.nanoTime();
        // A frame whose exit was skipped (exception) is dropped here: unwind down to the matching key.
        int at = depth - 1;
        while (at >= 0 && STACK[at] != key) {
            at--;
        }
        if (at < 0) {
            return;
        }
        add(STACK[depth - 1], now - segmentStart, 0);
        while (depth > at) {
            STACK[--depth] = null;
        }
        segmentStart = now;
    }

    private static void add(Object key, long nanos, int calls) {
        long[] acc = ACCUM.get(key);
        if (acc == null) {
            acc = new long[2];
            ACCUM.put(key, acc);
        }
        acc[0] += nanos;
        acc[1] += calls;
    }

    /** A chunk was generated while another was populating; charge it to the world generator that caused it. */
    public static void noteCascade() {
        if (!active()) {
            return;
        }
        Object key = POPULATE;
        for (int i = depth - 1; i >= 0; i--) {
            if (STACK[i] instanceof Class) {
                key = STACK[i];
                break;
            }
        }
        long[] count = CASCADES.get(key);
        if (count == null) {
            count = new long[1];
            CASCADES.put(key, count);
        }
        count[0]++;
    }

    static void onTickStart() {
        // Generation never spans ticks; anything left is from a frame that threw.
        while (depth > 0) {
            STACK[--depth] = null;
        }
    }

    public static void onEntryCreated(PlayerChunkMapEntry entry) {
        if (active()) {
            CREATED.put(entry, System.nanoTime());
        }
    }

    static void onProvided(PlayerChunkMapEntry entry) {
        if (CREATED.isEmpty()) {
            return;
        }
        Long created = CREATED.remove(entry);
        if (created != null) {
            long wait = System.nanoTime() - created;
            waitSamples++;
            waitNanosTotal += wait;
            waitNanosMax = Math.max(waitNanosMax, wait);
        }
    }

    /** One-line summary of the interval, then reset. */
    static String reportAndReset() {
        long[] terrainExcl = ACCUM.getOrDefault(TERRAIN, new long[2]);
        long[] populateExcl = ACCUM.getOrDefault(POPULATE, new long[2]);
        long[] genPopulate = ACCUM.getOrDefault(GEN_POPULATE, new long[2]);
        long[] light = ACCUM.getOrDefault(LIGHT, new long[2]);
        long genNanos = 0L;
        long structNanos = 0L;
        long mapGenNanos = 0L;
        List<Map.Entry<Object, long[]>> gens = new ArrayList<>();
        List<Map.Entry<Object, long[]>> structures = new ArrayList<>();
        List<Map.Entry<Object, long[]>> mapGens = new ArrayList<>();
        for (Map.Entry<Object, long[]> e : ACCUM.entrySet()) {
            Object key = e.getKey();
            if (key instanceof Class) {
                gens.add(e);
                genNanos += e.getValue()[0];
            } else if (key instanceof Key) {
                if (((Key) key).structure) {
                    structures.add(e);
                    structNanos += e.getValue()[0];
                } else {
                    mapGens.add(e);
                    mapGenNanos += e.getValue()[0];
                }
            }
        }
        Comparator<Map.Entry<Object, long[]>> byTime = (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]);
        gens.sort(byTime);
        structures.sort(byTime);
        mapGens.sort(byTime);

        // Exclusive times: every nanosecond is charged to the innermost frame, so the parts add up to the stage.
        long populateTotal = populateExcl[0] + genPopulate[0] + genNanos + structNanos;
        long terrainTotal = terrainExcl[0] + mapGenNanos;
        StringBuilder sb = new StringBuilder();
        sb.append("stages: terrain ").append(ms(terrainTotal)).append(" ms/").append(terrainExcl[1])
                .append(" (map-gens ").append(ms(mapGenNanos)).append(" ms)")
                .append(", populate ").append(ms(populateTotal)).append(" ms/").append(populateExcl[1])
                .append(" (generator populate ").append(ms(genPopulate[0])).append(" ms, structures ")
                .append(ms(structNanos)).append(" ms, world generators ").append(ms(genNanos))
                .append(" ms, other ").append(ms(populateExcl[0])).append(" ms)")
                .append(", light ").append(ms(light[0])).append(" ms/").append(light[1])
                .append("; wait avg ").append(waitSamples == 0 ? "-" : ms(waitNanosTotal / waitSamples))
                .append(" ms max ").append(waitSamples == 0 ? "-" : ms(waitNanosMax))
                .append(" ms over ").append(waitSamples).append(" chunks");
        int top = ChunkStewardConfig.logging.topGenerators;
        appendTop(sb, "top generators", gens, top);
        appendTop(sb, "structures", structures, top);
        appendTop(sb, "map-gens", mapGens, top);
        if (!CASCADES.isEmpty()) {
            List<Map.Entry<Object, long[]>> casc = new ArrayList<>(CASCADES.entrySet());
            casc.sort(byTime);
            sb.append("; cascades by generator:");
            for (int i = 0; i < Math.min(3, casc.size()); i++) {
                Object k = casc.get(i).getKey();
                sb.append(' ').append(k instanceof Class ? ((Class<?>) k).getName() : "(other populate)")
                        .append(' ').append(casc.get(i).getValue()[0]);
            }
        }
        CASCADES.clear();
        ACCUM.clear();
        waitSamples = waitNanosTotal = waitNanosMax = 0L;
        if (!active()) {
            CREATED.clear();
        }
        return sb.toString();
    }

    /** "; label: name X ms/N, ..." for the first {@code top} entries (class names or {@link Key} labels). */
    private static void appendTop(StringBuilder sb, String label, List<Map.Entry<Object, long[]>> entries, int top) {
        int n = Math.min(top, entries.size());
        if (n <= 0) {
            return;
        }
        sb.append("; ").append(label).append(':');
        for (int i = 0; i < n; i++) {
            Map.Entry<Object, long[]> e = entries.get(i);
            Object key = e.getKey();
            String name = key instanceof Class ? ((Class<?>) key).getName() : ((Key) key).label;
            sb.append(' ').append(name).append(' ').append(ms(e.getValue()[0])).append(" ms/")
                    .append(e.getValue()[1]);
            if (i + 1 < n) {
                sb.append(',');
            }
        }
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1.0e6);
    }
}
