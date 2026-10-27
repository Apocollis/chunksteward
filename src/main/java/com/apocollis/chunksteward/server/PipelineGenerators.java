package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.AccessorChunkGeneratorEnd;
import com.apocollis.chunksteward.mixin.AccessorChunkGeneratorHell;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.ChunkGeneratorEnd;
import net.minecraft.world.gen.ChunkGeneratorHell;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.WorldInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Private copies of the generator classes this pack actually runs. A subclass is not accepted as its parent: NetherEx
 * is not {@code ChunkGeneratorHell}. RTG's copy shares {@code RTGWorld}; verify is what shows whether that matches.
 */
final class PipelineGenerators {
    static final String HELL = "net.minecraft.world.gen.ChunkGeneratorHell";
    static final String END = "net.minecraft.world.gen.ChunkGeneratorEnd";
    static final String NETHEREX = "logictechcorp.netherex.world.generation.ChunkGeneratorNetherEx";
    static final String NETHER_API_NETHER = "git.jbredwards.nether_api.mod.common.world.gen.ChunkGeneratorNether";
    static final String NETHER_API_END = "git.jbredwards.nether_api.mod.common.world.gen.ChunkGeneratorTheEnd";
    static final String NETHER_API_END_BIOME = "git.jbredwards.nether_api.mod.common.world.biome.BiomeProviderTheEnd";
    static final String AURORIAN = "com.shiroroku.theaurorian.World.AurorianChunkGenerator";
    static final String ATUM = "com.teammetallurgy.atum.world.ChunkGeneratorAtum";
    static final String TWILIGHT = "twilightforest.world.ChunkGeneratorTwilightForest";
    static final String TWILIGHT_VOID = "twilightforest.world.ChunkGeneratorTwilightVoid";
    static final String AETHER = "com.gildedgames.the_aether.world.ChunkProviderAether";
    static final String BENEATH = "com.shinoow.beneath.common.world.gen.ChunkProviderDeepDank";
    static final String RTG = "rtg.world.gen.ChunkGeneratorRTG";
    static final String RTG_BIOME = "rtg.world.biome.BiomeProviderRTG";
    static final String BETWEEN = "thebetweenlands.common.world.gen.ChunkGeneratorBetweenlands";
    static final String BETWEEN_BIOME = "thebetweenlands.common.world.gen.biome.BiomeProviderBetweenlands";
    static final String EMPTINESS = "thecodex6824.thaumicaugmentation.common.world.ChunkGeneratorEmptiness";

    private static final String PYRAMID_PLATE = "com.apocollis.aqtweaks.atum.AtumPyramidPlate";

    private static final String[] STASH_FIELDS = {
            "STASHED_VILLAGE", "STASHED_RTG", "STASHED_VILLAGE_BY_DIM", "STASHED_RTG_BY_DIM"
    };

