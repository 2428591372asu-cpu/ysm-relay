package com.asu.ysmrelay.upload;

import com.asu.ysmrelay.config.RelayConfig;
import com.elfmcys.ysm.model.domain.Hash256;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 待审队列：上传收妥但服主还没决定的那批内容。
 *
 * <p>文件落在 {@code relay-pending/<reviewId>/}，与传输残片的 {@code relay-staging/} 物理分开：
 * 前者按天级 TTL，后者按分钟级，混在一起会被传输清理顺手删掉。
 *
 * <p>本类同时实现 {@link PendingIndex}，探测阶段的「第四处判重」直接查它。
 */
public final class PendingQueue implements PendingIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final PendingQueue INSTANCE = new PendingQueue();

    /** 一条待审记录。{@code relative} 是相对待审根目录的路径，便于整体搬迁。 */
    public record Entry(String reviewId, String modelId, String uploaderId, String uploaderName, String fileName,
        long size, String relative, long createdAt) {
    }

    private final Map<String, Entry> byReview = new LinkedHashMap<>();
    private final Map<String, String> reviewByModel = new LinkedHashMap<>();
    private boolean loaded;

    public static PendingQueue get() {
        return INSTANCE;
    }

    private PendingQueue() {}

    public static Path root() {
        return RelayPaths.pendingRoot();
    }

    public static Path stateFile() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm-relay").resolve("pending.json");
    }

    public static Path directoryOf(String reviewId) {
        return root().resolve(reviewId);
    }

    public synchronized void loadOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        var file = stateFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Entry> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<List<Entry>>() {}.getType());
            if (list == null) {
                return;
            }
            var now = System.currentTimeMillis();
            var ttl = RelayConfig.pendingTtlHours() * 3600_000L;
            for (var entry : list) {
                if (entry == null || entry.reviewId() == null || entry.modelId() == null) {
                    continue;
                }
                if (ttl > 0L && now - entry.createdAt() > ttl) {
                    deleteQuietly(directoryOf(entry.reviewId()));
                    continue;
                }
                byReview.put(entry.reviewId(), entry);
                reviewByModel.put(entry.modelId().toLowerCase(Locale.ROOT), entry.reviewId());
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[ysm-relay] 读取待审队列失败，按空队列继续", failure);
        }
    }

    public synchronized void add(Entry entry) {
        loadOnce();
        byReview.put(entry.reviewId(), entry);
        reviewByModel.put(entry.modelId().toLowerCase(Locale.ROOT), entry.reviewId());
        save();
    }

    /** 按 reviewId 查。 */
    public synchronized Entry find(String reviewId) {
        loadOnce();
        return byReview.get(reviewId);
    }

    /** 按创建时间倒序的快照。 */
    public synchronized List<Entry> list() {
        loadOnce();
        var snapshot = new ArrayList<>(byReview.values());
        snapshot.sort(Comparator.comparingLong(Entry::createdAt).reversed());
        return snapshot;
    }

    public synchronized void removeEntry(String reviewId) {
        loadOnce();
        var entry = byReview.remove(reviewId);
        if (entry != null) {
            reviewByModel.remove(entry.modelId().toLowerCase(Locale.ROOT));
            save();
        }
    }

    /** reviewId 只允许 8 位小写十六进制：它会被拼进路径，必须挡住路径穿越。 */
    public static boolean validReviewId(String reviewId) {
        if (reviewId == null || reviewId.length() != 8) {
            return false;
        }
        for (var index = 0; index < 8; index++) {
            var ch = reviewId.charAt(index);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** 本人撤回：只删自己的、且 reviewId 合法。成功返回被删的条目。 */
    public synchronized Entry withdraw(String reviewId, String uploaderId) {
        loadOnce();
        if (!validReviewId(reviewId) || uploaderId == null) {
            return null;
        }
        var entry = byReview.get(reviewId);
        if (entry == null || entry.uploaderId() == null
            || !entry.uploaderId().equalsIgnoreCase(uploaderId)) {
            return null;
        }
        removeEntry(reviewId);
        deleteQuietly(directoryOf(reviewId));
        return entry;
    }

    /** 某个人当前有多少条在等待审核。 */
    public synchronized int countByUploader(String uploaderId) {
        loadOnce();
        if (uploaderId == null || uploaderId.isEmpty()) {
            return 0;
        }
        var needle = uploaderId.toLowerCase(Locale.ROOT);
        var count = 0;
        for (var entry : byReview.values()) {
            if (entry.uploaderId() != null && entry.uploaderId().toLowerCase(Locale.ROOT).equals(needle)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized boolean contains(Hash256 modelId) {
        loadOnce();
        return reviewByModel.containsKey(modelId.toString().toLowerCase(Locale.ROOT));
    }

    @Override
    public synchronized int size() {
        loadOnce();
        return byReview.size();
    }

    @Override
    public void add(Hash256 modelId, String reviewId) {
        // 由 UploadService 用完整 Entry 调用 add(Entry)，这里不需要单独实现
    }

    @Override
    public void remove(Hash256 modelId) {
        loadOnce();
        var reviewId = reviewByModel.get(modelId.toString().toLowerCase(Locale.ROOT));
        if (reviewId != null) {
            removeEntry(reviewId);
        }
    }

    private void save() {
        var file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(new ArrayList<>(byReview.values())), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 写入待审队列失败", failure);
        }
    }

    private static void deleteQuietly(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 残留由下次 TTL 清理兜底
                }
            });
        } catch (IOException ignored) {
            // 目录本来就不存在
        }
    }
}
