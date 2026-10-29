# Windows 桌面客户端

## 构建

桌面客户端复用 `quant-desktop`、现有 `quant-web` 和正式 Production 生命周期合同。构建机需要 Windows、Node.js 22+，并且当前仓库 HEAD 已具有匹配的正式 Backend JAR 与 proof。

```powershell
cd quant-web
npm.cmd run build

cd ..\quant-desktop
npm.cmd test
npm.cmd run build
```

安装包输出为 `quant-desktop/release/Stock Quant Pro Setup.exe`。构建会自动记录当前仓库根目录和 Git commit；不会把数据库密码、Token 或 Key 写入安装包。

## 安装与启动

运行 `Stock Quant Pro Setup.exe` 完成安装。安装器创建桌面和开始菜单中的 `Stock Quant Pro` 快捷方式，并可从 Windows“已安装的应用”卸载。

双击快捷方式后，客户端先通过正式 `Status` 合同检查 Production；已运行时直接复用，已停止时通过正式 `Start` 合同启动。Backend 达到 `HEALTHY` 后，窗口加载 `http://127.0.0.1:8080/`，不会自动打开 Chrome 或 Edge。

V1 是开发者本机安装版，仍依赖构建时的本地仓库和正式制品。如果仓库被移动，可设置非敏感环境变量 `STOCK_QUANT_REPOSITORY_ROOT` 指向同一 Git commit 的仓库；客户端仍会校验 `.git`、固定生命周期脚本、固定 JAR 和 proof，不会搜索或执行任意同名 JAR。

## 关闭

点击窗口右上角 `X` 会阻止窗口立即销毁，调用正式 `Stop` 合同，并确认 Backend 为 `STOPPED`、`autoRecovery=DISARMED` 且 8080 不再监听后再退出。Resident Broker 不会被桌面客户端停止；已被 Broker 接受的原子任务继续按照既有合同完成或安全失败。

## 常见启动错误

- `STOCK_QUANT_REPOSITORY_NOT_FOUND`：安装包绑定的仓库不存在，或环境变量未指向合法仓库。
- `DESKTOP_FORMAL_ARTIFACT_MISSING`：正式 Backend JAR 或 proof 缺失，先完成受控 Production 构建。
- `M6_DATABASE_NOT_LISTENING`：本地研究数据库未监听 `127.0.0.1:38432`。
- `M6_JAVA_17_REQUIRED`：未安装或未配置 Java 17。
- `HOST_BROKER_TASK_NOT_INSTALLED` / `HOST_BROKER_TASK_DISABLED`：需要修复既有 `StockQuantLocalBroker`。
- `DESKTOP_BACKEND_HEALTH_TIMEOUT`：Backend 未在限定时间内达到 `HEALTHY`；客户端不会无限重试。

错误页只显示脱敏原因。可选择“重新尝试”执行一次新的 `Status → Start → health` 流程，或选择“退出”进入安全停止流程。
