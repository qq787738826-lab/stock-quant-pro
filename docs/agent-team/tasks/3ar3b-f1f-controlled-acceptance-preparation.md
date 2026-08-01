# 3A-R3B-F1F-A：Tushare 缩减研究受控验收机制准备任务书

## 1. 目标

在集成基线 `0e2b607bc068910319134790360d71a18a6a9e02` 上准备一次性、用户
批准、最多三次 Provider 调用的受控验收机制。本阶段只实现类型化授权、脱敏候选证据、
资格投影和离线测试，不执行真实验收。

## 2. 输入状态

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
F1F_A_PROVIDER_REAL_CALL_COUNT=0
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=20
```

F1E 三提交链 `d5f28066bee97a5485917e193926594b9961767e` →
`e95781687cd0af63507c42017ec8ca6d6f404f86` →
`0e2b607bc068910319134790360d71a18a6a9e02` 已通过实际 Git 最终审查并经用户批准
纯 fast-forward 合入。

## 3. 实现合同

1. 一次性授权绑定验收 ID、代码基线、Tushare、一个证券、一个交易日、三个固定
   Endpoint、三次请求、零重试、专用数据库/用户/Schema/V13、有效期和全部禁止阶段；
2. 基线、范围和有效期先于数据库检查；数据库检查先于委托 F1E 运行；授权在首次合法
   委托前原子消费，重复使用不得再次委托；
3. F1E 继续负责 Provider 前数据库守卫、捕获事务前后守卫、全部响应写前验证和整批
   原子捕获；F1F-A 不复制或弱化这些规则；
4. 执行结果只保存脱敏身份、计数、时间、批次 ID、事实数量、SYSTEM_KNOWLEDGE 证据、
   formula-only QFQ 摘要和安全结论，不保存 Token、密码、连接串或完整市场响应；
5. 离线成功只能生成 `CANDIDATE`，没有公开 `PASSED` 构造入口；只有未来独立授权的
   F1F-B 真实验收与证明才能讨论 `PASSED`；
6. `PASSED` 投影不得影响完整 F1 技术资格、生产、正常业务库、scheduler、Agent、回测、
   Shadow、F2B/F3 或交易资格。

## 4. 状态模型

至少支持：

- `NOT_RUN`
- `CANDIDATE`
- `PASSED`
- `FAILED`
- `STALE`
- `INCOMPATIBLE_BASELINE`

F1F-A 当前只能生成除 `PASSED` 外的状态；当前权威状态仍为 `NOT_RUN`。

## 5. 验收边界

- 全部新增测试必须离线；
- 不调用 Tushare/iFinD，不检查 Token，不访问数据库，不启动 PostgreSQL；
- 不修改 Flyway、Repository、Controller、scheduler、Agent、回测、Shadow 或交易代码；
- 不修改四项正式门禁，不把 operational ready 改为 true；
- 后续 F1F-B 必须由用户单独批准，真实请求最大为三次且零重试。
