package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.GeneratorPlan;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/**
 * The {@code IWorldGenerator} call of Forge's {@code GameRegistry.generateWorld} loop: timed by class, and filtered by the
 * plan of the thread when mod generators are staged (see {@link GeneratorPlan}).
 */
@Mixin(value = GameRegistry.class, remap = false)
public abstract class MixinGameRegistry {

    @Redirect(method = "generateWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/common/IWorldGenerator;generate(Ljava/util/Random;IILnet/minecraft/world/World;Lnet/minecraft/world/gen/IChunkGenerator;Lnet/minecraft/world/chunk/IChunkProvider;)V"))
    private static void chunksteward$generate(IWorldGenerator generator, Random random, int chunkX, int chunkZ,
                                            World world, IChunkGenerator chunkGenerator,
                                            IChunkProvider chunkProvider) {
        GeneratorPlan.dispatch(generator, random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
    }
}
