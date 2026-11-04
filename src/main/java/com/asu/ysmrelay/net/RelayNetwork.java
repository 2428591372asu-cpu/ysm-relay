package com.asu.ysmrelay.net;

import com.asu.ysmrelay.net.msg.ProbeReply;
import com.asu.ysmrelay.net.msg.UploadChunk;
import com.asu.ysmrelay.net.msg.UploadProbe;
import com.asu.ysmrelay.net.msg.UploadProgress;
import com.asu.ysmrelay.net.msg.UploadResult;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * relay 自己的上传通道。
 *
 * <p>为什么不用 YSM 的 {@code ysm:protocol}：那边的 {@code ProtocolMessageRegistry} 是
 * {@code Map.copyOf} 造出来的不可变表，外部没有插入消息 id 的入口；硬插会让整条会话被判
 * {@code SESSION_DECISION_DECLINE}。所以上传走独立 channel，和 YSM 协议零耦合。
 *
 * <p>版本策略：用 {@link NetworkRegistry#acceptMissingOr(String)}。装了 relay 的双方要求版本
 * 相同；只服务端装了 relay 的客户端不会被踢出服务器，只是用不了上传功能。
 */
public final class RelayNetwork {
    /**
     * 通道协议版本。加减消息或改字段都要 +1。
     *
     * <p>v2（2026-11-04）：{@code ProbeReply} 的 status 改用 {@code UploadResult} 的唯一值域
     * （{@code ALLOWED} 由 0 变 -1，整表重排），并新增 {@code rateLimitBytesPerSecond} 字段。
     * 老客户端 v1 的 {@code decode} 读不出新布局，所以必须升版本 —— 靠
     * {@link NetworkRegistry#acceptMissingOr(String)} 让版本不符的客户端进服但用不了上传。
     */
    public static final String PROTOCOL_VERSION = "2";

    public static final ResourceLocation CHANNEL_NAME = new ResourceLocation("ysm_relay", "upload");

    private static final int ID_PROBE = 0;
    private static final int ID_CHUNK = 1;
    private static final int ID_PROBE_REPLY = 2;
    private static final int ID_PROGRESS = 3;
    private static final int ID_RESULT = 4;

    private static SimpleChannel channel;

    private RelayNetwork() {}

    /** 在 mod 构造函数里调用一次。重复调用是 no-op。 */
    public static synchronized void init() {
        if (channel != null) {
            return;
        }
        var accept = NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION);
        var created = NetworkRegistry.newSimpleChannel(CHANNEL_NAME, () -> PROTOCOL_VERSION, accept, accept);

        created.messageBuilder(UploadProbe.class, ID_PROBE, NetworkDirection.PLAY_TO_SERVER)
            .encoder(UploadProbe::encode)
            .decoder(UploadProbe::decode)
            .consumerMainThread(UploadProbe::handle)
            .add();

        created.messageBuilder(UploadChunk.class, ID_CHUNK, NetworkDirection.PLAY_TO_SERVER)
            .encoder(UploadChunk::encode)
            .decoder(UploadChunk::decode)
            .consumerMainThread(UploadChunk::handle)
            .add();

        created.messageBuilder(ProbeReply.class, ID_PROBE_REPLY, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ProbeReply::encode)
            .decoder(ProbeReply::decode)
            .consumerMainThread(ProbeReply::handle)
            .add();

        created.messageBuilder(UploadProgress.class, ID_PROGRESS, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(UploadProgress::encode)
            .decoder(UploadProgress::decode)
            .consumerMainThread(UploadProgress::handle)
            .add();

        created.messageBuilder(UploadResult.class, ID_RESULT, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(UploadResult::encode)
            .decoder(UploadResult::decode)
            .consumerMainThread(UploadResult::handle)
            .add();

        channel = created;
    }

    private static SimpleChannel channel() {
        var active = channel;
        if (active == null) {
            throw new IllegalStateException("Relay channel is not initialised");
        }
        return active;
    }

    /** 客户端是否和被连的服务器共享本通道（双方都装了 relay 且版本一致）。 */
    public static boolean isAvailable(net.minecraft.network.Connection connection) {
        return connection != null && connection.channel() != null
            && channel().isRemotePresent(connection);
    }

    /** 发到服务端。 */
    public static <MSG> void toServer(MSG message) {
        channel().sendToServer(message);
    }

    /** 发给单个玩家。 */
    public static <MSG> void toPlayer(ServerPlayer player, MSG message) {
        channel().send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
