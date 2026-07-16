# Stock Quant Pro Codex 开发规则

## 项目状态

- 当前稳定版本：1.3.1
- 目标版本：1.4.0
- 当前分支：feature/1.4.0-agent-team
- 当前开发目标：股票智能体团队
- 智能体团队的当前阶段、真实能力与下一阶段入口，以 `docs/agent-team/CURRENT_STATE.md` 为唯一事实来源。

## 技术栈

- Java 17
- Spring Boot 3.2.8
- Maven 多模块
- PostgreSQL 16
- Flyway
- Python 3.11
- FastAPI
- Pandas
- NumPy
- AKShare
- Vue 3
- TypeScript
- Electron

## 项目模块

- quant-core：量化计算、策略和回测
- quant-server：Java API、数据库、任务和风控
- quant-ai：Python 行情、分析和智能体编排
- quant-web：Vue 前端
- quant-desktop：Electron 桌面端

## 服务端口

- Java：127.0.0.1:8080
- Python：127.0.0.1:8001
- Vue：127.0.0.1:5173

## 安全限制

- 不读取、显示、修改或提交 .env
- 不输出数据库真实密码或 API Key
- 不提交 node_modules、target、.venv、history-cache
- 不连接真实券商自动下单
- 不控制证券客户端
- 不自动使用真实资金
- 所有交易操作必须人工确认
- AI 不得直接修改账户现金、持仓和成交记录

## Git 限制

- 不直接修改 master
- 当前只在 feature/1.4.0-agent-team 开发
- 未经用户确认不得 git commit
- 未经用户确认不得 git push
- 禁止 git reset --hard
- 禁止 git clean -fd
- 禁止 git push --force
- 禁止删除 Git 历史

## 数据库限制

- 不修改已执行的 V1-V4 Flyway 文件
- 新结构必须创建新的 Flyway 迁移
- 1.4.0 使用 V5__agent_team.sql
- 数据库语法必须兼容 PostgreSQL 16
- 禁止删除或清空现有业务数据

## 股票业务限制

- 行情只能来自本地数据库或已配置数据源
- 不得编造价格、指标、公告或新闻
- 智能体结论必须引用真实证据
- 风控智能体拥有否决权
- AI 不得承诺收益
- AI 结果只用于研究和模拟交易
- 当前市场范围为沪深主板
- 排除创业板、科创板、北交所、ST 和退市股票

## 开发要求

- 每次只完成一个明确的小任务
- 修改前先阅读现有代码
- 优先复用现有实现
- 不做与任务无关的大范围重构
- 不随意增加生产依赖
- 修改后必须列出修改文件
- 修改后必须运行相关测试
- 测试失败必须明确说明
- 不允许用固定模拟数据掩盖错误

## 测试要求

Java 修改后：
- 读取项目实际 Maven 配置
- 运行相关模块编译或测试

Python 修改后：
- 使用 quant-ai/.venv
- 运行 compileall 或相关测试

Vue 修改后：
- 读取 package.json 中实际脚本
- 运行 TypeScript 检查和生产构建

## 每次任务完成后输出

1. 修改摘要
2. 修改文件列表
3. 数据库变化
4. 接口变化
5. 已执行测试
6. 测试结果
7. 未解决问题
8. 下一步建议
