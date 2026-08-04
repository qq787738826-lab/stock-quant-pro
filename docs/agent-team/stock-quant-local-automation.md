# STOCK-QUANT-LOCAL-AUTOMATION：Windows 秘密托管与 Codex 全自动执行

## 一次配置

用户只需在原生 Windows Console 中执行一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\set-stock-quant-secrets.ps1
```

脚本以 `Read-Host -AsSecureString` 读取专用研究库密码和 Tushare Token，并把 Generic
Credential 写入当前 Windows 用户的 Credential Manager：

```text
StockQuant/ResearchDbPassword
StockQuant/TushareToken
```

脚本没有秘密参数，不使用环境变量或文件，不输出原文、长度、摘要、前后缀或编码值。
凭据已存在时必须键入 `OVERWRITE` 才能更新。`-Status` 只调用 `CredReadW` 判断两个 Target
是否存在并立即 `CredFree`，不编组或显示 CredentialBlob。

## 自动运行入口

后续由本地 Codex 运行：

```powershell
.\quant-server\scripts\run-stock-quant-local-automation.ps1 `
  -AuthorizationFile <fresh-user-approved-day001.properties> `
  -ResultFile <new-redacted-result.json> `
  -ArtifactPath <verified-day001-runner.jar>
```

构建、测试和诊断使用默认 `stock_quant_local` 权限；只有上述正式入口由 Codex 以
`stock_quant_formal_runner` 权限运行（本地执行器等价于
`codex sandbox -P stock_quant_formal_runner -C . powershell ...`）。用户无需切换权限或
手工执行该命令。

统一入口依次校验 Day001 MANIFEST/Start-Class、正式授权与相邻 build proof、完整 Git SHA、
本地/远程集成分支 `0/0`、已跟踪工作区、`127.0.0.1:38432` 监听和两个 Credential Target
存在性；授权、JAR、sidecar 与结果路径都必须位于当前仓库（通常置于 `quant-server/target`），
然后才启动 Day001 Runner。Runner 默认显式模式为
`WINDOWS_CREDENTIAL_MANAGER`；只有人工应急时才能传 `-SecretMode CONSOLE`，且绝不自动
降级。入口最终只读解析脱敏结果，输出明确失败阶段和 reason。

## 秘密与进程生命周期

- Java `SecretProvider` 只接受两个枚举 Target；Windows 实现通过 `CredReadW/CredFree`
  读取当前用户 Generic Credential，并在释放前清零返回的 native blob。
- Java 值使用可清零 `char[]`；创建数据源和 Gateway 后立即清零临时副本，运行结束关闭
  Provider 客户端和数据源。任何 `toString`、异常、JSON、日志与审计证据都不含原文。
- 输出审计在正式秘密读取前安装。Credential 缺失、Target/运行环境/授权/构建/数据库
  不匹配均在 Provider 前拒绝。
- E2E 使用合成数据库密码和 Fake Provider；TEST/E2E 禁止实例化真实 Credential Manager
  Provider。非 Windows、CI 或 Codex Cloud 正式运行固定拒绝。

## Codex 自动验证门

真实调用前，Codex 必须自行完成并在失败时最小修复、重跑：Java clean compile、秘密与
Day001 定向回归、临时 PostgreSQL V1—V14、三事实事务与回滚、typed fact、
SYSTEM_KNOWLEDGE、QFQ 19 项、输出审计、最终打包 Day001 JAR、Fake Provider 打包 E2E、
PowerShell AST 和 `git diff --check`。Fake E2E 必须证明调用精确 `3`、重试 `0`、无
`RUNNING` 悬挂、临时端口和目录残留 `0`。这些步骤不得读取真实 Credential、访问
`38432` 或调用 Provider。

正式运行仍只接受一个明确用户批准的 runId、证券、日期、三个 Endpoint 与请求预算；
失败立即停止，不重试、不补跑、不复用 runId，也不自动签发第二份授权。

## 仓库权限

`.codex/config.toml` 使用项目级 `stock_quant_local` 权限配置，不关闭沙箱；仓库外不可写，
`.ai/` 和 `.env*` 拒绝访问。默认网络只列出 loopback 与当前 Git/Maven 构建所需官方
域名；仅 `stock_quant_formal_runner` 增加 `api.tushare.pro`，且按 `AGENTS.md` 只能运行
正式统一入口。正式秘密和运行不得上传到云端任务。
