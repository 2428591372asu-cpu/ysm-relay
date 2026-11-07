# libs

## ysm-3.0-dev-compileonly.jar

- **用途**：本模组编译期需要的 YSM 3.0 类型，只在 `compileOnly` 里使用，不会打进 `ysm_relay-*.jar`。
- **来源**：从 YSM 主仓的 `build/classes/java/main` 打包而成，供「克隆即可构建」使用。
- **许可证**：YSM（Yes Steve Model）以 **Apache License 2.0** 发布；本文件是其编译产物的再分发，遵循同一许可证。
- **更新方式**：如果 YSM 升级了 API，设置 `YSM_PROJECT_DIR` 指向新编译的 YSM，运行 `./gradlew ysmDevJar` 重新生成，并提交更新后的这份 jar。

运行时由玩家实际安装的 YSM 模组提供这些类，请勿把本 jar 放进 `mods/`。
