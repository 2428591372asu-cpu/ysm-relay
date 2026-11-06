# ysm-relay

YSM（Yes Steve Model）3.x 的附属模组，补上 YSM 原本缺少的**「客户端 → 服务端」上传半段**。

玩家在游戏里把本机的模型传给服务器，服务端落盘后由 **YSM 自己**重扫并分发给所有人。本模组不实现任何分发逻辑——分发、缓存、按需取块全部沿用 YSM 现成能力。

## 版本与依赖

| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47 以上（开发用 47.4.3） |
| Java | 17 |
| YSM | 3.x，**服务端与客户端都必须装** |
| 本模组 | 两端版本必须一致（协议 v10） |

YSM 是硬依赖（运行期要用它的模型目录、授权与导入入口）。YSM 不在时本模组只打日志停用，不会让服务器起不来。

## 安装

把 `ysm_relay-<版本>.jar` 放进客户端与服务端的 `mods/`。服务端首次启动会生成 `serverconfig/ysm-relay-server.toml`，改完重启生效。

## 快速上手

**服主**

1. 启动一次服务器，打开 `serverconfig/ysm-relay-server.toml`
2. 决定上传模式：`uploadMode = "review"`（逐个审核）/ `"auto"`（上限内直接入库）/ `"full"`（超限直接拒绝）
3. 需要逐玩家控制模型使用权时，把 `targetRoot` 改成 `"auth"`

**玩家**

1. 在 YSM 的模型界面点「上传模型」，或按热键 `U`
2. 面板里选文件（`.mxc` / `.ysm` / `.zip` / `.7z`），点上传
3. 审批模式下等服主在「待审队列」里批准；其它模式直接入库

面板顶部「目录…」可以改成任意本地文件夹，不必把模型放进 YSM 目录。

## 功能

- **手动上传**：多选、串行队列、逐文件进度与结果
- **自动上传**：指定本地目录，文件变化即上传（文件监听优先，失败回退轮询）
- **审批**：review / auto / full 三档，切档会把已有队列按新规则处理；待审可批准、拒绝、本人撤回
- **管理**：我的上传 / 我的待审 / 待审队列；删除同时清理源文件与 YSM 转换缓存
- **权限**：1 普通 / 2 权限组与 op / 3 服主，逐选项可配；授权矩阵 public / listed / except
- **准入**：禁止上传名单、上传白名单、每玩家配额、全服配额、待审总数与每人待审上限
- **界面**：YSM 模型界面入口按钮、独立上传面板、三栏设置页、缺服与单人模式提示

## 上传模式

| 模式 | 行为 |
| --- | --- |
| `review` | 所有上传进待审队列，服主批准后才入库 |
| `auto` | 不超过 `autoApproveMaxBytes` 直接入库，超过转待审 |
| `full` | 不超过上限直接入库，超过在探测阶段就拒绝（一个字节都不传） |

白名单玩家永远免审。

## 权限等级

| 等级 | 谁能达到 |
| --- | --- |
| 1 | 所有玩家 |
| 2 | 授权组、在线 op |
| 3 | 服主（单机存档主人 / 专用服务器控制台） |

每个配置项都有独立的所需等级（登记在 `RelayConfig` 的 `option()` 调用里）；客户端设置页按等级置灰。

## 命令

服务端（需要等级 2 以上）：

```
/ysmrelay status                         总览
/ysmrelay paths                          模型目录 / 暂存 / 待审目录与实际文件数
/ysmrelay list [页码]                     授权策略
/ysmrelay show <玩家>                     某玩家当前持有的授权
/ysmrelay policy <模型> public|listed|except|clear
/ysmrelay grant <玩家> <模型>
/ysmrelay revoke <玩家> <模型>
/ysmrelay move <模型> auth|custom
/ysmrelay delete <模型> confirm           删除源文件与 YSM 转换缓存，并禁止再次上传
/ysmrelay block <模型>
/ysmrelay unblock <模型>
/ysmrelay pending [页码]                  待审队列
/ysmrelay approve <编号>
/ysmrelay reject <编号>
/ysmrelay blockuploader add|remove|list <玩家>
/ysmrelay whitelist add|remove|list <玩家>
/ysmrelay level grant|revoke|list <玩家>
/ysmrelay refresh [玩家]
```

玩家：

```
/relayme list                    我上传的模型
/relayme delete <模型> confirm
/relayme cancel <编号>            撤回自己的待审
```

模型参数可写完整 64 位哈希、8 位以上唯一前缀，或 `global:custom/xxx.mxc` 这样的地址。

## 配置

