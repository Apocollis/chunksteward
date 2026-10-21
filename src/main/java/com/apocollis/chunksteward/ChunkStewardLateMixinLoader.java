package com.apocollis.chunksteward;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Late mixin configs: the ones that target another mod's classes and so must not be in the required early config.
 * {@code mixins.chunksteward.rtg.json} is queued only when RTG-Plus (the build whose chunks are
 * {@code rtg.world.gen.LatePopulatingChunk}) is loaded. Older RTG keeps the vanilla chunk class, which the early
 * config already gates. The mod jar is checked as a zip entry: loading the class first would stop the mixin applying.
 */
public class ChunkStewardLateMixinLoader implements ILateMixinLoader {
    static final String RTG_CONFIG = "mixins.chunksteward.rtg.json";
    private static final String RTG_CLASS_ENTRY = "rtg/world/gen/LatePopulatingChunk.class";

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList(RTG_CONFIG);
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        if (RTG_CONFIG.equals(mixinConfig)) {
            return rtgHasLatePopulatingChunk();
        }
        return true;
    }

    private static boolean rtgHasLatePopulatingChunk() {
        try {
            ModContainer rtg = Loader.instance().getIndexedModList().get("rtg");
            if (rtg == null) {
                return false;
            }
            File source = rtg.getSource();
            if (source == null) {
                return false;
            }
            if (source.isDirectory()) {
                return new File(source, RTG_CLASS_ENTRY).isFile();
            }
            try (ZipFile zip = new ZipFile(source)) {
                return zip.getEntry(RTG_CLASS_ENTRY) != null;
            }
        } catch (Throwable t) {
            ChunkSteward.LOGGER.warn("RTG-Plus populate support not queued: {}", t.toString());
            return false;
        }
    }
}
