package com.asu.ysmrelay.config;

import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.DataReply;
import com.asu.ysmrelay.net.msg.DataRequest;
import com.asu.ysmrelay.upload.PendingQueue;
import com.asu.ysmrelay.upload.UploadRegistry;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;

/**
 * 三张列表面的服务端实现：我的上传、我的待审、待审队列。
 *
 * <p>条数受两道上限：服务端配置 {@code listMaxEntries}，以及单包字节预算。
 * 后者是硬保险——配置写 32767 也不会真的塞一个几十兆的包出去。
 */
public final class RelayLists {
    private RelayLists() {}

    /** 单条的大致字节数：哈希 64 + 文件名 UTF-16 + 编号 + 长度前缀。 */
    private static int estimate(String fileName) {
        return 64 + (fileName == null ? 0 : fileName.length() * 2) + 8 + 8 + 12;
    }

    public static void reply(ServerPlayer player, int kind) {
        var entries = new ArrayList<DataReply.Entry>();
        var limit = RelayConfig.listMaxEntries();
        var budget = DataReply.MAX_BYTES;
        var mine = player.getUUID().toString().toLowerCase(Locale.ROOT);

        if (kind == DataRequest.KIND_MINE) {
            for (var entry : UploadRegistry.get().all()) {
                if (!entry.uploaderId().equalsIgnoreCase(mine)) {
                    continue;
                }
                budget -= estimate(entry.fileName());
                if (entries.size() >= limit || budget <= 0) {
                    break;
                }
                entries.add(new DataReply.Entry(entry.modelId(), entry.fileName(), entry.size(), ""));
            }
        } else if (kind == DataRequest.KIND_MY_PENDING) {
            for (var entry : PendingQueue.get().list()) {
                if (entry.uploaderId() == null || !entry.uploaderId().equalsIgnoreCase(mine)) {
                    continue;
                }
                budget -= estimate(entry.fileName());
                if (entries.size() >= limit || budget <= 0) {
                    break;
                }
                entries.add(new DataReply.Entry(entry.modelId(), entry.fileName(), entry.size(),
                    entry.reviewId()));
            }
        } else if (kind == DataRequest.KIND_PENDING) {
            if (RelayPermissions.canApprove(player)) {
                for (var entry : PendingQueue.get().list()) {
                    budget -= estimate(entry.fileName());
                    if (entries.size() >= limit || budget <= 0) {
                        break;
                    }
                    entries.add(new DataReply.Entry(entry.modelId(), entry.fileName(), entry.size(),
                        entry.reviewId()));
                }
            }
        }
        com.asu.ysmrelay.YsmRelay.LOGGER.info("[ysm-relay] 列表请求 kind={} 玩家={} 返回 {} 条（台账共 {} 条）",
            kind, player.getGameProfile().getName(), entries.size(), UploadRegistry.get().all().size());
        RelayNetwork.toPlayer(player, new DataReply(kind, entries));
    }
}
