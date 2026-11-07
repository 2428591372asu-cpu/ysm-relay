package com.asu.ysmrelay.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * relay 的服务端配置。
 *
 * <p>放在 SERVER 类型下：这些值决定的是「服务端愿不愿意收」，客户端不需要读。服务端在探测阶段
 * 就把结论通过 {@code ProbeReply} 告诉客户端，所以客户端也不需要本地副本。
 *
 * <p><b>文件实际落在哪</b>：注册见 {@code RelayBootstrap}（{@code ModConfig.Type.SERVER} +
 * {@code ysm-relay-server.toml}）。Forge 1.20.1 的 SERVER 配置不在 {@code config/} 下，而是
 * {@code <存档目录>/serverconfig/}（{@code ServerLifecycleHooks.getServerConfigPath} 走的是
 * {@code server.getWorldPath(new LevelResource("serverconfig"))}）。专用服务器的默认路径是
 * {@code world/serverconfig/ysm-relay-server.toml}，首次起服生成，改完重启生效。
 *
 * <p><b>分段</b>：{@code [upload]} 放上传准入与磁盘守卫；{@code [review]} 放审批、同步与权限等级。
 * 加键时三处要一起改：本类的定义、{@code option()} 里登记的等级、以及
 * {@code RelayConfigSync} 的快照读写（设置页靠它下发与回写）。
 */
public final class RelayConfig {
    public static final ForgeConfigSpec SPEC;

    // ------------------------------------------------------------------
    // [upload] 上传准入与磁盘守卫
    // ------------------------------------------------------------------

    /** 是否允许普通玩家上传。关掉后仍然保留服主手动投放的能力。 */
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLAYER_UPLOAD;
    /** 单个模型的上限（字节）。超过在探测阶段就拒，一个数据包都不收。 */
    public static final ForgeConfigSpec.IntValue MAX_MODEL_BYTES;
    /** 服务端建议的分片大小；客户端必须照这个切。 */
    public static final ForgeConfigSpec.IntValue CHUNK_BYTES;
    /**
     * 单个玩家累计占用上限（字节）。按 UploadRegistry 记录的上传大小累加，
     * 不实时遍历磁盘 —— 服主手动放进去的模型不计入玩家配额。0 = 不限制。
     */
    public static final ForgeConfigSpec.LongValue MAX_BYTES_PER_PLAYER;
    /** 全服上传累计上限（字节），口径同上。0 = 不限制。 */
    public static final ForgeConfigSpec.LongValue MAX_TOTAL_BYTES;
    /** 未完成的上传在 staging 里保留多久，超时清掉。 */
    public static final ForgeConfigSpec.IntValue STAGING_TTL_SECONDS;
    /** 需要多少权限等级才能上传。0 = 只要 ALLOW_PLAYER_UPLOAD 开着就行。 */
    public static final ForgeConfigSpec.IntValue UPLOAD_PERMISSION_LEVEL;
    /** 上传落哪个根：{@code custom} = 人人可见；{@code auth} = 只有被授权的玩家能选。 */
    public static final ForgeConfigSpec.ConfigValue<String> TARGET_ROOT;
    /**
     * 暂存区占用上限（字节），统计 {@code relay-staging/} + {@code relay-pending/} 之和。
     * 传输中残片和待审内容都算，因为它们吃的是同一块磁盘。0 = 不限制。
     */
    public static final ForgeConfigSpec.LongValue MAX_STAGING_BYTES;
    /**
     * 暂存区所在磁盘的剩余空间下限（字节）。低于它直接拒收，暂存目录一个文件都不留。
     * 0 = 不检查。
     */
    public static final ForgeConfigSpec.LongValue MIN_FREE_DISK_BYTES;
    /**
     * 每个玩家的上行速率上限（字节/秒）。0 = 不限制。
     *
     * <p>语义是「防暂存 IO 队列堆积」，不是带宽套餐 —— 别拿它跟 YSM 的 BandwidthLimit 对号，
     * 那个管的是服务端往客户端分发。
     */
    public static final ForgeConfigSpec.IntValue MAX_UPLOAD_BYTES_PER_SECOND;
    /**
     * 同时进行的模型转换数，上限 2。
     *
     * <p>有意的独立有界线程池，绝不与 YSM 的 {@code ThreadCount} 共池：共池的症状是服主
     * {@code /ysm model reload} 莫名变慢，而且从日志上归不了因。
     */
    public static final ForgeConfigSpec.IntValue MAX_CONCURRENT_CONVERSIONS;

