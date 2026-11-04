# ysm-relay 服主管理面

面向开服的人。所有命令需要权限等级 2（与 YSM 自带的 `/ysm` 一致）。

## 一句话结论：控制粒度由「文件放在哪个目录根」决定，不由开关决定

YSM 3.0 的选中判定只有一条：

```
canSelect(modelId) = record != null && (access == PUBLIC || grants.contains(modelId))
access = (模型所在根 == auth) ? AUTHORIZED : PUBLIC
```

所以：

| 模型放在 | 玩家能不能选 | relay 的授权矩阵管不管用 |
|---|---|---|
| `custom` 根（public） | 谁都能选 | **不管用**，YSM 不看 grant |
| `auth` 根（authorized） | 只有拿到 grant 的玩家 | 管用 |

客户端也按同一规则过滤：`auth` 根的模型在没有 grant 时不会出现在模型选择页。

**因此：想让服主逐个玩家控制模型，模型必须落在 auth 根。**
上传落点由 `config/ysm-relay-common.toml` 的 `upload.targetRoot` 决定，默认 `custom`。
要开逐玩家管理，改成 `auth`：

```toml
[upload]
    targetRoot = "auth"
```

默认 `custom` 是为了「上传即所有人可见」这个最常见诉求；改成 `auth` 之后新上传的模型默认谁都不能选，
需要服主用下面的命令授权。

## 三种策略

`/ysmrelay policy <模型> <策略>`：

| 策略 | 含义 | 模型应在 |
|---|---|---|
| `public` | 人人可用 | custom 根 |
| `listed <玩家...>` | 只有名单上的玩家可用 | auth 根 |
| `except <玩家...>` | 除名单上的玩家以外人人可用 | auth 根 |

YSM 没有「某玩家不能用某模型」的黑名单判定点，`except` 是唯一不改 YSM 的实现方式：
模型放 auth 根，然后给除名单外的所有玩家发 grant。

「某模型仅给某玩家使用」= `policy <模型> listed <该玩家>`。

## 命令

```
/ysmrelay status                        YSM 版本、模型数、策略数、禁止上传数、当前上传落点
/ysmrelay list [页码]                    列出所有策略（每页 8 条）
/ysmrelay show <玩家>                    该玩家当前持有的授权 + 策略是否与运行时一致
/ysmrelay policy <模型> public           设为人人可用
/ysmrelay policy <模型> listed <玩家...>  只给这些玩家
/ysmrelay policy <模型> except <玩家...>  除这些玩家以外所有人
/ysmrelay policy <模型> clear            删除策略，回到 YSM 默认语义
/ysmrelay grant <玩家> <模型>            往名单里加一个人（策略必须是 listed/except）
/ysmrelay revoke <玩家> <模型>           从名单里移除一个人
/ysmrelay move <模型> auth|custom        在 auth / custom 根之间搬文件（搬完自动重扫）
/ysmrelay delete <模型> confirm          删除服务端的模型文件，并加入禁止上传名单
/ysmrelay block <模型>                   只禁止再次上传，不删已有文件
/ysmrelay unblock <64位modelId>           取消禁止（被删的模型已不在目录里，只能给完整哈希）
/ysmrelay refresh [玩家]                 按矩阵重推授权
```

模型参数三种写法都行：

- 完整 64 位十六进制 modelId
- 8 位以上的十六进制前缀（唯一匹配才生效）
- YSM 的公开地址，例如 `local:auth/xxx.mxc`

## 删除玩家上传的模型

`/ysmrelay delete <模型> confirm`

按这个顺序做四件事，任何一步失败都会如实报出来：

1. 撤销所有在线玩家对该模型的 grant（先撤授权，再删文件；反过来会让玩家短暂持有一个已消失模型的授权）
2. 从授权矩阵里删掉这条策略
3. 删掉磁盘上的容器文件，然后重扫目录
4. 把 modelId 写进「禁止上传名单」

第 4 步是必须的：不记禁止名单的话，上传通道下一次探测就会把这个 modelId 又收回来，删了等于没删。
`blocked` 列表和授权矩阵存在同一个 `authz.json` 里。

`/ysmrelay block <模型>` 只做第 4 步，用于「文件留着但别再传」的场景。

**删除也可能失败**：YSM 的容器对象可能还开着文件句柄，Windows 上此时 `Files.delete` 报错。
失败会保留策略和禁止名单的现状，提示「可先重启服务端再删」，不会假装删成功。

## 数据放哪

| 文件 | 内容 |
|---|---|
| `config/ysm-relay-common.toml` | 上传总开关、单模型上限、分片大小、落点根 |
| `config/ysm-relay/authz.json` | 授权矩阵（模型 modelId → 策略 + 名单） |

玩家自己的 grant 集合是 YSM 的 `AuthModelsCapability`，存在玩家 NBT 里。
两边都要在：`authz.json` 是服主的意图，capability 是运行时事实。玩家登录时用矩阵覆盖事实。

`authz.json` 坏了不会让服务器起不来：读失败按空矩阵继续，只打日志。

## 与 YSM 自带 `/ysm auth` 的关系

不是替代，是同一套能力的两个入口：

- `/ysm auth <玩家> add|remove|all|clear <模型>` 直接改 capability，一次性。
- `/ysmrelay policy ...` 写持久策略，登录时自动重放，还能表达 `except`（YSM 指令做不到）。

`/ysmrelay` 只回收**自己矩阵里登记过**的模型授权。用 `/ysm auth` 手工加的授权不会被冲掉。

## 已知限制

1. **搬文件可能失败**。YSM 的容器对象可能还开着文件句柄，Windows 上此时 `Files.move` 报错。
   失败会如实上报，不会假装成功。稳定做法是上传时就选好根，而不是事后搬。
2. **`public` 策略 + auth 根的模型**需要给所有人发 grant 才有意义；`/ysmrelay list` 会把每条的
   实际根打出来，方便发现这类不一致。
3. **规模**。`applyAll` 是 O(玩家 × 策略)，每次策略变更和每次玩家登录都会跑一次。
   策略几百条、在线几十人的量级没问题；上千条策略时需要在登录时只算与该玩家相关的条目。
4. **单人存档**里客户端还有一个同名 `/ysmrelay status`（调试上传进度用），会遮蔽服务端的同名子命令。
   服务端管理命令的其它子命令不受影响。
