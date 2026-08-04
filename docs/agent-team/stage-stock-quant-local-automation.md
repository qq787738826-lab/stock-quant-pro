# STOCK-QUANT-LOCAL-AUTOMATION：Windows 秘密托管与 Codex 全自动执行

## 阶段结果

本阶段在 `3A-R3B-RR-DAY001` 之上增加本地秘密托管和统一人工自动入口，不改变 Day001
授权、三 Endpoint 预算、零重试、专用事务、回读、QFQ 或输出审计语义，也不复用
F1F-B2 验收状态机。正式默认秘密源为 Windows Credential Manager，Console 仅为显式
应急模式；不存在环境变量、命令参数或明文文件降级。

新增 `SecretProvider`、`ConsoleSecretProvider`、
`WindowsCredentialManagerSecretProvider` 与 `CompositeSecretProvider`。Windows 实现
通过 JNA 直连 `CredReadW/CredFree`，仅允许 `StockQuant/ResearchDbPassword` 和
`StockQuant/TushareToken`，所有 Java/native 临时值均尽快清零。TEST/E2E 沿用合成秘密
和 Fake Provider，不能读取真实 Credential。

一次配置和后续自动入口分别为：

```text
quant-server/scripts/set-stock-quant-secrets.ps1
quant-server/scripts/run-stock-quant-local-automation.ps1
```

Day001 构建证明新增专用 Runner profile，MANIFEST 的 Start-Class 必须精确为
`TushareReducedResearchManualRunner`；授权解析同时校验该 Start-Class，不能回退到
F1F-B2 Runner 或普通 Spring Boot 入口。统一脚本执行正式解析 preflight、Git/构建/
PostgreSQL/凭据存在性检查、一次 Runner 启动和脱敏结果只读确认。

## 不变边界

- 不新增 Flyway，不修改数据库结构或永久数据库。
- 本阶段真实 Provider 调用为 0，不读取真实 Token 或数据库密码。
- 不执行真实 Day001，不生成或消费正式授权，不创建真实 runId。
- 不启动 F2B、F3、scheduler、Agent、Shadow、回测或交易。
- F1F-B2 `PASSED` 与七项治理状态保持不变；Day001 成功仍不自动开放 F2B。

完整操作合同见 [本地自动执行说明](stock-quant-local-automation.md)。
