package com.asu.ysmrelay.ysm;

import com.elfmcys.ysm.capability.AuthModelsCapability;
import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * 本 mod 与 YSM 3.0 的唯一接触面。
 *
 * <p>其余代码一律只依赖本类，不直接 import {@code com.elfmcys.ysm.*}。这样 YSM 原生库
 * 缺失、YSM 未安装、或 YSM 小版本变化时，只有这一处需要跟进。
 *
 * <p>用到的 YSM 公开入口（均已编译进 compileOnly dev jar）：
 * <ul>
 *   <li>{@link ServerModelService#catalog()} —— 服务端权威目录投影，提供 modelId → 容器文件。</li>
 *   <li>{@link ServerModelService#reload()} —— 重扫磁盘根目录，上传落盘后由它把新模型接进目录。</li>
 *   <li>{@link ManagedContainer#file()} —— 每个模型在磁盘上的容器文件，用于哈希去重与删除。</li>
 *   <li>{@link AuthModelsCapability} —— 每玩家授权集合（NBT 持久化），AUTH 根模型的可见性由它决定。</li>
 * </ul>
 *
 * <p>不在本类做出的决定：上传内容是否可信、配额、限速、谁有权限。这些属于 relay 自己的策略层。
 */
public final class YsmBridge {
    /** YSM 玩家模型主界面。用字符串而不是 import，理由见 {@link #isYsmPlayerModelScreen}。 */
    private static final String PLAYER_MODEL_SCREEN = "com.elfmcys.ysm.client.gui.PlayerModelScreen";

    private YsmBridge() {}

    /** 已安装的 YSM 版本字符串（形如 {@code 3.0-dev-forge+mc1.20.1}）；未安装返回空。 */
    public static Optional<String> ysmVersion() {
        return ModList.get().getModContainerById("ysm")
            .map(container -> container.getModInfo().getVersion().toString());
    }

    /**
     * YSM 版本是否在受支持范围内（3.x）。
     *
     * <p>不用 Forge 的 {@code versionRange} 做这件事：{@code 3.0-dev-forge} 按 Maven 版本序
     * 排在 {@code 3.0} 之前，写 {@code [3.0,4.0)} 会把当前开发版判成不满足依赖，直接导致 mod 不加载。
     */
    public static boolean versionOk() {
        var version = ysmVersion().orElse(null);
        return version != null && majorOf(version) == 3;
    }

    private static int majorOf(String version) {
        var end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }

    /** 服务端模型服务是否已经建立（集成服务器与专用服务器都算）。 */
    public static boolean serverReady() {
        return ServerModelService.current().isPresent();
    }

    /** 服务端当前目录快照；服务未建立或目录尚未就绪时返回空。 */
    public static Optional<ServerCatalog> serverCatalog() {
        return ServerModelService.current().flatMap(ServerModelService::catalog);
    }

    /**
     * 服务端当前所有模型的 modelId。用于上传前的哈希去重：
     * 命中即说明该模型已在服上，直接丢弃这次上传。
     */
    public static Set<Hash256> serverModelIds() {
        return serverCatalog().map(catalog -> catalog.models().keySet()).orElseGet(Set::of);
    }

    /**
     * 服务端模型在磁盘上的容器文件。
     *
     * <p>只有由文件支撑的容器才有路径；内置模型（resident）会抛
     * {@link IllegalStateException}，因此这里先做判定。
     */
    public static Optional<Path> modelFile(Hash256 modelId) {
        var catalog = serverCatalog().orElse(null);
        if (catalog == null) {
            return Optional.empty();
        }
        var container = catalog.find(modelId).orElse(null);
        if (container == null || container.residentOnly()) {
            return Optional.empty();
        }
        return Optional.of(container.file());
    }

    /** 模型展示路径（root:相对路径），用于管理面列表。 */
    public static Optional<String> displayPath(Hash256 modelId) {
        return serverCatalog().flatMap(catalog -> catalog.find(modelId))
            .map(container -> container.location().value());
    }

    /** 目录里记录的全部模型包（含层级、显示名、封面），用于管理面分组。 */
    public static List<ModelPackDescriptor> serverPacks() {
        return serverCatalog().map(ServerCatalog::packs).orElseGet(List::of);
    }

    /**
     * 按容器文件反查 modelId。
     *
     * <p>上传落盘并 reload 之后，用写入路径反查 YSM 自己算出来的 modelId，
     * 而不是我们上传时声明的哈希 —— 服务端只信自己重算的结果。
     */
    public static Optional<Hash256> modelIdOfFile(Path file) {
        var catalog = serverCatalog().orElse(null);
        if (catalog == null) {
            return Optional.empty();
        }
        var absolute = file.toAbsolutePath().normalize();
        for (var entry : catalog.models().entrySet()) {
            var container = entry.getValue();
            if (container.residentOnly()) {
                continue;
            }
            if (container.file().toAbsolutePath().normalize().equals(absolute)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** 触发服务端目录重扫；上传落盘后必须调用。 */
    public static CompletableFuture<ServerModelService.ReloadOutcome> reloadServerCatalog() {
        return ServerModelService.instance().reload();
    }

    /** 服务端目录里全部 modelId → 容器文件，一次性快照，供去重与清理遍历。 */
    public static Map<Hash256, Path> serverFiles() {
        var result = new LinkedHashMap<Hash256, Path>();
        var catalog = serverCatalog().orElse(null);
        if (catalog == null) {
            return result;
        }
        catalog.models().forEach((modelId, container) -> {
            if (!container.residentOnly()) {
                result.put(modelId, container.file());
            }
        });
        return result;
    }

    // ---------------------------------------------------------------------
    // 每玩家授权（YSM 原生 AUTH 根语义）
    // ---------------------------------------------------------------------

    /** 玩家已获得的模型授权集合；无 capability 时为空集合。 */
    public static Set<Hash256> grantsOf(ServerPlayer player) {
        return player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP)
            .map(capability -> Set.copyOf(capability.getAuthModels()))
            .orElseGet(Set::of);
    }

    /** 授予玩家若干模型；返回真正写入的条目。 */
    public static List<Hash256> grant(ServerPlayer player, Collection<Hash256> modelIds) {
        var applied = new ArrayList<Hash256>();
        player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(capability -> {
            for (var modelId : modelIds) {
                if (capability.containModel(modelId)) {
                    continue;
                }
                capability.addModel(modelId);
                applied.add(modelId);
            }
        });
        if (!applied.isEmpty()) {
            refreshGrants(player);
        }
        return applied;
    }

    /** 收回玩家对若干模型的授权。若他正选着被收回的 auth 模型，自动回退默认模型。 */
    public static List<Hash256> revoke(ServerPlayer player, Collection<Hash256> modelIds) {
        var applied = new ArrayList<Hash256>();
        player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(capability -> {
            for (var modelId : modelIds) {
                if (!capability.containModel(modelId)) {
                    continue;
                }
                capability.removeModel(modelId);
                applied.add(modelId);
            }
        });
        if (!applied.isEmpty()) {
            fallbackSelection(player, applied);
            refreshGrants(player);
        }
        return applied;
    }

    /**
     * 授权被收回后，把仍选着该模型的玩家切回默认模型。
     *
     * <p>为什么必须做：授权只对 auth 根的模型生效。收回 grant 后该模型从这名玩家的目录里消失，
     * 但服务端 selection 还指着它，客户端会停在「选着一个自己已经没有的模型」的状态。
     * YSM 自己的 {@code ModelSelectionService.resolve} 只在模型从目录里彻底消失时才回退，
     * 而 auth 模型对别人仍然在目录里，所以那条自愈路径不会触发。
     *
     * <p>公开模型（custom 根）不受 grant 影响，因此不触发回退。
     */
    private static void fallbackSelection(ServerPlayer player, Collection<Hash256> revoked) {
        var catalog = serverCatalog().orElse(null);
        if (catalog == null) {
            return;
        }
        player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(selection -> {
            var selected = selection.getModelId();
            if (selected == null || !revoked.contains(selected) || selection.ignoresGrantsFor(selected)) {
                return;
            }
            var authorizedRoot = catalog.find(selected)
                .map(container -> container.location().rootKind() == CatalogRootKind.AUTH)
                .orElse(false);
            if (!authorizedRoot) {
                return;
            }
            if (!ModelSelectionService.selectDefault(selection, catalog)) {
                com.asu.ysmrelay.YsmRelay.LOGGER.warn(
                    "[ysm-relay] {} 失去模型 {} 的授权，但目录里没有可回退的默认模型",
                    player.getGameProfile().getName(), selected.toString().substring(0, 12));
                return;
            }
            selection.markDirty();
        });
    }

    /** 把授权变化同步到客户端；YSM 内部会重发该玩家的目录可见性。 */
    public static void refreshGrants(ServerPlayer player) {
        SessionProtocolHandler.refreshGrants(player);
    }

    // ---------------------------------------------------------------------
    // 客户端界面判定
    // ---------------------------------------------------------------------

    /**
     * 这个 {@code Screen} 是不是 YSM 的玩家模型主界面（{@code PlayerModelScreen}）。
     *
     * <p>**用类名逐级上溯，不写 {@code instanceof}，也不 import**。两个理由：
     * <ul>
     *   <li>本类同时被专用服务端加载（{@code YsmRelay.commonSetup} 直接调本类）。
     *       {@code PlayerModelScreen} 继承自 {@code net.minecraft.client.gui.screens.Screen}，
     *       而 client 包在专用服务端根本不存在。为一个 UI 判定把这个类拽进服务端的类加载路径，
     *       换来的只会是 {@code NoClassDefFoundError}。</li>
     *   <li>上溯而不是全等：YSM 的模型界面子页都继承同一个基类，按名字上溯才能一处覆盖。</li>
     * </ul>
     *
     * <p>判定失败（类名变了、YSM 结构变了）一律返回 {@code false}：界面注入是锦上添花，
     * 少了按钮玩家还能用快捷键，不值得为此抛异常。
     */
    public static boolean isYsmPlayerModelScreen(Object screen) {
        if (screen == null) {
            return false;
        }
        for (var type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if (PLAYER_MODEL_SCREEN.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }
}
