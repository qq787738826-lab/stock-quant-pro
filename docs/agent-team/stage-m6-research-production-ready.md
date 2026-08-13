# M6 Research Production Ready

## 定位

M6 将已验收的 M1—M5 收口为单机个人日常研究软件，不新增研究能力，也不开放真实交易。

## 固定运行链

1. 用户运行 `quant-server/scripts/start-stock-quant-pro.ps1`。
2. 启动器检查 Java 17、38432 PostgreSQL、Resident Broker、正式 JAR 与 build proof。
3. Broker 只接受固定 `START/STOP/CHECK_RESEARCH_PRODUCTION_STATUS` 操作，以真实 Windows 用户
   读取固定数据库凭据并启动 `StockQuantResearchProductionRunner`。
4. Runner 先安装秘密审计，再执行 Flyway 至当前受控版本（V1.0.1 为 V17），最后仅在 127.0.0.1:8080 启动 API、生产 UI
   和 `SHADOW_SCHEDULER_V1`。旧扫描与持仓风险 scheduler 在该入口关闭。
5. Broker 持久化无秘密 autostart 绑定；后端异常退出时最多恢复三次。显式 Stop 先解除恢复，
   再调用 loopback 优雅关闭；仍存活才强制终止。

## 日常入口

```powershell
& .\quant-server\scripts\start-stock-quant-pro.ps1
```

状态、停止与备份：

```powershell
& .\quant-server\scripts\start-stock-quant-pro.ps1 -Action Status -NoBrowser
& .\quant-server\scripts\start-stock-quant-pro.ps1 -Action Stop -NoBrowser
& .\quant-server\scripts\backup-stock-quant-research.ps1
```

生产 UI 与健康 API 固定为 `http://127.0.0.1:8080/` 和
`http://127.0.0.1:8080/api/system/health`。

## 安全边界

- 生产后端不接受密码、Token、动态脚本或 Provider 参数。
- 备份只包含研究、Shadow、Paper、Evaluation 和 Flyway 元数据，明确排除 Credential。
- Shadow 调度固定 Asia/Shanghai 17:20、工作日触发，并在数据库事实层确认交易日与 slot 幂等。
- 月度门禁维持百炼 CNY 30、Tushare 150 请求、项目 CNY 200。
- Agent 只能给研究建议；Paper 执行由确定性引擎完成。券商、订单和真实资金始终关闭。

## 验收状态

`M6_RESEARCH_PRODUCTION_READY=PASS`。正式真实整链运行资产 HEAD 为
`6f6a5c10678589c6d967ef381d48a9230518629c`；永久研究库已到 V16，Backend、Broker、生产 UI 和
Scheduler 为 `HEALTHY/IDLE/HEALTHY/ACTIVE`。打包 Fake E2E、临时 PostgreSQL V1—V16、后端与
Broker forced-kill 恢复、备份和重启均通过。

正式受控整链 request `SQHB_20260813T064259Z_0AD08453B039` 以 Tushare 6 次、百炼 13 次、
retry 0 成功冻结 Shadow run `4`；模型 token 为 `22530/3858/0/26388`，保守成本 CNY
`0.836400000000`。typed fact、SYSTEM_KNOWLEDGE、formula-only QFQ、防未来和输出审计通过；
证据不足被 Critic 正确冻结为 `INSUFFICIENT_EVIDENCE`，Paper 订单/成交为 0。历史 Shadow 不变，
真实交易、券商和真实资金仍全部关闭。

## V1.0.1 使用性增量

V17 新增不可变 `research_selection_runs` 历史，`RESEARCH_UNIVERSE_V1` 固定 25 只沪深主板
代表股票。首页和 `/research-selection` 提供“立即选股”：确定性 20/60 日扫描全池，Top 10
进入既有 7-Agent 与 M2 策略比较，量化门禁和 Critic 后最多输出 5 只。17:20 Shadow 使用
同一 Universe 和流水线；手工选股正在运行时，正式 slot 由 Resident Broker 排队串行消费，
不会被静默跳过。旧冻结 Shadow、Paper 和 Evaluation 事实不修改。
