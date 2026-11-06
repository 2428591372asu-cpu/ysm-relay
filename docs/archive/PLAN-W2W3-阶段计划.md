# ysm-relay 实施方案 v2

> v1 的调研结论（YSM 现成能力清单、接入点、协议表）全部有效，本文只重写**范围、契约、分工与验收**。
> 依据：阿苏 2026-11-04 的 ABC 场景需求 + 主管移交的 N1–N6 能力清单。
> 本文是唯一权威：任务板、模块归属、枚举值、验收用例都以本文为准，冲突时以本文为准。
> 状态：**工程侧已解冻**（2026-11-04 晚，native 阻塞解除）。

---

## 0.0 ⚠ 2026-11-04 晚间前提变更（本条优先于全文任何相反表述）

**native 阻塞解除，游戏内验收解锁。本文中所有「等 native」「装 conan + NASM」「游戏内验收挂起」「⏸ 需 native」的表述全部作废。**

事实（实测，证据见成果板 `181490ea` / `56d75423`）：

| 项 | 实测结果 |
|---|---|
| 可运行 YSM 3.0 | `YSM/ysm/build/libs/ysm-3.0-dev-forge+mc1.20.1.jar`（2026-11-04 整理前为 `YesSteveModel/build/libs/`），32,920,100 B，4664 条目 |
| 是否可当发布包 | **是**。class 已 reobf 成 SRG（`m_xxxxx_` 命中），`ysm.refmap.json` 在，MANIFEST 含 `MixinConfigs` |
| native | **已内嵌**在 jar 内 `META-INF/native/ysm.dll`，13,365,760 B |
| native 依赖 | 仅 4 个 Windows 系统库（KERNEL32 / ADVAPI32 / dbghelp / api-ms-win-core-synch-l1-2-0），**无 VC++ runtime**（静态链接） |
| native 可加载性 | `LoadLibraryW` 实测成功并正常释放 |
| 加载机制 | `NativeRuntime.load` 走 JNA `NativeLibrary.getInstance` + `System.load`，不需要 `JNI_OnLoad` 导出 |

**三条口径变化**：

1. **Q1 作废**。不装 conan、不装 NASM、不编任何 C++。§11 中「Q1 的连带后果」整段失效，见该处订正。
2. **交付口径升级**。不再固定为「编译通过 + 零依赖自检 + 审查结论」，可以把游戏内验收写进交付承诺。
3. **新增 W0.5**（任务板 `5a802961`）：从运行 jar 提取 dll → `YSM_NATIVE_PATH` → `runServer` → 验 `/ysmrelay status`。这是全队当前最高优先级的串行点。

**唯一残余不确定**：JNA 绑定层在真实 JVM 里能否解析符号。属 YSM 自身路径，一次 dev server 启动日志即可覆盖。

---

## 0. 一句话目标

把 YSM 的「玩家必须把模型发给服主 → 服主装进服务端」改成「玩家在游戏内交给服主 → 服主一键批准 → 全服自动可用」，
并且**永不重写 YSM 已有的格式转换与分发**。

## 1. ABC 场景（唯一的验收场景，其它都是细节）

服务器三人：A = 房主（op2，装了 relay）；B = 玩家（装了 YSM + relay）；C = 玩家（只装 YSM，没装 relay）。

| # | 能力 | 谁做什么 | 归属 |
|---|---|---|---|
| N1 | B 向 A 提交模型上传**申请** | B 客户端发字节 → 服务端隔离区 → 待审 | W1 + W3 |
| N2 | A 查看 / 同意 / 拒绝申请 | 命令 `/ysmrelay pending` `/approve` `/reject` | W3 + W4 |
| N3 | A 可切「一律允许 / 一律拒绝」 | 策略三态 `REVIEW` / `ALLOW_ALL` / `DENY_ALL` | W3 |
| N4 | **C 没装 relay 仍收到 A、B 的模型**，且看不到上传入口 | 分发 100% 交给 YSM；relay 只让文件进目录 + reload | 硬约束，不写新代码 |
| N5 | 只有 A（op2）能审批，B 只能申请 | 命令 permission 2，守卫顺序照抄 `startGenPreview` | W4 |
| N6 | 哈希相同的模型不进队列、不打扰 A | 申请阶段即算 modelId，命中 → `ALREADY_AVAILABLE`，0 字节 | W1 + W2 |

N4 是本方案的**架构红线**：C 没有 relay，所以任何「客户端侧才能完成的步骤」都不能成为模型可见的前提。
批准后的模型必须变成「服务端目录里的一个文件」，然后靠 YSM 自己的 reload + 按需分发让 C 看见。

---

## 2. 已裁定基线（不再讨论）

| # | 裁定 | 依据 |
|---|---|---|
| R1 | YSM 3.0 的「服务端→客户端」分发、带宽调度、按需拉取全部复用，relay 一行分发代码都不写 | `req-bounded-model-distribution.md` 状态 Complete；`catalog-sync.md` / `asset-transfer.md` |
| R2 | 上传走独立通道 `ysm_relay:upload`（YSM 的 `ProtocolMessageRegistry` 是 `Map.copyOf`，插不进去） | 源码核实 |
| R3 | 「某玩家不能用某模型」**只做到不能选**（`canSelect`）。`RestrictedAuth=true` 只做**告警 + 可选加固**，不默认强制 —— 它的代价是全局的，会让所有人看无权模型都降级成 default | 阿苏裁定 + 主管更正 |
| R4 | **默认落 `custom` 根**（PUBLIC，全员可见 = C 能收到 B 的模型）。只有服主要「限制某个模型」时才由 relay 把它搬到 `auth` 根，再按「默认全员 + 排除名单」发 grant | 由 N4 反推，见 §2.1 |
| R5 | relay 侧不派发任何 `/ysm` 指令字符串，只调 Java 能力（`AuthModelsCapability` / `SessionProtocolHandler.refreshGrants` / `ModelSelectionService.selectDefault`） | 阿苏原始需求「能不能调用」 |
| R6 | 上传默认策略 = `REVIEW`（逐次审批）。`ALLOW_ALL` / `DENY_ALL` 由服主一键切换，热生效，不需重启 | 阿苏 ABC 描述 |

### 2.1 R4 为什么是 custom 而不是 auth（这是本轮最关键的一条规划结论）

如果默认落 `auth` 根，那么**每个**上传模型都要对**每个**玩家发 grant，C 的可见性就依赖「全员授权补齐」这条链路；
一旦漏掉一次 `refreshGrants`，C 那边就是「模型在列表里但选不了」，表现为「坏了」而不是「暂时看不到」。

