# ysm-relay

YSM（Yes Steve Model）3.x 的附属模组，补上 YSM 缺少的「客户端 → 服务端」上传半段。

玩家在游戏里把本机模型传给服务器，服务端落盘后由 **YSM 自己**重扫并分发。本模组不实现分发、缓存、按需取块——那些全部沿用 YSM 现成能力。

## 版本与依赖

| 项 | 值 |
| --- | --- |
| 当前版本 | **1.4.6**（协议 v10） |
| Minecraft | 1.20.1 |
| Forge | 47 以上（开发用 47.4.3） |
| Java | 17 |
| YSM | 3.x，服务端与客户端都必须装 |
| 本模组 | 两端版本必须一致，协议 v10 |

YSM 是硬依赖（运行时使用它的模型目录、导入管线、授权与选中接口）。YSM 不存在时本模组只打日志停用，不会拖垮服务器启动。

## 安装

1. 把 `ysm_relay-1.4.6.jar` 放进**客户端**与**服务端**的 `mods/` 目录。
2. 启动一次服务器，生成 `<存档>/serverconfig/ysm-relay-server.toml`。
3. 按需改配置，重启生效。

## 快速上手

**服主**

1. 决定上传模式：`uploadMode` 默认 `review`（全部待审），想省事可改 `auto`。
2. 需要逐玩家控制模型使用权时，把 `targetRoot` 改成 `auth`。
3. 需要副手时用 `/ysmrelay level grant <玩家> 2` 授予管理员，或把 UUID 加进 `approveGroup` / `manageGroup`。

**玩家**

1. 在 YSM 模型界面点「上传模型」，或按热键 `U`。
2. 选文件（`.mxc` / `.ysm` / `.zip` / `.7z`），点上传。
3. 审批模式下等服主批准；其它模式直接入库。

## 功能清单

### 上传

- **手动上传**：多选、串行队列（服务端每人同时只允许一个传输）、逐文件进度与结果
- **自动上传**：设置一个本地目录，文件变化自动上传。优先用文件监听（零开销），监听不可用时回退轮询；`autoUploadScanSeconds` 可强制轮询
- **支持格式**：`.mxc`、旧版 `.ysm`（V1/V2/V3 三种容器都认）、`.zip`、`.7z`；目录型模型需服主手动放
- **自定义上传目录**：手动上传面板顶部「目录…」可指向任意本地文件夹，设置持久化
- **预检只作提示**：预检未通过的文件仍可上传，最终判定在服务端

### 审批

- **三档模式**：`review` / `auto` / `full`（见表）
- **待审队列**：列表显示「上传者 · 模型名」，服务端权威；批准后自动入库并重扫，拒绝则删除内容并通知上传者
- **切换模式即处理队列**：切到 `auto` / `full` 时，队列里已有条目按新规则处理（未超限批准、full 档超限拒绝、auto 档超限保留）
- **我的待审**：玩家可撤回自己还没被审的条目

### 管理

- **我的上传**：玩家查看、删除自己上传的模型（服务端按台账复核归属）
- **模型管理**：设置页按钮进入独立目录页，三列（上传者 / 模型名 / 大小），按上传者分组、本地模型排最前、组名 A–Z、支持搜索与翻页
- **删除**：弹确认框（确认 / 取消 / 不再提示），删除时同时清理 YSM 转换缓存与源文件，再重扫
- **授权矩阵**：`public` / `listed` / `except` 三种策略，作用于 `auth` 根的模型
- **名单**：禁止上传名单、上传白名单、审批组、管理组、手动等级授予
- **上传台账**：以内容哈希为键保存上传者，落盘 `config/ysm-relay/uploads.json`，重启不丢

### 界面

- YSM 模型界面内的入口按钮 + 独立上传面板 + 三栏设置页（上传模型 / 模型审批 / 同步配置 / 模型管理）
- 设置页按权限等级置灰，等级每次由服务端现算
- 进到没装 relay 的服务器时，入口提示「功能不可用」；未开 LAN 的单人模式提示「本功能在单人模式下不可用」

## 上传模式

| 值 | 行为 |
| --- | --- |
| `review` | 所有上传进待审队列，批准后才入库 |
| `auto` | 不超过 `autoApproveMaxBytes` 直接入库，超过转待审 |
| `full` | 不超过上限直接入库，超过在探测阶段直接拒绝（一个字节都不传） |

