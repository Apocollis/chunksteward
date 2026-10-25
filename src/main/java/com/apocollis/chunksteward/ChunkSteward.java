package com.apocollis.chunksteward;

import com.apocollis.chunksteward.server.ChunkStewardCommand;
import com.apocollis.chunksteward.server.SessionState;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = ChunkSteward.MODID, name = "Chunk Steward", version = ChunkSteward.VERSION,
        acceptableRemoteVersions = "*")
public class ChunkSteward {
    public static final String MODID = "chunksteward";
    public static final String VERSION = "1.0";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        SessionState.reset("server starting");
        event.registerServerCommand(new ChunkStewardCommand());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        SessionState.reset("server stopped");
    }
}