而且 `auth` 根模型的 `access == AUTHORIZED`，`canSelect` 是严格白名单 —— 新玩家进服必须补授权，这是长期负担。
默认走 `custom` 根则 access 恒为 PUBLIC，零授权、零隐患，正好匹配 N4。

代价：`custom` 根的模型没法做逐玩家黑名单。这个代价可接受 —— 逐玩家管控本来就是少数模型才需要，
由服主显式 `/ysmrelay move <model> auth` 触发一次跨根搬迁即可（`admin/ModelPlacement` 已实现）。

---

## 3. 上传状态机（唯一权威，任何人不得自行扩展枚举）

```
客户端                          服务端
  │ ① UploadProbe{size, modelId, name, ext}
  ├──────────────────────────────► 开关/权限/体积/配额/staging/磁盘/哈希判重/策略
  │                                  ├─ 命中已有哈希 ──► ② ALREADY_AVAILABLE（0 字节，不进队列）
  │                                  ├─ DENY_ALL/超限 ─► ② REJECTED_*（0 字节，记账）
  │                                  └─ 通过 ─────────► ② ACCEPTED{transferId}
  │ ② ProbeReply
  │◄──────────────────────────────
  │ ③ UploadChunk{transferId, seq, last, data}   ← 服务端按 maxUploadBytesPerSecond 背压
  ├──────────────────────────────► 落 staging（isolated）
  │                                  └─ 封口 → 解析出 图标/名称/作者/依赖
  │ ④ UploadResult
  │◄──────────────────────────────┬─ ALLOW_ALL ──► COMMITTED（原子迁入 custom → reload）
  │                               └─ REVIEW ─────► PENDING_REVIEW{reviewId}（持久化，等 A）
  │ ⑤ UploadResult（异步推送，同一 transferId）
  │◄─────────────────────────────── COMMITTED / REJECTED_BY_OWNER
```

### 3.1 状态枚举（`net/msg/UploadResult.Status`）

外层三分类对齐 YSM 的语义（`COMMITTED` / `ALREADY_AVAILABLE` / typed failure），内层细分：

| 值 | 含义 | 落盘 | 进队列 | 记账 |
|---|---|---|---|---|
| `COMMITTED` | 已入库并触发 reload | 保持 | 否 | 是 |
| `ALREADY_AVAILABLE` | 哈希命中（服务端已有），0 字节 | 无 | 否 | 是（dup 计数） |
| `PENDING_REVIEW` | 已收妥、已解析，等服主决定 | 隔离区 | **是** | 是 |
| `REJECTED_BY_OWNER` | 服主拒绝 | **删除** | 否 | 是 |
| `REJECTED_POLICY` | `DENY_ALL` / 总开关关闭 / 权限不足 | 无 | 否 | 是 |
| `REJECTED_QUOTA` | 单模型超限 / 每人累计 / 全局累计 / staging 总量 / 磁盘不足 | **删除** | 否 | 是 |
| `REJECTED_BUSY` | 该玩家已有未完成 transfer，或处于入库窗口 | 无 | 否 | 是 |
| `REJECTED_IDENTITY` | 声明哈希不合法 / 与服务端算出的不一致 | **删除** | 否 | 是 |
| `FAILED_CONVERSION` | 解析或转换失败（**native 缺失已不是现实诱因**，见 §0.0；主因是模型格式损坏 / 不支持的旧版容器） | **删除** | 否 | 是 |
| `CANCELLED` | 玩家主动取消 / 掉线 | **删除** | 否 | 是 |
| `REJECTED_QUEUE_FULL` | 待审队列已满（`maxPendingCount`），probe 阶段即拒 | 无 | 否 | 是 |

规则：**新增枚举值必须先在本文登记**，否则评审直接打回。这条是防「各造一套四态」的唯一闸门。

> `REJECTED_QUEUE_FULL` 于 2026-11-04 按 §10 的 J5 裁定登记。队列满必须是**明确的 typed failure**，不能借 `REJECTED_BUSY` 混过去 —— 服主看到「队列满」才知道要去批审批。

### 3.1.1 全链路只有一个状态枚举（2026-11-04 裁定，防止两套 switch）

`ProbeReply.status` **直接复用 `UploadResult.Status` 的常量值**，不另立 6 值枚举。唯一例外：probe 通过用 `ALLOWED = -1` 独占（它不是终态，不该混进终态值）。

**线协议值域（2026-11-04 钉死，任何人不得改号）** —— 光说「复用」不够：`ProbeReply.ALLOWED` 原来是 `0`、`UploadResult.COMMITTED` 也是 `0`，两套撞号，不重排的话日志里 `status=0` 到底是通过还是提交成功说不清。

```
ALLOWED             = -1   // 仅 ProbeReply：准入通过，不是终态
COMMITTED           =  0   // 0 归它，不再是 ALLOWED
ALREADY_AVAILABLE   =  1
PENDING_REVIEW      =  2
REJECTED_BY_OWNER   =  3
REJECTED_POLICY     =  4
REJECTED_QUOTA      =  5
REJECTED_BUSY       =  6
REJECTED_IDENTITY   =  7
REJECTED_QUEUE_FULL =  8
FAILED_CONVERSION   =  9
CANCELLED           = 10
```

- 常量留在 `UploadResult`（`DUPLICATE` → `ALREADY_AVAILABLE`，扩到 11 个）；`ALLOWED = -1` 留在 `ProbeReply` 自己身上。零新增类。
- **probe 允许 7 个**：`-1 / 1 / 4 / 5 / 6 / 7 / 8`；**probe 禁止 5 个**：`0 / 2 / 3 / 9 / 10`。
- **`2`（PENDING_REVIEW）是最易错点**：probe 阶段客户端还没有 `transferId`，回 `2` 会让客户端在 `transferId == 0` 下进「等待审批」态，永远等不到结果包（它压根没建立过 transfer）。W3 写代码时必须带注释。
- **`ProbeReply` v2 字段顺序钉死**：`status(int) → transferId(varLong) → chunkSize(varInt) → rateLimitBytesPerSecond(varInt) → message(utf/512)`。定长在前、变长在后，以后新字段插在 `message` 之前。

| 阶段 | 可能出现的值 |
|---|---|
| probe（无 `transferId`） | `ALLOWED` / `ALREADY_AVAILABLE` / `REJECTED_POLICY` / `REJECTED_QUOTA` / `REJECTED_BUSY` / `REJECTED_IDENTITY` / `REJECTED_QUEUE_FULL` |
| 终态（有 `transferId`） | `COMMITTED` / `PENDING_REVIEW` / `REJECTED_BY_OWNER` / `FAILED_CONVERSION` / `CANCELLED` + 上面任一拒绝值 |

