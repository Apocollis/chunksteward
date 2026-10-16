package com.apocollis.chunksteward;

import com.apocollis.chunksteward.server.ChunkStewardCommand;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = ChunkSteward.MODID, name = "Chunk Steward", version = ChunkSteward.VERSION,
        acceptableRemoteVersions = "*")
public class ChunkSteward {
    public static final String MODID = "chunksteward";
    public static final String VERSION = "0.3";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new ChunkStewardCommand());
    }
}
