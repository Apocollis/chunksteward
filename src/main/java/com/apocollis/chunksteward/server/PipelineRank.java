package com.apocollis.chunksteward.server;

import java.util.Arrays;

/** Highest score first, earlier index on a tie. The snapshot keeps at most {@code limit} entries. */
final class PipelineRank {
    private PipelineRank() {
    }

    static int[] top(int[] scores, int limit) {
        if (scores == null || scores.length == 0 || limit <= 0) {
            return new int[0];
        }
        Integer[] index = new Integer[scores.length];
        for (int i = 0; i < scores.length; i++) {
            index[i] = i;
        }
        Arrays.sort(index, (a, b) -> {
            int byScore = Integer.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        int n = Math.min(limit, index.length);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = index[i];
        }
        return out;
    }

    /** Speculative jobs fill only the window left after player jobs. */
    static int speculativeKept(int players, int speculative, int limit) {
        if (limit <= 0 || speculative <= 0) {
            return 0;
        }
        int room = limit - Math.min(Math.max(players, 0), limit);
        return Math.min(speculative, room);
    }
}