`ProbeReply` 永远**不会**返回 `COMMITTED` / `PENDING_REVIEW` / `FAILED_CONVERSION` / `CANCELLED` —— 这四个只在终态出现。
客户端因此只维护**一张**状态映射表（§3.1 的 11 个值），不维护两套。

`ProbeReply` 另带两个字段（一次定完，别开第二次协议）：
- `rateLimitBytesPerSecond`：本次上传客户端的自限速率（决策 4a578a48 方案 B）
- ~~`willRequireReview`~~ **撤销（2026-11-04）**：客户端不需要预知要不要等审批，`ProbeReply` 少一个字段 = 少一个要维护的真值。审批与否由服务端在 submit 侧决定，客户端只看终态。

### 3.2 待审队列（`review/pending.json`）

```json
{
  "id": "rv-0001",
  "uploader": "uuid",
  "uploaderName": "B",
  "content": "relay-pending/rv-0001/model.mxc",
  "modelId": "blake3-hex",
  "displayName": "从模型解析出的名字",
  "author": "从模型解析出的作者（可能为空）",
  "icon": "relay-pending/rv-0001/icon.png",
  "bytes": 12345678,
  "createdAt": 1762...,
  "expiresAt": 1762...,
  "status": "PENDING"
}
```

- **落点**：待审内容在**独立目录** `relay-pending/<reviewId>/`，**不在** `relay-staging/`。两个目录生命周期完全不同：
  `relay-staging/` 只放传输中残片，TTL 600s 快速回收；`relay-pending/` 归 W3 的 `PendingQueue` 独占（唯一写者），只受 `pendingTtlHours` 管。
  理由见 §10 的 J1 裁定 —— 混在一起会「重启 + 待审超过 10 分钟就必然被 sweep 删掉」，approve 找不到文件。
- **持久化**：服主不在线、服务器重启，申请都不丢。
- **TTL**：`pendingTtlHours`（默认 72h），超期自动 `REJECTED_BY_OWNER`(expired) 并删该 reviewId 目录，落审计。
- **容量**：`maxPendingCount`（默认 8）与 `maxStagingBytes`（统计 `relay-staging/` + `relay-pending/` 之和）双重封顶；队列满回 `REJECTED_QUEUE_FULL`。
  为什么是 8 不是 32：3 人服里 32 深的待审队列 = 最多 2 GiB 常驻磁盘且长期无人审，属设计错误。8 足够覆盖一次模型更新潮，超出时给的是明确 typed failure 而不是悄悄吃满磁盘。

### 3.3 审批策略：两个键组合，不设 tri-state（2026-11-04 按阿苏新需求重定）

原先的 `uploadPolicy = REVIEW/ALLOW_ALL/DENY_ALL` 作废。阿苏要的是「自动同意 + 自动同意的最大大小」，
tri-state 表达不了「小模型自动过、大模型我自己批」，而且三态里有两态能被两个布尔/数值键完全推出 —— 三态是冗余概念。

| 键 | 默认 | 语义 |
|---|---|---|
| `allowPlayerUpload`（已存在） | `true` | 总开关。`false` == 一律拒绝，probe 阶段 `REJECTED_POLICY`、0 字节 |
| `autoApprove` | `false` | `false` == 逐次审（收妥 → `PENDING_REVIEW`） |
| `autoApproveMaxBytes` | `16 MiB` | `autoApprove=true` 且 声明体积 ≤ 此值 → 直接 `COMMITTED`；超过仍进待审 |
| `autoSync` | **`true`** | 阿苏点名要的开关。**`true`** = 批准后立刻 reload 推全服；**`false`** = 批准了但不 reload，面板显示「待同步 N」+ 一个「立即同步」按钮，服主手动触发。 |
| `approvePermissionLevel` | **`2`** | 谁能处理上传请求（审批）。与官方 `/ysm` 指令的 OP 2 对齐。 |
| `accessPermissionLevel` | **`2`** | 谁能管模型使用权（`move auth` / `grant` / `revoke`）。阿苏原话「默认 op 可以处理」。 |

> `autoSync=false` 的语义边界：**批准仍然落盘**（文件进 `custom/`），只是不触发 reload —— 所以不 reload 时模型对玩家不可见，但数据不丢。
> 关掉它必须同时有两个出口：面板的「立即同步」按钮 + `/ysmrelay refresh` 命令。否则服主会以为批准失败了。

阿苏要的三种档位全部由此推出，服主不用记三个概念：

| 服主要的 | 配置 |
|---|---|
| 一律拒绝 | `allowPlayerUpload=false` |
| 一律允许 | `autoApprove=true`, `autoApproveMaxBytes ≥ maxModelBytes` |
| 一律逐次审 | `autoApprove=false` |
| 小模型直接过、大模型我批 | `autoApprove=true`, `autoApproveMaxBytes=16 MiB` |

三条硬语义不变：
1. **拒绝不影响已入库模型**，只拒新申请 —— 要写进 ADMIN.md，否则服主会以为一键清空。
2. `autoApprove` 的判定走**声明体积**（probe 阶段就知道），不是 `maxModelBytes` 的事后检查。
3. `willRequireReview` 在 `ProbeReply` 里回给客户端（§3.1.1），玩家上传前就知道要不要等审批。

---

## 4. 里程碑 DAG

```
W0 依赖与适配层（已达成编译侧）
 ├─► W1 上传通道 + 客户端契约对齐 ────────────┐
 ├─► W2 守卫加固（配额/限流/staging/原子化）   │
 │     └─► W3 审核门（PendingQueue+ReviewGate+策略三态）─┐
 ├─► W5 授权矩阵（auth 根搬迁 + RestrictedAuth 自检）    │
 ├─► W6 纯 Java 单测（verification sourceSet）          │
 │                                                      ├─► W8 ABC 集成验收
 └─► W4 服主管理面（pending/approve/reject/del/…）──────┘
        （W4 依赖 W3 的状态机与 PendingQueue）
 W1 + W3 ─► W7 客户端上传面板 + 并发打磨
```

