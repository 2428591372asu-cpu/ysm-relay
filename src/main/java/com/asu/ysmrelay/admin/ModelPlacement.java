package com.asu.ysmrelay.admin;

import com.asu.ysmrelay.YsmRelay;
import com.asu.ysmrelay.upload.RelayPaths;
import com.asu.ysmrelay.ysm.YsmBridge;
import com.elfmcys.ysm.model.domain.Hash256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 模型落在哪个目录根，决定了它对玩家是不是「需要授权才能选」。
 *
 * <p>YSM 的 {@code CatalogRecord} 把 {@code auth} 根一律标成 AUTHORIZED、其余标成 PUBLIC，而
 * {@code ServerModelSession.canSelect} 对 PUBLIC 模型直接放行、对 AUTHORIZED 模型要求
 * {@code grants.contains(modelId)}。所以「逐玩家控制」这件事不是靠加开关，而是靠文件放在哪。
 *
 * <p>本类只做两件事：判断模型现在在哪个根，以及在两个根之间搬文件（搬完必须 reload 才会被扫到）。
 *
 * <p>搬文件有一个已知限制：YSM 的 {@code ManagedContainer} 可能仍持有该文件的打开句柄，
 * Windows 上此时 {@code Files.move} 会失败。因此失败按「没搬成」如实上报，不做静默重试，
 * 也不假装成功。稳定做法是在上传时就选定根（{@code RelayConfig#targetAuthRoot()}），而不是事后搬。
 */
public final class ModelPlacement {
    /** 模型可能所处的目录根。UNKNOWN 表示不在 relay 认得的两个根里（例如 builtin 或另一个全局根）。 */
    public enum Root {
        CUSTOM,
        AUTH,
        UNKNOWN
    }

    /**
     * 一次搬迁的结果。
     *
     * @param moved  是否真的发生了移动
     * @param from   移动前的根
     * @param to     目标根
     * @param file   当前磁盘上的文件；模型没有文件支撑时为空
     * @param message 给人看的结论，可直接回显到命令
     */
    public record Result(boolean moved, Root from, Root to, Path file, String message) {}

    private ModelPlacement() {}

    /** 模型当前所在的根。 */
    public static Root rootOf(Hash256 modelId) {
        var file = YsmBridge.modelFile(modelId).orElse(null);
        return file == null ? Root.UNKNOWN : rootOfFile(file);
    }

    /** 按磁盘路径判断根；不查目录，纯路径比较。 */
    public static Root rootOfFile(Path file) {
        var absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(normalized(RelayPaths.authRoot()))) {
            return Root.AUTH;
        }
        if (absolute.startsWith(normalized(RelayPaths.uploadRoot()))) {
            return Root.CUSTOM;
        }
        return Root.UNKNOWN;
    }

    /**
     * 把模型搬到目标根，然后触发重扫。
     *
     * <p>不覆盖同名目标文件：目标已存在直接失败，避免把别的模型的容器顶掉。
     */
    public static Result moveTo(Hash256 modelId, Root target) {
        if (target == Root.UNKNOWN) {
            return new Result(false, Root.UNKNOWN, target, null, "目标根不能是 unknown");
        }
        var source = YsmBridge.modelFile(modelId).orElse(null);
        if (source == null) {
            return new Result(false, Root.UNKNOWN, target, null, "该模型没有文件支撑（内置模型），无法搬迁");
        }
        source = source.toAbsolutePath().normalize();
        var from = rootOfFile(source);
        if (from == target) {
            return new Result(false, from, target, source, "模型已经在 " + target.name().toLowerCase() + " 根，无需迁移");
        }
        if (from == Root.UNKNOWN) {
            return new Result(false, from, target, source,
                "模型不在 relay 认得的 custom/auth 根内（当前路径 " + source + "），拒绝迁移");
        }

        var targetRoot = normalized(target == Root.AUTH ? RelayPaths.authRoot() : RelayPaths.uploadRoot());
        var sourceRoot = normalized(from == Root.AUTH ? RelayPaths.authRoot() : RelayPaths.uploadRoot());
        Path relative;
        try {
            relative = sourceRoot.relativize(source);
        } catch (IllegalArgumentException notChild) {
            relative = source.getFileName();
        }
        var destination = targetRoot.resolve(relative);

        try {
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                return new Result(false, from, target, source,
                    "目标位置已被占用：" + destination + "（未覆盖）");
            }
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            YsmRelay.LOGGER.warn("[ysm-relay] 迁移模型文件失败 {} -> {}", source, destination, failure);
            return new Result(false, from, target, source,
                "迁移失败：" + failure.getMessage() + "（文件可能正被 YSM 占用；建议在上传时就选好根）");
        }

        reloadQuietly("迁移模型 " + shortId(modelId) + " 到 " + target.name().toLowerCase());
        return new Result(true, from, target, destination,
            "已迁移到 " + target.name().toLowerCase() + " 根：" + destination.getFileName());
    }

    /** 一次删除的结果。 */
    public record DeleteResult(boolean deleted, Path file, String message) {}

    /**
     * 从磁盘删掉模型的容器文件，然后重扫目录。
     *
     * <p>只删文件，不碰授权——授权回收由调用方（命令层）负责，因为删策略、写禁止名单、
     * 撤销在线玩家 grant 是三个不同的动作，混进这里会让失败无法分辨。
     */
    public static DeleteResult delete(Hash256 modelId) {
        var source = YsmBridge.modelFile(modelId).orElse(null);
        if (source == null) {
            return new DeleteResult(false, null,
                "该模型没有文件支撑（内置或仅驻留内存），relay 不删它");
        }
        source = source.toAbsolutePath().normalize();
        if (rootOfFile(source) == Root.UNKNOWN) {
            return new DeleteResult(false, source,
                "文件不在 relay 认得的 custom/auth 根内（" + source + "），拒绝删除");
        }
        try {
            Files.delete(source);
        } catch (IOException failure) {
            YsmRelay.LOGGER.warn("[ysm-relay] 删除模型文件失败 {}", source, failure);
            return new DeleteResult(false, source,
                "删除失败：" + failure.getMessage() + "（文件可能正被 YSM 占用；可先重启服务端再删）");
        }
        reloadQuietly("删除模型 " + shortId(modelId));
        return new DeleteResult(true, source, "已删除 " + source.getFileName());
    }

    /** 触发一次目录重扫；失败只记日志，不抛给命令调用方。 */
    public static void reloadQuietly(String reason) {
        try {
            YsmBridge.reloadServerCatalog().whenComplete((outcome, failure) -> {
                if (failure != null) {
                    YsmRelay.LOGGER.error("[ysm-relay] {} 后重扫目录失败", reason, failure);
                    return;
                }
                YsmRelay.LOGGER.info("[ysm-relay] {} 后重扫目录：{} 个模型，{} 个错误", reason, outcome.modelCount(),
                    outcome.errorCount());
            });
        } catch (RuntimeException failure) {
            YsmRelay.LOGGER.error("[ysm-relay] {} 后无法触发重扫（服务端模型服务未就绪？）", reason, failure);
        }
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String shortId(Hash256 modelId) {
        return modelId.toString().substring(0, 12);
    }
}
