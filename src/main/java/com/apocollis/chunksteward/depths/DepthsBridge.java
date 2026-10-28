package com.apocollis.chunksteward.depths;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Depths Update does three things to a new chunk after the generator returns it, on the server thread: the deep fill
 * (deepslate, bedrock), the caves and underground rivers, and a new sky light map. A terrain worker calls the generator
 * directly, so none of that ran there and the server thread did it all at install. With {@code pipeline.depthsPass} the
 * worker does it ({@link DepthsPass}, a copy of Depths' handler against its own noise objects) and the server skips
 * Depths' handler for a chunk the worker finished ({@code MixinDepthsProvide}).
 *
 * <p>Nothing here names a Depths type, so this class loads in a pack without Depths. It switches on only when Depths
 * Update is loaded, its handler class is the build this was copied from (SHA-256) or listed in
 * {@code pipeline.extraDepthsClassHashes}, and the skip hook is on {@code ChunkProviderServer}. Otherwise the worker does
 * not run the pass and Depths runs on the server thread, as before.
 */
public final class DepthsBridge {
    private static final String HANDLER_CLASS = "/sayys/depthsupdate/mixin/MixinChunkProviderServer.class";
    /** Depths Update 1.12.2-1.0.0-a12. */
    private static final String KNOWN_HASH = "2d5df6d83f690ebe9c7f052133b4c74e914d70380ccfeb236f66366029c80625";
    private static final Map<Chunk, Boolean> DONE = Collections.synchronizedMap(new WeakHashMap<Chunk, Boolean>());
    private static volatile int state; // 0 unknown, 1 on, -1 off

    private DepthsBridge() {
    }

    /** True when workers run the Depths pass and the server skips its own. Decided once, at the first call. */
    public static boolean enabled() {
        int now = state;
        if (now == 0) {
            boolean on = ChunkStewardConfig.pipeline.depthsPass && decide();
            state = now = on ? 1 : -1;
        }
        return now > 0;
    }

    private static boolean decide() {
        if (!Loader.isModLoaded("depthsupdate")) {
            return false;
        }
        String reason = null;
        if (!classMatches()) {
            reason = "its handler class is not the build this was written against (pipeline.extraDepthsClassHashes)";
        } else if (!hookApplied()) {
            reason = "the hook that skips Depths' own pass did not apply";
        }
        if (reason != null) {
            ChunkSteward.LOGGER.warn("pipeline: Depths Update's chunk work stays on the server thread: {}", reason);
            return false;
        }
        ChunkSteward.LOGGER.info("pipeline: Depths Update's fill, caves, rivers and sky light run on the terrain workers");
        return true;
    }

    private static boolean classMatches() {
        try (java.io.InputStream in = DepthsBridge.class.getResourceAsStream(HANDLER_CLASS)) {
            if (in == null) {
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            String hash = hex.toString();
            if (KNOWN_HASH.equals(hash)) {
                return true;
            }
            for (String extra : ChunkStewardConfig.pipeline.extraDepthsClassHashes) {
                if (extra != null && extra.trim().equalsIgnoreCase(hash)) {
                    return true;
                }
            }
            return false;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failed) {
            return false;
        }
    }

    /** The skip handler is merged into {@code ChunkProviderServer} only if the injection applied. */
    private static boolean hookApplied() {
        for (Method method : ChunkProviderServer.class.getDeclaredMethods()) {
            if (method.getName().contains("chunksteward$depthsSkip")) {
                return true;
            }
        }
        return false;
    }

    /** Worker thread, around {@code generateChunk}: what Depths' handler sets up before the generator runs. */
    public static Object begin(WorldServer world, IChunkGenerator generator) {
        return DepthsPass.begin(world, generator);
    }

    /** Worker thread, in a finally after {@code generateChunk}. */
    public static void end(Object token) {
        DepthsPass.end(token);
    }

    /**
     * Worker thread, after {@code generateChunk} returned the chunk: the rest of Depths' handler. True when the chunk
     * needs nothing more from Depths (so the server skips it). An exception fails the job: a half-carved chunk is never
     * installed.
     */
    public static boolean finish(Object token, WorldServer world, Chunk chunk, int x, int z, int slot) {
        DepthsPass.finish(token, world, chunk, x, z, slot);
        DONE.put(chunk, Boolean.TRUE);
        return true;
    }

    /**
     * Server thread, head of Depths' handler: when a finished worker chunk is waiting, installs it through the generator
     * (which hands it over) and returns it so the handler is skipped. Null when Depths must run as usual: no finished
     * chunk, or the install fell back to the server thread (that chunk has had no Depths work).
     */
    public static Chunk tryInstall(IChunkGenerator generator, WorldServer world, int x, int z) {
        if (!enabled() || !TerrainPipeline.readyWithDepthsPass(world, x, z)) {
            return null;
        }
        Chunk chunk = generator.generateChunk(x, z);
        return chunk != null && DONE.containsKey(chunk) ? chunk : null;
    }
}