| 里程碑 | owner | 前置 | 一句话 |
|---|---|---|---|
| **W0** 依赖与适配层 | 高等程序员2 | 无 | dev API JAR + `YsmBridge` + `mods.toml`。编译侧已绿；**运行侧已解锁**（native 到手，见 §0.0） |
| **W0.5** 真机解锁 | 高等程序员2（+高等程序员配合） | W0 | 从运行 jar 取 `ysm.dll` → `YSM_NATIVE_PATH` → `runServer` 日志确认加载 → `/ysmrelay status`。**全队当前第一优先级，它决定 W8b 能否开工**（任务板 `5a802961`） |
| **W1** 上传通道 + 客户端契约 | 高等程序员 | W0 | 协议/客户端对齐 §3 枚举；扫描补 `.7z`、补 `auth` 根；分片改 `ByteBuf` 切片减一次拷贝 |
| **W2** 守卫加固 | 高等程序员2 | W0 | staging 总量 + 磁盘下限、`onProbe` 原子化、`maxTotalBytes` 非零默认、上行限速、独立转换线程池、提交点抽成单一入口 |
| **W3** 审核门 | 高等程序员 | W2 | `PendingQueue` + `ReviewGate` + 策略三态 + 持久化 + 审计；`UploadService` 只改 1 处 |
| **W4** 服主管理面 | 高等程序员3 | W3 | `/ysmrelay pending/approve/reject/policy/list/del/grant/revoke/show` |
| **W5** 授权矩阵 | 高等程序员3 | W0 | auth 根搬迁 + 默认全员+排除名单 + 登录补 grant + `RestrictedAuth` 告警 |
| **W6** 纯 Java 单测 | 高等程序员3 | W0 | `verification` sourceSet + `ServerUploadTest` / `ModelPlacementTest` |
| **W7** 客户端面板 | 高等程序员 | W1,W3 | 列表 + 上传（**支持多选/全选**）+ 「我的申请状态」四态回显 |
| **W9** 服主配置面板（游戏内 GUI） | 高等程序员3 | W3 | 待审列表 + 策略开关 + 自动同意大小 + 立即同步；入口 = 挂在 YSM 模型界面的一个展开按钮（Forge `ScreenEvent`，**不用 mixin**） |
| **W8** ABC 集成验收 | ds_项目策划 主导 | W1–W7 | 跑 §5 的 13 条用例，出验收报告 |

### 4.1 W2 的交付契约（串行解耦的关键）

**修正（§10 · J3）**：单一入口字面不成立 —— `DENY_ALL` 只能在 probe 拦（要 0 字节），三态分叉只能在 import 之后（待审要存的模型名/作者/图标，convert 完才有）。改为**两个交接缝**：

**缝 1（W2 交付，probe 侧）** —— `onProbe` 里只做三件事：`DENY_ALL` → `REJECTED_POLICY`（0 字节）、三重判重（catalog / `pendingCommits` / `PendingIndex`，见 S6）、队满 → `REJECTED_QUEUE_FULL`。**W3 不碰这段代码。**

**缝 2（W2 交付骨架，W3 填实现，submit 侧）**：

```java
// W2 交付形态：行为 = 现状（直接导入并 reload）
UploadResult submit(ServerUpload upload, ServerPlayer player)
```

W3 只替换这个方法的**实现体**（`REVIEW` → 进 `PendingQueue`；`ALLOW_ALL` → 直接提交），
**不修改 W2 写的任何守卫代码**，也不碰 `onProbe`。这样 `UploadService` 的写入者始终只有一个。

**S6（新增 · J4 的必要条件）**：`upload/PendingIndex`（只有 `contains(Hash256)` / `add` / `remove`）由 W2 定义；
**只有 W3 的 `PendingQueue` 能 add/remove**，W2 的 probe 仅调用 `contains`。
没有它，REVIEW 策略下同一份内容可以同时进两次待审 —— 因为 `pendingCommits` 在 convert 后几秒就空了，catalog 里又还没有。

### 4.2 「先传后审」vs「先审后传」—— 裁决：**先落隔离区，再审批**

理由三条：

1. **审核质量**：A 要看的「申请人 / 模型名 / 作者 / 图标 / 体积」里，名字和作者只能从模型内容解析出来。
   `.ysm` 是加密容器，元数据门（只发文件名）必然漏，A 实际上在盲审。
2. **浪费可控**：`maxModelBytes` 默认 64 MiB 已经封顶了单次浪费；`DENY_ALL` 与哈希判重都在 probe 阶段就 0 字节拒绝，
   真正白传的只有「A 最后决定拒绝」的那一小部分。
3. **代码路径唯一**：先审后传会分叉出「已批准但字节没传完」「批准后哈希变了」两条新状态，
   这是典型的屎山入口。统一走隔离区后，只有一条路径、一套清理逻辑、一套 TTL。

---

## 5. ABC 验收用例（W8 逐条跑，缺一条不算过）

| # | 步骤 | 期望 |
|---|---|---|
| 1 | B 上传新模型 X | `PENDING_REVIEW`；A 收到提示；`/ysmrelay pending` 能看到 X 与申请人 |
| 2 | A `approve` | X 落 `custom`、reload 完成；**B 与 C 都能在模型列表看到并选中 X** |
| 3 | B 重复上传同一个 X | `ALREADY_AVAILABLE`，**0 字节传输**，不进队列 |
| 4 | B 上传超过 `maxModelBytes` 的文件 | `REJECTED_QUOTA`；隔离区无残留；磁盘字节数不变 |
| 5 | A 切 `DENY_ALL`，B 再上传 | `REJECTED_POLICY`；**0 字节**；不进队列 |
| 6 | A 切 `ALLOW_ALL`，B 再上传 | 直接 `COMMITTED`，不产生待审项 |
| 7 | A `reject` 一个申请 | C 看不到它；隔离区被删除；审计有一条记录 |
| 8 | B 上传中掉线 | 重连后可重传；staging 在 TTL 后自动清理 |
| 9 | B 已提交待审时服务器重启 | 申请仍在（持久化生效） |
| 10 | A 限制 X 只给 C（`move X auth` + 只授权 C） | **B 选不了 X**；C 能选 |
| 11 | A 取消 C 对某 auth 模型的授权 | C 的模型列表里该模型消失；若 C 正在用，自动回退默认且不报错 |
| 12 | A `del` X | 删前自动导出备份到 `config/ysm-relay/backup/`；reload 后 B、C 列表都消失；X 被列入禁止再上传名单 |
| 13 | C（未装 relay）全程 | 从头到尾看不到任何 relay UI / 命令；模型可见性不受影响 |

---

## 6. 文件归属与串行规则（防冲突，防屎山）

| 路径 | 单一 owner | 里程碑 |
|---|---|---|
| `upload/UploadService.java`、`config/RelayConfig.java` | 高等程序员2 → **W2 完成后交接** | W2 |
| `upload/ServerUpload.java`、`upload/*`、`net/**`、`client/**` | 高等程序员 | W1 / W3 / W7 |
| `admin/**`、`command/**` | 高等程序员3 | W4 / W5 |
| `src/verification/**`、`docs/ADMIN.md` | 高等程序员3 | W6 |
| `libs/**`、`build.gradle`、`mods.toml` | 高等程序员2 | W0 |
| `docs/PLAN.md` | ds_项目策划 | — |

