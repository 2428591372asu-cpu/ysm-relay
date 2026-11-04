# YSM 3.0 源码级接入点调研

> 调研对象：`D:\Asu\works\DSHwork\YSM\ysm`（YSM 3.0-dev-forge+mc1.20.1）
> 注：2026-11-04 项目整体搬进 `DSHwork\YSM\`，本文原写的 `D:\Asu\works\DSHwork\YesSteveModel` 现指向 `DSHwork\YSM\ysm`。
> 目的：确认 ysm-relay 要引用哪些类、能不能编译、以及"客户端上传→服务端重扫→二次分发"需要补哪一段。
> 所有方法签名均用 `javap` 从编译产物读出，不是猜的。

---

## 0. 结论先行

| 问题 | 结论 |
| --- | --- |
| YSM 能不能被 ysm-relay 编译依赖 | **能，已打通**。`build/classes/java/main` 有 1995 个已编译 class，打成 `libs/ysm-3.0-dev-classes.jar`（4.2 MB）。不需要等 shadowJar 或 native |
| YSM 有没有"服务端→客户端"分发 | **有，而且很完整**。自研 Frame 协议 + 分块资源传输 + 每玩家 catalog 快照 |
| YSM 有没有"客户端→服务端"的模型上传 | **没有**。C2S 方向只有"我要下载 X""我上传预览图 Y"，没有"我上传模型文件 Z" |
| 哈希去重能不能做 | **能，而且免费**。`Hash256` = BLAKE3 32 字节，`CatalogSnapshot.byModelId()` 直接 O(1) 判存 |
| "某模型仅某玩家可用" | **YSM 已实现**。`AuthModelsCapability` + `GrantCollectionOperation` + `/ysm auth`。我们要做的是把它包成图形界面，不是重造 |
| 上传落盘后怎么生效 | `ServerModelService.reload()` 是 public；`CatalogReconciler` 按 `SourceStamp` 差量，不会全量重解析 |

**一句话架构**：不动 YSM 自己的协议栈，另开一条 `ysm_relay:upload` 的 Forge SimpleChannel 走模型上传；服务端落盘到 staging → 用 YSM 自己的 `RawModelImporter` 规范化/校验 → 移入 YSM 的模型根 → 调 `reload()` → YSM 原生的 catalog 下发链路自动把新模型发给所有客户端。

---

## 1. 编译依赖（已完成）

YSM 仓库的 `build/libs/` 只有 45 KB 的 `declaration-processor.jar`；`build/devlibs/` 是空的（`jar` / `reobfJar` 任务没跑成功，`shadowJar` 卡在缺 `ysm.dll`）。但 `build/classes/java/main` 是完整的：

```
build/classes/java/main/com/elfmcys/ysm/**   1995 个 .class
build/classes/java/declaration/com/elfmcys/**  api 声明
```

已打包为：

```
ysm-relay/libs/ysm-3.0-dev-classes.jar   4,227,901 B   1995 classes
```

打包含 main + declaration 两个 classes 目录，dev 映射（不是 reobf），所以和 legacyforge 的 `compileJava` 命名空间一致。

### 接入方式

```groovy
dependencies {
    // YSM 3.0-dev：只在编译期需要，运行时由玩家的 ysm 本体提供
    compileOnly files('libs/ysm-3.0-dev-classes.jar')
    // 若编译报缺 proto 类型，再补这两个（dev classes 里是 us.hebi.quickbuf 原始包名）
    compileOnly files('D:/Asu/works/DSHwork/YSM/ysm/libs/quickbuf-runtime-1.4.1-ysm.jar')
}
```

`mods.toml` 里 ysm 已经是 `mandatory=false`，但实际运行时**必须**有 ysm，否则所有引用都会 `NoClassDefFoundError`。要在入口做存在性守卫（`ModList.get().isLoaded("ysm")`），并把依赖改成 `mandatory=true` 或至少在加载时自检报错。

### 注意
- 产物是 **dev 映射**，只用于 `compileOnly`。不要塞进 jar 里发布。
- 若要长期稳定，正确做法是让 YSM 跑一次 `./gradlew jar` 出 `build/devlibs/ysm-...-raw.jar`；但那需要 protobuf + curse maven 全依赖下载，且 `shadowJar` 链上有 native 门槛。当前用 classes 目录打 jar 是等价且零风险的做法。

---

## 2. YSM 3.0 的模型生命周期（现状）

```
磁盘模型根 ──scan──> ReloadableModelCatalog ──CatalogSnapshot──> ServerModelService
                                                                    │
                          ┌─────────────────────────────────────────┤
                          ▼                                         ▼
              SessionProtocolHandler.publishCatalog        ServerAssetTransfers
                          │                                         │
                          ▼                                         ▼
                   客户端 catalog 快照                   按需分块拉取 .mxc 内容
                   (ClientCatalogManager)                (ClientAssetRepository / RemoteModelStore)
```

### 模型在磁盘上从哪来

`ModelCatalogSources.reloadableSources(AssetPaths)`：

| CatalogRoot | AccessPolicy | 路径 |
| --- | --- | --- |
| `global:custom` | PUBLIC | `<dataRoot>/custom` |
| `global:auth` | SESSION_AUTHORIZED | `<dataRoot>/auth` |
| `local:custom` | PUBLIC | `<gameDir>/ysm/custom` |
| `local:auth` | SESSION_AUTHORIZED | `<gameDir>/ysm/auth` |
| `builtin:` | PUBLIC | 模组内置 |

`AssetPaths`：`gameModelsRoot(gameDir) = gameDir/ysm`，`customModelsRoot = <root>/custom`，`authModelsRoot = <root>/auth`。
服务端专用（`GLOBAL_*`）才是二次分发的根 —— **上传落盘必须落在这里**，落到 `local:*` 客户端看不见。

### 认的文件形态

`ModelSourceDiscovery.FILE_EXTENSIONS = {.mxc, .ysm, .zip, .7z}`，另外"含 `ysm.json` 或 `main.json` 的目录"也算一个 raw 模型。

`.ysm` 还要探测 magic：
- 有当前容器 magic → `DIRECT_CONTAINER`
- `LegacyContainerHeader.probe` = `V1_RAW` / `V2_RAW` → `CURRENT_RAW_ARCHIVE`
- `V3_ENCRYPTED` → `LEGACY_ARCHIVE`（旧加密包）
- `UNSUPPORTED` → 拒绝

### 哈希

`ModelHashing.blake3(Path | byte[] | UniBuffer)` → `Hash256`（32 字节，BLAKE3，走 native `Blake3`）。
`RawModelImporter.capture(Path).modelId()` 与 `convert()` 产出的 `compiled.modelHash()` **必须相等**，否则抛 `IllegalStateException("Captured and compiled model identities do not match")`。这就是天然的完整性校验。

---

## 3. 缺口分析（对照原始需求）

| 需求 | YSM 现状 | relay 要补什么 |
| --- | --- | --- |
| 客户端→服务端上传模型 | 无通道 | 新开 C2S 上传通道（分块、限速、断点、超时） |
| 服务端落盘后重扫 | `reload()` 有，但没人调用 | 落盘后排队 reload，节流合并（多个玩家同时上传只扫一次） |
| 免去玩家手动给服主 | 现有流程要玩家自己把 `.ysm` 交给服主 | 上面两条，加一个客户端"上传"入口 |
| 剔除哈希相同的模型 | `byModelId()` 天然去重 | 上传前先问服务端 "有 X 吗"；有就直接短路 |
| 服主删除玩家上传的模型 | `/ysm` 只能删不了；靠手动进目录删 | 服务端管理指令 + 卸载文件 + reload |
| 单模型大小上限 | 无 | 服务端配置 + 在 `PreviewUploadRequest` 式握手阶段就拒绝，不进内存 |
| 按玩家配"哪些模型能用" | **有**：`/ysm auth <players> add\|remove\|all\|clear <path>` | 包成 GUI/面板，直接调 capability，不重造语义 |
| "某模型只给某玩家" | 靠把模型放 `global:auth` + 只给该玩家 add | 同上，用 `CatalogAccess.AUTHORIZED` 天然隔离 |

---

## 4. 关键接入点清单（签名取自 javap）

### 4.1 服务端生命周期

```java
com.elfmcys.ysm.model.service.ServerModelService
  public static ServerModelService start(MinecraftServer)
  public static ServerModelService instance()
  public static Optional<ServerModelService> current()
  public Optional<ServerCatalog> catalog()
  public CompletableFuture<ReloadOutcome> reload()          // 全量重扫入口，public
  public ModelScanReport lastReport()
  record ReloadOutcome(boolean success, int modelCount, int errorCount,
                       List<ModelScanWarning> warnings, String message)

com.elfmcys.ysm.model.ModelRuntime
  public static ModelSystem system()
  public static AssetPaths paths()
  public static void initialize()
```

`start()` 内部会 `new ServerModelService(server)` 并做 `catalog.reload()`（仅 dedicated server）。relay 不应该自己 start，只读 `current()`，拿不到就说明 YSM 没起来。

### 4.2 目录与根

```java
com.elfmcys.ysm.AssetPaths
  public Path gameDirectory(); public Path configDirectory(); public Path configRoot();
  public Path gameModelsRoot(); public Path customModelsRoot(); public Path authModelsRoot();
  public Optional<GlobalDirectories> global();   // dataRoot / cacheRoot / cacheGuard
  public Path defaultCustomRoot();  // 有 global 就返回 global.customRoot()
  public Path remoteRoot();

com.elfmcys.ysm.model.catalog.source.ModelCatalogSources
  public static Path customPath()   // = ModelRuntime.paths().defaultCustomRoot()
```

**上传落盘用 `ModelCatalogSources.customPath()`**（服务端会拿到 `global:custom`），不要自己拼路径 —— YSM 自己用 `AssetDirectoryProbe` 决定 dataRoot，可能不在 gameDir 下。

### 4.3 扫描与目录快照

```java
com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog
  public Map<Hash256, ManagedContainer> models();       // 去重判存用这个
  public Optional<ManagedContainer> find(Hash256);
  public Optional<ManagedContainer> findPath(String);
  public Optional<ManagedContainer> find(CatalogLocation);
  public CatalogAddressResolver.Result resolvePath(String);
  public List<ModelPackDescriptor> packs();
  public ModelScanReport report();

com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot
  public Map<Hash256, CatalogRecord> byModelId();
  public Map<HierarchyPath, Hash256> byPath();
  public Map<CatalogLocation, Hash256> byLocation();
  public Optional<Hash256> resolve(CatalogLocation);
  public Optional<Hash256> resolve(HierarchyPath);

com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord
  public CatalogEntry entry();            // modelId / path / access / presentation
  public CatalogLocation location();      // 决定在哪个根
  public ContentBinding binding();

com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess  // PUBLIC / AUTHORIZED
```

### 4.4 规范化导入（核心复用点）

```java
com.elfmcys.ysm.model.catalog.RawModelImporter
  public RawModelImporter(DefaultAnimationFilter)
  public CapturedModel capture(Path source)                  // .mxc/.ysm/.zip/.7z/目录 → 冻结快照
  public RawCompileResult convert(CapturedModel, Path outputDirectory)   // → 写 .mxc
  public RawCompileResult convert(CapturedModel, DirectoryAccess)        // 更适合接续 YSM 的目录守卫

com.elfmcys.ysm.format.parser.CapturedModel
  public Hash256 modelId()      // 内容哈希 == 最终 modelId
  // data(), diagnostics(), originVer()

com.elfmcys.ysm.format.parser.RawCompileResult
  public Hash256 modelHash()    // 必须 == captured.modelId()
  // diagnostics()
```

**这就是上传通道的服务端后处理**：玩家上传的原始文件 → `capture()` → `convert(..., customRoot)` → 直接出现一个 `<modelId>-<uuid>.mxc`。
它自带：路径安全检查（`DirectoryAccess`）、身份一致性校验、失败自动清理。**不要自己写 zip 解包和格式解析。**

代价：`capture`/`convert` 是同步 I/O，必须丢到 YSM 的模型线程池（`ThreadScheduling` / `NativeRuntime.availableProcessors()`），不能占 server thread。

### 4.5 授权（"仅某玩家可用"）

```java
com.elfmcys.ysm.capability.AuthModelsCapability
  public void addModel(Hash256); public void removeModel(Hash256);
  public boolean containModel(Hash256); public Set<Hash256> getAuthModels();
  public void setAuthModels(Set<Hash256>); public void clear();
  // NBT: serializeNBT/deserializeNBT（旧 path 授权不回迁）

com.elfmcys.ysm.capability.AuthModelsCapabilityProvider.AUTH_MODELS_CAP

com.elfmcys.ysm.network.forge.SessionProtocolHandler
  public static void refreshGrants(ServerPlayer)              // 授权变更后必须调
  public static void refreshContentPolicy(ServerPlayer)
  public static void publishCatalog(ServerPlayer, CatalogSnapshot)
  public static boolean applyCommandSelection(ServerPlayer, ModelInfoCapability,
                                              Selection$Model, boolean)
```

YSM 原指令（`AuthCommand`，需要 permission 2）：

```
/ysm auth <players> add <model_path>
/ysm auth <players> remove <model_path>
/ysm auth <players> all
/ysm auth <players> clear
```

`add` 之后会 `SessionProtocolHandler.refreshGrants(player)`；`remove` 还会在当前选中模型被收回时 `ModelSelectionService.selectDefault(...)` 踢回默认模型。**这四条语义照抄即可，不要发明新的。**

### 4.6 分发侧（无需改，但要知道边界）

```java
com.elfmcys.ysm.model.service.ServerModelService
  public ResourceDispatchWorker dispatch();
  public void acceptModelChunkRequest(ServerPlayer, Connection, ServerModelSession, ModelChunkRequestProto)
  public void acceptMetadataPrefixRequest(...MetadataPrefixRequestProto)
  public void acceptPresentationPageRequest(...PresentationPageRequestProto)
  public boolean startGenPreview(ServerPlayer)   // 需要 permission 2

com.elfmcys.ysm.config.ServerConfig
  BANDWIDTH_LIMIT / DISPATCH_SOFT_LIMIT / DISPATCH_HARD_LIMIT
  RESTRICTED_AUTH      // true 时未授权模型连图标和 chunk 都不给
  CAN_SWITCH_MODEL
  CLIENT_NOT_DISPLAY_MODEL_PATHS
```

协议版本：`com.elfmcys.ysm.network.protocol.ProtocolVersion`
`CHANNEL_PATH = "protocol"`，`CURRENT = "0.5.0-unstable"`，channel = `ysm:protocol`（Forge `EventNetworkChannel`，**不是** SimpleChannel）。

`ProtocolMessageRegistry` 是**编译期构造的不可变注册表**（`byId` / `byType` 都是 `Map.copyOf`）。所以：

> **不能从外部往 YSM 协议里插新消息 ID。** 这不是配置问题，是设计上的封闭表。

→ 附属 mod 必须走**自己的通道**。

---

## 5. 协议边界：为什么另开通道

现有 C2S 消息（`model_session.proto`）：

| 消息 | 用途 |
| --- | --- |
| `SelectModelRequest` | 我选哪个模型 |
| `MetadataPrefixRequest` / `ModelChunkRequest` / `PresentationPageRequest` | **我要拉**某模型的内容 |
| `MissingPreviewQuery` / `PreviewUploadRequest` / `PreviewUploadFragment` / `PreviewRepairResult` | 上传/修复**预览图**（服务端向客户端要图，反向） |
| `ResourceTransferCancel` | 取消 |
| `UpdateStarredModelRequest` / `SubmitConfigActionRequest` | 收藏、配置项 |

S2C：`ServerHello` / `SessionResponse` / `SessionFullFragment` / `SessionDeltaFragment` / `CatalogPublication` / `GrantCollectionOperation` / `*Fragment`。

→ 全表都在"目录同步 + 按需拉取"。**没有一条是"客户端把模型文件推给服务端"。**

### 方案对比

| 方案 | 做法 | 风险 | 结论 |
| --- | --- | --- | --- |
| A. 独立通道 | 自建 `ysm_relay:upload` SimpleChannel，自己的消息 | 与 YSM 零耦合，YSM 升级不炸 | **采用** |
| B. 混入 YSM 协议 | 反射改 `ProtocolMessageRegistry` 或 mixin 扩展 ID | 注册表不可变；ID 冲突会让整条会话 `DECLINE`；协议自己标 `unstable` | 否 |
| C. Mixin 进客户端 GUI | `@Inject` 到 YSM 模型选择界面加按钮"上传" | 界面类是 client-only，且 YSM UI 常改 | 可作后续增强，不作主路径 |

---

## 6. 上传通道设计（建议实现）

### 6.1 消息（`ysm_relay` 自己的 channel）

```
C2S  UploadProbe       { byte[] blake3; long size; String fileName }        // 有 X 吗？
S2C  UploadProbeResult { enum { ALREADY, ALLOW, TOO_LARGE, DENIED, BUSY } }
C2S  UploadBegin       { byte[] blake3; long size; String fileName; int chunks }
S2C  UploadAdmission   { long transferId; int chunkSize }
C2S  UploadChunk       { long transferId; int seq; boolean last; byte[] data }
S2C  UploadProgress    { long transferId; int received; int total }
S2C  UploadResult      { long transferId; enum { COMMITTED, DUPLICATE, REJECTED, FAILED };
                         byte[] modelId; String location; String message }
C2S  UploadCancel      { long transferId }
```

要点：
- **握手先给 hash 和 size**。服务端在 `UploadProbe` 阶段就能：判重（`byModelId().containsKey`）、拒大（配置上限）、拒无权限 —— **一个字节都不用收**。
- 分块建议 32–64 KB，服务端按 `transferId` 组装到 `staging/<uuid>.part`，不要拼在内存里。
- `UploadResult.COMMITTED` 要回 `modelId` 和最终 `CatalogLocation`，客户端可以立刻选中。
- 一个玩家同时只允许 1 个上传（`BUSY`），避免打爆磁盘。

### 6.2 服务端流水线

```
UploadChunk 收齐
   → staging/<transferId>.part         (写盘，校验 BLAKE3)
   → RawModelImporter.capture(part)    (识别格式 / 拿 modelId)
   → modelId != 客户端声明的 hash ? 拒绝并删
   → RawModelImporter.convert(captured, customRoot)   (出 .mxc)
   → 同名同 hash 已存在 ? 删新文件，回 DUPLICATE
   → 排队 reload（节流：500 ms 合并窗口，一次 reload 覆盖多个人）
   → ServerModelService.reload()
   → 订阅 CatalogTransition → 自动 publishCatalog 给所有在线玩家（YSM 自己会做）
```

**关键：reload 之后的广播不用我们写**。`ServerModelService` 构造时订阅了 `catalog.subscribe(...)`，`tick()` 里发现 `pendingPublication` 就 `publishCatalog` 给所有人。我们只要保证 reload 被调用。

### 6.3 落哪个根

- 默认落 `global:custom`（PUBLIC，人人可见）
- 若服主开了"上传即私有"，落 `global:auth` 并自动给上传者 `addModel` → 相当于"作者自留"
- 私有改公开 = 移动文件 + 改授权，用管理面一条指令做完

---

## 7. 哈希去重

- 算法：**BLAKE3**，32 字节（`Hash256`）。客户端也必须用同一个 —— 直接 `compileOnly` 引用 YSM 的 `ModelHashing.blake3(Path)`，Client 端同一份代码可用（它只用 native + `NativeBuffer`，YSM 客户端本来就加载了 native）。
- 去重层级：
  1. `UploadProbe` 阶段按客户端声明的 hash 查 `catalog.models().containsKey` → 直接 `ALREADY`
  2. 落盘后 `capture().modelId()` 重算 → 和声明不符就拒（防伪造）
  3. `convert()` 内部再校验一次 captured == compiled
  4. 入目录后 `reload` 的 `CatalogReconciler` 按 `Hash256` 主键收敛，同 hash 只有一个 `CatalogRecord`
- 结果：同一个模型无论多少玩家上传，磁盘上只有一份 `.mxc`。

**注意**：YSM 的 modelId 是"内容哈希"，不是文件名哈希。所以改了贴图的模型是不同 modelId，不会误判重复。

---

## 8. 服主管理面

### 8.1 指令（对齐 YSM 的 `/ysm` 风格，permission 2+）

```
/ysmrelay upload allow|deny                      全局开关
/ysmrelay upload maxsize <MB>                    单模型上限
/ysmrelay upload dir custom|auth                 上传默认落哪个根
/ysmrelay list [page]                            列出所有模型（含来源：builtin/global/local + 谁传的）
/ysmrelay delete <model_path>                    删文件 + reload（要处理 builtin 保护）
/ysmrelay grant <players> <model_path>           等价 /ysm auth add
/ysmrelay revoke <players> <model_path>          等价 /ysm auth remove
/ysmrelay restrict <model_path>                  把模型移进 global:auth 并清空所有人授权
/ysmrelay quota <players> <MB>                   单人累计上传额度（可选）
```

> `/ysm` 根指令是 YSM 的，不要往里塞子指令（会和上游版本冲突）。用自己的 `/ysmrelay`，但**语义与 `/ysm auth` 一致**。

### 8.2 图形界面（"别堆屎山"的部分）

不做 Bukkit 式菜单树，做一个**一页式面板**（用原版 `Screen`）：

```
┌ 模型管理 ──────────────────────────────┐
│ [搜索____] [来源: 全部▼] [访问: 全部▼]  │
│ ┌────────────────────────────────────┐ │
│ │ 头像 │ 名称        │ 来源   │ 授权人数│ │
│ │ ...  │ xxx.ysm    │ 玩家上传│ 3      │ │
│ └────────────────────────────────────┘ │
│ 选中: xxx  [删除] [设为私有] [授权玩家…] │
│ 上传设置: [x]允许玩家上传  上限 [64] MB  │
└────────────────────────────────────────┘
```

授权玩家用二级列表（玩家头像网格 + 勾选），写回走 `AuthModelsCapability` + `refreshGrants`。

**面板读的是 `catalog` 快照 + `AuthModelsCapability`，写只走三条指令后端**，UI 不含业务逻辑。

### 8.3 "谁上传的"

YSM 的 `CatalogRecord` 里没有"上传者"字段。要记就得自己存：
`config/ysm/relay-uploads.json` → `{ "<modelId>": { "uploader": "<uuid>", "name": "...", "time": 1234567890, "size": 123 } }`
按 modelId 索引，模型被删/被替换时按 reload 结果对齐清理。

---

## 9. 风险清单

| 风险 | 说明 | 处置 |
| --- | --- | --- |
| YSM 协议 `0.5.0-unstable` | 上游明确标 unstable，类可能改名 | 只依赖 `model.service` / `model.catalog` / `capability` / `AssetPaths` 这四块，别碰 `network.*` 内部 |
| dev 映射 | `libs/ysm-3.0-dev-classes.jar` 是 dev 命名空间 | 只 `compileOnly`，不进产物 |
| 无 runtime API 稳定性承诺 | YSM 没有对外 API 包（`api` 只覆盖渲染/molang） | 在入口用 `ModList.get().isLoaded("ysm")` 守卫 + 版本探测，失败即降级为"仅日志" |
| `capture/convert` 同步 IO | 会卡主线程 | 一律走异步池 + 每玩家串行 |
| native 依赖 | `Blake3`、`NativeArchive` 都是 ysm.dll | relay 不自己加载 native，全部经由 YSM 的公开方法 |
| 断点/大文件 | 64 MB 模型上传中断会留垃圾 | `staging/` 带 TTL，启动时清 >1h 的残片 |
| 恶意包 | 玩家可传任意 zip | 用 `capture()`/`convert()` 走 YSM 的解析器 + `DirectoryAccess`；禁止 zip slip（YSM 的 vfs 已处理） |
| 磁盘打满 | 上传洪泛 | 全局磁盘水位检查 + 每日上传计数 |

---

## 10. 下一步（可执行）

1. **打通编译**（`build.gradle` 加 `compileOnly files('libs/ysm-3.0-dev-classes.jar')`，写一个只引用 `ServerModelService.current()` 的探针类，`compileJava` 过关）
2. **上传通道骨架**（`ysm_relay:upload` SimpleChannel + `UploadProbe`/`UploadProbeResult` 两条消息跑通，先不传文件）
3. **服务端落盘 + `RawModelImporter` 接入**（单块小文件全流程，验证 `modelId` 与 catalog 中的 `Hash256` 对上）
4. **reload 节流 + 去重短路**
5. **`/ysmrelay` 指令**（list / delete / grant / revoke / maxsize）
6. **一页面板 UI**
7. **上传者台账 + 配额**

---

## 附：调研时读过的文件

```
build.gradle, gradle.properties, local.properties
src/main/java/com/elfmcys/ysm/AssetPaths.java
src/main/java/com/elfmcys/ysm/model/ModelSystem.java
src/main/java/com/elfmcys/ysm/model/ModelRuntime.java（javap）
src/main/java/com/elfmcys/ysm/model/service/ServerModelService.java
src/main/java/com/elfmcys/ysm/model/catalog/RawModelImporter.java
src/main/java/com/elfmcys/ysm/model/catalog/source/{AccessPolicy,CatalogRoot,CatalogRootKind,CatalogLocation,ModelCatalogSources,ModelSourceDiscovery}.java
src/main/java/com/elfmcys/ysm/capability/{AuthModelsCapability,AuthModelsCapabilityProvider}.java
src/main/java/com/elfmcys/ysm/command/sub/AuthCommand.java
src/main/java/com/elfmcys/ysm/config/ServerConfig.java
src/main/java/com/elfmcys/ysm/network/NetworkHandler.java
src/main/java/com/elfmcys/ysm/network/protocol/{ProtocolMessageRegistry,ProtocolVersion}.java
src/main/java/com/elfmcys/ysm/model/storage/ModelHashing.java
src/main/proto/network/{model_session,control,player_state}.proto
```