    // ------------------------------------------------------------------
    // [review] 审批、同步、权限
    // ------------------------------------------------------------------

    /** 是否自动同意上传。{@code false} = 逐次审（收妥后进待审队列）。 */
    public static final ForgeConfigSpec.BooleanValue AUTO_APPROVE;
    /**
     * 自动同意的大小门槛（字节）。{@code autoApprove=true} 且声明体积不超过此值 → 直接入库；
     * 超过仍进待审。0 = 不限大小（等于放行全部）。判定用平台声明的体积，不是事后量文件。
     */
    public static final ForgeConfigSpec.LongValue AUTO_APPROVE_MAX_BYTES;
    /** 待审队列容量。满了在探测阶段就回 {@code REJECTED_QUEUE_FULL}。 */
    public static final ForgeConfigSpec.IntValue MAX_PENDING_COUNT;
    /** 审批模式下每个玩家最多能攒多少个未处理的上传。 */
    public static final ForgeConfigSpec.IntValue MAX_PENDING_PER_PLAYER;
    /** 上传白名单：名单内玩家不受待审上限、个人配额与权限等级限制，且跳过审核。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> UPLOAD_WHITELIST;
    /** 手动授予的等级，条目形如 {@code <uuid>:<1-3>}。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> LEVEL_GRANTS;

    /** 「我的上传 / 待审」列表一次最多回传多少条。 */
    public static final ForgeConfigSpec.IntValue LIST_MAX_ENTRIES;

    /**
     * 上传后的处理模式：{@code review} 全部待审、{@code auto} 超上限转待审、
     * {@code full} 超上限直接拒收。
     */
    public static final ForgeConfigSpec.ConfigValue<String> UPLOAD_MODE;

