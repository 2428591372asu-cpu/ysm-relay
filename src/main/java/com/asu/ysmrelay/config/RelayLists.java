package com.asu.ysmrelay.config;

import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.DataReply;
import com.asu.ysmrelay.net.msg.DataRequest;
import com.asu.ysmrelay.upload.PendingQueue;
import com.asu.ysmrelay.upload.UploadRegistry;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 三张列表面的服务端实现：我的上传、我的待审、待审队列。
 *
 * <p>条数受两道上限：服务端配置 {@code listMaxEntries}，以及单包字节预算。
 * 后者是硬保险——配置写 32767 也不会真的塞一个几十兆的包出去。
 */
public final class RelayLists {
    private RelayLists() {}

    /**
     * 待审列表里把上传者拼在模型名前，服主一眼能看出是谁传的。
     *
     * <p>只改展示字符串，{@code extra} 仍然是 reviewId —— 批准/拒绝按钮用的是它，
     * 不受这个前缀影响。
     */
    private static String withUploader(PendingQueue.Entry entry) {
        var uploader = entry.uploaderName();
        if (uploader == null || uploader.isBlank()) {
            return entry.fileName();
        }
        return uploader + " · " + entry.fileName();
    }

    /** 目录里所有非驻留模型，按「本地优先、再按上传者、再按名字」排好。 */
    private static java.util.List<java.util.Map.Entry<com.elfmcys.ysm.model.domain.Hash256, java.nio.file.Path>>
        managedModelIds() {
        var list = new java.util.ArrayList<>(com.asu.ysmrelay.ysm.YsmBridge.serverFiles().entrySet());
        list.sort((left, right) -> {
            var leftUploader = UploadRegistry.get().find(left.getKey())
                .map(UploadRegistry.Entry::uploaderName).orElse("");
            var rightUploader = UploadRegistry.get().find(right.getKey())
                .map(UploadRegistry.Entry::uploaderName).orElse("");
            var byUploader = leftUploader.compareToIgnoreCase(rightUploader);
            if (byUploader != 0) {
                return byUploader;
            }
            return left.getValue().getFileName().toString()
                .compareToIgnoreCase(right.getValue().getFileName().toString());
        });
        return list;
    }

    /** 单条的大致字节数：哈希 64 + 文件名 UTF-16 + 编号 + 长度前缀。 */
    private static int estimate(String fileName) {
        return 64 + (fileName == null ? 0 : fileName.length() * 2) + 8 + 8 + 12;
    }

    public static void reply(ServerPlayer player, int kind) {
        // 0 级 = 禁用：所有列表一律回空，管理面另有 canManage/canApprove 过滤。
        if (!RelayPermissions.canUseMod(player)) {
            RelayNetwork.toPlayer(player, new DataReply(kind, java.util.List.of()));
            return;
        }
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
        } else if (kind == DataRequest.KIND_MANAGED) {
            if (RelayPermissions.canManage(player)) {
                for (var entry : managedModelIds()) {
                    var uploader = UploadRegistry.get().find(entry.getKey())
                        .map(UploadRegistry.Entry::uploaderName).orElse("");
                    var label = com.asu.ysmrelay.ysm.YsmBridge.displayName(entry.getKey())
                        .orElseGet(() -> UploadRegistry.get().find(entry.getKey())
                            .map(UploadRegistry.Entry::fileName)
                            .filter(name -> !name.isBlank())
                            .orElseGet(() -> com.asu.ysmrelay.ysm.YsmBridge.displayPath(entry.getKey())
                                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                                .orElse("模型 " + entry.getKey().toString().substring(0, 8))));
                    budget -= estimate(label);
                    if (entries.size() >= limit || budget <= 0) {
                        break;
                    }
                    long size = 0L;
                    try {
                        size = java.nio.file.Files.size(entry.getValue());
                    } catch (java.io.IOException ignored) {
                        // 文件刚好被删掉，按 0 报
                    }
                    entries.add(new DataReply.Entry(entry.getKey().toString(), label, size, uploader));
                }
            }
        } else if (kind == DataRequest.KIND_BLOCKED) {
            if (RelayPermissions.canManage(player)) {
                var admin = com.asu.ysmrelay.admin.AuthAdmin.get();
                var models = new java.util.ArrayList<>(admin.blockedModels());
                models.sort(java.util.Comparator.comparing(modelId -> {
                    var name = admin.blockedName(modelId);
                    return name.isBlank() ? modelId.toString() : name.toLowerCase(Locale.ROOT);
                }));
                for (var modelId : models) {
                    var name = admin.blockedName(modelId);
                    var label = name.isBlank()
                        ? Component.translatable("screen.ysm_relay.manage.no_name").getString() : name;
                    var uploader = UploadRegistry.get().find(modelId)
                        .map(UploadRegistry.Entry::uploaderName).orElse("");
                    budget -= estimate(label) + 64;
                    if (entries.size() >= limit || budget <= 0) {
                        break;
                    }
                    entries.add(new DataReply.Entry(modelId.toString(), label, 0L, uploader));
                }
            }
        } else if (kind == DataRequest.KIND_PENDING) {
            if (RelayPermissions.canApprove(player)) {
                for (var entry : PendingQueue.get().list()) {
                    budget -= estimate(entry.fileName());
                    if (entries.size() >= limit || budget <= 0) {
                        break;
                    }
                    entries.add(new DataReply.Entry(entry.modelId(), withUploader(entry), entry.size(),
                        entry.reviewId()));
                }
            }
        }
        com.asu.ysmrelay.YsmRelay.LOGGER.info("[ysm-relay] 列表请求 kind={} 玩家={} 返回 {} 条（台账共 {} 条）",
            kind, player.getGameProfile().getName(), entries.size(), UploadRegistry.get().all().size());
        RelayNetwork.toPlayer(player, new DataReply(kind, entries));
    }
}
