package com.apocollis.chunksteward.rtg;

import com.apocollis.chunksteward.server.FeatureJob;
import com.apocollis.chunksteward.server.FeatureLane;
import com.apocollis.chunksteward.server.GeneratorChain;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.util.math.ChunkPos;
import rtg.api.world.RTGWorld;
import rtg.api.world.biome.IRealisticBiome;

import java.util.Collections;
import java.util.Random;

/**
 * Decides, for each RTG populate, whether the decorations are staged (run on a worker, applied later) or run inline,
 * and remembers the answer for the rest of that populate. Used by the mixins on {@code ChunkGeneratorRTG} and
 * {@code LatePopulatingChunk}.
 *
 * <p>Staging applies only inside {@code LatePopulatingChunk.doPopulate} on the server thread. The rest of the populate
 * ({@code populate}'s tail and {@code GameRegistry.generateWorld}) is skipped while a job holds it and run from the job's
 * {@code finish} when the decorations are applied.
 */
public final class RtgFeatures {
    private static final ThreadLocal<Object> IN_DO_POPULATE = new ThreadLocal<Object>();
    private static final ThreadLocal<FeatureJob> STAGED = new ThreadLocal<FeatureJob>();

    /** SHA-256 of the 9.0.3 classes the staged tail was copied from. */
    private static final String[] KNOWN_CLASS_HASHES = {
            "eef60ad8d39ff50d0afc785bc8c282be9dcd494728094e7fbc8fa95f6aa0f2e5", // ChunkGeneratorRTG
            "1aba4b4361571cd9d11d25c398cd747e5102c31ddc02714248a194ca005e8d7e"  // LatePopulatingChunk
    };
    private static volatile int supportedState; // 0 unknown, 1 yes, -1 no

    private RtgFeatures() {
    }

    /**
     * True when the RTG classes on the classpath are the build the staged tail was copied from (or a build the config
     * lists). Checked once, from the class files as shipped; false stops staging, never the game.
     */
    public static boolean supported() {
        int state = supportedState;
        if (state == 0) {
            boolean ok = classMatches("rtg/world/gen/ChunkGeneratorRTG.class")
                    && classMatches("rtg/world/gen/LatePopulatingChunk.class");
            supportedState = state = ok ? 1 : -1;
            if (!ok) {
                com.apocollis.chunksteward.ChunkSteward.LOGGER.warn(
                        "staged populate is off: this RTG-Plus build is not one its tail was written against "
                                + "(see features.extraRtgClassHashes)");
            }
        }
        return state > 0;
    }

    private static boolean classMatches(String resource) {
        try (java.io.InputStream in = RtgFeatures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            String hash = hex.toString();
            for (String known : KNOWN_CLASS_HASHES) {
                if (known.equals(hash)) {
                    return true;
                }
            }
            for (String extra : com.apocollis.chunksteward.config.ChunkStewardConfig.features.extraRtgClassHashes) {
                if (extra != null && extra.trim().equalsIgnoreCase(hash)) {
                    return true;
                }
            }
            return false;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failed) {
            return false;
        }
    }

    /** Head of {@code LatePopulatingChunk.doPopulate}. */
    public static void enterDoPopulate() {
        IN_DO_POPULATE.set(Boolean.TRUE);
        STAGED.remove();
    }

    /** Return of {@code LatePopulatingChunk.doPopulate}. */
    public static void exitDoPopulate() {
        IN_DO_POPULATE.remove();
        STAGED.remove();
    }

    /** True while the current populate handed its decorations to a job: the rest of it is skipped. */
    public static boolean staged() {
        return STAGED.get() != null;
    }

    /** The call site of {@code biome.rDecorate} in RTG's {@code populate}. */
    public static void decorate(RtgSplitAccess generator, IRealisticBiome biome, RTGWorld rtgWorld, Random rand,
                                ChunkPos chunkPos, float river, boolean hasVillage, IRealisticBiome treeBiome,
                                World world) {
        if (IN_DO_POPULATE.get() != null && world instanceof WorldServer) {
            WorldServer server = (WorldServer) world;
            // Staging off (the default) never reaches the build check below.
            if (FeatureLane.available(server) && supported()) {
                RtgDecorationStage stage = new RtgDecorationStage(generator, biome, chunkPos, river, hasVillage, rand);
                int x = chunkPos.x;
                int z = chunkPos.z;
                Runnable finish = new Finish(generator, stage, server, x, z);
                FeatureJob job = FeatureLane.submit(server, x, z, Collections.singletonList(stage), finish);
                if (job != null) {
                    STAGED.set(job);
                    return;
                }
                // Staging is on but this chunk cannot be staged: the decorations still take the lock a worker job holds.
                FeatureLane.decoLock().lock();
                try {
                    biome.rDecorate(rtgWorld, rand, chunkPos, river, hasVillage, treeBiome);
                } finally {
                    FeatureLane.decoLock().unlock();
                }
                return;
            }
        }
        biome.rDecorate(rtgWorld, rand, chunkPos, river, hasVillage, treeBiome);
    }

    /** What is left of the populate once the decorations are in the world. */
    private static final class Finish implements Runnable {
        private final RtgSplitAccess generator;
        private final RtgDecorationStage stage;
        private final WorldServer world;
        private final int x;
        private final int z;

        Finish(RtgSplitAccess generator, RtgDecorationStage stage, WorldServer world, int x, int z) {
            this.generator = generator;
            this.stage = stage;
            this.world = world;
            this.x = x;
            this.z = z;
        }

        @Override
        public void run() {
            stage.beforeTail();
            generator.chunksteward$tail(stage.biome(), x, z, stage.hasVillage());
            // The mod generators: staged runs and server-thread runs in Forge's order; markDirty after the last one.
            GeneratorChain.run(world, x, z, (IChunkGenerator) generator, new Runnable() {
                @Override
                public void run() {
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
                    if (chunk != null) {
                        chunk.markDirty();
                    }
                }
            });
        }
    }
}
