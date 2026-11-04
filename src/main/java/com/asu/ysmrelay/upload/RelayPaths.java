package com.asu.ysmrelay.upload;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;

import java.nio.file.Path;

/**
 * relay 用到的 YSM 目录。
 *
 * <p>服务端的模型根不是 {@code <world>/ysm/}。它由 YSM 的 {@code AssetDirectoryProbe} 决定，
 * 可能是游戏目录下一个独立的全局 dataRoot（多实例共享）。自己拼路径的后果是：文件落进了
 * 客户端根本看不见的 {@code local:*} 根，上传"成功"但没人能选。
 *
 * <p>所以这里只调 YSM 自己的取值入口，不拼路径。合并回 {@code YsmBridge} 之前，这是本 mod
 * 接触 {@code com.elfmcys.*} 的第二处（第一处是 {@code YsmBridge}）。
 */
public final class RelayPaths {
    private RelayPaths() {}

    /** 上传默认落点，等价于服务端的 {@code global:custom} 根。 */
    public static Path uploadRoot() {
        return ModelCatalogSources.customPath();
    }

    /** 服务端的 {@code global:auth} 根（只有在开了"上传即私有"时才用）。 */
    public static Path authRoot() {
        var paths = ModelRuntime.paths();
        return paths.global().map(AssetPaths.GlobalDirectories::authRoot).orElseGet(paths::authModelsRoot);
    }

    /**
     * 未完成上传的暂存目录。
     *
     * <p>必须放在模型根的外面：模型根会被 YSM 递归扫描，半截文件虽然因为扩展名不会被认成模型，
     * 但也没有理由让它出现在会被扫描的树里。
     */
    public static Path stagingRoot() {
        var root = uploadRoot();
        var parent = root.getParent();
        return (parent == null ? root : parent).resolve("relay-staging");
    }

    /**
     * 待审内容的独立目录，{@code relay-pending/<reviewId>/}。
     *
     * <p><b>必须与 {@link #stagingRoot()} 分开</b>，这不是洁癖：staging 有 600 秒的
     * {@code sweepStagingOnce}，「服务器重启 + 待审超过 10 分钟」之后任意玩家上传一次就会把
     * 待审文件删掉，而 pending.json 的记录还在 —— 服主批准时文件已经不存在。
     * 两个目录的 TTL 语义不同（分钟级 vs 天级），所以物理上必须分开。
     *
     * <p>暂存总量上限 {@code maxStagingBytes} 统计的是两者之和：它们吃的是同一块磁盘。
     */
    public static Path pendingRoot() {
        var root = uploadRoot();
        var parent = root.getParent();
        return (parent == null ? root : parent).resolve("relay-pending");
    }
}
