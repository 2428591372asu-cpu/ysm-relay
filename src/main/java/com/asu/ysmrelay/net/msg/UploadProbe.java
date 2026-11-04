package com.asu.ysmrelay.net.msg;

import com.asu.ysmrelay.upload.UploadService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：上传前探测。
 *
 * <p>只带三项：内容哈希（BLAKE3，32 字节）、文件字节数、原始文件名。服务端在收到一个数据字节
 * 之前就能完成三件判断：这个模型是不是已经有了、是不是超了单模型上限、这个玩家有没有上传权限。
 *
 * <p>哈希必须是 YSM 的 modelId 口径（{@code ModelHashing.blake3}）。用 SHA-256 会让去重永远
 * 不命中，因为服务端目录的键是 BLAKE3 的 {@code Hash256}。
 */
public final class UploadProbe {
    /** BLAKE3 摘要固定 32 字节。 */
    public static final int HASH_BYTES = 32;
    /** 文件名只是给人看的，服务端不据此拼路径。 */
    public static final int FILE_NAME_MAX = 200;

    public final byte[] blake3;
    public final long size;
    public final String fileName;

    public UploadProbe(byte[] blake3, long size, String fileName) {
        this.blake3 = blake3;
        this.size = size;
        this.fileName = fileName == null ? "" : fileName;
    }

    public static void encode(UploadProbe message, FriendlyByteBuf buffer) {
        buffer.writeByteArray(message.blake3);
        buffer.writeVarLong(message.size);
        buffer.writeUtf(message.fileName, FILE_NAME_MAX);
    }

    public static UploadProbe decode(FriendlyByteBuf buffer) {
        var hash = buffer.readByteArray(HASH_BYTES);
        var size = buffer.readVarLong();
        var name = buffer.readUtf(FILE_NAME_MAX);
        return new UploadProbe(hash, size, name);
    }

    public static void handle(UploadProbe message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            return;
        }
        if (message.blake3.length != HASH_BYTES || message.size < 0) {
            return;
        }
        UploadService.instance().onProbe(sender, message);
    }

    @Override
    public String toString() {
        return "UploadProbe{size=" + size + ", file=" + fileName + ", blake3="
            + Arrays.toString(Arrays.copyOf(blake3, Math.min(blake3.length, 4))) + "...}";
    }
}