    /** 每个选项所需等级的注册表，{@code [permissions]} 段填充。 */
    private static final Map<String, ForgeConfigSpec.IntValue> OPTION_LEVELS = new LinkedHashMap<>();
    /** 待审保留时长（小时）。超期自动按过期拒绝并删掉待审目录。 */
    public static final ForgeConfigSpec.IntValue PENDING_TTL_HOURS;
    /**
     * 是否自动同步。{@code true} = 入库后立刻 reload 推给全服；
     * {@code false} = 文件照样落盘，但不 reload，等服主手动触发。
     */
    public static final ForgeConfigSpec.BooleanValue AUTO_SYNC;
    /** 拒绝一切上传。优先级高于其它所有开关。 */
    public static final ForgeConfigSpec.BooleanValue REJECT_ALL_UPLOADS;
    /** 是否允许客户端使用「自动上传目录」。关掉后只禁自动上传，手动上传不受影响。 */
    public static final ForgeConfigSpec.BooleanValue ALLOW_AUTO_UPLOAD;
    /** 两次自动同步之间至少间隔多少秒，防止连续上传把 reload 打爆。 */
    public static final ForgeConfigSpec.IntValue AUTO_SYNC_MIN_INTERVAL_SECONDS;
    /**
     * 客户端自动上传目录的扫描间隔（秒）。0 = 用文件监听（推荐，几乎零开销）；
     * 大于 0 时改为轮询，用于网络盘等监听不可靠的目录。
     */
    public static final ForgeConfigSpec.IntValue AUTO_UPLOAD_SCAN_SECONDS;
    /** 被拉黑的玩家（UUID）：服务端不接收这些人上传的模型。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCKED_UPLOADERS;

    /** 审批组成员（UUID）：2 级，可审批待审队列。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> APPROVE_GROUP;
    /** 管理组成员（UUID）：2 级，可管理模型与上传名单。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MANAGE_GROUP;



    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.comment(
            "===== YSM Relay 权限模型 =====",
            "",
            "三级权限，数字越大越高。等级由服务端每次现算，不缓存。",
            "",
            "0 级（被禁用）：显式授予 0 级后，不能用本模组的任何功能——",
            "  上传被拒、/relayme 不可用、界面入口提示已禁用。优先级最高，白名单也盖不过它。",
            "  3 级可以向任何人授 0 级（包括另一个 3 级与房主），这是刻意保留的：",
            "  原版默认 op-permission-level=4，op 之间本来就能互相 /deop，不在这里加额外限制。",
            "",
            "1 级（所有玩家）：",
            "  - 上传模型（还受 allowPlayerUpload / uploadPermissionLevel / 配额限制）",
            "  - 查看、删除、撤回「自己上传」的模型（/relayme list / delete / cancel）",
            "  - 看不到设置页的管理项，也不能执行 /ysmrelay",
            "",
            "2 级（模型管理员）：op（原版权限 2）、approveGroup 或 manageGroup 组员、",
            "  或服主用 /ysmrelay level grant 授予。可以：",
            "  - 审批待审队列（/ysmrelay pending / approve / reject）",
            "  - 删除、搬迁、授权模型（/ysmrelay delete / move / policy / grant / revoke）",
            "  - 管理上传名单（/ysmrelay blockuploader / whitelist）",
            "  - 在设置页修改标记为 2 级的配置项",
            "  不能：分发等级、修改权限定义类配置",
            "",
            "3 级（服主信任的人）：只有服主能授予。等同于服主的权限，拥有 2 级的全部能力，另外可以：",
            "  - /ysmrelay level grant|revoke 分发 1–3 级（不能授予高于自己的等级）",
            "  - 修改所有配置项，包括权限定义类配置",
            "",
            "服主（专用服务器控制台 / LAN 房主）：不依赖任何配置，等同于 3 级。",
            "  3 级与服主权限相同；区别只在于服主身份来自控制台/主机，不占 levelGrants 名单。",
            "",
            "授予与撤销：",
            "  /ysmrelay level grant <玩家> <0-3>",
            "  /ysmrelay level revoke <uuid>",
            "  /ysmrelay level list",
            "",
            "各配置项「至少需要几级才能改」在 [permissions] 段登记；",
            "「哪个组能做什么」由 approveGroup / manageGroup 决定（组员一律 2 级）。",
            "",
            "YSM Relay upload settings")
            .push("upload");

        ALLOW_PLAYER_UPLOAD = builder
            .comment("Allow ordinary players to upload model files to this server.")
            .define("allowPlayerUpload", true);

        MAX_MODEL_BYTES = builder
            .comment("Maximum size of one uploaded model file, in bytes. 0 disables the limit.")
            .defineInRange("maxModelBytes", 64 * 1024 * 1024, 0, Integer.MAX_VALUE);

        CHUNK_BYTES = builder
            .comment("Chunk size the server asks clients to use.")
            // Minecraft 的自定义负载上限是 32767 字节，超一个字节就断线。
            // 留足包头余量，默认 16 KiB，上限也压到 16 KiB。
            .defineInRange("chunkBytes", 16 * 1024, 4096, 16 * 1024);

        MAX_BYTES_PER_PLAYER = builder
            .comment("Total bytes one player may have uploaded. 0 disables the limit.",
                "Counted from the relay upload registry, so models placed by the admin do not count.")
            .defineInRange("maxBytesPerPlayer", 1024L * 1024L * 1024L, 0L, Long.MAX_VALUE);

        MAX_TOTAL_BYTES = builder
            .comment("Total bytes all players together may have uploaded. 0 disables the limit.",
                "Defaults to 4 GiB, the same order of magnitude as maxBytesPerPlayer.")
            .defineInRange("maxTotalBytes", 4L * 1024L * 1024L * 1024L, 0L, Long.MAX_VALUE);

        STAGING_TTL_SECONDS = builder
            .comment("How long an unfinished upload may sit in the staging directory before cleanup.")
            .defineInRange("stagingTtlSeconds", 600, 30, 86400);

        UPLOAD_PERMISSION_LEVEL = builder
            .comment("Required YSM Relay level to upload.",
                "0 = everyone (subject to allowPlayerUpload), 1 = level-1 players and above,",
                "2 = model admins only, 3 = trusted (owner-granted) only.",
                "Whitelisted players always skip this check.")
            .defineInRange("uploadPermissionLevel", 0, 0, 3);

        TARGET_ROOT = builder
            .comment("Which catalog root uploads land in: 'custom' (public) or 'auth' (grant only).")
            .define("targetRoot", "custom");

        MAX_STAGING_BYTES = builder
            .comment("Total bytes the staging area may hold, counted over relay-staging/ and relay-pending/.",
                "Default 768 MiB = one full 8-slot pending queue of 64 MiB models plus headroom.",
                "0 disables the limit.")
            .defineInRange("maxStagingBytes", 768L * 1024L * 1024L, 0L, Long.MAX_VALUE);

        MIN_FREE_DISK_BYTES = builder
            .comment("Reject an upload when the filesystem holding the staging area has less free space",
                "than this, checked before any staging file is created. 0 disables the check.")
            .defineInRange("minFreeDiskBytes", 2L * 1024L * 1024L * 1024L, 0L, Long.MAX_VALUE);

        MAX_UPLOAD_BYTES_PER_SECOND = builder
            .comment("Per-player upload rate cap in bytes per second. 0 disables it.",
                "This guards the staging IO queue. It is NOT YSM's BandwidthLimit,",
                "which controls server to client distribution.")
            .defineInRange("maxUploadBytesPerSecond", 2 * 1024 * 1024, 0, Integer.MAX_VALUE);

        REJECT_ALL_UPLOADS = builder
            .comment("Reject every upload from players, regardless of other switches.",
                "Overrides allowPlayerUpload and every other upload setting.")
            .define("rejectAllUploads", false);

        ALLOW_AUTO_UPLOAD = builder
            .comment("Allow clients to auto-upload files dropped into a watched folder.",
                "Turning this off only disables the automatic path; players can still pick files manually.")
            .define("allowAutoUpload", true);

        MAX_CONCURRENT_CONVERSIONS = builder
            .comment("How many model conversions may run at the same time. Capped at 2.",
                "Deliberately its own bounded pool: sharing YSM's ThreadCount shows up as",
                "unexplainable slowness during /ysm model reload.")
            .defineInRange("maxConcurrentConversions", 1, 1, 2);

        builder.pop();

        builder.comment("Review gate, sync and permissions").push("review");

        AUTO_APPROVE = builder
            .comment("Commit uploads without asking the owner. false means every upload is reviewed.")
            .define("autoApprove", false);

        AUTO_APPROVE_MAX_BYTES = builder
            .comment("With autoApprove on, only uploads whose declared size is at most this are committed",
                "right away; bigger ones still wait for review. 0 means no size cap.")
            .defineInRange("autoApproveMaxBytes", 16L * 1024L * 1024L, 0L, Long.MAX_VALUE);

        MAX_PENDING_COUNT = builder
            .comment("How many uploads may wait for review at once. Full queue rejects at probe time.")
            .defineInRange("maxPendingCount", 8, 1, 256);

        MAX_PENDING_PER_PLAYER = builder
            .comment("In review mode, how many unreviewed uploads one player may keep queued.",
                "Reaching it blocks further uploads from that player until the owner acts.")
            .defineInRange("maxPendingPerPlayer", 8, 0, 256);

        UPLOAD_WHITELIST = builder
            .comment("UUIDs allowed to upload freely: no pending cap, no per-player quota, no review.",
                "Resource guards still apply: rejectAllUploads, blocklist, model size, total quota.",
                "Managed with /ysmrelay whitelist add|remove|list.")
            .defineList("uploadWhitelist", List.of(),
                value -> value instanceof String text && text.length() == 36);

        PENDING_TTL_HOURS = builder
            .comment("How long a pending upload is kept before it expires and gets cleaned up.")
            .defineInRange("pendingTtlHours", 72, 1, 24 * 30);

        AUTO_SYNC = builder
            .comment("Reload YSM right after an upload is committed, so players receive it immediately.",
                "false places the file but leaves it invisible until the owner triggers a sync.")
            .define("autoSync", true);

        AUTO_SYNC_MIN_INTERVAL_SECONDS = builder
            .comment("Shortest gap between two automatic reloads, in seconds.",
                "Several uploads landing together are merged into one reload window.")
            .defineInRange("autoSyncMinIntervalSeconds", 15, 0, 3600);

        BLOCKED_UPLOADERS = builder
            .comment("UUIDs whose uploads this server refuses. Existing models they uploaded stay.",
                "Managed with /ysmrelay blockuploader add|remove|list.")
            .defineList("blockedUploaders", List.of(),
                value -> value instanceof String text && text.length() == 36);

        UPLOAD_MODE = builder
            .comment("What happens to a finished upload.",
                "review = everything waits for approval;",
                "auto = under the cap goes in directly, bigger ones wait for approval;",
                "full = under the cap goes in directly, bigger ones are rejected outright.",
                "Switched in-game with the mode button; permission level 3.")
            .define("uploadMode", "review", value -> value instanceof String text
                && (text.equals("review") || text.equals("auto") || text.equals("full")));

        LIST_MAX_ENTRIES = builder
            .comment("How many rows the upload / pending list may return in one reply.",
                "Hard byte budget applies anyway, so huge values cannot blow up the packet.")
            .defineInRange("listMaxEntries", 200, 8, 32767);

        AUTO_UPLOAD_SCAN_SECONDS = builder
            .comment("How the client watches the auto-upload folder.",
                "0 = filesystem watch (near-zero cost, recommended).",
                "Above 0 = poll every N seconds, for folders where watching is unreliable (network shares).")
            .defineInRange("autoUploadScanSeconds", 0, 0, 3600);

        APPROVE_GROUP = builder
            .comment("UUIDs of level-2 model admins (approval side).",
                "Members can review/approve/reject pending uploads.",
                "Operators (vanilla permission level 2) always count as level 2, even with both lists empty.",
                "Only the owner can change this list; members cannot add anyone, including themselves.")
            .defineList("approveGroup", List.of(), value -> value instanceof String text && text.length() == 36);

        MANAGE_GROUP = builder
            .comment("UUIDs of level-2 model admins (management side).",
                "Members can delete/move/authorize models and manage upload lists.",
                "Operators (vanilla permission level 2) always count as level 2, even with both lists empty.",
                "Only the owner can change this list; members cannot add anyone, including themselves.")
            .defineList("manageGroup", List.of(), value -> value instanceof String text && text.length() == 36);

        builder.pop();

        builder.comment(
            "===== 每个配置项「至少需要几级」才能修改 =====",
            "1 = 所有玩家；2 = 模型管理员（op / 两个组的组员 / 被授予 2 级）；3 = 服主信任的人（被授予 3 级）。",
            "注意：这里只是「改配置」的门槛，不是「能做什么」的定义。",
            "能做什么见文件顶部的权限模型说明。",
            "权限定义类键（approveUploads / manageRights / blockUploaders / uploadWhitelist /",
            "groupManage / uploadPermissionLevel）要求 3 级（与服主同权）。")
            .push("permissions");
        option(builder, "allowPlayerUpload", 3);
        option(builder, "rejectAllUploads", 3);
        option(builder, "allowAutoUpload", 3);
        option(builder, "uploadMode", 3);
        option(builder, "autoApproveMaxBytes", 3);
        option(builder, "autoSync", 3);
        option(builder, "autoSyncMinIntervalSeconds", 3);
        option(builder, "autoUploadScanSeconds", 3);
        option(builder, "maxPendingCount", 3);
        option(builder, "maxPendingPerPlayer", 3);
        option(builder, "maxModelBytes", 3);
        option(builder, "maxBytesPerPlayer", 3);
        option(builder, "maxTotalBytes", 3);
        option(builder, "uploadPermissionLevel", 3);
        option(builder, "approveUploads", 3);
        option(builder, "manageRights", 3);
        option(builder, "blockUploaders", 3);
        option(builder, "uploadWhitelist", 3);
        option(builder, "groupManage", 3);
        option(builder, "listMaxEntries", 3);
        LEVEL_GRANTS = builder
            .comment("Manual level grants, one entry per player, formatted <uuid>:<level>.",
                "Level 0 disables every relay feature for that player (overrides op/host).")
            .defineList("levelGrants", List.of(),
                value -> value instanceof String text && text.contains(":") && text.length() <= 80);
        builder.pop();

        SPEC = builder.build();
    }

    private RelayConfig() {}

    private static void option(ForgeConfigSpec.Builder builder, String name, int defaultLevel) {
        OPTION_LEVELS.put(name,
            builder.comment("Required level to edit " + name + " (1 player / 2 model admin / 3 trusted).")
                .defineInRange(name, defaultLevel, 1, 3));
    }

    /** 某个选项需要的等级；没登记的按只有服主可用处理。 */
    public static int levelOf(String option) {
        var value = OPTION_LEVELS.get(option);
        return value == null ? 3 : value.get();
    }