白名单玩家始终免审、免配额、免等级门槛。

## 权限模型

| 等级 | 谁能拿到 | 能做什么 |
| --- | --- | --- |
| 1 | 所有玩家 | 上传；`/relayme` 管理自己的上传 |
| 2 | op、审批组/管理组组员、被授予 2 级 | 审批待审 + 删除/搬迁/授权模型 + 管理上传名单；**不能分发等级** |
| 3 | 服主授予的人 | **与服主同权**：2 级全部 + 分发 1–3 级 + 修改所有配置 |
| 服主 | 专用服务器控制台 / LAN 房主 | 与 3 级同权；身份来自主机，不占 `levelGrants` 名单 |

分发与撤销：

```
/ysmrelay level grant <玩家> <1-3>
/ysmrelay level revoke <uuid>
/ysmrelay level list
```

3 级只能授予不高于自己的等级；2 级执行 `level` 会被拒绝。权限定义类配置（`approveUploads`、`manageRights`、`blockUploaders`、`uploadWhitelist`、`groupManage`、`uploadPermissionLevel`）要求 3 级——2 级不能给自己放权。

## 命令

### `/ysmrelay`（管理面）

根命令只对「管理资格或审批资格」的玩家开放；命令方块一律拒绝，控制台放行。

| 命令 | 作用 | 等级 |
| --- | --- | --- |
| `/ysmrelay status` | 总览：YSM 版本、模型数、策略数、禁止上传数、落点 | 2 |
| `/ysmrelay paths` | 打印模型根 / 暂存根 / 待审根与实际文件数（排障第一步） | 2 |
| `/ysmrelay list [页码]` | 列出授权策略，每页 8 条 | 2 |
| `/ysmrelay show <玩家>` | 看某玩家当前持有的授权，并核对策略与运行时是否一致 | 2 |
| `/ysmrelay pending` | 列出待审队列（上传者 · 模型名） | 2 |
| `/ysmrelay approve <编号>` | 批准待审：文件进模型根并重扫 | 2 |
| `/ysmrelay reject <编号>` | 拒绝待审：删除内容并通知上传者 | 2 |
| `/ysmrelay policy <模型> public` | 设为公开（人人可用） | 2 |
| `/ysmrelay policy <模型> listed <玩家...>` | 只给名单上的玩家 | 2 |
| `/ysmrelay policy <模型> except <玩家...>` | 除名单外所有人 | 2 |
| `/ysmrelay policy <模型> clear` | 删除策略，回到 YSM 默认语义 | 2 |
| `/ysmrelay grant <玩家> <模型>` | 把玩家加进该模型名单 | 2 |
| `/ysmrelay revoke <玩家> <模型>` | 从名单移除 | 2 |
| `/ysmrelay move <模型> auth|custom` | 在 auth / custom 根之间搬文件（搬完重扫） | 2 |
| `/ysmrelay delete <模型> confirm` | 删除模型（源文件 + YSM 缓存），并加入禁止上传名单 | 2 |
| `/ysmrelay block <模型>` | 只禁止再次上传，不删文件 | 2 |
| `/ysmrelay unblock <模型>` | 取消禁止上传 | 2 |
| `/ysmrelay blockuploader add|remove|list <玩家|uuid>` | 禁止指定玩家上传 / 解除 / 查看 | 2 |
| `/ysmrelay whitelist add|remove|list <玩家|uuid>` | 上传白名单维护 | 2 |
| `/ysmrelay refresh [玩家]` | 按授权矩阵重推授权 | 2 |
| `/ysmrelay level grant <玩家> <1-3>` | 授予等级（不能高于自己的等级） | 3 |
| `/ysmrelay level revoke <uuid>` | 撤销手动授予的等级 | 3 |
| `/ysmrelay level list` | 列出手动授予的等级 | 3 |

模型参数三种写法：完整 64 位哈希、8 位以上唯一前缀、`global:custom/xxx.mxc` 这类地址。

### `/relayme`（自助面）

| 命令 | 作用 | 等级 |
| --- | --- | --- |
| `/relayme list` | 列出自己上传过的模型 | 1 |
| `/relayme delete <模型> confirm` | 删除自己上传的模型（不进制止名单，可以再传） | 1 |
| `/relayme cancel <编号>` | 撤回自己还没被审批的上传 | 1 |

