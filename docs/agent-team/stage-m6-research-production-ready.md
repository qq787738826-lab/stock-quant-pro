# M6 Research Production Ready

## 定位

M6 将已验收的 M1—M5 收口为单机个人日常研究软件，不新增研究能力，也不开放真实交易。

## 固定运行链

1. 用户运行 `quant-server/scripts/start-stock-quant-pro.ps1`。
2. 启动器检查 Java 17、38432 PostgreSQL、Resident Broker、正式 JAR 与 build proof。
3. Broker 只接受固定 `START/STOP/CHECK_RESEARCH_PRODUCTION_STATUS` 操作，以真实 Windows 用户
   读取固定数据库凭据并启动 `StockQuantResearchProductionRunner`。
4. Runner 先安装秘密审计，再执行 Flyway 至 V16，最后仅在 127.0.0.1:8080 启动 API、生产 UI
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

当前为 `M6_RESEARCH_PRODUCTION_READY=IN_PROGRESS`。最终任务提交验收前仍需完成正式 artifact、
永久研究库 V16、启动/重启恢复、本地备份、生产 UI、一次受控真实整链 smoke 及最终 Git 证据。
