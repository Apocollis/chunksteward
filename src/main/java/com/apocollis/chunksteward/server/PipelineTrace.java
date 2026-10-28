package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Optional per-chunk event trace of the generation pipeline ({@code logging.pipelineTrace}), written to
 * {@code logs/chunksteward-trace.csv}: one line per event, {@code ms,event,dimension,x,z,detail}. A background thread
 * writes; when it falls behind, events are dropped rather than slowing the game. Capped at 400000 lines per session.
 * Off costs one boolean read per event.
 */
final class PipelineTrace {
    private static final int MAX_LINES = 400_000;
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<String>(16384);
    private static final long START = System.nanoTime();
    private static volatile boolean started;
    private static volatile boolean failed;
    private static volatile int dropped;

    private PipelineTrace() {
    }

    static boolean enabled() {
        return ChunkStewardConfig.logging.pipelineTrace && !failed;
    }

    /** {@code kind} is a short event name; {@code detail} is free text without commas or newlines. */
    static void event(String kind, int dimension, int x, int z, String detail) {
        if (!enabled()) {
            return;
        }
        if (!started) {
            start();
        }
        long ms = (System.nanoTime() - START) / 1_000_000L;
        if (!QUEUE.offer(ms + "," + kind + "," + dimension + "," + x + "," + z + "," + (detail == null ? "" : detail))) {
            dropped++;
        }
    }

    static int dropped() {
        return dropped;
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                Path path = Paths.get("logs", "chunksteward-trace.csv");
                int lines = 0;
                try {
                    Files.createDirectories(path.getParent());
                    try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                        out.write("ms,event,dimension,x,z,detail\n");
                        while (lines < MAX_LINES) {
                            String line = QUEUE.poll(1, TimeUnit.SECONDS);
                            if (line == null) {
                                out.flush();
                                continue;
                            }
                            out.write(line);
                            out.write('\n');
                            lines++;
                        }
                    }
                    ChunkSteward.LOGGER.info("pipeline trace stopped at {} lines", MAX_LINES);
                } catch (IOException | InterruptedException error) {
                    ChunkSteward.LOGGER.warn("pipeline trace stopped", error);
                } finally {
                    failed = true;
                    QUEUE.clear();
                }
            }
        }, "chunksteward-trace");
        writer.setDaemon(true);
        writer.start();
        ChunkSteward.LOGGER.info("pipeline trace writing to logs/chunksteward-trace.csv");
    }
}