**S1** 任何人改文件前先 `claim`，改完 `release`；冲突不覆盖，找我裁决。
**S2** `UploadService.java` 同一时刻只有一个 owner，交接必须口头 + 板上双确认。
**S3** W2 之后 W3 才能动这个文件；W3 只改 §4.1 那一个方法体。
**S4** 状态枚举（§3.1）由高等程序员在 W1 第一步落地并**冻结**；W2/W4 只读取。
**S5** 谁都不改 `ysm/` 与 `ysm-native/`（整理前叫 `YesSteveModel/` 与 `YesSteveModel-Native/`）下任何文件，不写 mixin，不碰上游。

## 7. 反屎山红线（评审逐条卡）

1. 新增生产类总数 ≤ **13**（8 → 13 是为阿苏的 GUI 需求追加，追加项已列名）。超额需我批准。
   原 8 个：`PendingQueue`、`ReviewGate`、`ReviewRecord`、`ConversionPool`、`RateLimiter`、`ClientPanelScreen`、`AuditLog`、`PendingIndex`（`UploadPolicy` 已随 §3.3 两键化删除）。
   追加 5 个（W9）：`client/gui/RelayConfigScreen`、`client/gui/PendingListWidget`、`net/msg/RelayConfigGet`、`net/msg/RelayConfigData`、`net/msg/RelayConfigSet`。
   **包路径更正（2026-11-04，DS_高等程序员3 指出与红线 7 矛盾）**：Screen / Widget 是 client-only，必须落 `client/gui/`，不能放 `admin/`。
   这 5 个全是**新增文件**，不修改 `client/` 下任何现有文件（`UploadPanelScreen` / `UploadPanelKeys` / `UploadPanelOpener` 一个都不动），所以与 DS_高等程序员 的 W7 写入范围不重叠。
2. 新增配置键 ≤ **11**。红线从 8 提到 11 的唯一原因是**阿苏 2026-11-04 点名要的两个权限开关与自动同步开关**，逐条可对回他的原话，无一条是我自造。
   **全部由 W2 一次预置**（含 W3/W4/W5/W9 的键，注释标「谁只读」），下游零改动 `RelayConfig`。**命名不镜像 YSM**。

   | 键 | 默认 | 谁只读 | 说明 / 阿苏的原话 |
   |---|---|---|---|
   | `autoSync` | **`true`** | W3 / W9 | 「是否自动同步玩家上传的模型」；`false` 时面板出「立即同步」 |
   | `autoApprove` | `false` | W3 / W9 | 「是否自动同意玩家上传模型」 |
   | `autoApproveMaxBytes` | **`16 MiB`** | W3 / W9 | 「自动同意玩家上传模型的最大大小」 |
   | `approvePermissionLevel` | **`2`** | W4 / W9 | 「管理能够处理模型上传请求」 |
   | `accessPermissionLevel` | **`2`** | W5 / W9 | 「管理模型使用权的玩家（默认 op）」 |
   | `maxPendingCount` | **`8`** | W3 | 队满回 `REJECTED_QUEUE_FULL` |
   | `pendingTtlHours` | **`72`** | W3 | 待审保留时长 |
   | `maxStagingBytes` | **`768 MiB`** | W2 | 统计 `relay-staging/` + `relay-pending/` 之和 |
   | `minFreeDiskBytes` | **`2 GiB`** | W2 | 低于此值在 `upload.open()` 之前就拒 |
   | `maxUploadBytesPerSecond` | **`2 MiB/s`** | W2 | 每玩家额度，0 = 不限。**语义是「防 staging IO 队列堆积」，不是带宽套餐** —— `append` 挪出主线程后这条与 TPS 无关 |
   | `maxConcurrentConversions` | **`1`**（上限 2） | W2 | 独立有界线程池 |

   - `maxTotalBytes` 是**已有键只改默认值**（0 → 4 GiB），不占新增额度。
   - `uploadPolicy` **已删除**（§3.3 两键化）。**任何人不得再引入第三态**；谁需要「一律拒绝」就用 `allowPlayerUpload=false`。

   **两条启动自洽性 WARN（只报警、不拒绝启动）**：
   - 下界：`maxStagingBytes < maxPendingCount × maxModelBytes + maxModelBytes` → 容不下「满队列 + 一次在传」。
   - 上界：`maxStagingBytes > 可用磁盘 / 4` → 上限接近磁盘容量，等于没限（3 GiB 在 20 GB 的 VPS 上就是这个毛病，所以压到 768 MiB）。
3. 不写模型格式解析器、不写分发协议、不写 Web 后台、不做玩家自助删除。
4. 每个里程碑只做一件事；顺手重构只能顺自己的文件。
5. 所有 `com.elfmcys.*` 引用只能出现在 `ysm/YsmBridge.java` 一个文件里。
6. 转换用**独立有界线程池**，绝不与 YSM 的 `ThreadCount` 共池（否则症状是「服主 reload 莫名变慢」且无法归因）。
7. **单 jar 双端**（阿苏要求）：client-only 类只放 `client` 包、不得被公共代码静态引用；`ScreenEvent` / Screen 相关订阅只在 `Dist.CLIENT` 分支注册。
   `mods.toml` 必须 `displayTest="IGNORE_ALL_VERSION"` —— 没装 relay 的玩家必须能进服（C 的角色）。
8. **不给 YSM 打 mixin、不改上游**。要把按钮挂进 YSM 的界面，用 Forge 的 `ScreenEvent` 事件，不用字节码注入。

## 8. 冻结状态与放行条件

- 当前：**工程侧冻结**。任务板上的代码任务全部 `paused`，只允许规划、调研、评审。
- 解冻条件（阿苏一句话）：可直接开始 W1 / W2 / W5 / W6（四条互不冲突）。
- 解冻前允许做的事：写测试计划、写 ADMIN.md 大纲、把已有调研结论合并进任务描述。

## 9. 待阿苏点头的两件事

| # | 事项 | 为什么需要你拍 | 不拍的后果 |
|---|---|---|---|
| Q1 | 是否允许安装 `conan ≥2.34` 与 `NASM`，起 1~3 小时的 YSM native 冷构建 | 装软件属于系统改动，我不擅自动 | 没有 `ysm.dll` 就**永远无法做游戏内验收**，只能交付「编译通过 + 单测通过」 |
| Q2 | 批准后的模型默认对**全服可见**（`custom` 根）是否符合预期 | 决定 R4；改成「默认私有」会连带改变 C 的可见性 | 若你要的是「批准了也只有指定人能看见」，R4 要反过来，W3/W5 都要改 |

