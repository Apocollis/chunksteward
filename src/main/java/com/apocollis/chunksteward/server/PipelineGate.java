package com.apocollis.chunksteward.server;

/**
 * When the terrain pipeline is allowed to run. An empty generator list never engages, so a default install starts no
 * threads and does not change loading.
 */
final class PipelineGate {
    private PipelineGate() {
    }

    /** {@code max(1, min(2, processors - 2))}. Below four logical processors this is 1. */
    static int workerCount(int processors) {
        int cores = Math.max(1, processors);
        return Math.max(1, Math.min(2, cores - 2));
    }

    /** {@code configured <= 0} uses {@link #workerCount}. A set value stays in 1..2. */
    static int resolveWorkers(int configured, int processors) {
        if (configured <= 0) {
            return workerCount(processors);
        }
        return Math.max(1, Math.min(2, configured));
    }

    static boolean hasGenerator(String[] names) {
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The same array when {@code className} is already listed, otherwise a new array with it appended. */
    static String[] include(String[] names, String className) {
        if (className == null || className.isEmpty()) {
            return names;
        }
        int length = names == null ? 0 : names.length;
        for (int i = 0; i < length; i++) {
            String name = names[i];
            if (name != null && className.equals(name.trim())) {
                return names;
            }
        }
        String[] next = new String[length + 1];
        if (length > 0) {
            System.arraycopy(names, 0, next, 0, length);
        }
        next[length] = className;
        return next;
    }

    /** Config on, not killed this session, and at least one generator class listed. */
    static boolean engages(boolean enabled, boolean killed, boolean listed) {
        return enabled && !killed && listed;
    }
}
