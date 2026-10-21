package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * While {@code WorldServer.updateBlocks} runs its random block ticks, a request for a chunk that is not loaded gets an
 * {@link StewardEmptyChunk} instead of generating it: reads see air and writes are dropped. Lava and fire ticks, grass spread
 * and the like reach a few blocks past their own chunk; in 1.12 that used to generate the neighbour synchronously,
 * beyond the view distance, and kept pushing the generated area outward. Only the random-tick window is affected.
 */
public final class RandomTickGuard {
    private static boolean active;
    private static int sampledThisTick;
    private static long statGuarded;
    private static final Map<String, long[]> SOURCES = new HashMap<>();

    private static final StackWalker WALKER = StackWalker.getInstance();

    private RandomTickGuard() {
    }

    public static void begin() {
        active = ChunkStewardConfig.randomTicks.guardEnabled && Thread.currentThread() == TickMonitor.serverThread;
    }

    static boolean isActive() {
        return active;
    }

    public static void end() {
        active = false;
    }

    static void onTickStart() {
        active = false;
        sampledThisTick = 0;
    }

    /** The empty chunk to return for {@code (x, z)}, or {@code null} to let the provider run normally. */
    public static Chunk intercept(ChunkProviderServer provider, int x, int z) {
        if (!active || provider.loadedChunks.containsKey(ChunkPos.asLong(x, z))
                || !Dimensions.applies(provider.world.provider.getDimension())) {
            return null;
        }
        statGuarded++;
        if (GenMetrics.active() && sampledThisTick < 8) {
            sampledThisTick++;
            String source = sourceOfCall();
            long[] count = SOURCES.get(source);
            if (count == null) {
                count = new long[1];
                SOURCES.put(source, count);
            }
            count[0]++;
        }
        return new StewardEmptyChunk(provider.world, x, z);
    }

    /** First calling class that is not world or server plumbing: normally the block that is being ticked. */
    private static String sourceOfCall() {
        return WALKER.walk(frames -> frames.limit(40)
                .map(StackWalker.StackFrame::getClassName)
                .filter(name -> !name.startsWith("net.minecraft.world.") && !name.startsWith("net.minecraft.server.")
                        && !name.startsWith("com.apocollis.chunksteward.") && !name.startsWith("java.")
                        && !name.startsWith("jdk.") && !name.startsWith("sun.")
                        && !name.startsWith("org.spongepowered.") && !name.contains("$$Lambda"))
                .findFirst().orElse("unknown"));
    }

    static String reportAndReset() {
        StringBuilder sb = new StringBuilder("random-tick guard ").append(statGuarded);
        if (!SOURCES.isEmpty()) {
            List<Map.Entry<String, long[]>> top = SOURCES.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(3)
                    .collect(Collectors.toList());
            sb.append(" (sampled:");
            for (Map.Entry<String, long[]> e : top) {
                sb.append(' ').append(e.getKey()).append(' ').append(e.getValue()[0]);
            }
            sb.append(')');
        }
        statGuarded = 0L;
        SOURCES.clear();
        return sb.toString();
    }
}
