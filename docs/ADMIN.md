# ysm-relay 服主管理面

面向开服的人。命令与设置页都需要相应权限等级。

## 一句话：模型能不能被选，取决于它放在哪个根

YSM 3.0 的判定只有一条：

```
canSelect(modelId) = record != null && (access == PUBLIC || grants.contains(modelId))
access = (模型所在根 == auth) ? AUTHORIZED : PUBLIC
```

| 模型放在 | 玩家能不能选 | relay 的授权矩阵管不管用 |
| --- | --- | --- |
| `custom`（public） | 谁都能选 | 不管用，YSM 不看 grant |
| `auth`（authorized） | 只有拿到 grant 的玩家 | 管用 |

落点由 `targetRoot` 决定，默认 `custom`。要逐玩家管理就改成 `auth`，之后新上传的模型默认谁都不能选，需要用 `policy` 授权。

## 权限等级

| 等级 | 谁能达到 | 能做什么 |
| --- | --- | --- |
| 0 | 被显式授予 0 级 | 禁止上传/审批/管理他人模型；可进功能页，只能管理自己的上传 |
| 1 | 所有玩家 | 上传；管理自己上传的模型（`/relayme`） |
| 2 | op、两个组的组员、被授予 | 审批 + 模型管理 + 名单管理；**不能分发等级** |
| 3 | 服主授予 | 与服主同权：2 级全部 + 分发等级 + 改所有配置 |
| 服主 | 控制台 / LAN 房主 | 与 3 级同权；身份来自主机/控制台，不占名单 |

分发：`/ysmrelay level grant <玩家> <0-3>` / `revoke <uuid>` / `list`。0 = 禁用，3 级玩家只能授予不高于自己的等级。

3 级可以给任何人授 0 级，包括另一个 3 级与房主。

0 级会拦住上传、审批、管理他人模型与配置写入；功能页仍可打开，顶部显示红色提示，除「我的上传」外所有按钮置灰，`/relayme list|delete|cancel` 仍可用。唯一的例外是 **LAN 房主的命令面**：房主被授 0 级后仍可用 `/ysmrelay`（房主身份优先）。这是刻意保留的管理员语义：原版默认 `op-permission-level=4`，op 之间本来就能互相 `/deop`，不额外收紧。

等级每次现算（组、op、手动授予取最高），服务端不缓存。

客户端进服时拿一份 `ConfigSnapshot`（只存内存、掉线即清），点击入口时用它做即时判断：0 级进入功能页，除「我的上传」外全部置灰并显示顶部红色提示；等级不够的其他入口置灰。真正的动作——上传探测、分片、命令、列表——服务端都会再判一次，改客户端缓存绕不过去。等级授予/撤销后服务端会立刻推送新快照。

## 配置文件

