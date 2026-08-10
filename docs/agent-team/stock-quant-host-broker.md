# STOCK-QUANT-HOST-BROKER：本机宿主执行代理

## 目的与身份边界

Codex 的 Windows restricted-token sandbox 使用独立 `CodexSandbox` 身份，不能访问真实用户的
Windows Credential Manager。Broker 不再尝试改变该隔离：Codex 只生成非敏感请求、触发固定
计划任务并读取脱敏结果；固定任务以安装它的真实 Windows 用户、`Interactive/Limited` 方式按需
运行，从而由既有 Java `WindowsCredentialManagerSecretProvider` 在正式 Runner 内读取两个固定
Target：

```text
StockQuant/ResearchDbPassword
StockQuant/TushareToken
```

计划任务名称固定为 `StockQuantLocalBroker`。任务没有 trigger，不在开机、登录或定时条件下自动
运行；唯一触发命令为：

```powershell
schtasks.exe /Run /TN "StockQuantLocalBroker"
```

任务 action 只执行仓库中的固定
`quant-server/scripts/host-broker/stock-quant-host-broker.ps1`，不接受命令文本、动态脚本路径、
Credential Target、密码或 Token 参数。安装器不调用、不查找也不安装 `codex` CLI；任务以执行安装
的当前真实 Windows 用户为唯一 principal，并使用 Windows 任务计划程序的所有者/管理员默认安全
边界，不向 Authenticated Users、Everyone 或 SYSTEM 扩权。

## 一次安装与卸载

必须在真实用户的管理员 Windows PowerShell 中执行一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\install-stock-quant-host-broker.ps1
```

脚本显示任务名、固定脚本路径、真实账户、管理员状态、`Interactive/Limited`、trigger 数 `0` 和
两个凭据的整体存在状态，然后要求确认。它不读取 CredentialBlob、不保存账户密码、不调用 Provider，
也不要求 `codex` CLI 位于 PATH。`-WhatIf` 可在不创建或修改计划任务的前提下完成只读预检；真实
安装或卸载必须通过管理员检查。更新仍需真实用户重新运行该安装命令并确认。

安装后的无 Provider 自检命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\test-stock-quant-host-broker-host-smoke.ps1 -ExpectedCommit <当前完整Git SHA>
```

卸载命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\install-stock-quant-host-broker.ps1 -Uninstall
```

卸载仅删除固定任务，不删除 Credential。

## 严格请求与结果协议

请求只能原子写入：

```text
quant-server/target/stock-quant-host-broker/requests
```

结果只能原子写入：

```text
quant-server/target/stock-quant-host-broker/results
```

请求采用 UTF-8、无 BOM、严格逐行 `key=value` 的
`STOCK_QUANT_HOST_BROKER_REQUEST_V1`。重复、未知、缺失、空字段及注释行全部拒绝。固定字段包含
requestId、白名单 operation、完整 Git SHA、JAR 绝对路径/SHA-256、授权绝对路径、专用数据库、
三 Endpoint 顺序与各一次预算、总请求 3、零重试、`NEVER` redirects、创建/过期时间、固定
executionSource、`no.retry=true` 与只读来源 requestId。请求不得包含密码、Token、摘要、JDBC
秘密、命令或脚本内容。

operation 只允许：

- `CHECK_CREDENTIAL_STATUS`：只返回两个固定 Target 是否整体 ready，不编组内容；
- `RUN_FAKE_E2E`：固定打包 Fake Provider/临时 PostgreSQL E2E，禁止读取真实 Credential；
- `RUN_DAY001`：要求未消费、未过期、`USER_APPROVED` 的正式 Day001 授权；
- `READ_SANITIZED_RESULT`：只读 Broker 已生成的 V1 脱敏结果。

Broker 通过同目录原子重命名
`.request.properties → .processing.properties → .processed.properties` 领取一个请求。崩溃后留下的
processing 文件不会自动重试；requestId 只要在请求或结果目录出现过即不可复用。结果为严格生成的
`STOCK_QUANT_HOST_BROKER_RESULT_V1` JSON，只包含状态、阶段、安全 reason、调用/重试计数、时间和
白名单运行摘要。

## Codex 后续固定入口

完成正式构建和用户授权后，Codex 在 `stock_quant_formal_runner` profile 中运行：

```powershell
codex sandbox -P stock_quant_formal_runner -C . powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\quant-server\scripts\host-broker\invoke-stock-quant-host-broker.ps1 `
  -Operation RUN_DAY001 `
  -AuthorizationFile <fresh-user-approved-authorization.properties> `
  -ArtifactPath <verified-day001-runner.jar>
```

invoke 脚本自行生成全新 requestId 和十分钟请求窗口、验证本地/远程集成 SHA 与固定任务定义、写入
请求、执行唯一固定 `schtasks /Run` 并轮询结果。真实失败不触发第二次任务、不生成第二个 requestId、
不补跑、不重试、不重新签发授权。

## 不变边界

- `stock_quant_formal_runner` 不再拥有 `api.tushare.pro` 直连权限；真实网络只发生在固定宿主任务。
- Broker 不注册 Spring Bean、Controller、Runner 或 scheduler，不新增 Flyway/表。
- `RUN_DAY001` 仍固定 `600000/SSE/2025-01-03` 的授权合同、三调用、零重试与专用 38432 数据库。
- 不启动 F2B、F3、scheduler、Agent、Shadow、回测或交易；Day001 成功也不自动开放 F2B。
- 七项治理状态与 F1F-B2 `PASSED` 证据不因 Broker 改变。