## 配置参考

服务端：`<存档>/serverconfig/ysm-relay-server.toml`。改完重启生效。

### `[upload]` —— 上传准入与磁盘守卫

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `allowPlayerUpload` | `true` | 允许普通玩家上传；关掉后只有服主手动放模型 |
| `maxModelBytes` | `67108864`（64 MB） | 单个模型上限，0 = 不限 |
| `chunkBytes` | `16384` | 分片大小，上限也是 16384（超过 MC 自定义包上限会断线） |
| `maxBytesPerPlayer` | `1073741824`（1 GB） | 单玩家累计配额，按台账累计，0 = 不限 |
| `maxTotalBytes` | `4294967296`（4 GB） | 全服上传累计上限，0 = 不限 |
| `stagingTtlSeconds` | `600` | 未完成上传在暂存区的保留时长 |
| `uploadPermissionLevel` | `0` | 上传所需模组等级：0 所有人 / 1 等价 0 / 2 仅管理员 / 3 仅 3 级 |
| `targetRoot` | `"custom"` | 上传落点：`custom` 公开，或 `auth` 需授权 |
| `maxStagingBytes` | `805306368`（768 MB） | 暂存区总量上限（含待审），0 = 不限 |
| `minFreeDiskBytes` | `2147483648`（2 GB） | 磁盘剩余低于此值时拒收，0 = 不检查 |
| `maxUploadBytesPerSecond` | `2097152`（2 MB/s） | 下发给客户端的限速建议，0 = 不限 |
| `rejectAllUploads` | `false` | 一键拒绝所有上传，优先于其它开关 |
| `allowAutoUpload` | `true` | 允许客户端自动上传（关掉只禁自动路径） |
| `maxConcurrentConversions` | `1` | 同时进行的导入任务数，上限 2 |

### `[review]` —— 审批、同步、名单

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `uploadMode` | `"review"` | `review` / `auto` / `full` |
| `autoApprove` | `false` | 旧键，仅用于兼容；模式以 `uploadMode` 为准 |
| `autoApproveMaxBytes` | `16777216`（16 MB） | auto / full 档直接放行的体积阈值，0 = 不限 |
| `maxPendingCount` | `8` | 待审队列总容量，满了在探测阶段拒绝 |
| `maxPendingPerPlayer` | `8` | 每人待审条数上限，0 = 不限（设置页可调 0–100） |
| `pendingTtlHours` | `72` | 待审保留小时数，超期按过期拒绝 |
| `autoSync` | `true` | 落盘后是否自动触发 YSM 重扫 |
| `autoSyncMinIntervalSeconds` | `15` | 重扫去抖最短间隔 |
| `autoUploadScanSeconds` | `0` | 客户端扫描方式：0 文件监听，>0 轮询秒数 |
| `listMaxEntries` | `200` | 列表一次最多回传条数（另有 24 KB 单包预算） |
| `uploadWhitelist` | `[]` | 白名单 UUID：免审、免配额、免等级门槛 |
| `blockedUploaders` | `[]` | 禁止上传的 UUID |
| `approveGroup` | `[]` | 审批组 UUID（2 级） |
| `manageGroup` | `[]` | 管理组 UUID（2 级） |

### `[permissions]` —— 每个配置项至少需要几级才能改

20 个键默认全部为 `3`，只有 3 级（含服主）能改；键名与原配置项同名，值是 1–3。
权限定义类键（`approveUploads`、`manageRights`、`blockUploaders`、`uploadWhitelist`、`groupManage`、`uploadPermissionLevel`）同样要求 3 级，2 级不能给自己放权。

另有 `levelGrants`（`<uuid>:<level>` 列表），由 `/ysmrelay level` 维护，也可手动编辑。

### 客户端配置

`config/ysm-relay/client.properties`

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `autoUploadFolder` | 空 | 自动上传监听的本地目录 |
| `manualUploadFolder` | 空（= YSM 模型根） | 手动上传面板扫描的目录 |
| `skipDeleteConfirm` | `false` | 模型管理里删除时是否跳过确认框 |

## 数据文件与目录

