package com.apocollis.chunksteward.server;

import java.util.ArrayList;
import java.util.List;

/** Pure helpers for splitting the mod world generators into runs; no Minecraft types, so they are unit tested. */
final class GeneratorSegments {
    private GeneratorSegments() {
    }

    /**
     * The runs of equal eligibility in generator order: {@code true} for a run of generators that may go to a worker,
     * {@code false} for a run that stays on the server thread. Running the runs one after the other keeps the order
     * Forge runs the generators in.
     */
    static List<Boolean> runs(List<Boolean> eligible) {
        List<Boolean> out = new ArrayList<Boolean>();
        for (int i = 0; i < eligible.size(); i++) {
            if (i == 0 || eligible.get(i).booleanValue() != eligible.get(i - 1).booleanValue()) {
                out.add(eligible.get(i));
            }
        }
        return out;
    }

    /** The run the generator at {@code index} belongs to. */
    static int runOf(List<Boolean> eligible, int index) {
        int run = 0;
        for (int i = 1; i <= index; i++) {
            if (eligible.get(i).booleanValue() != eligible.get(i - 1).booleanValue()) {
                run++;
            }
        }
        return run;
    }

    /** True when {@code className} equals an entry, or an entry ending in a dot is a package prefix of it. */
    static boolean matches(String[] entries, String className) {
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith(".") ? className.startsWith(trimmed) : className.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
