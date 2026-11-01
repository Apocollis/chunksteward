package com.apocollis.chunksteward.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla sizes {@code buffer} exactly, then writes into a {@code wrappedBuffer} that cannot grow. Depths Update and
 * Roughly Enough IDs still leave that call in {@code getWriteBuffer}. A size that is short by a few bytes crashes the
 * server thread. The write goes into a buffer that can grow, and {@code buffer} is replaced with the bytes actually
 * written. The array is reused only when it is already that length; a larger backing array would be sent whole.
 */
@Mixin(SPacketChunkData.class)
public abstract class MixinSPacketChunkData {

    @Shadow
    private byte[] buffer;

    @Redirect(method = "getWriteBuffer()Lio/netty/buffer/ByteBuf;", at = @At(value = "INVOKE",
            target = "Lio/netty/buffer/Unpooled;wrappedBuffer([B)Lio/netty/buffer/ByteBuf;"))
    private ByteBuf chunksteward$growableWriteBuffer(byte[] calculated) {
        return Unpooled.buffer(calculated.length);
    }

    @Inject(method = "extractChunkData(Lnet/minecraft/network/PacketBuffer;Lnet/minecraft/world/chunk/Chunk;ZI)I",
            at = @At("RETURN"))
    private void chunksteward$fitWrittenBytes(PacketBuffer buf, Chunk chunk, boolean full, int changedSectionFilter,
                                              CallbackInfoReturnable<Integer> cir) {
        int written = buf.writerIndex();
        if (buf.hasArray() && buf.arrayOffset() == 0 && buf.array().length == written) {
            this.buffer = buf.array();
            return;
        }
        byte[] exact = new byte[written];
        if (written > 0) {
            buf.getBytes(0, exact);
        }
        this.buffer = exact;
    }
}
