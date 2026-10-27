package com.apocollis.chunksteward;

import com.apocollis.chunksteward.server.ChunkStewardCommand;
import com.apocollis.chunksteward.server.SessionState;
import com.apocollis.chunksteward.server.TerrainPipeline;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = ChunkSteward.MODID, name = "Chunk Steward", version = ChunkSteward.VERSION,
        acceptableRemoteVersions = "*")
public class ChunkSteward {
    public static final String MODID = "chunksteward";
    public static final String VERSION = "1.1";
    public static final Logger LOGGER = LogManager.getLogger(MODID);
    private boolean busRegistered;

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        SessionState.reset("server starting");
        event.registerServerCommand(new ChunkStewardCommand());
        if (!busRegistered) {
            MinecraftForge.EVENT_BUS.register(this);
            busRegistered = true;
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && !event.getWorld().isRemote) {
            TerrainPipeline.onDimensionUnloaded(event.getWorld().provider.getDimension());
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        SessionState.reset("server stopped");
    }
}
