package com.asu.ysmrelay.command;

import com.asu.ysmrelay.YsmRelay;
import com.asu.ysmrelay.admin.AuthAdmin;
import com.asu.ysmrelay.admin.ModelPlacement;
import com.asu.ysmrelay.ysm.YsmBridge;
import com.elfmcys.ysm.model.domain.Hash256;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /ysmrelay} —— 服主管理面。
 *
 * <p>参数全部走 Java 能力，不拼接、不派发 {@code /ysm} 指令字符串：授权变化直接写 YSM 的
 * {@code AuthModelsCapability} 并由 {@link YsmBridge} 推给客户端，目录变化直接调
 * {@code ServerModelService.reload()}。
 *
 * <p>权限等级 2（与 YSM 自己的 {@code /ysm} 对齐），没有第二套权限体系。
 */
@Mod.EventBusSubscriber(modid = YsmRelay.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RelayCommand {
    private static final String MODEL = "model";
    private static final String TARGETS = "targets";

    private RelayCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        var root = Commands.literal("ysmrelay").requires(source -> source.hasPermission(2));

        root.then(Commands.literal("status").executes(RelayCommand::status));

        var list = Commands.literal("list");
        list.executes(context -> list(context, 1));
        list.then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
            .executes(context -> list(context,
                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page"))));
        root.then(list);

        root.then(Commands.literal("show")
            .then(Commands.argument(TARGETS, EntityArgument.player()).executes(RelayCommand::show)));

        // 同一个 argument 节点只能出现一次：Brigadier 的子节点表按 name 索引，重复 then 会覆盖前一支。
        var policy = Commands.literal("policy").then(Commands.argument(MODEL, StringArgumentType.word())
            .then(Commands.literal("public").executes(context -> policy(context, AuthAdmin.Mode.PUBLIC, List.of())))
            .then(Commands.literal("listed").then(Commands.argument(TARGETS, EntityArgument.players())
                .executes(context -> policy(context, AuthAdmin.Mode.LISTED, online(context)))))
            .then(Commands.literal("except").then(Commands.argument(TARGETS, EntityArgument.players())
                .executes(context -> policy(context, AuthAdmin.Mode.EXCEPT, online(context)))))
            .then(Commands.literal("clear").executes(RelayCommand::clearPolicy)));
        root.then(policy);

        root.then(Commands.literal("grant").then(Commands.argument(TARGETS, EntityArgument.player())
            .then(Commands.argument(MODEL, StringArgumentType.word())
                .executes(context -> grant(context, true)))));
        root.then(Commands.literal("revoke").then(Commands.argument(TARGETS, EntityArgument.player())
            .then(Commands.argument(MODEL, StringArgumentType.word())
                .executes(context -> grant(context, false)))));

        root.then(Commands.literal("move").then(Commands.argument(MODEL, StringArgumentType.word())
            .then(Commands.literal("auth").executes(context -> move(context, ModelPlacement.Root.AUTH)))
            .then(Commands.literal("custom").executes(context -> move(context, ModelPlacement.Root.CUSTOM)))));

        // 删除是不可逆的，所以要求再打一次 confirm。
        root.then(Commands.literal("delete").then(Commands.argument(MODEL, StringArgumentType.word())
            .then(Commands.literal("confirm").executes(RelayCommand::delete))));

        root.then(Commands.literal("block").then(Commands.argument(MODEL, StringArgumentType.word())
            .executes(RelayCommand::block)));
        root.then(Commands.literal("unblock").then(Commands.argument(MODEL, StringArgumentType.word())
            .executes(RelayCommand::unblock)));

        root.then(Commands.literal("refresh").executes(RelayCommand::refreshAll)
            .then(Commands.argument(TARGETS, EntityArgument.player()).executes(RelayCommand::refreshOne)));

        return root;
    }

    // ------------------------------------------------------------------
    // 子命令
    // ------------------------------------------------------------------

    private static int status(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var admin = AuthAdmin.get();
        var rules = admin.rules();
        var modelCount = YsmBridge.serverModelIds().size();
        var fileCount = YsmBridge.serverFiles().size();
        var authRules = rules.values().stream().filter(rule -> rule.mode() != AuthAdmin.Mode.PUBLIC).count();

        source.sendSuccess(() -> Component.literal("[ysm-relay] YSM " + YsmBridge.ysmVersion().orElse("?")
            + "，服务端模型 " + modelCount + " 个（" + fileCount + " 个由文件支撑）"), false);
        source.sendSuccess(() -> Component.literal("[ysm-relay] 授权策略 " + rules.size() + " 条，其中需要 auth 根 "
            + authRules + " 条；禁止上传 " + admin.blockedModels().size() + " 条；上传落点 "
            + (com.asu.ysmrelay.config.RelayConfig.targetAuthRoot() ? "auth" : "custom")), false);
        if (authRules > 0 && !com.asu.ysmrelay.config.RelayConfig.targetAuthRoot()) {
            source.sendSuccess(() -> Component.literal(
                "[ysm-relay] 注意：上传落点是 custom（public），这些模型对所有人可选；逐玩家控制需要 auth 根"),
                false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> context, int page) {
        var source = context.getSource();
        var server = source.getServer();
        var rules = AuthAdmin.get().rules();
        if (rules.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[ysm-relay] 还没有任何授权策略。用 /ysmrelay policy <模型> ... 建立"),
                false);
            return Command.SINGLE_SUCCESS;
        }
        var rows = new ArrayList<Map.Entry<Hash256, AuthAdmin.Rule>>(rules.entrySet());
        rows.sort(Map.Entry.comparingByKey(Hash256::compareTo));
        var pageSize = 8;
        var pages = (rows.size() + pageSize - 1) / pageSize;
        if (page > pages) {
            source.sendFailure(Component.literal("[ysm-relay] 第 " + page + " 页不存在，共 " + pages + " 页"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[ysm-relay] 授权策略 " + rows.size() + " 条（第 " + page + "/" + pages
            + " 页）"), false);
        var from = (page - 1) * pageSize;
        var to = Math.min(rows.size(), from + pageSize);
        for (var index = from; index < to; index++) {
            var entry = rows.get(index);
            var modelId = entry.getKey();
            var rule = entry.getValue();
            var placement = ModelPlacement.rootOf(modelId);
            var display = YsmBridge.displayPath(modelId).orElse("(无文件支撑)");
            var line = String.format(Locale.ROOT, "#%d %s  %s  %s  %s  %d 人%s",
                index + 1, modelId.toString().substring(0, 12), rule.mode(),
                placement.name().toLowerCase(Locale.ROOT), display, rule.players().size(),
                AuthAdmin.get().isBlocked(modelId) ? "  [禁止上传]" : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int show(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = EntityArgument.getPlayer(context, TARGETS);
        var grants = YsmBridge.grantsOf(player);
        var admin = AuthAdmin.get();
        var name = player.getGameProfile().getName();

        source.sendSuccess(() -> Component.literal("[ysm-relay] " + name + " 当前持有 " + grants.size() + " 个模型授权"),
            false);

        var ids = new ArrayList<>(grants);
        ids.sort(Hash256::compareTo);
        var shown = 0;
        for (var modelId : ids) {
            if (shown >= 12) {
                source.sendSuccess(() -> Component.literal("  ..."), false);
                break;
            }
            var display = YsmBridge.displayPath(modelId).orElse(modelId.toString().substring(0, 12));
            var rule = admin.ruleOf(modelId);
            var line = "  + " + display + "  [" + rule.mode() + "]";
            source.sendSuccess(() -> Component.literal(line), false);
            shown++;
        }

        var derived = new ArrayList<String>();
        admin.rules().forEach((modelId, rule) -> {
            var listed = rule.players().contains(player.getUUID());
            var expected = switch (rule.mode()) {
                case PUBLIC -> null;
                case LISTED -> listed;
                case EXCEPT -> !listed;
            };
            if (expected == null || !expected) {
                return;
            }
            if (!grants.contains(modelId)) {
                derived.add(modelId.toString().substring(0, 12) + "(策略要求已授权但缺失)");
            }
        });
        if (!derived.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[ysm-relay] 策略与运行时不一致，执行 /ysmrelay refresh 修正："
                + String.join(", ", derived)), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int policy(CommandContext<CommandSourceStack> context, AuthAdmin.Mode mode,
        Collection<ServerPlayer> targets) {
        var source = context.getSource();
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        var modelId = resolved.modelId();
        var players = new LinkedHashSet<UUID>();
        for (var target : targets) {
            players.add(target.getUUID());
        }
        if (mode != AuthAdmin.Mode.PUBLIC && players.isEmpty()) {
            source.sendFailure(Component.literal("[ysm-relay] listed/except 至少要给一名在线玩家"));
            return 0;
        }

        var admin = AuthAdmin.get();
        admin.put(modelId, mode, players, "");
        var placement = ModelPlacement.rootOf(modelId);

        String warning = null;
        if (mode != AuthAdmin.Mode.PUBLIC && placement == ModelPlacement.Root.CUSTOM) {
            warning = "该模型现在在 custom 根（public），授权不生效。执行 /ysmrelay move "
                + modelId.toString().substring(0, 12) + " auth 迁移到 auth 根";
        } else if (mode == AuthAdmin.Mode.PUBLIC && placement == ModelPlacement.Root.AUTH) {
            warning = "该模型在 auth 根，public 策略只在给所有人授权后才有意义。执行 /ysmrelay refresh";
        }
        var text = "[ysm-relay] " + modelId.toString().substring(0, 12) + " 策略设为 " + mode + "，涉及 "
            + players.size() + " 人";
        source.sendSuccess(() -> Component.literal(text), true);
        if (warning != null) {
            var message = warning;
            source.sendSuccess(() -> Component.literal("[ysm-relay] " + message), false);
        }

        admin.applyAll(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    private static int clearPolicy(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        var removed = AuthAdmin.get().remove(resolved.modelId());
        var text = removed ? "已删除策略，模型回到 YSM 默认语义" : "该模型本来就没有策略";
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + text), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int grant(CommandContext<CommandSourceStack> context, boolean adding)
        throws CommandSyntaxException {
        var source = context.getSource();
        var player = EntityArgument.getPlayer(context, TARGETS);
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        var modelId = resolved.modelId();
        var admin = AuthAdmin.get();
        var rule = admin.ruleOf(modelId);

        if (rule.mode() == AuthAdmin.Mode.PUBLIC) {
            source.sendFailure(Component.literal("[ysm-relay] 该模型是 public 策略（人人可用），不需要单独授权。"
                + "要做逐玩家控制先执行 /ysmrelay policy " + modelId.toString().substring(0, 12) + " listed <玩家>"));
            return 0;
        }

        var players = new LinkedHashSet<>(rule.players());
        var changed = adding ? players.add(player.getUUID()) : players.remove(player.getUUID());
        if (!changed) {
            var text = player.getGameProfile().getName() + (adding ? " 已在名单里" : " 本来就不在名单里");
            source.sendSuccess(() -> Component.literal("[ysm-relay] " + text), false);
            return Command.SINGLE_SUCCESS;
        }
        admin.put(modelId, rule.mode(), players, rule.note());
        admin.applyAll(source.getServer());

        var text = (adding ? "已授权 " : "已收回 ") + player.getGameProfile().getName() + " 对 "
            + modelId.toString().substring(0, 12) + " 的使用权（" + rule.mode() + "）";
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + text), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int move(CommandContext<CommandSourceStack> context, ModelPlacement.Root target) {
        var source = context.getSource();
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        var result = ModelPlacement.moveTo(resolved.modelId(), target);
        if (!result.moved()) {
            source.sendFailure(Component.literal("[ysm-relay] " + result.message()));
            return result.file() == null ? 0 : Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + result.message()), true);
        AuthAdmin.get().applyAll(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    private static int delete(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        var modelId = resolved.modelId();
        var admin = AuthAdmin.get();
        var server = source.getServer();

        // 顺序有讲究：先撤在线玩家的 grant，再删文件。反过来会让玩家短暂持有一个已经不存在的模型的授权。
        admin.revokeEverywhere(server, modelId);
        admin.remove(modelId);

        var result = ModelPlacement.delete(modelId);
        if (!result.deleted()) {
            source.sendFailure(Component.literal("[ysm-relay] " + result.message()));
            return 0;
        }
        // 记进禁止名单：否则上传通道下个探测就会把这个 modelId 又收回来，删除等于没删。
        admin.block(modelId);
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + result.message() + "，已加入禁止上传名单"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int block(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        AuthAdmin.get().block(resolved.modelId());
        var shortId = resolved.modelId().toString().substring(0, 12);
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + shortId + " 已禁止再次上传（不影响已在服务端的文件）"),
            true);
        return Command.SINGLE_SUCCESS;
    }

    private static int unblock(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        // 被删除的模型已经不在 catalog 里，resolveModel 找不到它，所以这里允许直接给完整哈希。
        var token = StringArgumentType.getString(context, MODEL).trim().toLowerCase(Locale.ROOT);
        if (token.length() != Hash256.SIZE * 2 || !token.chars().allMatch(RelayCommand::isHexChar)) {
            source.sendFailure(Component.literal("[ysm-relay] 取消禁止需要完整 64 位 modelId（被删的模型已不在目录里）"));
            return 0;
        }
        Hash256 modelId;
        try {
            modelId = Hash256.parse(token);
        } catch (IllegalArgumentException malformed) {
            source.sendFailure(Component.literal("[ysm-relay] 模型哈希格式非法：" + token));
            return 0;
        }
        var removed = AuthAdmin.get().unblock(modelId);
        var text = removed ? "已允许重新上传该模型" : "该模型本来就不在禁止名单里";
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + text), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int refreshAll(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var admin = AuthAdmin.get();
        admin.applyAll(source.getServer());
        var count = source.getServer().getPlayerList().getPlayerCount();
        source.sendSuccess(() -> Component.literal("[ysm-relay] 已按授权矩阵重推 " + count + " 名在线玩家"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int refreshOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = EntityArgument.getPlayer(context, TARGETS);
        AuthAdmin.get().applyTo(player);
        YsmBridge.refreshGrants(player);
        var name = player.getGameProfile().getName();
        source.sendSuccess(() -> Component.literal("[ysm-relay] 已重推 " + name + " 的模型授权"), true);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------
    // 模型参数解析
    // ------------------------------------------------------------------

    /** 解析结果：要么给出 modelId，要么给出人话错误。 */
    private record Resolution(@Nullable Hash256 modelId, @Nullable String error) {
        static Resolution ok(Hash256 modelId) {
            return new Resolution(modelId, null);
        }

        static Resolution fail(String error) {
            return new Resolution(null, error);
        }
    }

    /**
     * 支持三种写法：完整 64 位十六进制、8 位以上的十六进制前缀、以及
     * {@code local:auth/xxx.mxc} 这样的公开目录地址。
     */
    private static Resolution resolveModel(String token) {
        var ids = YsmBridge.serverModelIds();
        if (ids.isEmpty()) {
            return Resolution.fail("服务端目录还没就绪，稍后再试（或先 /ysm model reload）");
        }
        var text = token.trim();

        if (text.chars().allMatch(RelayCommand::isHexChar)) {
            var lower = text.toLowerCase(Locale.ROOT);
            if (lower.length() == Hash256.SIZE * 2) {
                Hash256 exact;
                try {
                    exact = Hash256.parse(lower);
                } catch (IllegalArgumentException malformed) {
                    return Resolution.fail("模型哈希格式非法：" + text);
                }
                return ids.contains(exact) ? Resolution.ok(exact)
                    : Resolution.fail("目录里没有这个 modelId：" + lower);
            }
            if (lower.length() < 8) {
                return Resolution.fail("哈希前缀至少 8 位，当前只有 " + lower.length() + " 位");
            }
            Hash256 matched = null;
            var matches = 0;
            for (var candidate : ids) {
                if (candidate.toString().startsWith(lower)) {
                    matched = candidate;
                    matches++;
                }
            }
            if (matches == 1) {
                return Resolution.ok(matched);
            }
            return matches == 0 ? Resolution.fail("没有以 " + lower + " 开头的模型")
                : Resolution.fail("前缀 " + lower + " 匹配到 " + matches + " 个模型，请给更多位数");
        }

        if (text.indexOf(':') >= 0) {
            var found = new LinkedHashMap<Hash256, String>();
            for (var candidate : ids) {
                var display = YsmBridge.displayPath(candidate).orElse(null);
                if (display != null && display.equals(text)) {
                    found.put(candidate, display);
                }
            }
            if (found.size() == 1) {
                return Resolution.ok(found.keySet().iterator().next());
            }
            return found.isEmpty() ? Resolution.fail("没有这个公开地址：" + text)
                : Resolution.fail("地址 " + text + " 对应多个模型（同名不同内容），请改用哈希前缀");
        }

        return Resolution.fail("无法识别的模型写法：" + text + "（给 64 位哈希、哈希前缀，或 local:auth/xxx.mxc）");
    }

    private static boolean isHexChar(int value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') || (value >= 'A' && value <= 'F');
    }

    private static Collection<ServerPlayer> online(CommandContext<CommandSourceStack> context)
        throws CommandSyntaxException {
        return EntityArgument.getPlayers(context, TARGETS);
    }

    /** 供其它模块使用的只读视图：某个模型当前被列进哪些玩家的名单。 */
    public static Set<UUID> listedPlayers(Hash256 modelId) {
        return AuthAdmin.get().ruleOf(modelId).players();
    }
}