    private static final Set<String> COPYABLE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            HELL, END, NETHEREX, NETHER_API_NETHER, NETHER_API_END,
            AURORIAN, ATUM, TWILIGHT, TWILIGHT_VOID, AETHER, BENEATH,
            RTG, BETWEEN, EMPTINESS)));

    private PipelineGenerators() {
    }

    static boolean supported(IChunkGenerator generator) {
        return generator != null && COPYABLE.contains(generator.getClass().getName());
    }

    static boolean listed(IChunkGenerator generator) {
        if (generator == null) {
            return false;
        }
        String name = generator.getClass().getName();
        String[] allowed = ChunkStewardConfig.pipeline.allowedGenerators;
        if (allowed == null) {
            return false;
        }
        for (String entry : allowed) {
            if (entry != null && name.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    static IChunkGenerator of(World world) {
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) {
            return null;
        }
        return ((ChunkProviderServer) world.getChunkProvider()).chunkGenerator;
    }

    static World worldOf(IChunkGenerator generator) {
        if (generator instanceof ChunkGeneratorHell) {
            return ((AccessorChunkGeneratorHell) generator).chunksteward$world();
        }
        if (generator instanceof ChunkGeneratorEnd) {
            return ((AccessorChunkGeneratorEnd) generator).chunksteward$world();
        }
        for (Class<?> type = generator.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!World.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(generator);
                    if (value instanceof World) {
                        return (World) value;
                    }
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * A new instance of {@code main}'s exact class. Call on the server thread so map-gen and biome events run there.
     * {@code biome} is the provider this copy should keep; Nether API's end generator stores it.
     */
    static IChunkGenerator copy(World world, IChunkGenerator main, BiomeProvider biome) {
        if (main == null) {
            return null;
        }
        String name = main.getClass().getName();
        try {
            if (HELL.equals(name) || NETHEREX.equals(name) || NETHER_API_NETHER.equals(name)) {
                boolean structures = ((AccessorChunkGeneratorHell) main).chunksteward$structures();
                return construct(main.getClass(), new Class<?>[] {World.class, boolean.class, long.class},
                        new Object[] {world, structures, world.getSeed()});
            }
            if (END.equals(name)) {
                AccessorChunkGeneratorEnd access = (AccessorChunkGeneratorEnd) main;
                return new ChunkGeneratorEnd(world, access.chunksteward$mapFeatures(), world.getSeed(), access.chunksteward$spawn());
            }
            if (NETHER_API_END.equals(name)) {
                if (biome == null || !NETHER_API_END_BIOME.equals(biome.getClass().getName())) {
                    return null;
                }
                AccessorChunkGeneratorEnd access = (AccessorChunkGeneratorEnd) main;
                Class<?> biomeType = biome.getClass();
                return construct(main.getClass(), new Class<?>[] {World.class, boolean.class, biomeType, BlockPos.class},
                        new Object[] {world, access.chunksteward$mapFeatures(), biome, access.chunksteward$spawn()});
            }
            if (ATUM.equals(name)) {
                String options = world.getWorldInfo().getGeneratorOptions();
                return construct(main.getClass(), new Class<?>[] {World.class, long.class, boolean.class, String.class},
                        new Object[] {world, world.getSeed(), Boolean.TRUE, options});
            }
            if (TWILIGHT.equals(name) || TWILIGHT_VOID.equals(name)) {
                boolean features = world.getWorldInfo().isMapFeaturesEnabled();
                return construct(main.getClass(), new Class<?>[] {World.class, long.class, boolean.class},
                        new Object[] {world, world.getSeed(), features});
            }
            if (AETHER.equals(name) || BENEATH.equals(name)) {
                return construct(main.getClass(), new Class<?>[] {World.class, long.class},
                        new Object[] {world, world.getSeed()});
            }
            if (AURORIAN.equals(name) || EMPTINESS.equals(name)) {
                return construct(main.getClass(), new Class<?>[] {World.class}, new Object[] {world});
            }
            if (BETWEEN.equals(name)) {
                Field baseBlock = main.getClass().getField("baseBlock");
                Field layerBlock = main.getClass().getField("layerBlock");
                Field layerHeight = main.getClass().getDeclaredField("layerHeight");
                layerHeight.setAccessible(true);
                return construct(main.getClass(),
                        new Class<?>[] {World.class, long.class, Block.class, Block.class, int.class},
                        new Object[] {world, world.getSeed(), baseBlock.get(main), layerBlock.get(main),
                                layerHeight.getInt(main)});
            }
            if (RTG.equals(name)) {
                Class<?> rtgWorldType = Class.forName("rtg.api.world.RTGWorld");
                Object rtgWorld = rtgWorldType.getMethod("getInstance", World.class).invoke(null, world);
                Constructor<?> constructor = main.getClass().getConstructor(rtgWorldType);
                Map<Field, Map<Object, Object>> stash = snapshotVillageStash();
                try {
                    return (IChunkGenerator) constructor.newInstance(rtgWorld);
                } finally {
                    restoreVillageStash(stash);
                }
            }
        } catch (ReflectiveOperationException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not copy {}", name, failed);
        }
        return null;
    }

    /**
     * A provider the worker can mutate. Single-biome and a plain {@code BiomeProvider} are constructed directly. Other
     * classes use a public {@code (World)}, {@code (WorldInfo)}, or {@code (long)} constructor, which covers NetherEx,
     * Nether API, Twilight Forest, the Aurorian, Atum, and the Aether.
     */
    static BiomeProvider copyBiome(World world) {
        BiomeProvider current = world.provider.getBiomeProvider();
        if (current instanceof BiomeProviderSingle) {
            return new BiomeProviderSingle(((BiomeProviderSingle) current).getFixedBiome());
        }
        if (current == null) {
            return null;
        }
        if (current.getClass() == BiomeProvider.class) {
            return new BiomeProvider(world.getWorldInfo());
        }
        Class<?> type = current.getClass();
        try {
            if (RTG_BIOME.equals(type.getName())) {
                Class<?> rtgWorldType = Class.forName("rtg.api.world.RTGWorld");
                Object rtgWorld = rtgWorldType.getMethod("getInstance", World.class).invoke(null, world);
                return (BiomeProvider) type.getConstructor(rtgWorldType).newInstance(rtgWorld);
            }
            if (BETWEEN_BIOME.equals(type.getName())) {
                for (Constructor<?> candidate : type.getConstructors()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if (parameters.length == 2 && parameters[1] == WorldInfo.class && parameters[0].isInstance(world.provider)) {
                        return (BiomeProvider) candidate.newInstance(world.provider, world.getWorldInfo());
                    }
                }
                return null;
            }
            BiomeProvider constructed = tryConstruct(type, new Class<?>[] {World.class}, new Object[] {world});
            if (constructed != null) {
                return constructed;
            }
            constructed = tryConstruct(type, new Class<?>[] {WorldInfo.class}, new Object[] {world.getWorldInfo()});
            if (constructed != null) {
                return constructed;
            }
            return tryConstruct(type, new Class<?>[] {long.class}, new Object[] {world.getSeed()});
        } catch (ReflectiveOperationException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not copy biome provider {}", type.getName(), failed);
            return null;
        }
    }

    private static IChunkGenerator construct(Class<?> type, Class<?>[] parameters, Object[] arguments)
            throws ReflectiveOperationException {
        Constructor<?> constructor = type.getConstructor(parameters);
        return (IChunkGenerator) constructor.newInstance(arguments);
    }

    /**
     * Tweaks replaces the stashed RTG generator at the end of {@code ChunkGeneratorRTG}'s constructor, and again while
     * that generator lays out villages. Verify puts the previous entries back when the run returns.
     */
    static Map<Field, Map<Object, Object>> snapshotVillageStash() {
        Map<Field, Map<Object, Object>> saved = new HashMap<Field, Map<Object, Object>>();
        Class<?> helper;
        try {
            helper = Class.forName("com.apocollis.aqtweaks.rtg.VillageLandHelper");
        } catch (ClassNotFoundException missing) {
            return saved;
        }
        try {
            for (String name : STASH_FIELDS) {
                Field field = helper.getDeclaredField(name);
                field.setAccessible(true);
                Map<?, ?> live = (Map<?, ?>) field.get(null);
                saved.put(field, new HashMap<Object, Object>(live));
            }
        } catch (ReflectiveOperationException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not read the village generator stash", failed);
        }
        return saved;
    }

    static void restoreVillageStash(Map<Field, Map<Object, Object>> saved) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        for (Map.Entry<Field, Map<Object, Object>> entry : saved.entrySet()) {
            try {
                Map<Object, Object> live = (Map<Object, Object>) entry.getKey().get(null);
                live.clear();
                live.putAll(entry.getValue());
            } catch (ReflectiveOperationException failed) {
                ChunkSteward.LOGGER.warn("pipeline could not restore the village generator stash", failed);
            }
        }
    }

    /**
     * Arcana Quest Tweaks registers an Atum pyramid plate in one static map whenever a pyramid start is created, and a
     * verify run creates them for chunks it then discards. Read by name, so a pack without Tweaks returns null.
     */
    static Map<Object, Object> snapshotPyramidPlates() {
        Map<Object, Object> live = pyramidPlates();
        return live == null ? null : new HashMap<Object, Object>(live);
    }

    /**
     * Puts back plates whose footprint is in that dimension's block box: removes the ones added during the run and
     * reverts the ones it replaced. Plates elsewhere belong to the server thread, which keeps generating meanwhile.
     */
    static void restorePyramidPlates(Map<Object, Object> saved, int dimension, int minX, int maxX, int minZ, int maxZ) {
        if (saved == null) {
            return;
        }
        Map<Object, Object> live = pyramidPlates();
        if (live == null) {
            return;
        }
        try {
            for (Map.Entry<Object, Object> entry : new HashMap<Object, Object>(live).entrySet()) {
                Object before = saved.get(entry.getKey());
                if (before != entry.getValue() && plateIn(entry.getValue(), dimension, minX, maxX, minZ, maxZ)) {
                    if (before == null) {
                        live.remove(entry.getKey());
                    } else {
                        live.put(entry.getKey(), before);
                    }
                }
            }
        } catch (ReflectiveOperationException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not restore the Atum pyramid plates", failed);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> pyramidPlates() {
        try {
            Field plates = Class.forName(PYRAMID_PLATE).getDeclaredField("PLATES");
            plates.setAccessible(true);
            return (Map<Object, Object>) plates.get(null);
        } catch (ClassNotFoundException missing) {
            return null;
        } catch (ReflectiveOperationException failed) {
            ChunkSteward.LOGGER.warn("pipeline could not read the Atum pyramid plates", failed);
            return null;
        }
    }

    private static boolean plateIn(Object plate, int dimension, int minX, int maxX, int minZ, int maxZ)
            throws ReflectiveOperationException {
        Class<?> type = plate.getClass();
        return type.getField("dimension").getInt(plate) == dimension
                && type.getField("maxX").getInt(plate) >= minX && type.getField("minX").getInt(plate) <= maxX
                && type.getField("maxZ").getInt(plate) >= minZ && type.getField("minZ").getInt(plate) <= maxZ;
    }

    private static BiomeProvider tryConstruct(Class<?> type, Class<?>[] parameters, Object[] arguments)
            throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = type.getConstructor(parameters);
            return (BiomeProvider) constructor.newInstance(arguments);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }
}