Q1 若同意，我会先 `winget` 装两个工具再起后台长任务；Q2 若同意 R4，W2~W5 按本文开工。

## 10. 修订记录

### 2026-11-04 · J1–J5 裁定（由 ds_高等程序员2 在 W2/W3 接界处提出，全部采纳）

| # | 问题 | 裁定 |
|---|---|---|
| J1 | 待审文件放 `relay-staging/`，会被 600s 的 `sweepStagingOnce` 删掉；「重启 + 待审超 10 分钟」必然发生，approve 找不到文件 | **采纳 A（决策 85e3a112）**：待审内容独立放 `relay-pending/<reviewId>/`，只受 `pendingTtlHours` 管；`relay-staging/` 只管传输中残片。§3.2 字段 `staging`→`content` 并改路径。 |
| J2 | `uploadPolicy` / `maxPendingCount` / `pendingTtlHours` 是 W3 语义，但 `RelayConfig` 是 W2 的文件 | W2 一次性预置全部键（§7 表格），W3 零改动 `RelayConfig`。 |
| J3 | 「单一 submit 入口」字面不成立 | §4.1 改为**两个交接缝**：probe 缝（W2）+ submit 缝（W3）。W3 不碰 `onProbe`。 |
| J4 | REVIEW 下重复内容可同时进两次待审（`pendingCommits` 已空、catalog 无） | 新增 `PendingIndex`（S6）：probe 判三重，W2 只读、W3 独占写。 |
| J5 | `maxStagingBytes` 会被常驻待审内容撑爆 | 统计范围含 `relay-pending/`；默认值自洽化：`maxPendingCount` 32→8，`maxStagingBytes`=3 GiB，并加启动自洽性 WARN（§7）。 |

> 连带修正：W3 验收第 5 条「拒绝 → staging 目录内该文件不存在」按 J1 改为 **`relay-pending/<reviewId>/` 目录不存在**。
> 这条是 §5 的细化，不算新增验收项。

### 2026-11-04 · 网络线程约束（ds_高等程序员2 实证：两个 handler 都没有 `enqueueWork`）

`UploadProbe.handle` / `UploadChunk.handle` 直调 `UploadService`，跑在 Netty event loop 上。由此定死两条，进 W2 验收：

1. **`onProbe` 的 check-then-act 必须用原子操作修**（`putIfAbsent` 或同步块），不得以「主线程串行」当前提。
2. **令牌桶同时是网络线程的 I/O 预算器**，不只是带宽控制：超预算一个字节都不落盘，回 `UploadProgress(throttled)` 让客户端退避，**绝不丢分片**（`ServerUpload` 的 seq 状态机靠连续分片，丢一个整条 transfer 作废）。

未定项（查一次 Forge 源码即可，5 分钟，归 W1）：两个 handler 都没有显式 `context.setPacketHandled(true)`，Forge 1.20.1 的 `SimpleChannel` 包装层是否已自动设置 —— **未核实，不当结论用**。

---

### 2026-11-04 · 第二批裁定（阿苏新需求 + 两位程序员的技术实证）

1. **枚举统一**（采纳 DS_高等程序员 提案）：`ProbeReply.status` 复用 `UploadResult.Status`，`ALLOWED = -1`；probe 合法子集见 §3.1.1。
   两套 switch = 第二个屎山入口，与 S6 同一类问题。
2. **上行限速定为方案 B**（决策 4a578a48）：`ProbeReply` 下发速率、客户端自限、服务端兜底（超约定 2 倍且持续 2s 才 `REJECTED_QUOTA`，**绝不丢分片**）。
   A 的反馈频率与被限流量正相关 —— 用封包去压它自己制造的封包洪流，方向反了；B 常态零额外包。
3. **线程归属纠正**：「handler 跑在 Netty 线程」**不成立**。`RelayNetwork` 五处全是 `.consumerMainThread(...)`，
   其实现体内部调 `enqueueWork`，服务端 `WORKQUEUE` = `MinecraftServer` ⇒ **server 主线程**。连带：
   - `setPacketHandled` 由 `consumerMainThread` 自己设 `true`，未定项**关闭**；
   - `onProbe` 的 check-then-act **不构成数据竞态**（主线程串行）；原子 put 保留为防御，**但不记为竞态修复**；
   - 真正的缺陷是**主线程阻塞 I/O**：`upload.append` 每分片一次 `Files` 写。验收改为「3 人并发上传 50 MiB 期间 tick 不超预算」，
     预算做成**每 tick 字节额度**（2 MiB/s ÷ 20 tick ≈ 100 KiB/tick ≈ 3 分片），不是秒级桶（秒级桶在 tick 粒度上等于允许单 tick 爆发）。
4. **`maxStagingBytes` 补上界**：默认 **768 MiB**（= 8 × 64 MiB 待审 + 余量）；并加 `maxStagingBytes > 可用磁盘 / 4` 的 WARN。
   原定 3 GiB 在 20 GB 的 VPS 上约等于「没限」，正是要防的那个数字。
5. **队列上限是乐观的，不是硬保证**：probe 通过到真正入队之间隔着整段传输，期间可能被别人填满。
   **不做名额预留** —— 释放路径含客户端崩溃 / 断网 / 进程被 kill，漏一条就是永久泄漏一个名额，比偶尔超额更糟。
   最终闸门是 `maxStagingBytes`。`PendingIndex` 接口需 `contains` + `size` + `add/remove`（`size` 给队满判定用）。
6. **`mods.toml` 必须 `displayTest="IGNORE_ALL_VERSION"`** —— 阿苏要求「没装本拓展的玩家依旧能进服」。
   当前 `mods.toml` 没有 `displayTest`，默认按版本匹配 ⇒ **C 会在握手阶段被拒**。这是 M0 的现存缺陷，W0 修。
7. **单 jar 双端**（阿苏要求）：client-only 类只放 `client` 包、不得被公共代码静态引用；`ScreenEvent` 订阅只在 `Dist.CLIENT` 分支注册；
   客户端控制类不得出现在服务端 classpath 的静态初始化路径上。
8. **策略两键化**（§3.3）：`uploadPolicy` 删除，改 `autoApprove` + `autoApproveMaxBytes`。三态里有两态能被这两键完全推出，三态是冗余概念。

---

### 2026-11-04 · 第三批裁定（DS_高等程序员 的协议与 IO 实证）

