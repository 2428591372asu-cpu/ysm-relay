# 构建产物

这里放本模组自己的发布 jar，方便直接下载。上游 YSM 的 jar 与 native 库**不**放在本仓库。

| 文件 | 版本 | 字节 | SHA256 |
| --- | --- | --- | --- |
| `ysm_relay-0.2.0.jar` | 0.2.0 | 113,171 | `D867C2C42F3C07EAAB873FFC93EEE6CB842A019C11672F0B66F168FB7AA96AC4` |

## v0.2 的口径

- 编译通过、22 条零依赖自检通过、YSM 侧真机验证（native 加载、适配层版本判定、上传通道 v2 注册）。
- **上传 → 落盘 → 分发全链路还没有在生产服务器上跑过。** dev 环境因 SRG/named 命名空间差异无法验证业务逻辑。
- 这一版没有审批门，上传直接落盘 + reload。

## 怎么自己编

```powershell
.\gradlew.bat compileJava --offline
.\gradlew.bat verification --offline
.\gradlew.bat build                 # 需要先提升 mod_version
```

新版 jar 会出现在 `build/libs/`。要放进本目录，请同时更新上表的字节数与 SHA256。
