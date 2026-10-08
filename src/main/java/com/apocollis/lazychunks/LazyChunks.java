package com.apocollis.lazychunks;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = LazyChunks.MODID, name = "Lazy Chunks 1.12", version = LazyChunks.VERSION,
        acceptableRemoteVersions = "*")
public class LazyChunks {
    public static final String MODID = "lazychunks";
    public static final String VERSION = "0.2";
    public static final Logger LOGGER = LogManager.getLogger(MODID);
}
