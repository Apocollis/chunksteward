package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Temporary diagnostics, switched by {@code logging.debug} (off by default). Every line starts with
 * {@code dbg} so {@code grep dbg latest.log} finds them. Two cheap checks run even with debug off, because they would
 * mean a bug: the random-tick guard still active while player chunks are being provided, and a player chunk map entry
 * holding an {@link EmptyChunk}. Both warn at most once per 10 seconds.
 *
 * <p>With debug on, every second: counts of throttle outcomes (why {@code providePlayerChunk} calls were passed, bypassed,
 * admitted or deferred, and with {@code canGenerate} true or false) and of populate-gate outcomes, plus the state of
 * the credit, load factor, populate queue and holds. Every second for each player: a scan of the chunks within
 * {@value #SCAN_RADIUS} chunks, listing chunks that are missing (with whether the player chunk map has an entry for them) and chunks
 * that are loaded but not populated (with why: queued, terrain-only, which +x/+z neighbours are loaded). Anything the
 * throttle or the populate gate defers within {@value #NEAR_RADIUS} chunks of a player is logged as it happens (rate
 * limited), since near chunks should never wait.
 */
@Mod.EventBusSubscriber
public final class Diagnostics {
    private static final int SCAN_RADIUS = 4;
    private static final int NEAR_RADIUS = 3;
    private static final int SUMMARY_TICKS = 20;
    private static final int MAX_SCAN_LINES = 8;
    private static final int MAX_NEAR_EVENTS_PER_SUMMARY = 6;
    private static final int WARN_INTERVAL_TICKS = 200;

    private static final Map<String, Integer> PROVIDE = new TreeMap<>();
    private static final Map<String, Integer> POPULATE = new TreeMap<>();
    private static int nearEvents;
    private static int lastWarnTick = Integer.MIN_VALUE / 2;

    private Diagnostics() {
    }

    public static boolean on() {
        return ChunkStewardConfig.logging.debug;
    }

    private static void bump(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    /** Smallest chunk distance (Chebyshev) from a player in the world to the chunk, or a large number. */
    private static int nearestPlayer(World world, int cx, int cz) {
        int best = Integer.MAX_VALUE;
        for (EntityPlayer player : world.playerEntities) {
            int d = Math.max(Math.abs(cx - (((int) Math.floor(player.posX)) >> 4)),
                    Math.abs(cz - (((int) Math.floor(player.posZ)) >> 4)));
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    // ---- throttle ----------------------------------------------------------------------------------------------------

    /** One {@code providePlayerChunk} call and what the throttle did with it. */
    static void provide(String outcome, PlayerChunkMap map, PlayerChunkMapEntry entry, boolean canGenerate) {
        bump(PROVIDE, outcome + (canGenerate ? "" : " [canGenerate=false]"));
        if (outcome.startsWith("deferred") && nearEvents < MAX_NEAR_EVENTS_PER_SUMMARY) {
            ChunkPos pos = entry.getPos();
            WorldServer world = map.getWorldServer();
            int d = nearestPlayer(world, pos.x, pos.z);
            if (d <= NEAR_RADIUS) {
                nearEvents++;
                ChunkSteward.LOGGER.info("dbg NEAR chunk ({}, {}) {} chunks from a player was {} (credit {} ms, load factor {}, "
                                + "emergency radius {}, tick {})", pos.x, pos.z, d, outcome,
                        String.format("%.1f", ChunkThrottle.credit()), String.format("%.2f", TickMonitor.loadFactor()),
                        nearestEmergencyRadius(world), TickMonitor.tick);
            }
        }
    }

    private static int nearestEmergencyRadius(WorldServer world) {
        int max = 0;
        for (EntityPlayer player : world.playerEntities) {
            if (player instanceof EntityPlayerMP) {
                max = Math.max(max, ChunkThrottle.emergencyRadius((EntityPlayerMP) player));
            }
        }
        return max;
    }

    // ---- populate gate -----------------------------------------------------------------------------------------------

    static void populate(String code, boolean run, Chunk chunk) {
        bump(POPULATE, (run ? "run " : "defer ") + code);
        if (!run && nearEvents < MAX_NEAR_EVENTS_PER_SUMMARY) {
            int d = nearestPlayer(chunk.getWorld(), chunk.x, chunk.z);
            if (d <= NEAR_RADIUS) {
                nearEvents++;
                ChunkSteward.LOGGER.info("dbg NEAR populate of chunk ({}, {}) {} chunks from a player was deferred ({}), tick {}",
                        chunk.x, chunk.z, d, code, TickMonitor.tick);
            }
        }
    }

    // ---- always-on sanity checks -------------------------------------------------------------------------------------

    /** Called for every provide: a stuck random-tick guard would return empty chunks to players. */
    static void sanity(PlayerChunkMapEntry entry, boolean provided) {
        if (RandomTickGuard.isActive()) {
            warn("the random-tick guard is ACTIVE while player chunks are provided; it would hand out empty chunks");
        }
        if (provided && entry.getChunk() instanceof EmptyChunk) {
            warn("a player chunk map entry for chunk " + entry.getPos() + " holds an EmptyChunk (it will never load)");
        }
    }

    private static void warn(String message) {
        if (TickMonitor.tick - lastWarnTick >= WARN_INTERVAL_TICKS) {
            lastWarnTick = TickMonitor.tick;
            ChunkSteward.LOGGER.warn("dbg WARNING: {} (tick {})", message, TickMonitor.tick);
        }
    }

    // ---- per-second summary and scan ----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !on() || TickMonitor.tick % SUMMARY_TICKS != 0) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        ChunkSteward.LOGGER.info("dbg tick {}: provide {} | populate {} | credit {} ms, TPS {}, load {}, other EMA {} ms, "
                        + "populate queue {}, held {}, waiting {}, guard active {}",
                TickMonitor.tick, PROVIDE, POPULATE, String.format("%.1f", ChunkThrottle.credit()),
                String.format("%.2f", TickMonitor.measuredTps()), String.format("%.2f", TickMonitor.loadFactor()),
                String.format("%.1f", TickMonitor.otherEmaMs), PopulateQueue.size(), ChunkHolds.heldCount(),
                TickMonitor.waitingNow(), RandomTickGuard.isActive());
        PROVIDE.clear();
        POPULATE.clear();
        nearEvents = 0;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            scan(player);
        }
    }

    private static void scan(EntityPlayerMP player) {
        WorldServer world = player.getServerWorld();
        ChunkProviderServer provider = world.getChunkProvider();
        PlayerChunkMap map = world.getPlayerChunkMap();
        int dim = world.provider.getDimension();
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        int missing = 0;
        int missingNoEntry = 0;
        int unpopulated = 0;
        int populated = 0;
        List<String> lines = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                Chunk chunk = StageGate.peek(provider, x, z);
                if (chunk == null) {
                    missing++;
                    PlayerChunkMapEntry entry = map.getEntry(x, z);
                    if (entry == null) {
                        missingNoEntry++;
                    }
                    if (lines.size() < MAX_SCAN_LINES) {
                        lines.add("missing (" + x + ", " + z + ") d=" + dist + " entry="
                                + (entry == null ? "NONE" : "yes, chunk " + (entry.getChunk() == null ? "null" : "set")));
                    }
                } else if (!chunk.isTerrainPopulated()) {
                    unpopulated++;
                    if (lines.size() < MAX_SCAN_LINES) {
                        lines.add("loaded-unpopulated (" + x + ", " + z + ") d=" + dist + " queued="
                                + PopulateQueue.contains(dim, ChunkPos.asLong(x, z)) + " terrainOnly="
                                + StageGate.isTerrainOnly(dim, x, z) + " held=" + (ChunkHolds.holdRemaining(dim, x, z) >= 0)
                                + " watched=" + map.contains(x, z) + " neighbours(+x,+z,+x+z)="
                                + loaded(provider, x + 1, z) + "," + loaded(provider, x, z + 1) + ","
                                + loaded(provider, x + 1, z + 1));
                    }
                } else {
                    populated++;
                }
            }
        }
        ChunkSteward.LOGGER.info("dbg near {} chunk ({}, {}) speed {} emergency radius {}: within {} chunks populated {}, "
                        + "loaded-unpopulated {}, missing {} (no chunk-map entry: {}){}", player.getName(), cx, cz,
                String.format("%.2f", PlayerMotion.speed(player)), ChunkThrottle.emergencyRadius(player), SCAN_RADIUS,
                populated, unpopulated, missing, missingNoEntry, lines.isEmpty() ? "" : " | " + String.join(" | ", lines));
    }

    private static char loaded(ChunkProviderServer provider, int x, int z) {
        return StageGate.peek(provider, x, z) != null ? 'y' : 'n';
    }
}