1. **线协议值域钉死**（§3.1.1）：`0` 归 `COMMITTED`、`ALLOWED = -1`，probe 允许 7 个 / 禁止 5 个。原来说「复用枚举」不够 —— 两个类现在的 `0` 是撞的。
2. **`append` 挪出主线程，限速理由改写**：`UploadChunk.handle` 保持 `.consumerMainThread`（查表/归属校验/回包本来就该在主线程）；
   真正要挪的是 `UploadService.onChunk` 里的 `upload.append()` —— 加**单线程** `stagingIo` 执行器（同一 transfer 的分片必须串行，磁盘写也不该并行），IO 完成回主线程发结果，不开第二条回包路径。
   ⇒ 验收从「tick 不超预算」改为「**IO 队列深度上限**：`stagingIo` 等待队列超过 `chunkBytes × 64`（≈2 MiB/玩家）时回 `UploadProgress(throttled)` 让客户端等，**不丢包**」。
   无上限的后果比主线程卡顿更难受：1600 个 32 KiB 分片堆在单线程队列 = 单玩家 50 MB 堆。
   ⇒ `maxUploadBytesPerSecond` 的注释改为「防 IO 队列堆积」，**2 MiB/s 默认值不动**。归属：W2（`UploadService` 是 高等程序员2 的文件）。
3. **`ProbeReply.allowed` 加重载切掉编译环**：旧签名 `allowed(long, int)` 保留并转发 `rate=0`，新签名 `allowed(long, int, int)` 供 W2 改 onProbe 时切换。
   3 行重载换来 W1-e 与 W2 之间零编译期依赖 —— 两个人的改动谁先合都不炸。**采纳。**
4. **W2 拆 a/b 两个阶段交付**（采纳 高等程序员 的顺序建议）：
   - **W2-a**：只往 `RelayConfig` 加 §7 那 11 个键 + 改 `maxTotalBytes` 默认值，**不写逻辑**，`compileJava` 立刻绿。半小时量级。
   - **W2-b**：`onProbe` 守卫 + `stagingIo` + 令牌桶。
   理由：W1-e / W3 / W9a 三处都在等这几个键，而 W2 一开口就是整包 500 行。**W2-a 先合，三个人立刻解锁。**
5. **注入器定 A**（只写一个）：`client/gui/RelayScreenInjection` 归 **W9**，`instanceof ModelScreen` 后一次挂两个按钮（「上传模型」给所有人 /「模型上传设置」给 op2）。
   W7 只提供 `new UploadPanelScreen()`，不碰 `ScreenEvent`，**零新增类**。两个 listener 各自硬编码坐标必然叠按钮，这条没有第二个选项值得考虑。

---

## 11. 阿苏答复（2026-11-04，五个问题全部回收）

| # | 问题 | 阿苏的答复 | 对计划的影响 |
|---|---|---|---|
| Q1 | 装 conan + NASM 起 native 冷构建？ | **「可以装的时候我会通知你，现在先不装」** ~~⇒ 游戏内验收挂起~~ | ~~native 继续没有 ⇒ 游戏内验收挂起~~ **【2026-11-04 晚作废】native 不需要自建了**：阿苏随后给出了 `build/libs/ysm-3.0-dev-forge+mc1.20.1.jar`，`ysm.dll` 已内嵌且实测可加载。见 §0.0 |
| Q2 | 批准后默认全服可见？ | **「对」** | R4 成立，默认 `custom` 根，不动 |
| Q3 | Architectury 双端还是结构参考？ | **「仅 forge」** | **Architectury 整体不做**，继续 legacyforge 单 loader。这条记进「明确不做」，防以后有人再提 |
| Q4 | 面板要多深？ | **重述完整需求**（自动同步 / 自动同意 / 谁能处理请求 / 谁能管使用权 / 自动同意上限 / 客户端全选 / 未装可进服 / 单 jar） | **按他的清单逐条实现，不多做**：面板 = 5 个配置项 + 待审列表；**按玩家授权矩阵编辑不进 GUI**（走命令，他没要求，加了就是屎山） |
| Q5 | autoSync 语义？ | **「我的要求里有提：能够选择是否自动同步」** | **保留 `autoSync` 键**（不采纳「删掉只留按钮」），默认 `true`；`false` 时面板出「待同步 N」+ 立即同步按钮 |

**~~Q1 的连带后果，写死~~【2026-11-04 晚整段作废，见 §0.0】**

~~不装工具链 = 不碰游戏内验收。那就更不能容忍「没跑过就说好的」，所以 **W6（零依赖自检）与 W8a（离线集成验收：协议 / 命令 / 配置落盘 / 权限拒绝）优先级上调**；任何带「游戏内」字样的验收一律标 `⏸ 需 native`，**不写进本轮交付承诺**。~~

**现行口径（取代上一段）**：
- **游戏内验收恢复进交付承诺**。YSM 3.0 运行 jar 已到手，native 实测可加载。
- **W6 仍照做**（零依赖自检与真机验收不互斥，前者能回归、后者只能现场看一次），但**不再是「因为没有 native 才退而求其次」**，它本来就是该有的。
- **W8a（离线）与 W8b（真机）并行推进**，W8b 前置是 W0.5 跑通。
- 任何验收项**不得再标 `⏸ 需 native`**。要标就标 `⏸ 需 W0.5`（dev server 还没起来）或 `⏸ 需 W3`，指向真实的前置任务，不能再用 native 当挡箭牌。

### 另两条同时定下的（不占阿苏的决策）

- **W6 走零依赖方案 A**：本机 Gradle 缓存里 `org.junit` 只有 `junit-bom`，没有 jupiter / platform / opentest4j / apiguardian，也没有 JUnit4。
  `testImplementation 'org.junit.jupiter:junit-jupiter'` 会在解冻第一天去拉一整串传递依赖，而阿苏已说网络不好。
  ⇒ `src/verification/java` 写带 `main()` 的自检程序，全过正常退出、任一失败 `System.exit(1)` 并打印用例名。9 个用例全是纯逻辑断言，用不到 JUnit 任何特性，以后网络好了断言原样搬进 `@Test`。
  **`build.gradle` 写入边界**：W6 只加 `verification` sourceSet + 一个 `JavaExec` 任务，**不碰 `legacyForge` 块与依赖声明**（那属 W2）。这条边界由我裁定，两人不必再往返。
- **W9 分 a/b 两阶段**：**W9a 配置面**（注入按钮 + 配置读写三包 + 落盘）**不依赖 W3**，解冻当天即可开工；
  **W9b 待审列表**（行内同意/拒绝）依赖 W3 的 `PendingQueue`。两阶段共用同一个 Screen。

### 原始提问（留档）

