package com.apocollis.chunksteward;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Late mixin configs: the ones that target another mod's classes and so must not be in the required early config.
 * {@code mixins.chunksteward.rtg.json} is queued only when RTG-Plus (the build whose chunks are
 * {@code rtg.world.gen.LatePopulatingChunk}) is loaded. Older RTG keeps the vanilla chunk class, which the early
 * config already gates. Dimension generators are queued when that class file is in a loaded mod jar. Loading the
 * class first would stop the mixin applying.
 */
public class ChunkStewardLateMixinLoader implements ILateMixinLoader {
    static final String RTG_CONFIG = "mixins.chunksteward.rtg.json";
    static final String DEPTHS_CONFIG = "mixins.chunksteward.depths.json";
    private static final String DEPTHS_CLASS_ENTRY = "sayys/depthsupdate/mixin/MixinChunkProviderServer.class";
    private static final String RTG_CLASS_ENTRY = "rtg/world/gen/LatePopulatingChunk.class";

    private static final Map<String, String> DIMENSION_CONFIGS = new LinkedHashMap<String, String>();

    static {
        DIMENSION_CONFIGS.put("mixins.chunksteward.netherex.json",
                "logictechcorp/netherex/world/generation/ChunkGeneratorNetherEx.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.netherapi.json",
                "git/jbredwards/nether_api/mod/common/world/gen/ChunkGeneratorTheEnd.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.endexpansion.json",
                "com/example/structure/world/Biome/BiomeBarrendLands.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.aurorian.json",
                "com/shiroroku/theaurorian/World/AurorianChunkGenerator.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.atum.json",
                "com/teammetallurgy/atum/world/ChunkGeneratorAtum.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.twilight.json",
                "twilightforest/world/ChunkGeneratorTwilightForest.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.aether.json",
                "com/gildedgames/the_aether/world/ChunkProviderAether.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.beneath.json",
                "com/shinoow/beneath/common/world/gen/ChunkProviderDeepDank.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.betweenlands.json",
                "thebetweenlands/common/world/gen/ChunkGeneratorBetweenlands.class");
        DIMENSION_CONFIGS.put("mixins.chunksteward.emptiness.json",
                "thecodex6824/thaumicaugmentation/common/world/ChunkGeneratorEmptiness.class");
    }

    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<String>();
        configs.add(RTG_CONFIG);
        configs.add(DEPTHS_CONFIG);
        configs.addAll(DIMENSION_CONFIGS.keySet());
        return configs;
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        if (RTG_CONFIG.equals(mixinConfig)) {
            return modHasClass("rtg", RTG_CLASS_ENTRY);
        }
        if (DEPTHS_CONFIG.equals(mixinConfig)) {
            return modHasClass("depthsupdate", DEPTHS_CLASS_ENTRY);
        }
        String entry = DIMENSION_CONFIGS.get(mixinConfig);
        if (entry == null) {
            return false;
        }
        return anyModHas(entry);
    }

    private static boolean modHasClass(String modid, String classEntry) {
        try {
            ModContainer mod = Loader.instance().getIndexedModList().get(modid);
            if (mod == null) {
                return false;
            }
            return sourceHas(mod.getSource(), classEntry);
        } catch (Throwable t) {
            ChunkSteward.LOGGER.warn("pipeline mixin {} not queued: {}", classEntry, t.toString());
            return false;
        }
    }

    /** A mod whose source cannot be read (this pack's {@code minecraft.jar} entry) is skipped, not fatal to the scan. */
    private static boolean anyModHas(String classEntry) {
        List<ModContainer> mods;
        try {
            mods = Loader.instance().getActiveModList();
        } catch (Throwable t) {
            ChunkSteward.LOGGER.warn("pipeline mixin {} not queued: {}", classEntry, t.toString());
            return false;
        }
        for (ModContainer mod : mods) {
            try {
                if (sourceHas(mod.getSource(), classEntry)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean sourceHas(File source, String classEntry) throws java.io.IOException {
        if (source == null) {
            return false;
        }
        if (source.isDirectory()) {
            return new File(source, classEntry).isFile();
        }
        try (ZipFile zip = new ZipFile(source)) {
            return zip.getEntry(classEntry) != null;
        }
    }
}
