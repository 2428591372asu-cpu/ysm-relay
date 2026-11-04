package com.asu.ysmrelay.upload;

import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.NativeArchive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 算一个模型源的「模型标识」。
 *
 * <p>这个标识必须和 YSM 目录里的键是同一个东西，否则去重永远不命中。它不是文件字节的哈希，
 * 而是 {@code ModelParser.scanModelHash} 对解析后的规范内容算出来的值 —— 也就是说，同一个模型
 * 换个文件名、重新压一次包，标识不变；改了贴图的模型标识一定变。这正是去重想要的口径。
 *
 * <p>{@code scanModelHash} 只做扫描不做编译，比完整解析便宜，但它仍然要打开归档，所以调用方
 * 应该放在后台线程。
 *
 * <p>这是本 mod 接触 {@code com.elfmcys.*} 的又一处；和 {@code RelayPaths} 一起，等
 * {@code YsmBridge} 的写锁释放后合并进适配层。
 */
public final class ModelIdentity {
    private ModelIdentity() {}

    /**
     * 计算模型标识。
     *
     * @param source 模型文件（{@code .mxc/.ysm/.zip/.7z}）或含 {@code ysm.json}/{@code main.json}
     *     的目录
     * @throws IOException 打不开、不是合法模型、或 native 不可用
     */
    public static Hash256 of(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            try (var vfs = new Directory(source)) {
                return ModelParser.scanModelHash(vfs);
            }
        }
        try (var vfs = NativeArchive.open(source.toString())) {
            return ModelParser.scanModelHash(vfs);
        }
    }
}
