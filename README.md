# ysm-relay

YSM（Yes Steve Model）3.0 的附属模组。补上 YSM 原本缺失的「客户端 → 服务端」上传半段：
玩家在游戏里上传模型，服务端落盘并触发 YSM 重扫，之后由 YSM 自己的分发链路发给所有人。

**本模组不实现任何分发逻辑。** 服务端 → 客户端的分发、缓存、按需取块全部复用 YSM 现成能力。

## 环境

| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.3 |
| Java | 17 |
| 构建插件 | `net.neoforged.moddev.legacyforge` |
| 运行时依赖 | YSM 3.x（服务端与客户端都需要） |

## 构建

编译期需要 YSM 主仓的编译产物。主仓默认在 `../ysm`，可用环境变量 `YSM_PROJECT_DIR` 覆盖。

```powershell
.\gradlew.bat compileJava --offline       # 只编译，不联网、不需要 native
.\gradlew.bat verification --offline      # 零依赖自检：cases=N failures=M
.\gradlew.bat ysmDevJar                   # 把 ../ysm/build/classes 打成本地 compileOnly jar
```

> **v0.2 已封版，不要直接跑 `build`。** `mod_version` 仍是 `0.2.0`，再 build 会用同名文件覆盖
> `build/libs/ysm_relay-0.2.0.jar`，与交付副本失去对应关系。要出新包先把版本提上去。

## 文档

- [实施方案](docs/PLAN.md)：范围、状态机、验收场景、裁定记录（唯一权威）
- [服主管理](docs/ADMIN.md)：命令与授权模型
- [使用说明](docs/使用说明.md)：玩家与服主视角
- [YSM 3.0 接入点调研](docs/02-YSM3.0接入点调研.md)：上游能力与协议调研

## 目录

```
src/main/java/com/asu/ysmrelay/   生产代码
src/main/resources/               mods.toml 与语言文件
src/verification/java/            零依赖自检（不依赖 Forge 与 native）
docs/                             方案与说明
```

## 当前状态

v0.2：编译通过、22 条自检通过、YSM 侧真机验证（native 加载、适配层版本判定、上传通道 v2 注册）。
**上传 → 落盘 → 分发全链路尚未在生产服务器验证**，dev 环境因 SRG/named 命名空间差异无法验证业务逻辑。
