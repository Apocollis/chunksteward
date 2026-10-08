package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;
import com.google.common.base.Predicate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Players who joined less than {@code warmupTicks} ago load chunks without limits. */
@Mod.EventBusSubscriber
public final class Warmup {
    private static final Map<UUID, Integer> JOINED = new HashMap<>();

    private static final Predicate<EntityPlayerMP> WARM = new Predicate<EntityPlayerMP>() {
        @Override
        public boolean apply(EntityPlayerMP player) {
            if (player == null) {
                return false;
            }
            Integer joined = JOINED.get(player.getUniqueID());
            return joined != null && TickMonitor.tick - joined < LazyChunksConfig.throttle.warmupTicks;
        }
    };

    private Warmup() {
    }

    static boolean isWarm(PlayerChunkMapEntry entry) {
        return !JOINED.isEmpty() && entry.hasPlayerMatching(WARM);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        JOINED.put(event.player.getUniqueID(), TickMonitor.tick);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        JOINED.remove(event.player.getUniqueID());
    }
}
