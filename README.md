# ysm-relay

YSM（Yes Steve Model）3.x 的附属模组：让玩家在游戏里把自己电脑上的模型传给服务器，服务端落盘后交给 YSM 自己分发。

> 只补「客户端 → 服务端」这半段。模型同步、按需下载、缓存仍然全部由 YSM 负责。

## 环境要求

| 项 | 要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47 以上 |
| YSM | 3.x，客户端和服务端都要装 |
| Java | 17 |
| 本模组 | 两端版本必须一致 |

## 安装

1. 从 [Releases](https://github.com/2428591372asu-cpu/ysm-relay/releases) 下载最新 `ysm_relay-*.jar`。
2. 放进**客户端**与**服务端**的 `mods/` 目录。
3. 启动一次服务器，生成 `<存档>/serverconfig/ysm-relay-server.toml`，按需修改后重启。

## 快速上手

**服主**

1. 选上传模式：`uploadMode` 默认 `review`（全部待审）；想省事改成 `auto`。
2. 需要逐玩家授权时，把 `targetRoot` 改成 `auth`。
3. 需要帮手时用 `/ysmrelay level grant <玩家> 2` 授予模型管理员。

**玩家**

1. 打开 YSM 模型界面，点「上传模型」，或按热键 `U`。
2. 选文件（`.mxc` / `.ysm` / `.zip` / `.7z`），上传。
3. 审批模式下等服主通过；其他模式直接入库。

## 功能

### 上传

- 手动上传：多选、逐个进度、失败原因单独显示
- 自动上传：指定一个本地目录，文件变化自动上传；目录可在设置页选择
- 支持 `.mxc`、旧版 `.ysm`（V1/V2/V3）、`.zip`、`.7z
- 预检只做提示，最终以服务端判定为准

### 审批

| 模式 | 行为 |
| --- | --- |
| `review` | 所有上传进待审队列，批准后入库 |
| `auto` | 不超过 `autoApproveMaxBytes` 直接入库，超过转待审 |
| `full` | 不超过上限直接入库，超过直接拒绝 |

- 待审列表显示「上传者 · 模型名」
- 切换模式时，队列里已有条目按新规则处理
- 玩家可以撤回自己未被审批的上传

### 管理

- 我的上传：查看、删除自己上传的模型
- 模型管理：独立目录页，按上传者分组，支持搜索、翻页
- 删除：只删内容和记录，允许再传
- 拉黑：删除并禁止这个模型再次上传；拉黑名单可查看与解除
- 授权：public / listed / except 三种策略
- 名单：禁止上传玩家、上传白名单、审批组、管理组

### 界面

- 入口按钮挂在 YSM 模型界面上，另有热键 `U` 打开上传面板
- 进到没装 relay 的服务器会提示「功能不可用」
- 未开 LAN 的单人模式会提示本功能不可用
- 被服主设为 0 级的玩家点入口会看到「您不被本服务器授予使用ysm-relay！」

## 权限等级

| 等级 | 谁能拿到 | 能做什么 |
| --- | --- | --- |
| 0 | 被服主授予 0 级 | 禁用本模组全部功能 |
| 1 | 所有玩家 | 上传；管理自己的上传 |
| 2 | op、审批组/管理组、被授予 | 审批、模型管理、名单管理 |
| 3 | 服主授予 | 与服主同权：2 级全部 + 分发等级 + 改配置 |

授予与撤销：

```
/ysmrelay level grant <玩家> <0-3>
/ysmrelay level revoke <uuid>
/ysmrelay level list
```

3 级只能授予不高于自己的等级；0 级只能由有分发权的人授予。

## 常用命令

### 玩家

```
/relayme list                    我上传过哪些模型
/relayme delete <模型> confirm   删除自己的模型
/relayme cancel <编号>           撤回还没被审批的上传
```

### 管理与审批

```
/ysmrelay pending                待审队列
/ysmrelay approve <编号>         批准
/ysmrelay reject <编号>          拒绝
/ysmrelay delete <模型> confirm  删除（不禁止再次上传）
/ysmrelay deleteblock <模型> confirm  删除并禁止再次上传
/ysmrelay block <模型>           只禁止再次上传
/ysmrelay unblock <名字或哈希>   解除禁止
/ysmrelay blocked [页码]         拉黑名单
```

### 授权与诊断

```
/ysmrelay status                 总览
/ysmrelay paths                  目录与实际文件数
/ysmrelay list [页码]            授权策略
/ysmrelay policy <模型> public|listed|except|clear
/ysmrelay grant <玩家> <模型>    加入名单
/ysmrelay revoke <玩家> <模型>   移出名单
/ysmrelay move <模型> auth|custom
/ysmrelay blockuploader add|remove|list
/ysmrelay whitelist add|remove|list
/ysmrelay refresh [玩家]
```

命令完整说明与权限要求见 [ADMIN.md](docs/ADMIN.md)。

## 配置

服务端配置在 `<存档>/serverconfig/ysm-relay-server.toml`，首次启动生成，改完重启生效。

### `[upload]`

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `allowPlayerUpload` | `true` | 允许普通玩家上传 |
| `maxModelBytes` | 64 MB | 单个模型上限，0 = 不限 |
| `maxBytesPerPlayer` | 1 GB | 单玩家累计配额，0 = 不限 |
| `maxTotalBytes` | 4 GB | 全服累计上限，0 = 不限 |
| `uploadPermissionLevel` | 0 | 上传所需等级（0 所有人 / 2 管理员 / 3 服主级） |
| `targetRoot` | `custom` | 上传落点：custom 公开，auth 需授权 |
| `chunkBytes` | 16384 | 分片大小（已接近协议上限） |
| `maxStagingBytes` | 768 MB | 暂存与待审总量上限 |
| `minFreeDiskBytes` | 2 GB | 磁盘剩余低于此值拒收 |
| `stagingTtlSeconds` | 600 | 未完成上传保留时长 |
| `maxUploadBytesPerSecond` | 2 MB/s | 上传限速，0 = 不限 |
| `maxConcurrentConversions` | 1 | 同时导入任务数，最大 2 |
| `rejectAllUploads` | `false` | 一键拒绝所有上传 |
| `allowAutoUpload` | `true` | 允许客户端自动上传 |

### `[review]`

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `uploadMode` | `review` | review / auto / full |
| `autoApproveMaxBytes` | 16 MB | auto / full 直接放行的阈值，0 = 不限 |
| `maxPendingCount` | 8 | 待审队列总容量 |
| `maxPendingPerPlayer` | 8 | 每人待审上限，0 = 不限 |
| `pendingTtlHours` | 72 | 待审保留小时数 |
| `autoSync` | `true` | 落盘后自动触发 YSM 重扫 |
| `autoSyncMinIntervalSeconds` | 15 | 重扫去抖间隔 |
| `autoUploadScanSeconds` | 0 | 客户端扫描：0 监听，>0 轮询秒数 |
| `listMaxEntries` | 200 | 列表单次最多条数 |
| `uploadWhitelist` | 空 | 白名单 UUID：免审、免配额、免等级门槛 |
| `blockedUploaders` | 空 | 禁止上传的玩家 UUID |
| `approveGroup` | 空 | 审批组 UUID（2 级） |
| `manageGroup` | 空 | 管理组 UUID（2 级） |

### `[permissions]`

每一项配置「至少需要几级才能改」，默认全部为 3；键名与原配置项相同，值是 1–3。

### 客户端

`config/ysm-relay/client.properties`：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `autoUploadFolder` | 空 | 自动上传监听的目录 |
| `manualUploadFolder` | 空 | 手动上传面板默认目录 |
| `skipDeleteConfirm` | `false` | 删除时跳过确认框 |

## 常见问题

- **点入口提示「功能不可用」**：服务端没装 relay。
- **上传成功但列表没有**：先在「我的待审」里找，再运行 `/ysmrelay paths` 确认上传根。
- **删除报文件被占用**：Windows 上 YSM 可能还握着句柄，重启服务端后再删。
- **删除后模型又回来了**：目录里还有同名副本，或用「拉黑」禁止再次上传。
- **模型名是一串十六进制**：旧版本的行为，1.2.7 起已保留原始文件名。

## 文档

- [服主管理手册](docs/ADMIN.md)
- [玩家使用说明](docs/USER-GUIDE.md)
- [开发与构建](docs/DEVELOP.md)
- [YSM 3.0 接入点调研](docs/YSM-INTEGRATION.md)
