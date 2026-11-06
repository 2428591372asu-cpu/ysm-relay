package com.asu.ysmrelay.upload;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.NativeArchive;
import com.elfmcys.ysm.natives.legacy.LegacyModelImporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 算一个模型源的「模型标识」。
 *
 * <p>YSM 的模型文件分三种，各走各的读取路径，混用必错：
 * <ul>
 *   <li><b>当前资产容器</b>：头部 {@code EF BB BF YSGP2}，用 {@link ManagedContainer#openDirect} 直接读元数据；</li>
 *   <li><b>旧版 V3 加密容器</b>：头部 {@code EF BB BF YSGP} 但没有 {@code 2}，必须用
 *       {@link LegacyModelImporter#stage} 转成当前容器才能拿到标识；</li>
 *   <li><b>V1/V2 原始归档与 zip/7z</b>：交给 {@code NativeArchive} 打开后解析原始资源。</li>
 * </ul>
 *
 * <p>早期版本只实现了第三条，新格式与 V3 加密模型于是全被判「不支持」。
 */
public final class ModelIdentity {
    /** 文件格式分类。 */
    public enum Format {
        CURRENT_CONTAINER, LEGACY_V3, RAW_OR_ARCHIVE
    }

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] YSGP = {'Y', 'S', 'G', 'P'};

    private ModelIdentity() {}

    /** 只读文件头判断格式，不解析内容。 */
    public static Format detect(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Format.RAW_OR_ARCHIVE;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(16);
        } catch (IOException failure) {
            return Format.RAW_OR_ARCHIVE;
        }
        if (head.length >= AssetContainerConstant.HEAD.length
            && Arrays.equals(AssetContainerConstant.HEAD,
                Arrays.copyOfRange(head, 0, AssetContainerConstant.HEAD.length))) {
            return Format.CURRENT_CONTAINER;
        }
        if (head.length >= 7 && Arrays.equals(BOM, Arrays.copyOfRange(head, 0, 3))
            && Arrays.equals(YSGP, Arrays.copyOfRange(head, 3, 7))) {
            return Format.LEGACY_V3;
        }
        return Format.RAW_OR_ARCHIVE;
    }

    /**
     * 计算模型标识。
     *
     * @param source 模型文件（{@code .mxc/.ysm/.zip/.7z}）或含 {@code ysm.json}/{@code main.json} 的目录
     * @throws IOException 打不开、不是合法模型、或 native 不可用
     */
    public static Hash256 of(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            try (var vfs = new Directory(source)) {
                return ModelParser.scanModelHash(vfs);
            }
        }
        return switch (detect(source)) {
            case CURRENT_CONTAINER -> currentContainerId(source);
            case LEGACY_V3 -> legacyV3Id(source);
            case RAW_OR_ARCHIVE -> {
                try (var vfs = NativeArchive.open(source.toString())) {
                    yield ModelParser.scanModelHash(vfs);
                }
            }
        };
    }

    private static Hash256 currentContainerId(Path source) throws IOException {
        var container = ManagedContainer.openDirect(source, CatalogLocation.DEFAULT);
        try {
            return container.modelId();
        } finally {
            container.representation().close();
        }
    }

    /** V3 加密容器：转成当前容器后取标识，临时目录用完即删。 */
    private static Hash256 legacyV3Id(Path source) throws IOException {
        var temporary = Files.createTempDirectory("ysm-relay-legacy-check");
        try {
            var compiled = new LegacyModelImporter().stage(source, temporary);
            return compiled.modelHash();
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static void deleteQuietly(Path directory) {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 临时文件残留下次开机清
                }
            });
        } catch (IOException ignored) {
            // 目录已经不存在
        }
    }
}