服务端：`<存档目录>/serverconfig/ysm-relay-server.toml`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `allowPlayerUpload` | true | 允许普通玩家上传 |
| `maxModelBytes` | 64 MiB | 单个模型上限，0 = 不限 |
| `chunkBytes` | 16384 | 分片大小，上限 16384（超过 MC 的自定义包限制） |
| `maxBytesPerPlayer` / `maxTotalBytes` | 1 GiB / 4 GiB | 配额，0 = 不限 |
| `stagingTtlSeconds` | 600 | 暂存残片保留时长 |
| `uploadPermissionLevel` | 0 | 上传所需等级 |
| `targetRoot` | custom | custom 人人可见；auth 需授权 |
| `maxStagingBytes` / `minFreeDiskBytes` | 768 MiB / 2 GiB | 磁盘守卫 |
| `maxUploadBytesPerSecond` | 2 MiB | 下发给客户端的限速建议 |
| `rejectAllUploads` | false | 一键关闭上传 |
| `allowAutoUpload` | true | 允许客户端自动上传 |
| `uploadMode` | review | review / auto / full |
| `autoApproveMaxBytes` | 16 MiB | 自动放行阈值 |
| `maxPendingCount` / `maxPendingPerPlayer` | 8 / 8 | 待审总条数 / 每人条数，0 = 不限 |
| `pendingTtlHours` | 72 | 待审保留时长 |
| `autoSync` / `autoSyncMinIntervalSeconds` | true / 15 | 落盘后自动重扫与去抖 |
| `autoUploadScanSeconds` | 0 | 0 = 文件监听，>0 = 轮询秒数 |
| `listMaxEntries` | 200 | 列表一次最多回传条数 |
| `uploadWhitelist` / `blockedUploaders` / `approveGroup` / `manageGroup` / `levelGrants` | 空 | 名单与授权组 |

客户端：`config/ysm-relay/client.properties`（自动上传目录、手动上传目录）

## 目录与数据

| 路径 | 内容 |
| --- | --- |
| `%LOCALAPPDATA%/ysm/custom` | 公开模型根（`YSM_HOME` 可改） |
| `%LOCALAPPDATA%/ysm/auth` | 授权模型根 |
| `%LOCALAPPDATA%/ysm/relay-staging` | 传输中的残片，TTL 600 秒 |
| `%LOCALAPPDATA%/ysm/relay-pending` | 待审内容，按编号分目录 |
| `%LOCALAPPDATA%/ysm/cache/converted` | YSM 转换缓存（删除模型时要一起清） |
| `config/ysm-relay/uploads.json` | 上传台账 |
| `config/ysm-relay/pending.json` | 待审记录 |
| `config/ysm-relay/authz.json` | 授权矩阵 |

注意：`custom` 是 YSM 的**导入入口**，不是长期仓库。YSM 导入后会把容器放进 `cache/converted`。

## 开发

```powershell
.\gradlew.bat compileJava --offline     # 只编译，不联网
.\gradlew.bat verification --offline    # 零依赖自检，输出 cases=N failures=M
.\gradlew.bat build --offline           # 出包
.\gradlew.bat ysmDevJar                 # 把 ../ysm 的编译产物打成 compileOnly jar
```

编译需要 YSM 主仓的编译产物；默认在 `../ysm`，可用环境变量 `YSM_PROJECT_DIR` 覆盖。

```
src/main/java/com/asu/ysmrelay/
  client/      上传面板、设置页、列表页、自动上传、目录设置
  command/     /ysmrelay 与 /relayme
  config/      配置定义、同步、权限、列表面
  net/         独立通道 ysm_relay:upload 与消息体
  upload/      探测、接收、落盘、待审队列、台账、路径
  admin/       授权矩阵与模型搬迁/删除
  ysm/         与 YSM 的唯一接缝
src/verification/java/   零依赖自检（不加载 Forge 与 native）
```

## 已知限制

1. **成品容器必须有内嵌预览**。直接投放到模型目录的 `.mxc`（YSGP2）会被 YSM 当作成品容器，缺预览会被拒收；旧版 `.ysm` 与 zip 走 YSM 的源导入路径，不受此限制。
2. **删除需要文件不被占用**。Windows 上 YSM 可能仍持有句柄，删除会失败并明确报错，重启服务端后重试。
3. **同内容副本会重新导入**。若 `custom` 里还留着同一模型的另一个文件名副本，删除后重扫会把它再导进来。
4. **列表有上限**。单包 24 KiB 与 `listMaxEntries` 双重截断。
5. **两端版本必须一致**。协议 v10，混版用不了上传通道（仍能正常进服）。
6. **目录型模型不支持上传**。只收文件；含 `ysm.json` 的目录要服主手动放。

## 文档

- [服主管理面](docs/ADMIN.md)
- [使用说明](docs/使用说明.md)
- [YSM 3.0 接入点调研](docs/02-YSM3.0接入点调研.md)
- [早期阶段计划（归档）](docs/archive/PLAN-W2W3-阶段计划.md)
