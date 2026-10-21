package com.apocollis.chunksteward.mixin.rtg;

import com.apocollis.chunksteward.server.GenMetrics;
import com.apocollis.chunksteward.server.StageGate;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RTG-Plus replaces the chunks it generates with {@code rtg.world.gen.LatePopulatingChunk}, which overrides the outer
 * {@code populate(IChunkProvider, IChunkGenerator)} and never reaches the inner {@code populate(IChunkGenerator)} that
 * {@code MixinChunk} gates. Its pass populates a 5x5 window around the loaded chunk from a static {@code populate},
 * with an internal stack for populates that start inside another one. This mixin applies the same stage gate:
 * <ul>
 * <li>outer method: one decision for the chunk that was asked for; a deferred or re-entrant call is cancelled</li>
 * <li>static {@code populate}: one decision per chunk in the window, before RTG touches its stack</li>
 * <li>{@code doPopulate}: generator populate timing</li>
 * </ul>
 * Loaded through a late mixin config only when RTG-Plus is present (see {@code ChunkStewardLateMixinLoader}). The
 * target is not a Minecraft class, so the injectors do not remap; the overridden outer method is named by its SRG name.
 * {@code checkLight} inside {@code doPopulate} sets the terrain-populated flag, as in vanilla.
 */
@Mixin(targets = "rtg.world.gen.LatePopulatingChunk", remap = false)
public abstract class MixinLatePopulatingChunk {

    @Inject(method = {"populate(Lnet/minecraft/world/chunk/IChunkProvider;Lnet/minecraft/world/gen/IChunkGenerator;)V",
            "func_186030_a(Lnet/minecraft/world/chunk/IChunkProvider;Lnet/minecraft/world/gen/IChunkGenerator;)V"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$outerHead(IChunkProvider provider, IChunkGenerator generator, CallbackInfo ci) {
        if (!StageGate.rtgOuterAllow((Chunk) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = {"populate(Lnet/minecraft/world/chunk/IChunkProvider;Lnet/minecraft/world/gen/IChunkGenerator;)V",
            "func_186030_a(Lnet/minecraft/world/chunk/IChunkProvider;Lnet/minecraft/world/gen/IChunkGenerator;)V"},
            at = @At("RETURN"), remap = false)
    private void chunksteward$outerReturn(IChunkProvider provider, IChunkGenerator generator, CallbackInfo ci) {
        StageGate.rtgOuterExit((Chunk) (Object) this);
    }

    @Inject(method = "populate(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/IChunkGenerator;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void chunksteward$populateHead(Chunk chunk, IChunkGenerator generator, CallbackInfo ci) {
        // Already populated: RTG's own body does nothing here. Cancel so the RETURN hook cannot unwind a frame
        // that belongs to the chunk that is really decorating.
        if (chunk.isTerrainPopulated() || !StageGate.rtgMemberAllow(chunk)) {
            ci.cancel();
            return;
        }
        StageGate.populateEnter(chunk);
        GenMetrics.enter(GenMetrics.POPULATE);
    }

    @Inject(method = "populate(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/IChunkGenerator;)V",
            at = @At("RETURN"), remap = false)
    private static void chunksteward$populateReturn(Chunk chunk, IChunkGenerator generator, CallbackInfo ci) {
        GenMetrics.exit(GenMetrics.POPULATE);
        StageGate.populateExit(chunk);
    }

    @Inject(method = "doPopulate(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/IChunkGenerator;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/gen/IChunkGenerator;populate(II)V", remap = true),
            remap = false)
    private static void chunksteward$generatorPopulateStart(Chunk chunk, IChunkGenerator generator, CallbackInfo ci) {
        GenMetrics.enter(GenMetrics.GEN_POPULATE);
    }

    @Inject(method = "doPopulate(Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/IChunkGenerator;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/gen/IChunkGenerator;populate(II)V",
                    shift = At.Shift.AFTER, remap = true), remap = false)
    private static void chunksteward$generatorPopulateEnd(Chunk chunk, IChunkGenerator generator, CallbackInfo ci) {
        GenMetrics.exit(GenMetrics.GEN_POPULATE);
    }
}
