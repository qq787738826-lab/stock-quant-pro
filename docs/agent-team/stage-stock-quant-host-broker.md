# STOCK-QUANT-HOST-BROKER：本机宿主执行代理

## 阶段结果

本阶段为 `STOCK-QUANT-LOCAL-AUTOMATION` 增加真实 Windows 用户宿主边界，不再要求或尝试让
CodexSandbox 身份直接读取 Credential Manager。交付固定安装、触发和 Broker 脚本，以及共享的
严格非敏感 request/result 协议。Codex formal profile 已移除 Tushare 直连域名；原统一正式入口
增加宿主身份拒绝，只能由 Broker 以真实用户调用。

任务 `StockQuantLocalBroker` 使用安装用户、`Interactive/Limited`、无 trigger、无密码任务登录，
action 只执行固定 Broker 脚本。安装器要求管理员 PowerShell，但不调用、不查找或安装 `codex` CLI；
任务使用当前真实 Windows 用户和任务计划程序的所有者/管理员默认安全边界，不向 Authenticated
Users、Everyone 或 SYSTEM 扩权。

Broker 对请求执行固定目录、完整字段、过期时间、重复 ID、路径、JAR/hash/sidecar、授权、Git 和
预算校验，使用同目录原子 rename 领取一次后退出。四个 operation 均映射到仓库内固定函数与固定
脚本，不存在 `Invoke-Expression`、动态命令或动态脚本路径。Fake E2E 不检查或读取 Credential；
正式 Day001 必须先通过 USER_APPROVED preflight，再复用既有输出审计、秘密清零、专用数据库与
三请求/零重试 Runner。

## 验证与边界

- 协议攻击面覆盖：命令字段、路径逃逸、未知 operation、过期、重复 key、秘密字段和重复 requestId。
- 真实用户安装 `-WhatIf` 在 PATH 不存在 `codex` 时仍只检查两个 Target 的存在状态，并确认实际
  用户、管理员要求和固定任务定义；不创建任务。
- host smoke 在 PATH 不存在 `codex` 时仍可完成固定 Target 存在性与脱敏结果检查，Provider 调用 0、
  永久数据库写入 0。
- 打包 Fake Provider、临时 PostgreSQL、typed fact、SYSTEM_KNOWLEDGE、QFQ、输出审计和残留检查
  必须全部通过后才可合入。
- 本阶段不安装计划任务、不执行真实 Day001、不读取 CredentialBlob、不调用真实 Provider、不写
  38432 永久库，也不生成/消费 USER_APPROVED 正式授权。
- F1F-B2 `PASSED`、`REDUCED_RESEARCH_OPERATIONAL_READY=true` 与七项治理状态保持不变；
  F2B、F3、scheduler、Agent、Shadow、回测和交易均未启动。

完整安装和运行合同见 [宿主 Broker 操作说明](stock-quant-host-broker.md)。
