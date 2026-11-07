package com.asu.ysmrelay.client;

import com.asu.ysmrelay.upload.RelayPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端本地目录设置。
 *
 * <p>手动上传面板默认扫 YSM 的模型根，但玩家经常把模型放在别处（下载目录、U 盘），
 * 所以允许自己指定一个扫描目录。设置存在 {@code config/ysm-relay/client.properties}，
 * 与自动上传目录同一个文件、不同的键。
 */
public final class ClientPaths {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static final String KEY = "manualUploadFolder";
    private static final String KEY_SKIP_DELETE_CONFIRM = "skipDeleteConfirm";

    private ClientPaths() {}

    private static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm-relay").resolve("client.properties");
    }

    private static Properties load() {
        var properties = new Properties();
        var file = configFile();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            // 读不出来按空配置
        }
        return properties;
    }

    /** 手动上传面板要扫描的目录；没设置时回落到 YSM 模型根。 */
    public static Path manualFolder() {
        var raw = load().getProperty(KEY, "").trim();
        if (raw.isEmpty()) {
            return RelayPaths.uploadRoot();
        }
        try {
            var path = Path.of(raw);
            return Files.isDirectory(path) ? path : RelayPaths.uploadRoot();
        } catch (RuntimeException invalid) {
            return RelayPaths.uploadRoot();
        }
    }

    /** 是不是玩家自己设的目录（false = 用的是默认模型根）。 */
    public static boolean manualFolderConfigured() {
        return !load().getProperty(KEY, "").trim().isEmpty();
    }

    /** 删除模型时是否跳过确认框。 */
    public static boolean skipDeleteConfirm() {
        return Boolean.parseBoolean(load().getProperty(KEY_SKIP_DELETE_CONFIRM, "false"));
    }

    public static void setSkipDeleteConfirm(boolean skip) {
        var properties = load();
        properties.setProperty(KEY_SKIP_DELETE_CONFIRM, Boolean.toString(skip));
        save(properties);
    }

    public static void setManualFolder(Path folder) {
        var properties = load();
        properties.setProperty(KEY, folder == null ? "" : folder.toAbsolutePath().toString());
        save(properties);
    }

    private static void save(Properties properties) {
        var file = configFile();
        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(temporary)) {
                properties.store(out, "ysm-relay client settings");
            }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 写入客户端设置失败", failure);
        }
    }
}
