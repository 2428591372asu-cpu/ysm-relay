package com.asu.ysmrelay.upload;

import com.elfmcys.ysm.model.domain.Hash256;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 上传者台账：谁、什么时候、上传了哪个模型。
 *
 * <p>为什么要自己存：YSM 的 {@code CatalogRecord} 只有 modelId / 路径 / 访问策略 / 展示信息，
 * 没有「来源」这一维。服主问「这个模型是谁传的」时，目录里答不上来。
 *
 * <p>为什么要按 modelId 索引：modelId 是内容哈希，不是文件名。玩家把同一个模型改名再传一次，
 * modelId 不变，台账仍然是一条，不会分裂成两条记录。
 *
 * <p>写入是整文件替换（先写临时文件再原子移动），因为条目数量在几十到几百量级，
 * 比维护增量日志简单且不会出现半截 JSON。
 */
public final class UploadRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final UploadRegistry INSTANCE = new UploadRegistry();

    public static UploadRegistry get() {
        return INSTANCE;
    }

    /** 一条上传记录。{@code modelId} 是 64 位十六进制，方便直接进 JSON 和对比。 */
    public record Entry(String modelId, String uploaderId, String uploaderName, String fileName, long size,
        long uploadedAt) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    private UploadRegistry() {}

    public static Path stateDir() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm-relay");
    }

    public static Path stateFile() {
        return stateDir().resolve("uploads.json");
    }

    /** 记录一次成功落盘的上传。覆盖同 modelId 的旧记录（后传的算数）。 */
    public synchronized void record(Hash256 modelId, ServerPlayer uploader, String fileName, long size) {
        loadOnce();
        var key = modelId.toString();
        entries.put(key, new Entry(key, uploader.getUUID().toString(), uploader.getGameProfile().getName(),
            fileName == null ? "" : fileName, size, System.currentTimeMillis()));
        save();
    }

    /** 按 UUID 记账：批准待审时上传者可能已经离线。 */
    public synchronized void record(Hash256 modelId, String uploaderId, String uploaderName, String fileName,
        long size) {
        loadOnce();
        var key = modelId.toString();
        entries.put(key, new Entry(key, uploaderId == null ? "" : uploaderId,
            uploaderName == null ? "" : uploaderName, fileName == null ? "" : fileName, size,
            System.currentTimeMillis()));
        save();
    }

    public synchronized Optional<Entry> find(Hash256 modelId) {
        loadOnce();
        return Optional.ofNullable(entries.get(modelId.toString()));
    }

    /** 按上传时间倒序，新的在前。 */
    public synchronized Collection<Entry> all() {
        loadOnce();
        var snapshot = new ArrayList<>(entries.values());
        snapshot.sort(Comparator.comparingLong(Entry::uploadedAt).reversed());
        return snapshot;
    }

    /** 删掉一条记录；模型被服主删除时调用。 */
    public synchronized boolean remove(Hash256 modelId) {
        loadOnce();
        var removed = entries.remove(modelId.toString()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** 某个玩家上传过多少字节。用于配合上传配额。 */
    public synchronized long bytesBy(String uploaderId) {
        loadOnce();
        var total = 0L;
        for (var entry : entries.values()) {
            if (entry.uploaderId().equals(uploaderId)) {
                total += entry.size();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------

    private void loadOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        var file = stateFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            var json = Files.readString(file, StandardCharsets.UTF_8);
            List<Entry> list = GSON.fromJson(json, new TypeToken<List<Entry>>() {}.getType());
            if (list == null) {
                return;
            }
            for (var entry : list) {
                if (entry != null && entry.modelId() != null && !entry.modelId().isEmpty()) {
                    entries.put(entry.modelId(), entry);
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[ysm-relay] 读取上传台账失败，按空台账继续：{}", file, failure);
        }
    }

    private void save() {
        var file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            var json = GSON.toJson(new ArrayList<>(entries.values()));
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 写入上传台账失败：{}", file, failure);
        }
    }
}
