package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.data.HotspotData;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@code /chunksteward}: information and troubleshooting for operators. Everything is read-only except the
 * session-only switches ({@code log}, {@code pregen}, {@code pipeline off|on}) and the hotspot edits
 * ({@code hotspots add|remove|reset}). None of it generates chunks.
 */
public class ChunkStewardCommand extends CommandBase {
    private static final List<String> SUBCOMMANDS = Arrays.asList("status", "chunkinfo", "queue", "holds", "log",
            "pregen", "pipeline", "hotspots");
    private static final List<String> HOTSPOT_SUBCOMMANDS = Arrays.asList("list", "here", "add", "remove", "reset");

    @Override
    public String getName() {
        return "chunksteward";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/chunksteward <status | chunkinfo [x z] | queue | holds | log <seconds> | pregen <pause|resume>"
                + " | pipeline <status|off|on|verify <count> [dimension]> | hotspots <list|here|add [x z]|remove <id>|reset <id>>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }
        switch (args[0].toLowerCase()) {
            case "status":
                status(sender);
                break;
            case "chunkinfo":
                chunkInfo(server, sender, args);
                break;
            case "queue":
                queue(sender);
                break;
            case "holds":
                holds(sender);
                break;
            case "log":
                log(sender, args);
                break;
            case "pregen":
                pregen(sender, args);
                break;
            case "pipeline":
                pipeline(server, sender, args);
                break;
            case "hotspots":
                hotspots(server, sender, args);
                break;
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        if (args.length == 2 && "hotspots".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, HOTSPOT_SUBCOMMANDS);
        }
        if (args.length == 2 && "pregen".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "pause", "resume");
        }
        if (args.length == 2 && "pipeline".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "status", "off", "on", "verify");
        }
        return Collections.emptyList();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static void send(ICommandSender sender, String text) {
        send(sender, text, TextFormatting.WHITE);
    }

    private static void send(ICommandSender sender, String text, TextFormatting color) {
        TextComponentString component = new TextComponentString(text);
        component.getStyle().setColor(color);
        sender.sendMessage(component);
    }

    private static WorldServer worldOf(MinecraftServer server, ICommandSender sender) {
        World world = sender.getEntityWorld();
        return world instanceof WorldServer ? (WorldServer) world : server.getWorld(0);
    }

    private static boolean onOff(boolean value) {
        return value;
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static int minutesAgo(WorldServer world, long lastWorked) {
        return lastWorked <= 0L ? -1 : (int) ((world.getTotalWorldTime() - lastWorked) / 1200L);
    }

    // ---- status ---------------------------------------------------------------------------------------------------

    private static void status(ICommandSender sender) {
        send(sender, "Chunk Steward " + ChunkSteward.VERSION, TextFormatting.GOLD);
        send(sender, "features: throttle " + onOff(ChunkStewardConfig.throttle.enabled) + ", populate budget "
                + onOff(ChunkStewardConfig.populate.enabled) + ", random-tick guard "
                + onOff(ChunkStewardConfig.randomTicks.guardEnabled) + ", preload "
                + onOff(ChunkStewardConfig.preload.enabled) + ", stage 2 " + onOff(ChunkStewardConfig.stage2.enabled)
                + (Pregen.paused ? " (pregen PAUSED this session)" : ""));
        send(sender, String.format("tick avg %.1f ms (non-chunk %.1f ms, chunk %.1f ms), measured TPS %.2f, load factor %.2f"
                        + " (full at %.1f TPS, none at %.1f)", TickMonitor.totalEmaMs, TickMonitor.otherEmaMs,
                TickMonitor.chunkEmaMs, TickMonitor.measuredTps(), TickMonitor.loadFactor(),
                ChunkStewardConfig.throttle.tpsFull, ChunkStewardConfig.throttle.tpsLoaded));
        send(sender, "populate queue " + PopulateQueue.size() + ", held chunks " + ChunkHolds.heldCount()
                + ", chunks waiting for players " + TickMonitor.waitingNow());
        ChunkStewardConfig.Stage2 s2 = ChunkStewardConfig.stage2;
        long total = Stage2Pregen.totalPregenerated();
        send(sender, "stage 2: " + Stage2Pregen.hotspotCount() + " hotspots, " + total + " chunks pregenerated"
                + (s2.warnTotalChunks > 0 ? " (warn at " + s2.warnTotalChunks + ")" : "")
                + (s2.maxTotalChunks > 0 ? " (limit " + s2.maxTotalChunks + ")" : ""));
        send(sender, "stage 2 now: " + (Stage2Pregen.activeId < 0 ? "idle" : "hotspot #" + Stage2Pregen.activeId
                + " in dimension " + Stage2Pregen.activeDim + ", " + Stage2Pregen.activeWhat)
                + "; last hold-off: " + Stage2Pregen.lastYield());
        send(sender, TerrainPipeline.summary());
    }

    private static void pipeline(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/chunksteward pipeline <status|off|on|verify <count> [dimension]>");
        }
        switch (args[1].toLowerCase()) {
            case "status":
                send(sender, TerrainPipeline.summary());
                break;
            case "off":
                TerrainPipeline.setKilled(true);
                send(sender, TerrainPipeline.summary(), TextFormatting.YELLOW);
                break;
            case "on":
                TerrainPipeline.setKilled(false);
                send(sender, TerrainPipeline.summary(), TextFormatting.GREEN);
                break;
            case "verify":
                verify(server, sender, args);
                break;
            default:
                throw new WrongUsageException("/chunksteward pipeline <status|off|on|verify <count> [dimension]>");
        }
    }

    private static void verify(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new WrongUsageException("/chunksteward pipeline verify <count> [dimension]");
        }
        int count = parseInt(args[2], 1, 20000);
        WorldServer world = worldOf(server, sender);
        if (args.length >= 4) {
            WorldServer named = server.getWorld(parseInt(args[3]));
            if (named == null) {
                send(sender, "dimension " + args[3] + " is not loaded", TextFormatting.RED);
                return;
            }
            world = named;
        }
        if (PipelineVerify.running()) {
            send(sender, "pipeline verify already running. " + PipelineVerify.status(), TextFormatting.YELLOW);
            return;
        }
        if (!PipelineVerify.start(world, count, sender)) {
            send(sender, PipelineVerify.status(), TextFormatting.RED);
            return;
        }
        send(sender, "pipeline verify started: " + count + " chunks in dimension " + world.provider.getDimension()
                + ". A pass needs 10000 clean chunks. " + PipelineVerify.status(), TextFormatting.GREEN);
    }

    // ---- chunkinfo ------------------------------------------------------------------------------------------------

    private static void chunkInfo(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        WorldServer world = worldOf(server, sender);
        int cx;
        int cz;
        if (args.length >= 3) {
            cx = parseInt(args[1]);
            cz = parseInt(args[2]);
        } else if (sender.getCommandSenderEntity() != null) {
            Entity entity = sender.getCommandSenderEntity();
            cx = ((int) Math.floor(entity.posX)) >> 4;
            cz = ((int) Math.floor(entity.posZ)) >> 4;
        } else {
            throw new WrongUsageException("/chunksteward chunkinfo <x z>   (chunk coordinates)");
        }
        int dim = world.provider.getDimension();
        ChunkProviderServer provider = world.getChunkProvider();
        Chunk chunk = StageGate.peek(provider, cx, cz);
        send(sender, "chunk (" + cx + ", " + cz + ") in dimension " + dim + ", blocks " + (cx * 16) + ".."
                + (cx * 16 + 15) + " / " + (cz * 16) + ".." + (cz * 16 + 15), TextFormatting.GOLD);
        if (chunk == null) {
            send(sender, "loaded: no   on disk: " + yesNo(GeneratedCache.known(dim, cx, cz)
                    || provider.isChunkGeneratedAt(cx, cz)));
        } else {
            send(sender, "loaded: yes   terrain-populated: " + yesNo(chunk.isTerrainPopulated())
                    + "   light-populated: " + yesNo(chunk.isLightPopulated()));
            send(sender, "centre biome: " + centreBiome(chunk) + "   terrain generated by: "
                    + sourceOf(dim, cx, cz));
        }
        send(sender, "queued for populate: " + yesNo(PopulateQueue.contains(dim, ChunkPos.asLong(cx, cz)))
                + "   terrain-only preload chunk: " + yesNo(StageGate.isTerrainOnly(dim, cx, cz)));
        int hold = ChunkHolds.holdRemaining(dim, cx, cz);
        send(sender, "held by Chunk Steward: " + (hold < 0 ? "no" : "yes, " + hold + " more ticks")
                + "   watched by a player: " + yesNo(world.getPlayerChunkMap().contains(cx, cz)));
        Set<ForgeChunkManager.Ticket> tickets = ForgeChunkManager.getPersistentChunksFor(world)
                .get(new ChunkPos(cx, cz));
        StringBuilder owners = new StringBuilder();
        for (ForgeChunkManager.Ticket ticket : tickets) {
            if (owners.length() > 0) {
                owners.append(", ");
            }
            owners.append(ticket.getModId());
        }
        send(sender, "Forge tickets: " + (tickets.isEmpty() ? "none" : owners.toString()));
    }

    // ---- queue, holds, log, pregen ----------------------------------------------------------------------------------

    private static String centreBiome(Chunk chunk) {
        byte[] ids = chunk.getBiomeArray();
        Biome biome = Biome.getBiome(ids[8 << 4 | 8] & 255);
        return biome == null ? "unset" : biome.getRegistryName() + " (" + biome.getBiomeName() + ")";
    }

    private static String sourceOf(int dimension, int cx, int cz) {
        String source = GenSource.of(dimension, cx, cz);
        return source != null ? source : "not recorded (loaded from disk, an earlier session, or not a pipeline generator)";
    }

    private static void queue(ICommandSender sender) {
        send(sender, "populate queue: " + PopulateQueue.size(), TextFormatting.GOLD);
        for (int[] entry : PopulateQueue.sample(10)) {
            send(sender, "  dimension " + entry[0] + " chunk (" + entry[1] + ", " + entry[2] + ")");
        }
        if (PopulateQueue.size() > 10) {
            send(sender, "  ... and " + (PopulateQueue.size() - 10) + " more");
        }
    }

    private static void holds(ICommandSender sender) {
        int oldest = ChunkHolds.oldestRemaining();
        send(sender, "held chunks: " + ChunkHolds.heldCount() + (oldest < 0 ? "" : ", oldest hold ends in " + oldest
                + " ticks"), TextFormatting.GOLD);
    }

    private static void log(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/chunksteward log <seconds>   (0 = off; this session only)");
        }
        int seconds = parseInt(args[1], 0, 3600);
        ChunkStewardConfig.logging.statusIntervalSeconds = seconds;
        send(sender, seconds == 0 ? "status log off (this session)" : "status log every " + seconds
                + " s (this session; set logging.statusIntervalSeconds in the config to keep it)");
    }

    private static void pregen(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/chunksteward pregen <pause|resume>");
        }
        switch (args[1].toLowerCase()) {
            case "pause":
                Pregen.paused = true;
                send(sender, "preload and stage 2 paused until resumed or restart");
                break;
            case "resume":
                Pregen.paused = false;
                send(sender, "preload and stage 2 resumed");
                break;
            default:
                throw new WrongUsageException("/chunksteward pregen <pause|resume>");
        }
    }

    // ---- hotspots -------------------------------------------------------------------------------------------------

    private static void hotspots(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/chunksteward hotspots <list|here|add [x z]|remove <id>|reset <id>>");
        }
        WorldServer world = worldOf(server, sender);
        HotspotData data = HotspotData.get(world);
        int dim = world.provider.getDimension();
        switch (args[1].toLowerCase()) {
            case "list":
                hotspotList(sender, world, data);
                break;
            case "here":
                hotspotHere(sender, world, data);
                break;
            case "add": {
                int cx;
                int cz;
                if (args.length >= 4) {
                    cx = parseInt(args[2]);
                    cz = parseInt(args[3]);
                } else if (sender.getCommandSenderEntity() != null) {
                    cx = ((int) Math.floor(sender.getCommandSenderEntity().posX)) >> 4;
                    cz = ((int) Math.floor(sender.getCommandSenderEntity().posZ)) >> 4;
                } else {
                    throw new WrongUsageException("/chunksteward hotspots add <x z>   (chunk coordinates)");
                }
                HotspotData.Hotspot added = Hotspots.addManual(world, cx, cz);
                if (added == null) {
                    HotspotData.Hotspot near = data.near(cx, cz, ChunkStewardConfig.stage2.mergeRadiusChunks);
                    send(sender, "already covered by hotspot #" + (near == null ? "?" : String.valueOf(near.id))
                            + " (merge radius " + ChunkStewardConfig.stage2.mergeRadiusChunks + " chunks)",
                            TextFormatting.YELLOW);
                } else {
                    send(sender, "hotspot #" + added.id + " added at chunk (" + cx + ", " + cz + ") in dimension " + dim);
                }
                break;
            }
            case "remove": {
                if (args.length < 3) {
                    throw new WrongUsageException("/chunksteward hotspots remove <id>");
                }
                int id = parseInt(args[2]);
                if (Hotspots.remove(world, id)) {
                    Stage2Pregen.forget(dim, id);
                    send(sender, "hotspot #" + id + " removed (generated chunks are kept)");
                } else {
                    send(sender, "no hotspot #" + id + " in this dimension", TextFormatting.RED);
                }
                break;
            }
            case "reset": {
                if (args.length < 3) {
                    throw new WrongUsageException("/chunksteward hotspots reset <id>");
                }
                int id = parseInt(args[2]);
                HotspotData.Hotspot h = data.find(id);
                if (h == null) {
                    send(sender, "no hotspot #" + id + " in this dimension", TextFormatting.RED);
                } else {
                    h.terrainRing = -1;
                    h.popRing = -1;
                    data.markDirty();
                    Stage2Pregen.forget(dim, id);
                    send(sender, "hotspot #" + id + " will rescan from the start (existing chunks are only loaded)");
                }
                break;
            }
            default:
                throw new WrongUsageException("/chunksteward hotspots <list|here|add [x z]|remove <id>|reset <id>>");
        }
    }

    private static void hotspotList(ICommandSender sender, WorldServer world, HotspotData data) {
        int dim = world.provider.getDimension();
        send(sender, data.hotspots.size() + " hotspot(s) in dimension " + dim + " (radius " + ChunkStewardConfig.stage2.radiusFull
                + " populated, " + Stage2Pregen.terrainRadius() + " terrain)", TextFormatting.GOLD);
        List<HotspotData.Hotspot> sorted = new ArrayList<>(data.hotspots);
        Entity entity = sender.getCommandSenderEntity();
        if (entity != null) {
            final int px = ((int) Math.floor(entity.posX)) >> 4;
            final int pz = ((int) Math.floor(entity.posZ)) >> 4;
            sorted.sort((a, b) -> Integer.compare(Math.max(Math.abs(a.x - px), Math.abs(a.z - pz)),
                    Math.max(Math.abs(b.x - px), Math.abs(b.z - pz))));
        }
        String[] sources = {"bed", "dwell", "manual"};
        for (HotspotData.Hotspot h : sorted) {
            int ago = minutesAgo(world, h.lastWorked);
            send(sender, "#" + h.id + " chunk (" + h.x + ", " + h.z + ") block (" + (h.x * 16 + 8) + ", "
                    + (h.z * 16 + 8) + ") " + sources[Math.max(0, Math.min(2, h.source))] + ": "
                    + (Stage2Pregen.isDone(h) ? "done" : "terrain ring " + h.terrainRing + "/" + Stage2Pregen.terrainRadius()
                    + ", populate ring " + h.popRing + "/" + ChunkStewardConfig.stage2.radiusFull)
                    + ", generated " + h.generated + ", populated " + h.populated
                    + (ago < 0 ? ", not worked on yet" : ", last worked " + ago + " min ago"));
        }
    }

    private static void hotspotHere(ICommandSender sender, WorldServer world, HotspotData data)
            throws CommandException {
        Entity entity = sender.getCommandSenderEntity();
        if (entity == null) {
            throw new WrongUsageException("run this as a player");
        }
        ChunkStewardConfig.Stage2 cfg = ChunkStewardConfig.stage2;
        int cx = ((int) Math.floor(entity.posX)) >> 4;
        int cz = ((int) Math.floor(entity.posZ)) >> 4;
        int cellX = Math.floorDiv(cx, cfg.cellSizeChunks);
        int cellZ = Math.floorDiv(cz, cfg.cellSizeChunks);
        int need = cfg.dwellMinutes * 60;
        send(sender, String.format("this cell %d s, surrounding 3x3 cells %d of %d s (%.1f of %d min)",
                data.cell(cellX, cellZ), data.sum3x3(cellX, cellZ), need, data.sum3x3(cellX, cellZ) / 60.0,
                cfg.dwellMinutes), TextFormatting.GOLD);
        HotspotData.Hotspot near = data.near(cx, cz, cfg.mergeRadiusChunks);
        send(sender, near == null ? "no hotspot within " + cfg.mergeRadiusChunks + " chunks" : "hotspot #" + near.id
                + " is within " + cfg.mergeRadiusChunks + " chunks");
        List<Long2IntMap.Entry> top = new ArrayList<>(data.dwell.long2IntEntrySet());
        top.sort((a, b) -> Integer.compare(b.getIntValue(), a.getIntValue()));
        send(sender, "most dwell in this dimension:");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            long key = top.get(i).getLongKey();
            int x = (int) key;
            int z = (int) (key >> 32);
            int blockX = x * cfg.cellSizeChunks * 16 + cfg.cellSizeChunks * 8;
            int blockZ = z * cfg.cellSizeChunks * 16 + cfg.cellSizeChunks * 8;
            send(sender, String.format("  cell (%d, %d) near block (%d, %d): %.1f min", x, z, blockX, blockZ,
                    top.get(i).getIntValue() / 60.0));
        }
    }
}
