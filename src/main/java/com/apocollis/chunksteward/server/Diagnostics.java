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
 * Temporary diagnostics, switched by {@code logging.debug} (off by default). Every line starts with {@code dbg} so
 * {@code grep dbg latest.log} finds them. Two cheap checks run even with debug off, because they would mean a bug: the
 * random-tick guard still active while player chunks are being provided, and a player chunk map entry holding an
 * {@link StewardEmptyChunk}. Both warn at most once per 10 seconds.
 *
 * <p>With debug on:
 * <ul>
 * <li><b>Every tick, an alert</b> when the player's own chunk or the eight around it is missing or not populated
 * ({@code dbg ALERT}, at most {@value #MAX_ALERTS_PER_SECOND} per second), with the detail below.</li>
 * <li><b>Every second:</b> counts of throttle outcomes (why {@code providePlayerChunk} calls were passed, bypassed,
 * admitted or deferred, and with {@code canGenerate} true or false), populate-gate outcomes, and the state of the
 * non-chunk average, load factor, populate queue and holds.</li>
 * <li><b>Every second for each player:</b> a scan out to the view distance, by ring: populated, loaded but unpopulated
 * and missing chunks per ring, the first ring with a gap (the frontier), and the nearest problem chunks in detail:
 * whether the chunk map has an entry for a missing chunk, why an unpopulated one waits (queued, terrain-only, which
 * +x/+z neighbours are loaded).</li>
 * <li><b>As it happens (rate limited):</b> anything the throttle or the populate gate defers within
 * {@value #NEAR_RADIUS} chunks of a player.</li>
 * </ul>
 */
@Mod.EventBusSubscriber
public final class Diagnostics {
    private static final int SCAN_RADIUS = 4;
    private static final int MAX_SCAN_RADIUS = 12;
    private static final int NEAR_RADIUS = 3;
    private static final int SUMMARY_TICKS = 20;
    private static final int MAX_SCAN_LINES = 8;
    private static final int MAX_NEAR_EVENTS_PER_SUMMARY = 6;
    private static final int MAX_ALERTS_PER_SECOND = 4;
    private static final int WARN_INTERVAL_TICKS = 200;

    private static final Map<String, Integer> PROVIDE = new TreeMap<>();
    private static final Map<String, Integer> POPULATE = new TreeMap<>();
    private static int nearEvents;
    private static int alertsThisSecond;
    private static int lastWarnTick = Integer.MIN_VALUE / 2;
    private static final double[] DIR = new double[2];

    private Diagnostics() {
    }

    static void reset() {
        PROVIDE.clear();
        POPULATE.clear();
        nearEvents = 0;
        alertsThisSecond = 0;
        lastWarnTick = Integer.MIN_VALUE / 2;
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
                ChunkSteward.LOGGER.info("dbg NEAR chunk ({}, {}) {} chunks from a player was {} (non-chunk EMA {} ms, load factor {}, "
                                + "emergency radius {}, tick {})", pos.x, pos.z, d, outcome,
                        String.format("%.1f", TickMonitor.otherEmaMs), String.format("%.2f", TickMonitor.loadFactor()),
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
        if (provided && entry.getChunk() instanceof StewardEmptyChunk) {
            warn("a player chunk map entry for chunk " + entry.getPos() + " holds a StewardEmptyChunk from the random-tick guard (it will never load)");
        }
    }

    private static void warn(String message) {
        if (TickMonitor.ticksSince(lastWarnTick) >= WARN_INTERVAL_TICKS) {
            lastWarnTick = TickMonitor.tick;
            ChunkSteward.LOGGER.warn("dbg WARNING: {} (tick {})", message, TickMonitor.tick);
        }
    }

    // ---- per-tick alerts, per-second summary and scan -----------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !on()) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (EntityPlayerMP player : players) {
            alertCheck(player);
        }
        if (TickMonitor.tick % SUMMARY_TICKS != 0) {
            return;
        }
        ChunkSteward.LOGGER.info("dbg tick {}: provide {} | populate {} | TPS {}, load {}, other EMA {} ms, "
                        + "chunk EMA {} ms, populate queue {}, held {}, waiting {}, guard active {}",
                TickMonitor.tick, PROVIDE, POPULATE, String.format("%.2f", TickMonitor.measuredTps()), String.format("%.2f", TickMonitor.loadFactor()),
                String.format("%.1f", TickMonitor.otherEmaMs), String.format("%.1f", TickMonitor.chunkEmaMs),
                PopulateQueue.size(), ChunkHolds.heldCount(),
                TickMonitor.waitingNow(), RandomTickGuard.isActive());
        PROVIDE.clear();
        POPULATE.clear();
        nearEvents = 0;
        alertsThisSecond = 0;
        for (EntityPlayerMP player : players) {
            scan(player);
        }
    }

    /** The player's own chunk and the eight around it must be loaded and populated: say so the moment one is not. */
    private static void alertCheck(EntityPlayerMP player) {
        if (alertsThisSecond >= MAX_ALERTS_PER_SECOND) {
            return;
        }
        WorldServer world = player.getServerWorld();
        ChunkProviderServer provider = world.getChunkProvider();
        PlayerChunkMap map = world.getPlayerChunkMap();
        int dim = world.provider.getDimension();
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                Chunk chunk = StageGate.peek(provider, x, z);
                String problem = chunk == null ? "MISSING" : !chunk.isTerrainPopulated() ? "UNPOPULATED" : null;
                if (problem == null) {
                    continue;
                }
                alertsThisSecond++;
                ChunkSteward.LOGGER.info("dbg ALERT {} at chunk ({}, {}) speed {} emergency radius {}: chunk ({}, {}) d={} is {}: {} "
                                + "| last provide outcomes {} (tick {})", player.getName(), cx, cz,
                        String.format("%.2f", PlayerMotion.speed(player)), ChunkThrottle.emergencyRadius(player), x, z,
                        Math.max(Math.abs(dx), Math.abs(dz)), problem, describe(provider, map, dim, x, z, chunk), PROVIDE,
                        TickMonitor.tick);
                if (alertsThisSecond >= MAX_ALERTS_PER_SECOND) {
                    return;
                }
            }
        }
    }

    private static String describe(ChunkProviderServer provider, PlayerChunkMap map, int dim, int x, int z, Chunk chunk) {
        if (chunk == null) {
            PlayerChunkMapEntry entry = map.getEntry(x, z);
            return "entry=" + (entry == null ? "NONE" : "yes, chunk " + (entry.getChunk() == null ? "null" : "set"));
        }
        return "queued=" + PopulateQueue.contains(dim, ChunkPos.asLong(x, z)) + " terrainOnly="
                + StageGate.isTerrainOnly(dim, x, z) + " held=" + (ChunkHolds.holdRemaining(dim, x, z) >= 0)
                + " watched=" + map.contains(x, z) + " neighbours(+x,+z,+x+z)=" + loaded(provider, x + 1, z) + ","
                + loaded(provider, x, z + 1) + "," + loaded(provider, x + 1, z + 1);
    }

    private static void scan(EntityPlayerMP player) {
        WorldServer world = player.getServerWorld();
        ChunkProviderServer provider = world.getChunkProvider();
        PlayerChunkMap map = world.getPlayerChunkMap();
        int dim = world.provider.getDimension();
        int view = TickMonitor.viewRadius(world);
        int radius = Math.max(SCAN_RADIUS, Math.min(view + 1, MAX_SCAN_RADIUS));
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        PlayerMotion.direction(player, DIR);
        double dirX = DIR[0];
        double dirZ = DIR[1];
        // Sides relative to the direction: missing chunks ahead, left, right, behind (out to the view distance).
        int[] missBySide = new int[4];
        int[] missByRing = new int[radius + 1];
        int[] unpopByRing = new int[radius + 1];
        int missing = 0;
        int missingNoEntry = 0;
        int unpopulated = 0;
        int populated = 0;
        List<int[]> problems = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                Chunk chunk = StageGate.peek(provider, x, z);
                if (chunk == null) {
                    missing++;
                    missByRing[ring]++;
                    if (ring > 0 && ring <= view) {
                        double ahead = dx * dirX + dz * dirZ;
                        double left = dx * dirZ - dz * dirX;
                        missBySide[Math.abs(ahead) >= Math.abs(left) ? (ahead >= 0 ? 0 : 3) : (left >= 0 ? 1 : 2)]++;
                    }
                    if (map.getEntry(x, z) == null) {
                        missingNoEntry++;
                    }
                    problems.add(new int[] {ring, x, z});
                } else if (!chunk.isTerrainPopulated()) {
                    unpopulated++;
                    unpopByRing[ring]++;
                    problems.add(new int[] {ring, x, z});
                } else {
                    populated++;
                }
            }
        }
        problems.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder gaps = new StringBuilder();
        int firstMissing = -1;
        for (int r = 0; r <= radius; r++) {
            if (missByRing[r] > 0 && firstMissing < 0) {
                firstMissing = r;
            }
            if (missByRing[r] > 0 || unpopByRing[r] > 0) {
                gaps.append(" r").append(r).append(' ').append(missByRing[r]).append("m/").append(unpopByRing[r]).append('u');
            }
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_SCAN_LINES, problems.size()); i++) {
            int[] p = problems.get(i);
            Chunk chunk = StageGate.peek(provider, p[1], p[2]);
            lines.add((chunk == null ? "missing" : "loaded-unpopulated") + " (" + p[1] + ", " + p[2] + ") d=" + p[0] + " "
                    + describe(provider, map, dim, p[1], p[2], chunk));
        }
        ChunkSteward.LOGGER.info("dbg near {} chunk ({}, {}) speed {} emergency radius {}, view {}: within {} chunks populated {}, "
                        + "loaded-unpopulated {}, missing {} (no chunk-map entry: {}); missing within view by side: ahead {}, "
                        + "left {}, right {}, behind {} (direction {}, {}; pos {}, {}); first missing ring {}; gaps by ring:{}{}",
                player.getName(), cx, cz, String.format("%.2f", PlayerMotion.speed(player)),
                ChunkThrottle.emergencyRadius(player), view, radius, populated, unpopulated, missing, missingNoEntry,
                missBySide[0], missBySide[1], missBySide[2], missBySide[3], String.format("%.2f", dirX),
                String.format("%.2f", dirZ), String.format("%.1f", player.posX), String.format("%.1f", player.posZ),
                firstMissing < 0 ? "-" : String.valueOf(firstMissing), gaps.length() == 0 ? " none" : gaps.toString(),
                lines.isEmpty() ? "" : " | " + String.join(" | ", lines));
    }

    private static char loaded(ChunkProviderServer provider, int x, int z) {
        return StageGate.peek(provider, x, z) != null ? 'y' : 'n';
    }
}