    /** 所有选项的等级快照，发给客户端决定按钮是否可用。 */
    public static Map<String, Integer> optionLevels() {
        var result = new LinkedHashMap<String, Integer>();
        for (var entry : OPTION_LEVELS.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }

    /** 手动授予的等级（uuid → 等级，取最高）；没有任何授予时返回 -1。 */
    public static int grantedLevel(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return -1;
        }
        var best = -1;
        for (var raw : LEVEL_GRANTS.get()) {
            if (raw == null) {
                continue;
            }
            var text = raw.trim().toLowerCase(Locale.ROOT);
            var split = text.lastIndexOf(':');
            if (split <= 0) {
                continue;
            }
            if (!text.substring(0, split).equals(uuid.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                best = Math.max(best, Integer.parseInt(text.substring(split + 1).trim()));
            } catch (NumberFormatException ignored) {
                // 坏条目跳过
            }
        }
        return Math.min(best, 3);
    }

    /** 改写等级授予表。 */
    public static void setLevelGrants(Set<String> entries) {
        LEVEL_GRANTS.set(List.copyOf(entries));
    }

    /** 现有的等级授予条目（原样）。 */
    public static Set<String> levelGrants() {
        var result = new LinkedHashSet<String>();
        for (var raw : LEVEL_GRANTS.get()) {
            if (raw != null && !raw.isBlank()) {
                result.add(raw.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** 实际下发给客户端的分片大小，硬顶 16 KiB，老配置里的超大值也压回安全线。 */
    public static int safeChunkBytes() {
        var raw = CHUNK_BYTES.get();
        return Math.max(4096, Math.min(16 * 1024, raw));
    }

    /** 上限字节数；0 或负数表示不限制。 */
    public static long maxModelBytes() {
        var value = MAX_MODEL_BYTES.get();
        return value <= 0 ? 0L : value;
    }

    /** 单玩家累计上限；0 表示不限制。 */
    public static long maxBytesPerPlayer() {
        var value = MAX_BYTES_PER_PLAYER.get();
        return value <= 0L ? 0L : value;
    }

    /** 全服累计上限；0 表示不限制。 */
    public static long maxTotalBytes() {
        var value = MAX_TOTAL_BYTES.get();
        return value <= 0L ? 0L : value;
    }

    /** 暂存区（staging + pending）总占用上限；0 表示不限制。 */
    public static long maxStagingBytes() {
        var value = MAX_STAGING_BYTES.get();
        return value <= 0L ? 0L : value;
    }

    /** 磁盘剩余下限；0 表示不检查。 */
    public static long minFreeDiskBytes() {
        var value = MIN_FREE_DISK_BYTES.get();
        return value <= 0L ? 0L : value;
    }

    /** 每玩家上行速率（字节/秒）；0 表示不限制。 */
    public static int maxUploadBytesPerSecond() {
        var value = MAX_UPLOAD_BYTES_PER_SECOND.get();
        return value <= 0 ? 0 : value;
    }

    /** 转换并发数，至少 1。 */
    public static int maxConcurrentConversions() {
        return MAX_CONCURRENT_CONVERSIONS.get();
    }

    /** 是否自动同意上传。 */
    public static boolean autoApprove() {
        return AUTO_APPROVE.get();
    }

    /** 自动同意的大小门槛；0 表示不限大小。 */
    public static long autoApproveMaxBytes() {
        var value = AUTO_APPROVE_MAX_BYTES.get();
        return value <= 0L ? 0L : value;
    }

    /** 待审队列容量。 */
    public static int maxPendingCount() {
        return MAX_PENDING_COUNT.get();
    }

    /** 待审保留时长（小时）。 */
    public static int pendingTtlHours() {
        return PENDING_TTL_HOURS.get();
    }

    /** 是否入库后立刻 reload。 */
    public static boolean autoSync() {
        return AUTO_SYNC.get();
    }

    /** 上传是否应该落进 auth 根。 */
    public static boolean targetAuthRoot() {
        return "auth".equalsIgnoreCase(TARGET_ROOT.get());
    }

    /** 服务端是否拒绝一切上传。 */
    public static boolean rejectAllUploads() {
        return REJECT_ALL_UPLOADS.get();
    }

    /** 是否允许客户端自动上传目录里的模型。 */
    public static boolean allowAutoUpload() {
        return ALLOW_AUTO_UPLOAD.get();
    }

    /** 归一化后的上传模式：review / auto / full。旧配置里 autoApprove=true 视为 auto。 */
    public static String uploadMode() {
        var raw = UPLOAD_MODE.get();
        if (raw != null) {
            var text = raw.trim().toLowerCase(Locale.ROOT);
            if (text.equals("auto") || text.equals("full")) {
                return text;
            }
            if (text.equals("review")) {
                // 老版本把「自动同意」放在布尔键里，升级后第一次读到时迁移过来。
                // 界面切档时会同时写 autoApprove，所以这条只对旧配置生效。
                return AUTO_APPROVE.get() ? "auto" : "review";
            }
        }
        return AUTO_APPROVE.get() ? "auto" : "review";
    }

    /** 写模式，并同步旧布尔键，避免下一次读到时被迁移逻辑覆盖。 */
    public static void setUploadMode(String mode) {
        var normalized = mode == null ? "review" : mode.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("auto") && !normalized.equals("full")) {
            normalized = "review";
        }
        UPLOAD_MODE.set(normalized);
        AUTO_APPROVE.set(!normalized.equals("review"));
    }

    /** 列表一次最多回传多少条。 */
    public static int listMaxEntries() {
        return LIST_MAX_ENTRIES.get();
    }

    /** 审批模式下每人的待审上限。 */
    public static int maxPendingPerPlayer() {
        return MAX_PENDING_PER_PLAYER.get();
    }

    /** 上传白名单（小写 UUID 集合）。 */
    public static Set<String> uploadWhitelist() {
        return normalizeGroup(UPLOAD_WHITELIST.get());
    }

    /** 改写上传白名单（即时生效并落盘）。 */
    public static void setUploadWhitelist(Set<String> uuids) {
        UPLOAD_WHITELIST.set(List.copyOf(uuids));
    }

    /** 客户端扫描自动上传目录的方式：0 = 文件监听，>0 = 轮询秒数。 */
    public static int autoUploadScanSeconds() {
        return AUTO_UPLOAD_SCAN_SECONDS.get();
    }

    /** 自动同步的最短间隔（毫秒），与内部的 500ms 合并窗口取较大值。 */
    public static long autoSyncMinIntervalMillis() {
        return AUTO_SYNC_MIN_INTERVAL_SECONDS.get() * 1000L;
    }

    /** 被拉黑的上传者 UUID 集合（小写）。 */
    public static Set<String> blockedUploaders() {
        return normalizeGroup(BLOCKED_UPLOADERS.get());
    }

    /** 改写拉黑名单（即时生效并落盘）。 */
    public static void setBlockedUploaders(Set<String> uuids) {
        BLOCKED_UPLOADERS.set(List.copyOf(uuids));
    }

    /** 可以处理上传请求的 UUID 集合（小写）。 */
    public static Set<String> approveGroup() {
        return normalizeGroup(APPROVE_GROUP.get());
    }

    /** 可以管理模型使用权的 UUID 集合（小写）。 */
    public static Set<String> manageGroup() {
        return normalizeGroup(MANAGE_GROUP.get());
    }

    private static Set<String> normalizeGroup(List<? extends String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        var result = new LinkedHashSet<String>();
        for (var value : raw) {
            if (value == null) {
                continue;
            }
            var text = value.trim().toLowerCase(Locale.ROOT);
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return Set.copyOf(result);
    }
}
