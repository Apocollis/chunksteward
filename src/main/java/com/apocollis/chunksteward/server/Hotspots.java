package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.data.HotspotData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds the places stage 2 should pregenerate.
 *
 * <p>Dwell: every {@code dwellSampleSeconds} each player is compared with a fixed anchor position. Within
 * {@code dwellAnchorRadiusBlocks} (horizontal) the player is idle and the sample is credited to their cell; farther
 * away the sample earns nothing and the anchor moves to where they are. Keeping the anchor fixed while they stay
 * near it means a slow mover (sneaking, a slow ride) leaves range after a couple of samples and cannot build dwell by
 * drifting. When the credited cell's 3x3 block reaches {@code dwellMinutes}, a hotspot is created at the
 * dwell-weighted centroid, unless one already exists within {@code mergeRadiusChunks}.
 *
 * <p>Bed: a player's bed or respawn point in their dimension is a hotspot at once (checked at login and every minute).
 */
@Mod.EventBusSubscriber
public final class Hotspots {
    private static final int BED_CHECK_TICKS = 1200;

    private static final class DwellState {
        int dim = Integer.MIN_VALUE;
        double anchorX;
        double anchorZ;
        int ticks;
    }

    private static final Map<UUID, DwellState> STATES = new HashMap<>();

    private Hotspots() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP && ChunkStewardConfig.stage2.enabled) {
            checkBed((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ChunkStewardConfig.Stage2 cfg = ChunkStewardConfig.stage2;
        if (!cfg.enabled) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        int sampleTicks = cfg.dwellSampleSeconds * 20;
        double radiusSq = (double) cfg.dwellAnchorRadiusBlocks * cfg.dwellAnchorRadiusBlocks;
        for (EntityPlayerMP player : players) {
            DwellState s = STATES.computeIfAbsent(player.getUniqueID(), id -> new DwellState());
            if (player.dimension != s.dim) {
                reanchor(s, player);
                continue;
            }
            if (++s.ticks < sampleTicks) {
                continue;
            }
            s.ticks = 0;
            if (player.isSpectator() || !Dimensions.applies(player.dimension)) {
                reanchor(s, player);
                continue;
            }
            double dx = player.posX - s.anchorX;
            double dz = player.posZ - s.anchorZ;
            if (dx * dx + dz * dz <= radiusSq) {
                sampleIdle(player, cfg);
            } else {
                reanchor(s, player);
            }
        }
        if (cfg.homeFromBed && TickMonitor.tick % BED_CHECK_TICKS == 0) {
            for (EntityPlayerMP player : players) {
                checkBed(player);
            }
        }
    }

    private static void reanchor(DwellState s, EntityPlayer player) {
        s.dim = player.dimension;
        s.anchorX = player.posX;
        s.anchorZ = player.posZ;
        s.ticks = 0;
    }

    private static void sampleIdle(EntityPlayerMP player, ChunkStewardConfig.Stage2 cfg) {
        WorldServer world = player.getServerWorld();
        HotspotData data = HotspotData.get(world);
        int cx = ((int) Math.floor(player.posX)) >> 4;
        int cz = ((int) Math.floor(player.posZ)) >> 4;
        int cell = cfg.cellSizeChunks;
        int cellX = Math.floorDiv(cx, cell);
        int cellZ = Math.floorDiv(cz, cell);
        data.credit(cellX, cellZ, cfg.dwellSampleSeconds);
        if (data.sum3x3(cellX, cellZ) < cfg.dwellMinutes * 60) {
            return;
        }
        // Dwell-weighted centroid of the 3x3 block, in chunk coordinates.
        double sx = 0.0;
        double sz = 0.0;
        double sw = 0.0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                int w = data.cell(cellX + i, cellZ + j);
                if (w > 0) {
                    sx += w * ((cellX + i) * cell + cell / 2.0);
                    sz += w * ((cellZ + j) * cell + cell / 2.0);
                    sw += w;
                }
            }
        }
        if (sw <= 0.0) {
            return;
        }
        int hx = (int) Math.round(sx / sw);
        int hz = (int) Math.round(sz / sw);
        if (data.near(hx, hz, cfg.mergeRadiusChunks) != null || data.near(cx, cz, cfg.mergeRadiusChunks) != null) {
            return;
        }
        HotspotData.Hotspot h = data.add(hx, hz, HotspotData.SOURCE_DWELL);
        ChunkSteward.LOGGER.info("hotspot #{} created from dwell at chunk ({}, {}) in dimension {}", h.id, hx, hz,
                world.provider.getDimension());
    }

    private static void checkBed(EntityPlayerMP player) {
        ChunkStewardConfig.Stage2 cfg = ChunkStewardConfig.stage2;
        if (!cfg.enabled || !cfg.homeFromBed || !Dimensions.applies(player.dimension)) {
            return;
        }
        BlockPos bed = player.getBedLocation(player.dimension);
        WorldServer world = DimensionManager.getWorld(player.dimension);
        if (bed == null || world == null) {
            return;
        }
        int cx = bed.getX() >> 4;
        int cz = bed.getZ() >> 4;
        HotspotData data = HotspotData.get(world);
        if (data.near(cx, cz, cfg.mergeRadiusChunks) == null) {
            HotspotData.Hotspot h = data.add(cx, cz, HotspotData.SOURCE_BED);
            ChunkSteward.LOGGER.info("hotspot #{} created from {}'s bed at chunk ({}, {}) in dimension {}", h.id,
                    player.getName(), cx, cz, player.dimension);
        }
    }

    /** A hotspot added by an operator; false if one already exists within the merge radius. */
    static HotspotData.Hotspot addManual(WorldServer world, int cx, int cz) {
        HotspotData data = HotspotData.get(world);
        if (data.near(cx, cz, ChunkStewardConfig.stage2.mergeRadiusChunks) != null) {
            return null;
        }
        return data.add(cx, cz, HotspotData.SOURCE_MANUAL);
    }

    /** Removes the hotspot and its dwell counters, so the same stay does not create it again. */
    static boolean remove(WorldServer world, int id) {
        HotspotData data = HotspotData.get(world);
        HotspotData.Hotspot h = data.find(id);
        if (h == null) {
            return false;
        }
        int cell = ChunkStewardConfig.stage2.cellSizeChunks;
        data.clear3x3(Math.floorDiv(h.x, cell), Math.floorDiv(h.z, cell));
        return data.remove(id);
    }
}
