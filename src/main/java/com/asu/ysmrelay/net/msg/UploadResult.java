package com.asu.ysmrelay.net.msg;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：终态结果。
 *
 * <p>无论成功失败，这条消息都是本次上传的最后一条。客户端收到就清掉本地传输状态。
 *
 * <h2>本类是整条链路唯一的状态值域</h2>
 * <p>{@code ProbeReply.status} 也用这里的常量，不另立一套编号。历史上 {@code ProbeReply.ALLOWED}
 * 与 {@code UploadResult.COMMITTED} 都是 {@code 0}，日志里 {@code status=0} 分不清是「准入通过」
 * 还是「提交成功」，排障只能靠猜。所以 {@code 0} 归 {@code COMMITTED}，准入用 {@code -1}。
 *
 * <p><b>任何人不得改号。</b>编号是线协议的一部分，服务端和客户端各持一份，改号等于协议不兼容。
 * 要加语义只能往后加新值。
 *
 * <h2>probe 阶段允许与禁止的取值</h2>
 * <p>{@code onProbe} 只能回这 7 个：
 * {@code -1 ALLOWED / 1 ALREADY_AVAILABLE / 4 REJECTED_POLICY / 5 REJECTED_QUOTA /
 * 6 REJECTED_BUSY / 7 REJECTED_IDENTITY / 8 REJECTED_QUEUE_FULL}。
 *
 * <p>禁止回这 5 个：{@code 0 COMMITTED / 2 PENDING_REVIEW / 3 REJECTED_BY_OWNER /
 * 9 FAILED_CONVERSION / 10 CANCELLED}。
 *
 * <p><b>禁止清单里最危险的是 {@code 2}（PENDING_REVIEW）</b>：probe 阶段客户端还没有
 * {@code transferId}，回它会客户端在 {@code transferId == 0} 的状态下进「等待服主审批」分支，
 * 而那个分支永远等不到 approve 结果包 —— 因为压根没建立过 transfer。
 * 「等待审批」只有 import 之后（已经有 transferId）才允许出现。
 */
public final class UploadResult {
    // ------------------------------------------------------------------
    // 唯一值域（线协议，不得改号）
    // ------------------------------------------------------------------

    /** 落盘 + 走进目录成功，{@code modelId} 是服务端自己重算出来的值。 */
    public static final int COMMITTED = 0;
    /** 服务端已有同 hash 模型，没有新增任何文件。probe 阶段也会回这个（0 字节传输）。 */
    public static final int ALREADY_AVAILABLE = 1;
    /** 内容已收下并转换完成，等服主审批。**probe 阶段禁止回**，见类注释。 */
    public static final int PENDING_REVIEW = 2;
    /** 服主拒绝了这次申请，或申请超时过期。 */
    public static final int REJECTED_BY_OWNER = 3;
    /** 服务端策略拒绝：关闭上传、无权限、模型服务未就绪、玩家被拉黑。 */
    public static final int REJECTED_POLICY = 4;
    /** 体积或配额拒绝：单模型上限、每人累计、全局总量、暂存区总量、磁盘剩余空间。 */
    public static final int REJECTED_QUOTA = 5;
    /** 这个玩家已有另一个上传在进行中。 */
    public static final int REJECTED_BUSY = 6;
    /** 请求本身的标识/声明确实有问题：标识长度不对、文件为空、扩展名不支持、声明与实际不符。 */
    public static final int REJECTED_IDENTITY = 7;
    /** 待审队列已满。 */
    public static final int REJECTED_QUEUE_FULL = 8;
    /**
     * 转换或落盘阶段失败。
     *
     * <p>同时兼「服务端内部故障」：暂存写入 IO 中断、导入异常这些也归这里。
     * 值域里没有单独的 internal-failure 位，而客户端看到的动作相同（这次上传结束了，没成功），
     * 所以不再多开一个值。
     */
    public static final int FAILED_CONVERSION = 9;
    /** 客户端主动取消。 */
    public static final int CANCELLED = 10;

    // ------------------------------------------------------------------

    public final long transferId;
    public final int status;
    /** 成功时是服务端重算的 modelId（32 字节）；失败时为空数组。 */
    public final byte[] modelId;
    /** 成功时是目录地址，例如 {@code global:custom/xxx.mxc}。 */
    public final String location;
    public final String message;

    public UploadResult(long transferId, int status, byte[] modelId, String location, String message) {
        this.transferId = transferId;
        this.status = status;
        this.modelId = modelId == null ? new byte[0] : modelId;
        this.location = location == null ? "" : location;
        this.message = message == null ? "" : message;
    }

    public static void encode(UploadResult message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.transferId);
        buffer.writeVarInt(message.status);
        buffer.writeByteArray(message.modelId);
        buffer.writeUtf(message.location, 256);
        buffer.writeUtf(message.message, 512);
    }

    public static UploadResult decode(FriendlyByteBuf buffer) {
        var transferId = buffer.readVarLong();
        var status = buffer.readVarInt();
        var modelId = buffer.readByteArray(UploadProbe.HASH_BYTES);
        var location = buffer.readUtf(256);
        var text = buffer.readUtf(512);
        return new UploadResult(transferId, status, modelId, location, text);
    }

    public static void handle(UploadResult message, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.asu.ysmrelay.client.ClientUploads.handleResult(message));
    }
}
