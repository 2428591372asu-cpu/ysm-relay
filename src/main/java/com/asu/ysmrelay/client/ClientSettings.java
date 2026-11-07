package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.ConfigRequest;
import com.asu.ysmrelay.net.msg.ConfigSnapshot;
import java.util.Optional;

/**
 * 客户端手里的「服务端分发设置快照」。
 *
 * <p>它只决定界面显示（哪些按钮置灰），不是权威副本：真实判定每次都发生在服务端，
 * 所以这里被改包也改不动服务端行为。
 */
public final class ClientSettings {
    private static volatile ConfigSnapshot snapshot;
    /** 进服后等多久还没收到快照，就认定服务端没装 relay。 */
    private static final int MISSING_TIMEOUT_TICKS = 100;
    private static volatile boolean relayMissing;
    private static volatile int ticksWithoutSnapshot;

    private ClientSettings() {}

    public static Optional<ConfigSnapshot> current() {
        return Optional.ofNullable(snapshot);
    }

    public static void accept(ConfigSnapshot value) {
        snapshot = value;
        relayMissing = false;
        ticksWithoutSnapshot = 0;
        com.asu.ysmrelay.client.SettingsScreen.fromSnapshot();
    }

    /** 断开连接时清空，避免把上一个服的配置带到下一个服。 */
    public static void clear() {
        snapshot = null;
        relayMissing = false;
        ticksWithoutSnapshot = 0;
    }

    /**
     * 这个服务器到底装没装 relay。
     *
     * <p>不再用「超时没回应就算没装」——服务器卡一下会永久误判。改成问 Forge 的通道表：
     * 服务端在登录时把注册过的通道下发给客户端，{@code isRemotePresent} 直接读这份表。
     * 没有本通道 = 确定没装；有通道但没回配置 = 只是卡，不该拦人。
     */
    /** 服务端已把当前玩家设为 0 级（禁用 relay 功能）。 */
    public static boolean disabledByServer() {
        var current = snapshot;
        return current != null && current.effectiveLevel <= 0;
    }

    public static boolean isRelayMissing() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var listener = minecraft.getConnection();
        if (listener == null) {
            // 还在连接中，先不判定
            return false;
        }
        var connection = listener.getConnection();
        try {
            return !com.asu.ysmrelay.net.RelayNetwork.isAvailable(connection);
        } catch (RuntimeException failure) {
            // 通道还没初始化等异常情况，按「在」处理，避免误拦
            return false;
        }
    }

    /** 保留给旧调用点，不再参与判定。 */
    public static void noteTick(net.minecraft.client.Minecraft minecraft) {
        // 判定改为实时查通道表，这里不再累计超时
    }

    /** 打开设置页时主动问一次，服务端会回一份快照。 */
    public static void request() {
        RelayNetwork.toServer(new ConfigRequest());
    }
}