`<存档目录>/serverconfig/ysm-relay-server.toml`（Forge 的 SERVER 配置不在 `config/` 下）。首次启动生成，改完重启生效。键表见 [README](../README.md#配置)。

## 命令

### 总览与诊断

```
/ysmrelay status                  YSM 版本、模型数、策略数、禁止上传数、落点
/ysmrelay paths                   打印模型根 / 暂存根 / 待审根与实际文件数
/ysmrelay refresh [玩家]          按矩阵重推授权
```

`paths` 是最常用的排障入口：上传“成功”但看不到时，先确认它打出来的上传根是不是你以为的那个目录。

### 待审队列

```
/ysmrelay pending [页码]          按时间倒序列出待审条目
/ysmrelay approve <编号>          批准：文件进模型根并重扫
/ysmrelay reject <编号>           拒绝：删掉内容并通知上传者
```

模式切到 `auto` 或 `full` 时，队列里已有的条目会按新规则自动处理一遍：未超上限直接批准；`full` 档超上限直接拒绝；`auto` 档超上限保留待人工。处理结果会在动作栏显示。

### 授权矩阵

```
/ysmrelay policy <模型> public            人人可用
/ysmrelay policy <模型> listed <玩家...>   只给名单上的人
/ysmrelay policy <模型> except <玩家...>   除名单外所有人
/ysmrelay policy <模型> clear             删除策略
/ysmrelay grant <玩家> <模型>             往名单加人（策略须为 listed/except）
/ysmrelay revoke <玩家> <模型>            从名单移除
/ysmrelay list [页码] / show <玩家>        查看策略与运行时一致性
```

YSM 没有「禁止某人用某模型」的判定点，`except` 是唯一不改 YSM 的实现：模型放 `auth` 根，再给除名单外的所有玩家发 grant。

### 模型文件

```
/ysmrelay move <模型> auth|custom         在两个根之间搬文件（搬完自动重扫）
/ysmrelay delete <模型> confirm           只删除内容
/ysmrelay deleteblock <模型> confirm      删除并加入禁止上传名单
/ysmrelay block <模型> / unblock <名字或哈希>  只禁止/允许再次上传
/ysmrelay blocked [页码]                  查看禁止名单（名字 + 哈希前缀 + 上传者）
```

模型参数三种写法：完整 64 位哈希、8 位以上唯一前缀、`global:custom/xxx.mxc` 这类地址。

### 名单

```
/ysmrelay blockuploader add|remove|list <玩家>   禁止这些玩家上传
/ysmrelay whitelist add|remove|list <玩家>       白名单：免审、跳过配额与等级限制
/ysmrelay level grant|revoke|list <玩家> [0-3]  临时授予等级（0 = 禁用）
```

## 删除一个模型会发生什么

`delete` 只删内容，`deleteblock` 额外把模型写进禁止上传名单。两者的删除部分顺序相同：

1. 撤销所有在线玩家对该模型的 grant
2. 从授权矩阵删掉这条策略
3. 删除 YSM 的转换缓存目录 `cache/converted/…/objects/<模型标识>/`，以及 `custom`/`auth` 里同名源文件
4. 重扫目录
5. 仅 `deleteblock`：把模型写进禁止上传名单（只做第 5 步、不删文件的是 `/ysmrelay block`）

为什么缓存和源文件都要删：YSM 导入源文件后，目录真正引用的是缓存里的容器。只删源文件，模型还在缓存里；只删缓存，下次重扫会从源文件再导一次。

**失败是可能的**：Windows 上 YSM 可能仍持有句柄，此时会明确报“删除缓存失败…可先重启服务端再删”，不会假装成功。

**副本会复活**：如果 `custom` 里还有同一模型另一个文件名的副本，删除后重扫会把它重新导入。这种情况要么手动删副本，要么用 `block` 限制再次上传（禁止名单只拦上传通道，不拦 YSM 扫描已有文件）。

## 界面：模型管理

设置页「模型管理」块是命令的可视化入口，权限要求与 /ysmrelay delete 相同（等级 2）：

- 分组：服主本地模型在最前，之后按上传者名字 A–Z
- 删除：每行两个按钮。「删除」只删内容，玩家可以再传回来；「拉黑」= 删除 + 加入禁止上传名单，两者都弹确认框
- 拉黑名单：切到该分页列出被禁传的模型（名字 + 哈希前缀 + 上传者），可直接「解除」；拉黑时会把模型名一并记进 authz.json
- 只列出目录里真实存在文件支撑的模型，内置模型不出现

大规模目录（几百个模型）时列表会截断到 120 行，并显示「…还有 N 个」；用命令处理更省事。

## 数据文件

| 文件 | 内容 |
| --- | --- |
| `config/ysm-relay/authz.json` | 授权矩阵（模型 → 策略 + 名单） |
| `config/ysm-relay/uploads.json` | 上传台账（谁传了什么、多大、何时） |
| `config/ysm-relay/pending.json` | 待审记录 |
| `relay-staging/` | 传输残片，TTL 600 秒 |
| `relay-pending/<编号>/` | 待审内容 |

台账跟着**真正跑服务端的实例**走。单机开 LAN 时，服务端就是房主那台客户端，台账在房主的 `config/` 下；换一个实例开服，看到的是另一份台账。

## 与 YSM 自带 `/ysm auth` 的关系

不是替代，是同一套能力的两个入口：

- `/ysm auth` 直接改玩家 capability，一次性；
- `policy` 写持久策略，登录时自动重放，还能表达 `except`（YSM 指令做不到）。

relay 只回收自己矩阵里登记过的模型授权，手工加的不会被冲掉。

## 已知限制

1. 搬文件可能失败（句柄占用），稳定做法是上传时就选好根。
2. `public` 策略配 `auth` 根的模型需要给所有人发 grant；`/ysmrelay list` 会打出每条的实际根，便于发现不一致。
3. `applyAll` 是 O(玩家 × 策略)，策略上千条时登录会变慢。
4. 名单里存 UUID；玩家改名不影响。
