package com.asu.ysmrelay.admin;

import com.asu.ysmrelay.YsmRelay;
import com.asu.ysmrelay.ysm.YsmBridge;
import com.elfmcys.ysm.model.domain.Hash256;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 服主制定的「模型 ↔ 玩家」授权矩阵。
 *
 * <p>YSM 自己只有白名单：{@code ServerModelSession.canSelect} 的判定是
 * {@code access == PUBLIC || grants.contains(modelId)}，而 {@code access} 只由模型落在哪个目录根决定
 * （{@code auth} 根 = AUTHORIZED，其余 = PUBLIC）。因此：
 *
 * <ul>
 *   <li><b>只给某些玩家</b>：模型放 auth 根，只给这些人 {@code addModel}。</li>
 *   <li><b>禁止某些玩家</b>：模型放 auth 根，给「除这些人以外的所有玩家」{@code addModel}。</li>
 *   <li><b>人人可用</b>：模型放 custom 根（public），或放 auth 根但给所有人授权。</li>
 * </ul>
 *
 * <p>本类只负责「谁该有哪些 grant」这一层事实，以及把它落到在线玩家身上。它<b>不改</b> YSM 的任何
 * 判定逻辑，也不接管 YSM 自己的 /ysm auth 指令：由 /ysm auth 手工加的 grant 不会被本类清掉，
 * 因为 {@link #applyTo(ServerPlayer)} 只回收「本矩阵管理过」的模型。
 *
 * <p>持久化在 {@code config/ysm-relay/authz.json}。玩家自身的 grant 集合由 YSM 的 capability 存进
 * 玩家 NBT，所以两边都要在：矩阵是服主的意图，capability 是运行时事实。登录时用矩阵覆盖事实。
 */
public final class AuthAdmin {
    /** 一个模型对玩家的开放方式。 */
    public enum Mode {
        /** 人人可用。模型应在 custom 根；本矩阵不强制任何 grant。 */
        PUBLIC,
        /** 只有名单上的玩家可用。模型必须在 auth 根。 */
        LISTED,
        /** 除名单上的玩家以外人人可用。模型必须在 auth 根。 */
        EXCEPT
    }

    /** 单个模型的策略快照。 */
    public record Rule(Mode mode, Set<UUID> players, String note, long updatedAt) {
        public Rule {
            players = Set.copyOf(players);
            note = note == null ? "" : note;
        }

        static Rule of(Mode mode, Collection<UUID> players, String note) {
            return new Rule(mode, new LinkedHashSet<>(players), note, System.currentTimeMillis());
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int FORMAT_VERSION = 1;

    @Nullable
    private static volatile AuthAdmin instance;

    private final Path file;
    private final Map<Hash256, Rule> rules = new LinkedHashMap<>();
    /**
     * 被服主删除过的模型。上传通道应当在探测阶段就拒掉这些 modelId，
     * 否则玩家可以立刻把同一份模型再传回来，删除等于没删。
     */
    private final Set<Hash256> blocked = new LinkedHashSet<>();
    /** 拉黑时顺手记下的模型名，便于用名字解除。旧文件里没有这一项时为空。 */
    private final Map<Hash256, String> blockedNames = new LinkedHashMap<>();

    private AuthAdmin(Path file) {
        this.file = file;
    }

    /** 进程内单例；首次调用时从磁盘读。读失败按空矩阵处理，不打断服务器启动。 */
    public static AuthAdmin get() {
        var current = instance;
        if (current != null) {
            return current;
        }
        synchronized (AuthAdmin.class) {
            if (instance == null) {
                instance = load();
            }
            return instance;
        }
    }

    /** relay 自己的状态目录：{@code config/ysm-relay/}。与 YSM 的 {@code config/ysm/} 分开，互不干扰。 */
    public static Path stateDir() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("ysm-relay");
    }

    private static AuthAdmin load() {
        var file = stateDir().resolve("authz.json");
        var admin = new AuthAdmin(file);
        if (!Files.isRegularFile(file)) {
            return admin;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var stored = GSON.fromJson(reader, StoredFile.class);
            if (stored != null && stored.models != null) {
                stored.models.forEach((hex, entry) -> {
                    if (entry == null || entry.mode == null) {
                        return;
                    }
                    try {
                        var mode = Mode.valueOf(entry.mode);
                        var players = new LinkedHashSet<UUID>();
                        if (entry.players != null) {
                            for (var text : entry.players) {
                                try {
                                    players.add(UUID.fromString(text));
                                } catch (IllegalArgumentException malformed) {
                                    YsmRelay.LOGGER.warn("[ysm-relay] authz.json 里的玩家 UUID 非法，已跳过: {}", text);
                                }
                            }
                        }
                        admin.rules.put(Hash256.parse(hex), new Rule(mode, players, entry.note, entry.updatedAt));
                    } catch (IllegalArgumentException malformed) {
                        YsmRelay.LOGGER.warn("[ysm-relay] authz.json 里的模型条目非法，已跳过: {}", hex);
                    }
                });
            }
            if (stored != null && stored.blocked != null) {
                for (var hex : stored.blocked) {
                    try {
                        admin.blocked.add(Hash256.parse(hex));
                    } catch (IllegalArgumentException malformed) {
                        YsmRelay.LOGGER.warn("[ysm-relay] authz.json 里的禁止项非法，已跳过: {}", hex);
                    }
                }
            }
            if (stored != null && stored.blockedNames != null) {
                stored.blockedNames.forEach((hex, name) -> {
                    try {
                        admin.blockedNames.put(Hash256.parse(hex), name == null ? "" : name);
                    } catch (IllegalArgumentException malformed) {
                        YsmRelay.LOGGER.warn("[ysm-relay] authz.json 里的禁止项名字非法，已跳过: {}", hex);
                    }
                });
            }
        } catch (IOException | JsonParseException failure) {
            YsmRelay.LOGGER.error("[ysm-relay] 读取 {} 失败，按空授权矩阵继续", file, failure);
        }
        return admin;
    }

    /** 当前被禁止再次上传的模型。 */
    public synchronized Set<Hash256> blockedModels() {
        return Set.copyOf(blocked);
    }

    /** 该模型是否已被服主删除并禁止再次上传。 */
    public synchronized boolean isBlocked(Hash256 modelId) {
        return blocked.contains(modelId);
    }

    /** 记下「这个模型被删除了」，并落盘。 */
    public synchronized void block(Hash256 modelId) {
        block(modelId, null);
    }

    /** 记下禁止项，同时保存模型名（用于按名字解除）。 */
    public synchronized void block(Hash256 modelId, String name) {
        var changed = blocked.add(modelId);
        var clean = name == null ? "" : name.trim();
        if (!clean.isEmpty() && !clean.equals(blockedNames.get(modelId))) {
            blockedNames.put(modelId, clean);
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** 禁止项当时记下的模型名；没有记录时为空串。 */
    public synchronized String blockedName(Hash256 modelId) {
        return blockedNames.getOrDefault(modelId, "");
    }

    /** 取消禁止（允许重新上传同一个模型）。 */
    public synchronized boolean unblock(Hash256 modelId) {
        var removed = blocked.remove(modelId);
        if (blockedNames.remove(modelId) != null) {
            removed = true;
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /** 当前矩阵的只读视图。 */
    public synchronized Map<Hash256, Rule> rules() {
        return Map.copyOf(rules);
    }

    /** 模型策略；未登记的模型视为 {@link Mode#PUBLIC}。 */
    public synchronized Rule ruleOf(Hash256 modelId) {
        return rules.getOrDefault(modelId, new Rule(Mode.PUBLIC, Set.of(), "", 0L));
    }

    /** 写入或覆盖一条策略，并立即落盘。 */
    public synchronized Rule put(Hash256 modelId, Mode mode, Collection<UUID> players, String note) {
        var rule = Rule.of(mode, players, note);
        rules.put(modelId, rule);
        save();
        return rule;
    }

    /** 删除策略（模型回到 YSM 默认语义）。 */
    public synchronized boolean remove(Hash256 modelId) {
        var removed = rules.remove(modelId) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** 把矩阵写到磁盘。先写临时文件再原子替换，避免半截 JSON。 */
    public synchronized void save() {
        var stored = new StoredFile();
        stored.version = FORMAT_VERSION;
        stored.models = new LinkedHashMap<>();
        rules.forEach((modelId, rule) -> {
            var entry = new StoredRule();
            entry.mode = rule.mode().name();
            entry.players = new ArrayList<>();
            rule.players().forEach(uuid -> entry.players.add(uuid.toString()));
            entry.note = rule.note();
            entry.updatedAt = rule.updatedAt();
            stored.models.put(modelId.toString(), entry);
        });
        stored.blocked = new ArrayList<>();
        blocked.forEach(modelId -> stored.blocked.add(modelId.toString()));
        stored.blockedNames = new LinkedHashMap<>();
        blockedNames.forEach((modelId, name) -> {
            if (name != null && !name.isBlank()) {
                stored.blockedNames.put(modelId.toString(), name);
            }
        });
        try {
            Files.createDirectories(file.getParent());
            var temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            YsmRelay.LOGGER.error("[ysm-relay] 写入 {} 失败，授权矩阵仅在内存中生效", file, failure);
        }
    }

    /**
     * 让一名在线玩家的 grant 与本矩阵一致。
     *
     * <p>只处理「本矩阵登记过、且当前服务端真有这个模型」的条目。登记者之外的 grant 原样保留。
     */
    public void applyTo(ServerPlayer player) {
        var catalogIds = YsmBridge.serverModelIds();
        if (catalogIds.isEmpty()) {
            return;
        }
        Set<Hash256> desired;
        Set<Hash256> managed;
        synchronized (this) {
            managed = new LinkedHashSet<>(rules.keySet());
            desired = new LinkedHashSet<>();
            for (var entry : rules.entrySet()) {
                var modelId = entry.getKey();
                if (!catalogIds.contains(modelId)) {
                    continue;
                }
                var rule = entry.getValue();
                var listed = rule.players().contains(player.getUUID());
                switch (rule.mode()) {
                    case PUBLIC -> {
                        // 不需要 grant；见下方回收。
                    }
                    case LISTED -> {
                        if (listed) {
                            desired.add(modelId);
                        }
                    }
                    case EXCEPT -> {
                        if (!listed) {
                            desired.add(modelId);
                        }
                    }
                }
            }
        }
        managed.retainAll(catalogIds);

        var installed = YsmBridge.grantsOf(player);
        var toAdd = new ArrayList<Hash256>();
        var toRemove = new ArrayList<Hash256>();
        for (var modelId : managed) {
            if (desired.contains(modelId) && !installed.contains(modelId)) {
                toAdd.add(modelId);
            } else if (!desired.contains(modelId) && installed.contains(modelId)) {
                toRemove.add(modelId);
            }
        }
        if (!toAdd.isEmpty()) {
            YsmBridge.grant(player, toAdd);
        }
        if (!toRemove.isEmpty()) {
            YsmBridge.revoke(player, toRemove);
            AuthAdmin.releaseIfUnusable(player, toRemove);
        }
    }

    /**
     * 把某个模型的授权从所有在线玩家身上摘掉。删除模型时用。
     *
     * <p>不碰矩阵里的其它条目；删除动作本身由 {@link #remove(Hash256)} + {@link #block(Hash256)} 负责。
     */
    public void revokeEverywhere(MinecraftServer server, Hash256 modelId) {
        var target = List.of(modelId);
        for (var player : server.getPlayerList().getPlayers()) {
            if (!YsmBridge.grantsOf(player).contains(modelId)) {
                continue;
            }
            try {
                YsmBridge.revoke(player, target);
            } catch (RuntimeException failure) {
                YsmRelay.LOGGER.error("[ysm-relay] 从 {} 身上撤销 {} 的授权失败",
                    player.getGameProfile().getName(), modelId.toString().substring(0, 12), failure);
            }
        }
    }

    /** 矩阵变更后，对全部在线玩家重放一次。 */
    public void applyAll(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) {
            try {
                applyTo(player);
            } catch (RuntimeException failure) {
                YsmRelay.LOGGER.error("[ysm-relay] 刷新 {} 的模型授权失败", player.getGameProfile().getName(),
                    failure);
            }
        }
    }

    /**
     * 撤销授权后，若该玩家正选着这些模型之一，回退到默认模型。
     *
     * <p>不复用 YSM 的 {@code ModelSelectionService.selectDefault}：那需要再引一个 YSM 内部类，
     * 而 {@link YsmBridge} 已经提供了「清空后重推」这一条更窄的路径。这里只做回退判定，
     * 真正的选择切换交给客户端在收到新 grant 后自己收敛。
     */
    private static void releaseIfUnusable(ServerPlayer player, List<Hash256> revoked) {
        YsmBridge.refreshGrants(player);
        var stillHeld = YsmBridge.grantsOf(player);
        for (var modelId : revoked) {
            if (!stillHeld.contains(modelId)) {
                YsmRelay.LOGGER.debug("[ysm-relay] {} 的模型 {} 授权已撤销", player.getGameProfile().getName(),
                    modelId.toString().substring(0, 12));
            }
        }
    }

    private static final class StoredFile {
        int version;
        Map<String, StoredRule> models;
        List<String> blocked;
        Map<String, String> blockedNames;
    }

    private static final class StoredRule {
        String mode;
        List<String> players;
        String note;
        long updatedAt;
    }
}
