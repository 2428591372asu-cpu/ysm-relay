# 开发与构建

## 环境

- JDK 17
- 仓库自带的 Gradle wrapper（`./gradlew` / `gradlew.bat`）
- 首次构建需要联网拉 Forge / NeoGradle 依赖；之后可加 `--offline`

## 克隆即构建

仓库的 `libs/ysm-3.0-dev-compileonly.jar` 是从 YSM 3.0 编译产物打出的 `compileOnly` 依赖（Apache-2.0，见 `libs/README.md`），所以**不需要准备 YSM 源码**：

```bash
git clone https://github.com/2428591372asu-cpu/ysm-relay.git
cd ysm-relay
./gradlew build          # Windows: .\gradlew.bat build
```

产物：`build/libs/ysm_relay-<version>.jar`。

## 自检

```bash
./gradlew verification
```

零依赖自检，不加载 Forge / native。输出 `cases=N failures=M`，退出码 0 表示全过。
被测的是 `ServerUpload`：分片顺序、重放拦截、超量拒收、空包拒收、封口条件。

## 使用自己改过的 YSM

默认只读仓库里那份 dev jar。要跟随本地 YSM 源码：

```bash
# 1. 先在 YSM 主仓编译出 class
cd ../ysm && ./gradlew compileJava

# 2. 回到 relay，删掉仓库自带的那份，再重新生成
cd ../relay
rm libs/ysm-3.0-dev-compileonly.jar        # Windows: Remove-Item libs\ysm-3.0-dev-compileonly.jar
./gradlew ysmDevJar
./gradlew build
```

`YSM_PROJECT_DIR` 可指向任意已编译的 YSM 目录；不设时默认 `../ysm`。

## 开发运行

```bash
./gradlew runClient
./gradlew runServer
```

运行目录在 `run/client`、`run/server`。YSM 本体需要自己放进对应的 `mods/`。

## 项目结构

```
build.gradle            构建、YSM compileOnly、自检任务
gradle.properties       版本号与 mod 元数据
libs/                   仓库自带的 YSM dev jar（只用于编译）
artifacts/legacy/       0.2.0 的历史产物，仅存档
docs/
  ADMIN.md              服主手册（命令、权限、删除行为）
  USER-GUIDE.md         玩家使用说明
  DEVELOP.md            本文件
  YSM-INTEGRATION.md    YSM 3.0 接入点调研
  archive/              早期计划文档
src/main/java/com/asu/ysmrelay/
  client/               上传面板、设置页、模型管理页、列表页、自动上传
  command/              /ysmrelay 与 /relayme
  config/               配置定义、同步、权限、服务端列表
  net/                  通道 ysm_relay:upload 与消息体
  upload/               探测、接收、落盘、待审队列、台账、路径
  admin/                授权矩阵、模型搬迁与删除
  ysm/                  与 YSM 的唯一接缝
src/main/resources/     mods.toml、语言文件、pack.mcmeta
src/verification/java/  零依赖自检
```

## 发版

1. 改 `gradle.properties` 里的 `mod_version`
2. `./gradlew build verification`
3. 提交并推送，打 tag `v<version>`
4. 把 `build/libs/ysm_relay-<version>.jar` 传到对应的 GitHub Release

## 许可证

YSM（Yes Steve Model）以 Apache License 2.0 发布。`libs/ysm-3.0-dev-compileonly.jar` 是它编译产物的再分发，仅用于编译期类型检查，不会打进本模组的 jar；运行时由玩家安装的 YSM 提供这些类。
