# STOCK-QUANT-HOST-BROKER：本机宿主执行代理

## 阶段结果

本阶段为 `STOCK-QUANT-LOCAL-AUTOMATION` 增加真实 Windows 用户宿主边界，不再要求或尝试让
CodexSandbox 身份直接读取 Credential Manager。交付固定安装、触发和 Broker 脚本，以及共享的
严格非敏感 request/result 协议。Codex formal profile 已移除 Tushare 直连域名；原统一正式入口
增加宿主身份拒绝，只能由 Broker 以真实用户调用。

任务 `StockQuantLocalBroker` 使用安装用户、`Interactive/Limited`、当前用户登录 trigger 加无结束边界的
`PT1M` watchdog TimeTrigger、`MultipleInstancesPolicy=IgnoreNew`，无密码任务登录，action 只启动固定
常驻 Broker 脚本。任一 trigger 都不生成 Provider 请求；Broker 空闲时只维护脱敏
heartbeat 和检查固定 request 目录。安装器要求管理员 PowerShell，但不调用、不查找或安装 `codex` CLI；
任务使用当前真实 Windows 用户和任务计划程序的所有者/管理员默认安全边界，不向 Authenticated
Users、Everyone 或 SYSTEM 扩权。

Codex formal profile 不再查询任务定义或调用 `schtasks /Run`；它只在 fresh、同 Git SHA heartbeat
存在时原子写 request 并等待对应 result。Broker 以
`.request → .processing → .processed` 原子领取，崩溃后遗留的 claimed 文件不会在有限任务重启中重放。

Broker 对请求执行固定目录、完整字段、过期时间、重复 ID、路径、JAR/hash/sidecar、授权、Git 和
预算校验，使用同目录原子 rename 领取一次并写 terminal 结果，随后回到空闲监听。四个 operation
均映射到仓库内固定函数与固定
脚本，不存在 `Invoke-Expression`、动态命令或动态脚本路径。Fake E2E 不检查或读取 Credential；
正式 Day001 必须先通过 USER_APPROVED preflight，再复用既有输出审计、秘密清零、专用数据库与
三请求/零重试 Runner。

## 验证与边界

生命周期收口前的正式任务实际状态为 `Ready`、仅一个 `MSFT_TaskLogonTrigger`、
`LastTaskResult=0xC000013A`、`NextRunTime=NONE`。`RestartCount=3/RestartInterval=PT1M` 只是一次运行失败后的
有限恢复预算，耗尽或未进入重启条件后不会生成新的调度事件；Codex 又被明确禁止 demand-start，因此
Broker 退出后只能等下一次用户登录。新增 watchdog TimeTrigger 为该缺失的持续调度事件，不改变 Provider
启动边界。

- 协议攻击面覆盖：命令字段、路径逃逸、未知 operation、过期、重复 key、秘密字段和重复 requestId。
- 真实用户安装 `-WhatIf` 在 PATH 不存在 `codex` 时仍只检查两个 Target 的存在状态，并确认实际
  用户、管理员要求和固定任务定义；不创建任务。
- host smoke 在 PATH 不存在 `codex` 时仍可验证空闲 heartbeat、自动领取、固定 Target 存在性与
  脱敏结果，空闲 Credential 读取 0、Provider 调用 0、永久数据库写入 0。
- 实际 Task Scheduler 定义 round-trip 覆盖 Windows PowerShell 5.1、中文本地用户名的裸名/SID 规范化、
  Actions 单元素、当前用户 LogonTrigger、`PT1M` TimeTrigger、Interactive/Limited、固定路径、无限监听
  与有限重启，并验证旧零 trigger 和旧登录单 trigger 定义可安全升级、新建失败无残留、更新失败恢复
  原定义。
- 实际生命周期 round-trip 启动唯一隔离临时 Broker，跨越首次周期边界保持单实例，forced-kill 后不再
  demand-start，并在 `41.528s` 后由下一周期触发器恢复 `IDLE`；Credential/Provider/38432 写入均为 0，
  临时任务、进程、heartbeat 和目录残留 0。
- 安装校验按条件返回独立脱敏 reason；正常路径、参数和 principal 规范化不再误报统一
  `TASK_DEFINITION_INVALID`。
- 打包 Fake Provider、临时 PostgreSQL、typed fact、SYSTEM_KNOWLEDGE、QFQ、输出审计和残留检查
  必须全部通过后才可合入。
- 本阶段不安装计划任务、不执行真实 Day001、不读取 CredentialBlob、不调用真实 Provider、不写
  38432 永久库，也不生成/消费 USER_APPROVED 正式授权。
- F1F-B2 `PASSED`、`REDUCED_RESEARCH_OPERATIONAL_READY=true` 与七项治理状态保持不变；
  F2B、F3、scheduler、Agent、Shadow、回测和交易均未启动。

完整安装和运行合同见 [宿主 Broker 操作说明](stock-quant-host-broker.md)。
