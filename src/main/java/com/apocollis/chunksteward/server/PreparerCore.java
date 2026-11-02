package com.apocollis.chunksteward.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The part of {@link PipelinePreparers} that needs no Minecraft types, so it can be tested. One preparer cannot break
 * the rest: a {@code prepare} that throws leaves a gap in the contexts and marks the job failed for that preparer (the
 * worker chunk is then discarded, not installed unprepared); an {@code isStillValid} that throws discards too. Each
 * discard is counted against the preparer's full class name so the status line can name it.
 */
final class PreparerCore {
    /** One preparer bound to a job: the world and position are closed over by the adapter. */
    interface Slot {
        String name();

        Object prepare();

        boolean valid(Object context);
    }

    /** Where the one-time log lines go. Each method is called at most once per preparer name. */
    interface Reporter {
        void prepareThrew(String name, Throwable error);

        void validThrew(String name, Throwable error);

        void firstDiscard(String name);
    }

    /** What {@link #prepare} produced: the contexts handed to the worker, and a bit per preparer whose prepare threw. */
    static final class Prepared {
        final Object[] contexts;
        final long failed;

        Prepared(Object[] contexts, long failed) {
            this.contexts = contexts;
            this.failed = failed;
        }
    }

    private static final ConcurrentHashMap<String, AtomicLong> DISCARDS = new ConcurrentHashMap<String, AtomicLong>();
    private static final Set<String> LOGGED_PREPARE = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_VALID = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_FIRST = ConcurrentHashMap.newKeySet();

    private PreparerCore() {
    }

    static Prepared prepare(List<? extends Slot> slots, Reporter reporter) {
        Object[] contexts = new Object[slots.size()];
        long failed = 0L;
        for (int i = 0; i < contexts.length; i++) {
            Slot slot = slots.get(i);
            try {
                contexts[i] = slot.prepare();
            } catch (Throwable error) {
                if (LOGGED_PREPARE.add(slot.name())) {
                    reporter.prepareThrew(slot.name(), error);
                }
                if (i < Long.SIZE) {
                    failed |= 1L << i;
                }
            }
        }
        return new Prepared(contexts, failed);
    }

    static boolean valid(List<? extends Slot> slots, Object[] contexts, long failed, Reporter reporter) {
        int n = Math.min(slots.size(), contexts.length);
        for (int i = 0; i < n; i++) {
            Slot slot = slots.get(i);
            boolean prepareFailed = i < Long.SIZE && (failed & 1L << i) != 0L;
            boolean ok;
            if (prepareFailed) {
                ok = false;
            } else if (contexts[i] == null) {
                continue;
            } else {
                try {
                    ok = slot.valid(contexts[i]);
                } catch (Throwable error) {
                    if (LOGGED_VALID.add(slot.name())) {
                        reporter.validThrew(slot.name(), error);
                    }
                    ok = false;
                }
            }
            if (!ok) {
                DISCARDS.computeIfAbsent(slot.name(), k -> new AtomicLong()).incrementAndGet();
                if (!prepareFailed && LOGGED_FIRST.add(slot.name())) {
                    reporter.firstDiscard(slot.name());
                }
                return false;
            }
        }
        return true;
    }

    /**
     * "preparer VillagePlateJobPreparer 12, Other 3": the non-zero discard counts since {@code since} (null = total).
     * A map passed as {@code since} is advanced to the current counts. Empty when nothing was discarded. The simple class
     * name is used unless two counted preparers share it, then the full name.
     */
    static String discardSummary(Map<String, Long> since) {
        TreeMap<String, Long> counts = new TreeMap<String, Long>();
        for (Map.Entry<String, AtomicLong> entry : DISCARDS.entrySet()) {
            long now = entry.getValue().get();
            long before = since == null ? 0L : since.getOrDefault(entry.getKey(), 0L);
            if (since != null) {
                since.put(entry.getKey(), now);
            }
            if (now > before) {
                counts.put(entry.getKey(), now - before);
            }
        }
        return format(counts);
    }

    static String format(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "";
        }
        Map<String, Integer> simpleUses = new HashMap<String, Integer>();
        for (String full : counts.keySet()) {
            simpleUses.merge(simple(full), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String label = simpleUses.get(simple(entry.getKey())) > 1 ? entry.getKey() : simple(entry.getKey());
            parts.add(label + " " + entry.getValue());
        }
        Collections.sort(parts);
        return "preparer " + String.join(", ", parts);
    }

    private static String simple(String full) {
        int dot = full.lastIndexOf('.');
        return dot < 0 ? full : full.substring(dot + 1);
    }

    /** Status text to append after the discard reasons: "; preparer X 12", or nothing. */
    static String statusSuffix(Map<String, Long> since) {
        String summary = discardSummary(since);
        return summary.isEmpty() ? "" : "; " + summary;
    }

    static void resetForTest() {
        DISCARDS.clear();
        LOGGED_PREPARE.clear();
        LOGGED_VALID.clear();
        LOGGED_FIRST.clear();
    }
}
