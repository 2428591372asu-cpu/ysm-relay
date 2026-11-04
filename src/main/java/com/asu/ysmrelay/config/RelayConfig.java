package com.asu.ysmrelay.config;

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
 * <p><b>键的归属</b>：{@code [upload]} 段由 W2 用；{@code [review]} 段的审批与同步键由 W3 / W9 读，
 * 两个模块都不要再往本类加键 —— 键表在 {@code docs/PLAN.md} §7 一次性定死，加键要先改那张表。
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
    // [review] 审批、同步、权限（W3 / W9 只读，键表见 PLAN §7）
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
    /** 待审保留时长（小时）。超期自动按过期拒绝并删掉待审目录。 */
    public static final ForgeConfigSpec.IntValue PENDING_TTL_HOURS;
    /**
     * 是否自动同步。{@code true} = 入库后立刻 reload 推给全服；
     * {@code false} = 文件照样落盘，但不 reload，等服主手动触发。
     */
    public static final ForgeConfigSpec.BooleanValue AUTO_SYNC;
    /** 处理上传请求（审批）所需的权限等级。 */
    public static final ForgeConfigSpec.IntValue APPROVE_PERMISSION_LEVEL;
    /** 管理模型使用权（grant / revoke / move）所需的权限等级。 */
    public static final ForgeConfigSpec.IntValue ACCESS_PERMISSION_LEVEL;

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.comment("YSM Relay upload settings").push("upload");

        ALLOW_PLAYER_UPLOAD = builder
            .comment("Allow ordinary players to upload model files to this server.")
            .define("allowPlayerUpload", true);

        MAX_MODEL_BYTES = builder
            .comment("Maximum size of one uploaded model file, in bytes. 0 disables the limit.")
            .defineInRange("maxModelBytes", 64 * 1024 * 1024, 0, Integer.MAX_VALUE);

        CHUNK_BYTES = builder
            .comment("Chunk size the server asks clients to use.")
            .defineInRange("chunkBytes", 32 * 1024, 4096, 512 * 1024);

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
            .comment("Required permission level for uploading. 0 means any player.")
            .defineInRange("uploadPermissionLevel", 0, 0, 4);

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

        PENDING_TTL_HOURS = builder
            .comment("How long a pending upload is kept before it expires and gets cleaned up.")
            .defineInRange("pendingTtlHours", 72, 1, 24 * 30);

        AUTO_SYNC = builder
            .comment("Reload YSM right after an upload is committed, so players receive it immediately.",
                "false places the file but leaves it invisible until the owner triggers a sync.")
            .define("autoSync", true);

        APPROVE_PERMISSION_LEVEL = builder
            .comment("Permission level required to handle upload requests.")
            .defineInRange("approvePermissionLevel", 2, 0, 4);

        ACCESS_PERMISSION_LEVEL = builder
            .comment("Permission level required to change which player may use which model.")
            .defineInRange("accessPermissionLevel", 2, 0, 4);

        builder.pop();

        SPEC = builder.build();
    }

    private RelayConfig() {}

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

    /** 处理上传请求所需的权限等级。 */
    public static int approvePermissionLevel() {
        return APPROVE_PERMISSION_LEVEL.get();
    }

    /** 管理模型使用权所需的权限等级。 */
    public static int accessPermissionLevel() {
        return ACCESS_PERMISSION_LEVEL.get();
    }

    /** 上传是否应该落进 auth 根。 */
    public static boolean targetAuthRoot() {
        return "auth".equalsIgnoreCase(TARGET_ROOT.get());
    }
}
