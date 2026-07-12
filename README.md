# Stock Quant Pro（A股短线量化终端）

面向 Windows 个人投资者的专业量化基础版。默认覆盖沪深主板 A 股，排除创业板、科创板、北交所、ST、退市整理、新股与停牌证券；持仓周期 2–10 个交易日。

## 已包含

- Vue 3 + TypeScript + Electron 桌面端
- Spring Boot 3.2.8 + JDK 17 核心服务
- Python 3.11 + FastAPI + AKShare 免费数据/AI辅助服务
- PostgreSQL 16 + Flyway 数据库迁移
- 多因子短线评分、交易计划、风险控制、回测、模拟持仓
- 人工确认委托（不自动操作券商客户端）
- 在线 AI 可选、本地规则分析自动降级
- Windows 一键初始化、启动、构建脚本

## 安全边界

本项目是投研与交易辅助软件，不保证收益。真实下单必须由用户人工确认。默认不保存券商密码，不注入或控制券商客户端，不绕过券商风控。程序化交易及数据使用应遵守适用法律、交易所规则、券商协议和数据源条款。

## 环境

- Windows 10/11 64 位
- JDK 17
- Maven 由 `mvnw.cmd` 首次自动准备（也可自行安装 Maven 3.9+）
- Node.js 22.18+
- Python 3.11.x 64 位
- PostgreSQL 16（默认端口 5432）

## 首次运行

1. 复制 `.env.example` 为 `.env`，修改数据库密码。
2. 双击 `scripts\\01-init-python.cmd`。
3. 双击 `scripts\\02-init-database.cmd`。
4. 双击 `scripts\\03-start-dev.cmd`。
5. Electron 窗口未自动出现时，在 `quant-desktop` 执行 `npm run dev`。

默认地址：

- 核心 API：http://localhost:8080
- AI/行情服务：http://localhost:8001
- Web：http://localhost:5173

## 生产构建

运行 `scripts\\04-build-all.cmd`。生成内容位于各模块的 `target` / `dist` 目录。

## 项目结构

```text
stock-quant-pro
├─ quant-core       Java 指标、策略、风控、回测核心
├─ quant-server     Spring Boot API、任务、数据库
├─ quant-ai         FastAPI、AKShare、AI降级分析
├─ quant-web        Vue 3 量化终端界面
├─ quant-desktop    Electron 桌面壳
├─ database         手工建库脚本
├─ installer        安装打包配置说明
├─ scripts          Windows 初始化/启动/构建脚本
└─ docs             架构、接口、使用与维护文档
```

详细说明见 `docs/`。
