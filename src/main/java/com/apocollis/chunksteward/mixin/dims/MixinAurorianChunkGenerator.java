package com.apocollis.chunksteward.mixin.dims;

import com.apocollis.chunksteward.server.TerrainPipeline;
import com.shiroroku.theaurorian.World.AurorianChunkGenerator;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * The Aurorian builds one {@code Random} from the world seed and never reseeds it, so a chunk's surface depended on
 * which chunks were generated before it. It is reseeded from the seed and the chunk position before each generated
 * chunk, and again (position terms swapped) before populate.
 */
@Mixin(value = AurorianChunkGenerator.class, remap = false)
public abstract class MixinAurorianChunkGenerator {

    @Shadow
    @Final
    private World worldObj;

    @Shadow
    @Final
    private Random random;

    @Inject(method = "func_185932_a(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true, remap = false)
    private void chunksteward$pipeline(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk ready = TerrainPipeline.takeGenerated((IChunkGenerator) (Object) this, x, z);
        if (ready != null) {
            cir.setReturnValue(ready);
            return;
        }
        random.setSeed(salt() ^ (x * 341873128712L + z * 132897987541L));
    }

    @Inject(method = "func_185931_b(II)V", at = @At("HEAD"), remap = false)
    private void chunksteward$populateSeed(int x, int z, CallbackInfo ci) {
        random.setSeed(salt() ^ (x * 132897987541L + z * 341873128712L));
    }

    private long salt() {
        return (worldObj.getSeed() + 516L) * 314L;
    }
}