| # | 问题 | 为什么要你定 | 不定的后果 |
|---|---|---|---|
| Q3 | 那个 Architectury 例子，你是想要**双端（Forge+Fabric）**，还是只要「common/client 分离 + 单 jar 双端」的结构参考？ | **YSM 3.0 本身只有 forge 变体**（`gradle.properties: mod_version=3.0-dev-forge+mc1.20.1`，无 fabric 产物）。relay 调的是 YSM 的 Java 类，Fabric 侧没有对端可挂 —— 现在做成 architectury 双端，编译得过、跑不起来。 | 若你要双端而我按 forge 做，等于白锁一个 loader 维度；若你别无他意而我上了 architectury，三模块重构吃掉两天且没有任何收益 |
| Q4 | 服主面板要多深？**A** 只读配置 + 一键同步；**B** 在上面加待审列表（行内同意/拒绝）；**C** 再加按玩家授权矩阵编辑 | C 意味着一个完整的管理 UI（分页、搜索、二次确认），是 W9 里最大的一块；A 只要一个 Screen + 4 条消息 | 定 B 我做 B；做多了是屎山，做少了服主还得回命令行 |
| Q5 | 「自动同步玩家上传的模型」= 批准后**立刻 reload 推给全服**，还是**攒着等服主手动同步**？ | 这条决定 `autoSync` 的默认值与 W3 的提交路径（reload 是 YSM 现成能力，延迟同步要自己握住触发点） | 默认值猜错会让服主以为模型没上传成功 |

## 12. 发布节奏（回答阿苏「什么时候能编出能装进游戏的 jar」）

**更正（2026-11-04，DS_高等程序员 拆包实证）**：`build/libs/ysm_relay-0.1.0.jar` 这个文件在，**但它比源码旧 5 分钟** ——
缺 `UploadRegistry` / `UploadPanelScreen` / `UploadPanelKeys` / `UploadPanelOpener` 四个类，`YsmBridge` / `RelayConfig` / `UploadService` 是旧版本。
拿它装进去，**客户端按 U 打不开上传面板**。所以 V0.1 的第一条不是「改三处」，是「**先 `gradlew build --offline` 重新出包**」，十几秒，不需要 native、不需要联网。

**「能不能装进游戏」的三分情况（`YsmBridge.versionOk()` = `majorOf(version) == 3`）**：

| 你手上的 YSM | relay 装上去会怎样 |
|---|---|
| **3.x（任意 dev 版本）** | 正常加载。`/ysmrelay status` 能看到版本、模型数、通道是否注册 —— **这一步不需要 native，是纯 Java 侧接得上的第一手证据** |
| **2.6.5（Modrinth 上唯一能下到的）** | `versionRange="*"` 让 Forge 放行，但 `versionOk()` 假 → `commonSetup` 打 error「不在受支持范围 3.x，功能全部停用」。**另有未核实风险**：2.6.5 与 3.0 的 `com.elfmcys.*` 类结构完全不同，若任何引用在 `commonSetup` 之前被触发，会是 `NoClassDefFoundError` 而不是优雅停用 |
| 一个都没有 | **Forge 在 mod 加载期直接报 `Missing or unsupported mandatory dependencies`，游戏起不来** —— 不是静默停用，`commonSetup` 里的降级日志压根走不到 |

**`displayTest` 的值必须是 `IGNORE_ALL_VERSION`，不能是 `IGNORE_SERVER_VERSION`**：后者只忽略「客户端版本与服务端不同」，客户端**完全没装**这个 mod 时仍会被拒。阿苏要的是 C（没装 relay）能进服。
两层各管各的：Forge 放行 C 进服，`RelayNetwork` 的 `acceptMissingOr("2")` 在 v1/v2 不匹配时拦上传。

| 版本 | 内容 | 阻塞 | 目标 |
|---|---|---|---|
| **V0.0**（已有） | 今天的 jar | 只证明打包链路通 | — |
| **V0.1** | 能装 / 能进服 / 协议自洽 | **不需要 native** | **解冻后半天** |
| **V0.2** | 阿苏要的审批流程完整 | 主线程 IO + 审核门 | — |
| **V0.3** | 授权矩阵 + 客户端多选 | — | — |

**V0.1 只需三件事，全都不需要 native：**
1. `mods.toml` 加 `displayTest="IGNORE_ALL_VERSION"`（W0，1 行）—— 否则 C（没装 relay）握手被拒，阿苏的核心场景当场失败。
2. **W1-e**：值域钉死 + `PROTOCOL_VERSION` v1→v2（4 个文件，全在 W1）。
3. **W2-a**：只加 §7 那 11 个键 + 改 `maxTotalBytes` 默认值，不写逻辑（半小时，解 W1-e / W3 / W9a 三处阻塞）。

V0.1 到手后能验的是：装得上、C 能进服、双方协议不打架、`/ysmrelay upload` 走得通（approve 门还没有，批准 = 直接入库）。

~~**验不了的是**：模型被解析、被 reload、被分发 —— 那一段要 YSM 3.0 活着。~~
**【2026-11-04 晚更正】这一段也能验了。** YSM 3.0 运行 jar 到手、`ysm.dll` 内嵌且实测可加载（§0.0），
所以「模型被解析 / 被 reload / 被分发」不再是禁区 —— 前置只是 **W0.5**（把 dll 喂进 dev 环境，起一次 server）。

~~**真正的分水岭不在这三个版本号上。** V0.1 的 jar 在任何一端都需要对端 YSM 3.0 跑得起来，而 YSM 3.0 需要 `ysm.dll`，本机没编、阿苏说先不装 ⇒ **V0.1 出来我们能验的只有「加载 / 进服 / 协议」三样**。模型相关的全部挂起，标 ⏸。~~

**现行分水岭只有一条：W0.5 跑没跑通。**
- W0.5 通 → V0.1 可以当场做全链路真机验收（ABC 三人服在单机双客户端 + 一个假 C 上就能搭），版本号可以往后压。
- W0.5 不通 → 立刻回到「只能验加载 / 进服 / 协议」，并且要按 §0.0 的加载路径逐段排查（JNA 符号解析是唯一已知可疑点）。

**排期上现在的第一优先级不是 V0.1 的三件事，是 W0.5。** V0.1 那三件在两小时内能做完，W0.5 决定了后面所有验收能不能落地。

---

## 13. v1 保留内容

v1 §2「YSM 3.0 现成能力清单」（含 `ServerModelService.reload()` / `AuthModelsCapability` / `RawModelImporter` 的具体入口与行号）
与 §5 风险表继续有效，见 `docs/02-YSM3.0接入点调研.md`。v1 的 M0–M4 编号已作废，全部以本文 W0–W8 为准。
