# 3A-R3B-F1F-A：Tushare 缩减研究受控验收机制准备阶段记录

## 1. 阶段定位

本阶段从已合入的 F1E 基线
`0e2b607bc068910319134790360d71a18a6a9e02` 开始，只准备未来 F1F-B 的安全验收
机制。真实受控验收未运行，Provider、Token和数据库均未访问。

## 2. 类型化实现

- `TushareControlledAcceptanceAuthorization`：一次性授权；精确绑定代码基线、Provider、
  一个证券/日期、三个 Endpoint、三次调用、零重试、专用数据库目标、V13、有效期和
  全部禁止运行范围；
- `TushareControlledAcceptanceQualification`：证据状态、失败阶段、事实计数、原子提交、
  SYSTEM_KNOWLEDGE、formula-only QFQ、敏感信息和禁止阶段的不可变投影；
- `TushareControlledAcceptanceService`：非 Controller 的显式入口；基线和授权预检先于
  数据库，专用数据库预检先于 F1E 委托；成功只产生 `CANDIDATE`；
- `TushareReducedResearchAdmissionQualification`：不再把受控验收简化为固定裸布尔值，
  而是接收类型化验收资格；当前默认 `NOT_RUN`，因此 operational 仍为 false；
- F1E 批次结果增加实际捕获使用的 `observedAt`，供 SYSTEM_KNOWLEDGE 验收证据引用；
  F1E 的捕获、QFQ和资格行为不改变。

## 3. 失败分类

脱敏失败阶段可以区分授权、Provider前置、Provider调用、响应验证、数据库身份、
Schema/V13、写入、事务回滚、QFQ、禁止阶段和资格投影。失败证据只保留安全码，不保存
自然语言Provider响应、Token、密码或连接串。

## 4. 当前投影

```text
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true
REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true
REDUCED_RESEARCH_OPERATIONAL_READY=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

F1 完整技术资格的十项阻断保持不变。离线完整成功路径只能产生 `CANDIDATE`，不能伪造
真实 Provider 验收或升级 operational。

## 5. 安全边界

- 无 Controller、scheduler 或自动入口；配置未显式提供40位验收基线时，入口在数据库
  与Provider之前失败关闭；
- 一次性授权在合法委托前原子消费，失败后不得自动重试；
- F1E 原有 Provider 前守卫、捕获事务内前后守卫和全部响应写前验证继续生效；
- 证据不含 Token、密码、JDBC URL、完整市场响应或真实市场 fixture；
- 不启动 Agent、回测、Shadow、Day 002、F2B、F3、3A-R3B-1、3B 或交易。

## 6. 验证结果

全部验证均使用 Fake/Stub 和纯离线入口，命令级排除了 Live、Integration、Postgres 与
CrossLanguage 测试；没有启动数据库或访问 Provider。

| 验证套件 | Tests run / Failures / Errors / Skipped | 结果 |
| --- | --- | --- |
| Java 干净编译 | 不适用 | `quant-core` 与 `quant-server` 编译成功 |
| F1F-A 定向测试 | `19/0/0/0` | 一次性授权、基线、过期、请求范围、证据资格与候选投影通过 |
| F1F-A 与 F1E 边界回归 | `49/0/0/0` | 数据库前置、委托边界、捕获合同与准入投影通过 |
| F1A—F1F-A、Provider V2 与 QFQ 联合回归 | `165/0/0/0` | Provider V2 `10/0/0/0`；QFQ 权威引擎 `19/0/0/0` |
| `quant-core` 全量 | `4/0/0/0` | 通过 |
| `quant-server` 安全离线全量 | `455/0/0/0` | 65 个安全离线测试类全部通过 |

`git diff --check`、Markdown 相对链接、表格、UTF-8、文件结尾、尾随空白、敏感信息、
授权变更范围和禁止文件检查均在提交前通过。F1F-A 的真实 Provider 请求数为 0，Tushare
累计真实业务请求继续为 20，iFinD 真实调用数继续为 0。

## 7. Git与阶段状态

- 任务分支：`codex/1.4.0-stage-3ar3b-f1f-controlled-acceptance-preparation`
- 父提交：`0e2b607bc068910319134790360d71a18a6a9e02`
- F1F-A 完成后待 ChatGPT 基于实际 Git 提交验收，尚未合入；
- F1F-B 尚未授权或开始；未来真实 Provider 预算上限为三次、零重试。
