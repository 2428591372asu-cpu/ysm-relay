package com.asu.ysmrelay.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在系统文件管理器里打开一个目录。 */
public final class ClientFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");

    private ClientFiles() {}

    /**
     * 打开目录；目录不存在就先建出来。
     *
     * <p>以前直接调 {@code Util.getPlatform().openFile}，目录不存在时它什么都不做、也不报错，
     * 表现就是「点了没反应」。
     */
    public static void openFolder(Path folder) {
        if (folder == null) {
            return;
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 创建目录失败 {}", folder, failure);
            return;
        }
        try {
            Util.getPlatform().openFile(folder.toFile());
            return;
        } catch (RuntimeException failure) {
            LOGGER.warn("[ysm-relay] openFile 失败，改用 openUri：{}", folder, failure);
        }
        try {
            Util.getPlatform().openUri(folder.toUri().toString());
        } catch (RuntimeException failure) {
            LOGGER.warn("[ysm-relay] 打开目录失败 {}", folder, failure);
        }
    }
}
