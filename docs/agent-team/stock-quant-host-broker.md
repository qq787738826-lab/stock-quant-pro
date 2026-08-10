# STOCK-QUANT-HOST-BROKER：本机宿主执行代理

## 目的与身份边界

Codex 的 Windows restricted-token sandbox 使用独立 `CodexSandbox` 身份，不能访问真实用户的
Windows Credential Manager。Broker 不再尝试改变该隔离：Codex 只生成非敏感请求并读取脱敏
结果，不查询、不触发也不修改 Task Scheduler。固定任务以安装它的真实 Windows 用户、
`Interactive/Limited` 方式在该用户登录及每分钟 watchdog 边界尝试启动常驻 Broker，从而由既有 Java
`WindowsCredentialManagerSecretProvider` 仅在合法正式请求中读取两个固定
Target：

```text
StockQuant/ResearchDbPassword
StockQuant/TushareToken
```

计划任务名称固定为 `StockQuantLocalBroker`，包含绑定当前用户的登录 trigger 和一个无结束边界的
`PT1M` watchdog TimeTrigger。两个 trigger 都只启动固定 Broker 监听进程；
`MultipleInstancesPolicy=IgnoreNew` 保证健康 Broker 不产生第二实例：`BROKER_AUTOSTART=true`，
但 trigger 本身不创建或运行 Provider 请求，
`PROVIDER_AUTOSTART=false`。Broker 无请求时只更新脱敏 heartbeat、检查固定 request 目录并以
一秒间隔休眠；不会读取 Credential Manager、连接数据库、创建 HTTP 客户端或访问 Tushare。

旧登录单 trigger 正式任务在 Broker 退出后的实际状态为 `Ready`、`LastTaskResult=0xC000013A`、
`NextRunTime=NONE`。有限 `RestartCount` 不是永久监督器；没有新的登录或时间触发事件时不会再次启动。
watchdog TimeTrigger 仅补足该生命周期事件，不自动创建 request，因此不等于 Provider 自动运行。

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

脚本显示任务名、固定脚本路径、真实账户、管理员状态、`Interactive/Limited`、当前用户登录与
每分钟 watchdog trigger 总数 `2` 和
两个凭据的整体存在状态，然后要求确认。它不读取 CredentialBlob、不保存账户密码、不调用 Provider，
也不要求 `codex` CLI 位于 PATH。`-WhatIf` 可在不创建或修改计划任务的前提下完成只读预检；真实
安装或卸载必须通过管理员检查。更新仍需真实用户重新运行该安装命令并确认。

Windows Task Scheduler 注册后可能把 `计算机名\用户名` 规范化为裸用户名，并在 XML 中使用 SID；
安装器因此按 SID 比较 principal，同时把 `Interactive`/`InteractiveToken`、`Limited`/
`LeastPrivilege` 和路径/参数的安全等价形式归一化。action、固定 Broker 路径、working directory、
单一登录 trigger、单一 `PT1M` 无限重复 TimeTrigger、`StopAtDurationEnd=false`、`IgnoreNew`、
无限监听时限、有限重启、`AllowDemandStart=true`、`StartWhenAvailable=false` 等
边界仍逐项严格验证，
每项失败返回独立脱敏 reason，不再折叠为 `TASK_DEFINITION_INVALID`。

安装前可运行真实 Task Scheduler round-trip。它只注册一个唯一临时任务，绝不执行该任务，并在同次
运行精确删除；测试覆盖注册/Get/Export/XML 恢复、中文用户名规范化、新建失败清理和已有定义恢复：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\test-stock-quant-host-broker-install-roundtrip.ps1
```

升级前的真实生命周期验证使用另一个唯一临时任务及隔离 heartbeat 目录。测试先启动一次临时 Broker，
跨越周期边界验证 `IgnoreNew`，随后强制终止该唯一进程；强制终止后不再调用启动命令，只等待下一个
watchdog 边界恢复 `IDLE`。测试不读取 Credential、不连接 38432、不创建 Provider 客户端，并在结束时
删除精确临时任务、进程和目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\test-stock-quant-host-broker-watchdog-roundtrip.ps1 -ExpectedCommit <当前完整Git SHA>
```

正式安装采用事务式更新：更新前验证并导出已有 `StockQuantLocalBroker`；注册后校验失败时，新建场景
只删除本次精确任务，更新场景则恢复并复验原 XML，其他计划任务不受影响。

安装/升级会在确认待领取队列为空后启动一次监听进程并验证 fresh heartbeat；它不会生成 request。
安装后的无 Provider 自检命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\test-stock-quant-host-broker-resident-health.ps1 -ExpectedCommit <当前完整Git SHA>
```

卸载命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\quant-server\scripts\host-broker\install-stock-quant-host-broker.ps1 -Uninstall
```

卸载仅停止并删除固定任务及其 heartbeat，不删除 request/result 或 Credential。

## 严格请求与结果协议

请求只能原子写入：

```text
quant-server/target/stock-quant-host-broker/requests
```

结果只能原子写入：

```text
quant-server/target/stock-quant-host-broker/results
```

常驻进程以原子 JSON 写入
`quant-server/target/stock-quant-host-broker/heartbeat.json`，仅包含 Broker 版本、Git SHA、Windows
用户、进程 ID、启动时间、最近心跳和 `IDLE/BUSY` 状态。Codex 只把 fresh、同 SHA 的 heartbeat
视为健康；缺失、过期或构建不匹配统一返回 `HOST_BROKER_NOT_RUNNING`，不会尝试提升权限或安装任务。

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

invoke 脚本自行生成全新 requestId 和十分钟请求窗口、验证本地/远程集成 SHA、正式资产以及两次
fresh heartbeat，然后原子写入请求并轮询对应结果。常驻 Broker 自动发现并领取请求，Codex 无需
Task Scheduler 访问权。真实失败不触发第二次任务、不生成第二个 requestId、
不补跑、不重试、不重新签发授权。

## 不变边界

- `stock_quant_formal_runner` 不再拥有 `api.tushare.pro` 直连权限；真实网络只发生在固定宿主任务。
- Broker 不注册 Spring Bean、Controller、Runner 或 scheduler，不新增 Flyway/表。
- `RUN_DAY001` 仍固定 `600000/SSE/2025-01-03` 的授权合同、三调用、零重试与专用 38432 数据库。
- 不启动 F2B、F3、scheduler、Agent、Shadow、回测或交易；Day001 成功也不自动开放 F2B。
- 七项治理状态与 F1F-B2 `PASSED` 证据不因 Broker 改变。
