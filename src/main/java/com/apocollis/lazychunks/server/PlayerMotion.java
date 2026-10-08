package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;
import com.apocollis.lazychunks.mixin.AccessorPlayerChunkMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player horizontal speed and direction, updated at server tick END from position deltas (server-side motion
 * fields are not reliable for players). The direction is the view direction when nearly still and the travel
 * direction when moving fast, blended in between. When it turns past {@code resortAngleDegrees} the player's
 * {@code PlayerChunkMap} is flagged to re-sort its missing chunks (vanilla only sorts on ticks divisible by 4).
 */
@Mod.EventBusSubscriber
public final class PlayerMotion {
    private static final double VELOCITY_ALPHA = 0.5;
    private static final double TELEPORT_SQ = 10.0 * 10.0;

    static final class State {
        double lastX;
        double lastZ;
        int lastDim = Integer.MIN_VALUE;
        double velX;
        double velZ;
        /** Blocks per tick, horizontal. */
        double speed;
        double dirX;
        double dirZ = 1.0;
        double sortDirX;
        double sortDirZ = 1.0;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private PlayerMotion() {
    }

    static State get(EntityPlayer player) {
        return STATES.get(player.getUniqueID());
    }

    static double speed(EntityPlayer player) {
        State state = STATES.get(player.getUniqueID());
        return state == null ? 0.0 : state.speed;
    }

    /** Unit direction as {x, z} into {@code out}. Falls back to the view direction for untracked players. */
    static void direction(EntityPlayer player, double[] out) {
        State state = STATES.get(player.getUniqueID());
        if (state != null) {
            out[0] = state.dirX;
            out[1] = state.dirZ;
        } else {
            double yaw = Math.toRadians(player.rotationYaw);
            out[0] = -Math.sin(yaw);
            out[1] = Math.cos(yaw);
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
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        LazyChunksConfig.Ordering cfg = LazyChunksConfig.ordering;
        boolean resort = cfg.enabled && cfg.directionPenalty > 0.0 && cfg.resortAngleDegrees > 0.0;
        double resortCos = Math.cos(Math.toRadians(cfg.resortAngleDegrees));
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            State state = STATES.computeIfAbsent(player.getUniqueID(), id -> new State());
            update(player, state, cfg);
            if (resort && state.dirX * state.sortDirX + state.dirZ * state.sortDirZ < resortCos) {
                state.sortDirX = state.dirX;
                state.sortDirZ = state.dirZ;
                ((AccessorPlayerChunkMap) player.getServerWorld().getPlayerChunkMap())
                        .lazychunks$setSortMissingChunks(true);
            }
        }
    }

    private static void update(EntityPlayerMP player, State state, LazyChunksConfig.Ordering cfg) {
        double dx = player.posX - state.lastX;
        double dz = player.posZ - state.lastZ;
        state.lastX = player.posX;
        state.lastZ = player.posZ;
        if (player.dimension != state.lastDim || dx * dx + dz * dz > TELEPORT_SQ) {
            state.lastDim = player.dimension;
            state.velX = 0.0;
            state.velZ = 0.0;
        } else {
            state.velX += VELOCITY_ALPHA * (dx - state.velX);
            state.velZ += VELOCITY_ALPHA * (dz - state.velZ);
        }
        state.speed = Math.sqrt(state.velX * state.velX + state.velZ * state.velZ);

        double yaw = Math.toRadians(player.rotationYaw);
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        double span = Math.max(1.0e-6, cfg.velocityFullSpeed - cfg.velocityMinSpeed);
        double w = Math.max(0.0, Math.min(1.0, (state.speed - cfg.velocityMinSpeed) / span));
        double x = lookX;
        double z = lookZ;
        if (w > 0.0 && state.speed > 1.0e-6) {
            x = lookX * (1.0 - w) + state.velX / state.speed * w;
            z = lookZ * (1.0 - w) + state.velZ / state.speed * w;
        }
        double len = Math.sqrt(x * x + z * z);
        if (len < 1.0e-6) {
            x = lookX;
            z = lookZ;
            len = 1.0;
        }
        state.dirX = x / len;
        state.dirZ = z / len;
    }
}
