package com.asu.ysmrelay.command;

import com.asu.ysmrelay.YsmRelay;
import com.asu.ysmrelay.admin.AuthAdmin;
import com.asu.ysmrelay.admin.ModelPlacement;
import com.asu.ysmrelay.config.RelayConfig;
import com.asu.ysmrelay.config.RelayPermissions;
import com.asu.ysmrelay.upload.UploadRegistry;
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
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
        // 自助面：任何人都能用，只操作自己上传的模型，所以不加权限门槛。
        event.getDispatcher().register(selfService());
    }

    /**
     * 只有「真服主」能授予等级：控制台，或单人开 LAN 的房主。
     *
     * <p>刻意不认权限等级 4：op 也是 4，认了就等于 op 能给自己发 3 级。
     * 命令方块会被挡在外面——它的命令源没有实体，但文本名不是 {@code Server}，
     * 而是方块自己的名字（默认 {@code !}）。
     */
    /**
     * 命令根门槛：服主源，或等级够跑审批/管理任一类的玩家。
     *
     * <p>不写死 {@code hasPermission(2)}：那样只有原版 op 能用，把「权限组」成员挡在外面，
     * 而设置页又允许他们点管理。具体子命令再各自按等级收口。
     */
    private static boolean canUseRoot(CommandSourceStack source) {
        if (isRealOwnerSource(source)) {
            return true;
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            return RelayPermissions.canManage(player) || RelayPermissions.canApprove(player);
        }
        // 命令方块等非玩家源：一律不给
        return false;
    }

    /** 管理类子命令的门槛。 */
    private static boolean manageSource(CommandSourceStack source) {
        if (isRealOwnerSource(source)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer player && RelayPermissions.canManage(player);
    }

    /** 审批类子命令的门槛（控制台永远放行）。 */
    private static boolean approveSource(CommandSourceStack source) {
        if (isRealOwnerSource(source)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer player && RelayPermissions.canApprove(player);
    }

    /** 处理函数里再兜一次底：requires 只影响命令是否可见，不替执行路径担保。 */
    private static boolean requireManage(CommandContext<CommandSourceStack> context) {
        if (manageSource(context.getSource())) {
            return true;
        }
        context.getSource().sendFailure(Component.literal(
            "[ysm-relay] 权限不足：需要等级 " + RelayConfig.levelOf("manageRights") + "（管理模型）"));
        return false;
    }

    /** 等级分发的门槛：服主源，或 3 级玩家。2 级不能分发权限。 */
    private static boolean distributorSource(CommandSourceStack source) {
        if (isRealOwnerSource(source)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer player
            && RelayPermissions.canDistributeLevels(player);
    }

    /** 分发者自己的等级；控制台按 4（最高）算。 */
    private static int distributorLevel(CommandSourceStack source) {
        if (isRealOwnerSource(source)) {
            return 4;
        }
        return source.getEntity() instanceof ServerPlayer player
            ? RelayPermissions.effectiveLevel(player) : 0;
    }

    private static boolean isRealOwnerSource(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return RelayPermissions.isHost(player);
        }
        // 控制台/RCON 的命令源没有世界（level == null）；命令方块永远带着自己的世界，
        // 光靠名字还能被改名的方块绕过，加上这一条才真的挡死。
        return source.getEntity() == null
            && source.getLevel() == null
            && "Server".equals(source.getTextName());
    }

    private static int levelGrant(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (!distributorSource(context.getSource())) {
            context.getSource().sendFailure(Component.literal("[ysm-relay] 只有服主或 3 级玩家能分发等级"));
            return 0;
        }
        var target = EntityArgument.getPlayer(context, TARGETS);
        var level = IntegerArgumentType.getInteger(context, "level");
        // 不能授予比自己更高的等级：3 级最多给到 3 级，防止越级提权。
        var mine = distributorLevel(context.getSource());
        if (level > mine) {
            context.getSource().sendFailure(Component.literal(
                "[ysm-relay] 你最多只能授予 " + mine + " 级"));
            return 0;
        }
        var uuid = target.getUUID().toString().toLowerCase(Locale.ROOT);
        var next = RelayConfig.levelGrants();
        next.removeIf(entry -> entry.startsWith(uuid + ":"));
        next.add(uuid + ":" + level);
        RelayConfig.setLevelGrants(next);
        context.getSource().sendSuccess(() -> Component.literal(
            "[ysm-relay] 已授予 " + target.getGameProfile().getName() + " 等级 " + level), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int levelRevoke(CommandContext<CommandSourceStack> context) {
        if (!distributorSource(context.getSource())) {
            context.getSource().sendFailure(Component.literal("[ysm-relay] 只有服主或 3 级玩家能撤销等级"));
            return 0;
        }
        var uuid = StringArgumentType.getString(context, "uuid").trim().toLowerCase(Locale.ROOT);
        var next = RelayConfig.levelGrants();
        var removed = next.removeIf(entry -> entry.startsWith(uuid + ":"));
        if (!removed) {
            context.getSource().sendFailure(Component.literal("[ysm-relay] 没有这个玩家的等级授予"));
            return 0;
        }
        RelayConfig.setLevelGrants(next);
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 已撤销 " + uuid + " 的等级"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int levelList(CommandContext<CommandSourceStack> context) {
        var grants = RelayConfig.levelGrants();
        if (grants.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 没有手动授予的等级"), false);
            return Command.SINGLE_SUCCESS;
        }
        for (var entry : grants) {
            context.getSource().sendSuccess(() -> Component.literal("  " + entry), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 把玩家加进上传白名单。 */
    private static int whitelistAdd(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var target = EntityArgument.getPlayer(context, TARGETS);
        var uuid = target.getUUID().toString().toLowerCase(Locale.ROOT);
        var next = new LinkedHashSet<>(RelayConfig.uploadWhitelist());
        next.add(uuid);
        RelayConfig.setUploadWhitelist(next);
        var name = target.getGameProfile().getName();
        context.getSource().sendSuccess(() -> Component.literal(
            "[ysm-relay] 已加入上传白名单：" + name + "（" + uuid + "）"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int whitelistRemove(CommandContext<CommandSourceStack> context) {
        var raw = StringArgumentType.getString(context, "uuid").trim().toLowerCase(Locale.ROOT);
        var next = new LinkedHashSet<>(RelayConfig.uploadWhitelist());
        if (!next.remove(raw)) {
            context.getSource().sendFailure(Component.literal("[ysm-relay] 白名单里没有这个 UUID"));
            return 0;
        }
        RelayConfig.setUploadWhitelist(next);
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 已移出上传白名单 " + raw), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int whitelistList(CommandContext<CommandSourceStack> context) {
        var list = RelayConfig.uploadWhitelist();
        if (list.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 上传白名单为空"), false);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 上传白名单 " + list.size() + " 人："), false);
        for (var uuid : list) {
            context.getSource().sendSuccess(() -> Component.literal("  " + uuid), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 打印各个模型目录与目录里的实际文件数，用来确认「上传到底落到哪」。 */
    private static int printPaths(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        try {
            var upload = com.asu.ysmrelay.upload.RelayPaths.uploadRoot();
            var auth = com.asu.ysmrelay.upload.RelayPaths.authRoot();
            var staging = com.asu.ysmrelay.upload.RelayPaths.stagingRoot();
            var pending = com.asu.ysmrelay.upload.RelayPaths.pendingRoot();
            source.sendSuccess(() -> Component.literal("[ysm-relay] 上传根: " + upload), false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] 授权根: " + auth), false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] 暂存: " + staging), false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] 待审: " + pending), false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] targetRoot = "
                + (RelayConfig.targetAuthRoot() ? "auth" : "custom")), false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] 目录里 .mxc 数量: "
                + countFiles(upload, ".mxc") + "（授权根 " + countFiles(auth, ".mxc") + "）"), false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] 目录里所有文件: " + countFiles(upload, "")),
                false);
            source.sendSuccess(() -> Component.literal("[ysm-relay] 目录模型数（YSM 目录）: "
                + YsmBridge.serverModelIds().size()), false);
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("[ysm-relay] 读取路径失败：" + failure.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static long countFiles(java.nio.file.Path root, String extension) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            return 0L;
        }
        try (var stream = java.nio.file.Files.walk(root, 4)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                .filter(path -> extension.isEmpty()
                    || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                .count();
        } catch (java.io.IOException failure) {
            return -1L;
        }
    }

    /** 列出待审条目。 */
    private static int pendingList(CommandContext<CommandSourceStack> context) {
        var entries = com.asu.ysmrelay.upload.PendingQueue.get().list();
        if (entries.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 待审队列是空的"), false);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendSuccess(() -> Component.literal(
            "[ysm-relay] 待审 " + entries.size() + " 条："), false);
        for (var entry : entries) {
            var dir = com.asu.ysmrelay.upload.PendingQueue.directoryOf(entry.reviewId());
            var path = dir.resolve(entry.fileName()).toAbsolutePath();
            var shortId = entry.modelId().substring(0, 12);
            var sizeMB = entry.size() / (1024.0 * 1024.0);
            context.getSource().sendSuccess(() -> Component.literal(
                "  " + entry.reviewId() + "  " + entry.uploaderName() + "  " + entry.fileName()
                    + String.format(Locale.ROOT, "  %.2f MB  ", sizeMB) + shortId), false);
        }
        context.getSource().sendSuccess(() -> Component.literal(
            "[ysm-relay] 批准 /ysmrelay approve <review>，拒绝 /ysmrelay reject <review>"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int approveReview(CommandContext<CommandSourceStack> context) {
        var reviewId = StringArgumentType.getString(context, "review");
        var message = com.asu.ysmrelay.upload.UploadService.instance().approve(reviewId);
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] " + message), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int rejectReview(CommandContext<CommandSourceStack> context) {
        var reviewId = StringArgumentType.getString(context, "review");
        var message = com.asu.ysmrelay.upload.UploadService.instance().reject(reviewId);
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] " + message), true);
        return Command.SINGLE_SUCCESS;
    }

    /** 拉黑一个在线玩家：之后不再接收他上传的模型。 */
    private static int blockUploaderAdd(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var target = EntityArgument.getPlayer(context, TARGETS);
        var uuid = target.getUUID().toString().toLowerCase(Locale.ROOT);
        var next = new LinkedHashSet<>(RelayConfig.blockedUploaders());
        next.add(uuid);
        RelayConfig.setBlockedUploaders(next);
        var name = target.getGameProfile().getName();
        context.getSource().sendSuccess(() -> Component.literal(
            "[ysm-relay] 已拉黑 " + name + "（" + uuid + "），之后不再接收他的上传；已入库的模型保持不动"), true);
        return Command.SINGLE_SUCCESS;
    }

    /** 按 UUID 解除拉黑（玩家不在线也能解）。 */
    private static int blockUploaderRemove(CommandContext<CommandSourceStack> context) {
        var raw = StringArgumentType.getString(context, "uuid").trim().toLowerCase(Locale.ROOT);
        var next = new LinkedHashSet<>(RelayConfig.blockedUploaders());
        if (!next.remove(raw)) {
            context.getSource().sendFailure(Component.literal("[ysm-relay] 名单里没有这个 UUID"));
            return 0;
        }
        RelayConfig.setBlockedUploaders(next);
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 已解除拉黑 " + raw), true);
        return Command.SINGLE_SUCCESS;
    }

    /** 列出被拉黑的上传者。 */
    private static int blockUploaderList(CommandContext<CommandSourceStack> context) {
        var blocked = RelayConfig.blockedUploaders();
        if (blocked.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 拉黑名单为空"), false);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendSuccess(() -> Component.literal("[ysm-relay] 已拉黑 " + blocked.size() + " 人："), false);
        for (var uuid : blocked) {
            context.getSource().sendSuccess(() -> Component.literal("  " + uuid), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /relayme}：玩家自助面。
     *
     * <p>刻意不挂权限要求：命令本身只读/只删「调用者自己上传的」那几个模型，
     * 服务端在 {@link #deleteOwn} 里用台账的 uploaderId 复核，越权一律拒绝。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> selfService() {
        var root = Commands.literal("relayme");
        root.then(Commands.literal("list").executes(RelayCommand::myUploads));
        root.then(Commands.literal("delete")
            .then(Commands.argument(MODEL, StringArgumentType.word())
                .then(Commands.literal("confirm").executes(RelayCommand::deleteOwn))));
        // 撤回自己还没被审批的内容。
        root.then(Commands.literal("cancel")
            .then(Commands.argument("review", StringArgumentType.word()).executes(RelayCommand::cancelOwn)));
        return root;
    }

    /** 列出自己上传过的模型。 */
    private static int myUploads(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[ysm-relay] 这条命令只能由玩家执行"));
            return 0;
        }
        var mine = player.getUUID().toString().toLowerCase(Locale.ROOT);
        var entries = UploadRegistry.get().all().stream()
            .filter(entry -> entry.uploaderId().equalsIgnoreCase(mine))
            .toList();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[ysm-relay] 你还没有上传过模型"), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(() -> Component.literal("[ysm-relay] 你上传过 " + entries.size() + " 个模型："), false);
        var limit = Math.min(entries.size(), 12);
        for (var index = 0; index < limit; index++) {
            var entry = entries.get(index);
            var shortId = entry.modelId().substring(0, 12);
            var sizeMB = entry.size() / (1024.0 * 1024.0);
            source.sendSuccess(() -> Component.literal("  " + shortId + "  " + entry.fileName()
                + String.format(Locale.ROOT, "  %.2f MB", sizeMB)), false);
        }
        if (entries.size() > limit) {
            source.sendSuccess(() -> Component.literal("  …还有 " + (entries.size() - limit) + " 条未显示"), false);
        }
        source.sendSuccess(() -> Component.literal("[ysm-relay] 删除用 /relayme delete <哈希前缀> confirm"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 撤回自己上传、还没被审批的内容。 */
    private static int cancelOwn(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[ysm-relay] 这条命令只能由玩家执行"));
            return 0;
        }
        var reviewId = StringArgumentType.getString(context, "review").trim().toLowerCase(Locale.ROOT);
        var message = com.asu.ysmrelay.upload.UploadService.instance().withdraw(reviewId, player.getUUID());
        source.sendSuccess(() -> Component.literal("[ysm-relay] " + message), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 删除自己上传的模型。服务端按台账复核归属，删完不进制止名单。 */
    private static int deleteOwn(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[ysm-relay] 这条命令只能由玩家执行"));
            return 0;
        }
        var resolved = resolveModel(StringArgumentType.getString(context, MODEL));
        if (resolved.error() != null) {
            source.sendFailure(Component.literal("[ysm-relay] " + resolved.error()));
            return 0;
        }
        var modelId = resolved.modelId();
        var entry = UploadRegistry.get().find(modelId).orElse(null);
        if (entry == null) {
            source.sendFailure(Component.literal("[ysm-relay] 服务端没有你的上传记录：可能是服主手动放的模型"));
            return 0;
        }
        if (!RelayPermissions.canDeleteOwn(player, entry.uploaderId())) {
            source.sendFailure(Component.literal("[ysm-relay] 这个模型不是你上传的，不能删"));
            return 0;
        }
        var admin = AuthAdmin.get();
        var server = source.getServer();
        admin.revokeEverywhere(server, modelId);
        admin.remove(modelId);
        var result = ModelPlacement.delete(modelId, entry.fileName());
        if (!result.deleted()) {
            source.sendFailure(Component.literal("[ysm-relay] 删除失败：" + result.message()));
            return 0;
        }
        UploadRegistry.get().remove(modelId);
        // 不写进「禁止上传」名单：玩家自己删的，应该还能再传回来。
        ModelPlacement.reloadQuietly("player deleted own upload");
        source.sendSuccess(() -> Component.literal("[ysm-relay] 已删除：" + result.message()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        var root = Commands.literal("ysmrelay").requires(RelayCommand::canUseRoot);

        root.then(Commands.literal("status").requires(RelayCommand::manageSource).executes(RelayCommand::status));

        var list = Commands.literal("list").requires(RelayCommand::manageSource);
        list.executes(context -> list(context, 1));
        list.then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
            .executes(context -> list(context,
                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page"))));
        root.then(list);

        root.then(Commands.literal("show").requires(RelayCommand::manageSource)
            .then(Commands.argument(TARGETS, EntityArgument.player()).executes(RelayCommand::show)));

        // 同一个 argument 节点只能出现一次：Brigadier 的子节点表按 name 索引，重复 then 会覆盖前一支。
        var policy = Commands.literal("policy").requires(RelayCommand::manageSource)
            .then(Commands.argument(MODEL, StringArgumentType.word())
            .then(Commands.literal("public").executes(context -> policy(context, AuthAdmin.Mode.PUBLIC, List.of())))
            .then(Commands.literal("listed").then(Commands.argument(TARGETS, EntityArgument.players())
                .executes(context -> policy(context, AuthAdmin.Mode.LISTED, online(context)))))
            .then(Commands.literal("except").then(Commands.argument(TARGETS, EntityArgument.players())
                .executes(context -> policy(context, AuthAdmin.Mode.EXCEPT, online(context)))))
            .then(Commands.literal("clear").executes(RelayCommand::clearPolicy)));
        root.then(policy);

        root.then(Commands.literal("grant").requires(RelayCommand::manageSource)
            .then(Commands.argument(TARGETS, EntityArgument.player())
            .then(Commands.argument(MODEL, StringArgumentType.word())
                .executes(context -> grant(context, true)))));
        root.then(Commands.literal("revoke").requires(RelayCommand::manageSource)
            .then(Commands.argument(TARGETS, EntityArgument.player())
            .then(Commands.argument(MODEL, StringArgumentType.word())
                .executes(context -> grant(context, false)))));

        root.then(Commands.literal("move").requires(RelayCommand::manageSource)
            .then(Commands.argument(MODEL, StringArgumentType.word())
            .then(Commands.literal("auth").executes(context -> move(context, ModelPlacement.Root.AUTH)))
            .then(Commands.literal("custom").executes(context -> move(context, ModelPlacement.Root.CUSTOM)))));

        // 删除是不可逆的，所以要求再打一次 confirm。
        root.then(Commands.literal("delete").requires(RelayCommand::manageSource)
            .then(Commands.argument(MODEL, StringArgumentType.word())
            .then(Commands.literal("confirm").executes(RelayCommand::delete))));

        root.then(Commands.literal("block").requires(RelayCommand::manageSource)
            .then(Commands.argument(MODEL, StringArgumentType.word())
            .executes(RelayCommand::block)));
        root.then(Commands.literal("unblock").requires(RelayCommand::manageSource)
            .then(Commands.argument(MODEL, StringArgumentType.word())
            .executes(RelayCommand::unblock)));

        var blockUploader = Commands.literal("blockuploader").requires(RelayCommand::manageSource);
        blockUploader.then(Commands.literal("add")
            .then(Commands.argument(TARGETS, EntityArgument.player()).executes(RelayCommand::blockUploaderAdd)));
        blockUploader.then(Commands.literal("remove")
            .then(Commands.argument("uuid", StringArgumentType.word()).executes(RelayCommand::blockUploaderRemove)));
        blockUploader.then(Commands.literal("list").executes(RelayCommand::blockUploaderList));
        root.then(blockUploader);

        // 上传白名单：名单内玩家免审核、免个人配额。
        var whitelist = Commands.literal("whitelist").requires(RelayCommand::manageSource);
        whitelist.then(Commands.literal("add")
            .then(Commands.argument(TARGETS, EntityArgument.player()).executes(RelayCommand::whitelistAdd)));
        whitelist.then(Commands.literal("remove")
            .then(Commands.argument("uuid", StringArgumentType.word()).executes(RelayCommand::whitelistRemove)));
        whitelist.then(Commands.literal("list").executes(RelayCommand::whitelistList));
        root.then(whitelist);

        // 等级授予：只有服主能加/撤。
        var level = Commands.literal("level")
            .requires(RelayCommand::distributorSource);
        level.then(Commands.literal("grant")
            .then(Commands.argument(TARGETS, EntityArgument.player())
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
                    .executes(RelayCommand::levelGrant))));
        level.then(Commands.literal("revoke")
            .then(Commands.argument("uuid", StringArgumentType.word()).executes(RelayCommand::levelRevoke)));
        level.then(Commands.literal("list").executes(RelayCommand::levelList));
        root.then(level);

        // 审批面：只有服主或权限组成员能看/能批。
        var pending = Commands.literal("pending").requires(RelayCommand::approveSource)
            .executes(RelayCommand::pendingList);
        root.then(pending);

        var approve = Commands.literal("approve").requires(RelayCommand::approveSource)
            .then(Commands.argument("review", StringArgumentType.word()).executes(RelayCommand::approveReview));
        root.then(approve);

        var reject = Commands.literal("reject").requires(RelayCommand::approveSource)
            .then(Commands.argument("review", StringArgumentType.word()).executes(RelayCommand::rejectReview));
        root.then(reject);

        root.then(Commands.literal("paths").requires(RelayCommand::manageSource).executes(RelayCommand::printPaths));

        root.then(Commands.literal("refresh").requires(RelayCommand::manageSource).executes(RelayCommand::refreshAll)
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
        if (!requireManage(context)) {
            return 0;
        }
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
        if (!requireManage(context)) {
            return 0;
        }
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
        if (!requireManage(context)) {
            return 0;
        }
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
        if (!requireManage(context)) {
            return 0;
        }
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
        if (!requireManage(context)) {
            return 0;
        }
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

        var result = ModelPlacement.delete(modelId,
            UploadRegistry.get().find(modelId).map(UploadRegistry.Entry::fileName).orElse(null));
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
        if (!requireManage(context)) {
            return 0;
        }
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
        if (!requireManage(context)) {
            return 0;
        }
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

}