| 路径 | 内容 |
| --- | --- |
| `%LOCALAPPDATA%/ysm/custom` | 公开模型根（`YSM_HOME` 可改） |
| `%LOCALAPPDATA%/ysm/auth` | 授权模型根 |
| `%LOCALAPPDATA%/ysm/relay-staging` | 传输中的残片，TTL 默认 600 秒 |
| `%LOCALAPPDATA%/ysm/relay-pending` | 待审内容，按编号分目录 |
| `%LOCALAPPDATA%/ysm/cache/converted` | YSM 转换缓存（删除模型时一并清理） |
| `config/ysm-relay/uploads.json` | 上传台账（modelId → 上传者、文件名、大小、时间） |
| `config/ysm-relay/pending.json` | 待审队列记录 |
| `config/ysm-relay/authz.json` | 授权策略、名单、禁止上传、等级授予 |

台账以**模型内容哈希**为键、每次上传后原子写盘，重开游戏不会丢失上传者信息。注意它属于**跑服务端那个实例**的游戏目录：换实例看到的是另一份台账。

## 网络与协议

- 独立通道 `ysm_relay:upload`，协议版本 `10`，与 YSM 自己的协议零耦合
- 使用 `acceptMissingOr`：没装 relay 的客户端仍能进服，只是没有上传功能
- 分片上限 16 KB，远低于 MC 的自定义负载上限 32767 字节
- 列表回包受 24 KB 预算与 `listMaxEntries` 双重截断

## 开发与构建

```powershell
.\gradlew.bat compileJava --offline     # 只编译
.\gradlew.bat verification --offline    # 零依赖自检，输出 cases=N failures=M
.\gradlew.bat build --offline           # 出包
.\gradlew.bat ysmDevJar                 # 把 ../ysm 的编译产物打成 compileOnly jar
```

编译需要 YSM 主仓编译产物，默认 `../ysm`，可用 `YSM_PROJECT_DIR` 覆盖。

```
src/main/java/com/asu/ysmrelay/
  client/      上传面板、设置页、模型管理页、列表页、自动上传、目录设置
  command/     /ysmrelay 与 /relayme
  config/      配置定义、同步、权限、服务端列表
  net/         通道 ysm_relay:upload 与全部消息体
  upload/      探测、接收、落盘、待审队列、台账、路径
  admin/       授权矩阵与模型搬迁/删除
  ysm/         与 YSM 的唯一接缝
src/verification/java/   零依赖自检（不加载 Forge 与 native）
```

## 已知限制

1. **成品容器需要内嵌预览**：直接投放的 `.mxc`（YSGP2）会被 YSM 当作成品容器，缺预览会被拒收；旧版 `.ysm` 与压缩包走 YSM 源导入路径，不受此限制。
2. **删除可能被文件占用挡住**：Windows 上 YSM 可能仍持有句柄，删除会失败并明确报错，重启服务端后重试。
3. **同内容副本会复活**：若 `custom` 里还有同一模型另一个文件名的副本，删除后重扫会重新导入。
4. **列表有上限**：单包 24 KB 与 `listMaxEntries` 双重截断。
5. **两端必须同版本**：协议 v10，混版用不了上传通道（仍能进服）。
6. **目录型模型不支持上传**：只收文件。
7. **台账读坏会覆盖**：JSON 损坏时按空台账继续，下一次上传会覆盖原文件（已知问题，未自动备份）。

## 故障排查

- **点入口提示「功能不可用」**：服务端没装 relay（按 Forge 登录通道表判定，确定性结论）。
- **单人模式提示不可用**：没开 LAN 的单人存档没有服务端。
- **上传成功但列表没有**：看「我的待审」→ 查服务端日志 `上传模型成功 path=… size=…` → 运行 `/ysmrelay paths` 确认上传根 → 检查 YSM 扫描错误。
- **模型名是一长串十六进制**：旧版本用模型标识当文件名，1.2.7 起保留原始文件名。
- **删除报「未知或不完整命令」**：旧版本客户端发送命令多带斜杠，1.4.6 已修。

## 文档

- [服主管理面](docs/ADMIN.md)
- [使用说明](docs/使用说明.md)
- [YSM 3.0 接入点调研](docs/02-YSM3.0接入点调研.md)
- [早期阶段计划（归档）](docs/archive/PLAN-W2W3-阶段计划.md)
