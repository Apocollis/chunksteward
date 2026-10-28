package com.apocollis.chunksteward.mixin.rtg;

import com.apocollis.chunksteward.rtg.RtgFeatures;
import com.apocollis.chunksteward.rtg.RtgSplitAccess;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldEntitySpawner;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.event.terraingen.TerrainGen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rtg.api.world.RTGWorld;
import rtg.api.world.biome.IRealisticBiome;
import rtg.api.world.gen.RTGChunkGenSettings;
import rtg.event.EventHandlerCommon;

import java.util.Random;

/**
 * Staged populate for RTG-Plus 9.0.3. {@code ChunkGeneratorRTG.populate} runs: events, structures, lakes, lava,
 * dungeons, {@code rDecorate}, animals, ice and snow, the post-populate event, the light fix. The first part stays on the
 * server thread. At {@code rDecorate} the call is handed to {@link RtgFeatures}, which stages it on a worker; the rest
 * of the method is skipped for that populate and run from {@link #chunksteward$tail} when the decorations are applied.
 * The tail is a copy of the original code in the original order (RTG's {@code populate} cannot be resumed
 * mid-method); this mixin applies only to the build whose class shape it was written against.
 */
@Mixin(targets = "rtg.world.gen.ChunkGeneratorRTG", remap = false)
public abstract class MixinChunkGeneratorRTGSplit implements RtgSplitAccess {

    @Shadow
    @Final
    private World world;

    @Shadow
    private Random rand;

    @Shadow
    @Final
    private RTGChunkGenSettings settings;

    @Override
    public Random chunksteward$rand() {
        return rand;
    }

    @Redirect(method = "func_185931_b(II)V", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lrtg/api/world/biome/IRealisticBiome;rDecorate(Lrtg/api/world/RTGWorld;Ljava/util/Random;"
                    + "Lnet/minecraft/util/math/ChunkPos;FZLrtg/api/world/biome/IRealisticBiome;)V"))
    private void chunksteward$decorate(IRealisticBiome biome, RTGWorld rtgWorld, Random random, ChunkPos chunkPos,
                                       float river, boolean hasVillage, IRealisticBiome treeBiome) {
        RtgFeatures.decorate(this, biome, rtgWorld, random, chunkPos, river, hasVillage, treeBiome, world);
    }

    /** While the decorations are staged, the animals and ice events are not fired here; the tail fires them. */
    @Redirect(method = "func_185931_b(II)V", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/event/terraingen/TerrainGen;populate(Lnet/minecraft/world/gen/IChunkGenerator;"
                    + "Lnet/minecraft/world/World;Ljava/util/Random;IIZ"
                    + "Lnet/minecraftforge/event/terraingen/PopulateChunkEvent$Populate$EventType;)Z"))
    private boolean chunksteward$populateEvent(IChunkGenerator generator, World eventWorld, Random random, int chunkX,
                                               int chunkZ, boolean hasVillage,
                                               PopulateChunkEvent.Populate.EventType type) {
        if (RtgFeatures.staged() && (type == PopulateChunkEvent.Populate.EventType.ANIMALS
                || type == PopulateChunkEvent.Populate.EventType.ICE)) {
            return false;
        }
        return TerrainGen.populate(generator, eventWorld, random, chunkX, chunkZ, hasVillage, type);
    }

    @Redirect(method = "func_185931_b(II)V", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/event/ForgeEventFactory;onChunkPopulate(ZLnet/minecraft/world/gen/IChunkGenerator;"
                    + "Lnet/minecraft/world/World;Ljava/util/Random;IIZ)V"))
    private void chunksteward$populateDone(boolean pre, IChunkGenerator generator, World eventWorld, Random random,
                                           int chunkX, int chunkZ, boolean hasVillage) {
        if (!pre && RtgFeatures.staged()) {
            return;
        }
        ForgeEventFactory.onChunkPopulate(pre, generator, eventWorld, random, chunkX, chunkZ, hasVillage);
    }

    @Redirect(method = "func_185931_b(II)V", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lrtg/event/EventHandlerCommon;fixLightingAround(Lnet/minecraft/world/World;"
                    + "Lnet/minecraft/util/math/ChunkPos;)V"))
    private void chunksteward$fixLighting(World fixWorld, ChunkPos pos) {
        if (RtgFeatures.staged()) {
            return;
        }
        EventHandlerCommon.fixLightingAround(fixWorld, pos);
    }

    /** RTG-Plus 9.0.3's {@code populate} after {@code rDecorate}, unchanged apart from where it runs. */
    @Override
    public void chunksteward$tail(IRealisticBiome biome, int chunkX, int chunkZ, boolean hasVillage) {
        IChunkGenerator self = (IChunkGenerator) (Object) this;
        BiomeProvider biomeProvider = this.world.getBiomeProvider();
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        BlockPos blockPos = new BlockPos(chunkX * 16, 0, chunkZ * 16);
        BlockPos offsetpos = blockPos.add(8, 0, 8);

        if (TerrainGen.populate(self, this.world, this.rand, chunkX, chunkZ, hasVillage,
                PopulateChunkEvent.Populate.EventType.ANIMALS)) {
            WorldEntitySpawner.performWorldGenSpawning(this.world, biome.baseBiome(), blockPos.getX() + 8,
                    blockPos.getZ() + 8, 16, 16, this.rand);
        }

        if (TerrainGen.populate(self, this.world, this.rand, chunkX, chunkZ, hasVillage,
                PopulateChunkEvent.Populate.EventType.ICE)) {
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    BlockPos freezePos = this.world.getPrecipitationHeight(offsetpos.add(x, 0, z)).down();
                    if (this.world.canBlockFreezeWater(freezePos)) {
                        this.world.setBlockState(freezePos, Blocks.ICE.getDefaultState(), 2);
                    }

                    BlockPos surfacePos = this.world.getTopSolidOrLiquidBlock(offsetpos.add(x, 0, z));
                    if (this.settings.useSnowLayers) {
                        for (BlockPos checkPos = surfacePos.up(32); checkPos.getY() >= surfacePos.getY();
                             checkPos = checkPos.down()) {
                            if (this.world.getBlockState(checkPos).getMaterial() == Material.AIR) {
                                float temp = biomeProvider.getBiome(surfacePos).getTemperature(checkPos);
                                if (temp <= this.settings.getClampedSnowLayerTemp()
                                        && Blocks.SNOW_LAYER.canPlaceBlockAt(this.world, checkPos)) {
                                    this.world.setBlockState(checkPos, Blocks.SNOW_LAYER.getDefaultState(), 2);
                                    checkPos = checkPos.down();
                                }
                            }
                        }
                    }
                }
            }
        }

        ForgeEventFactory.onChunkPopulate(false, self, this.world, this.rand, chunkX, chunkZ, hasVillage);
        EventHandlerCommon.fixLightingAround(this.world, chunkPos);
    }
}
