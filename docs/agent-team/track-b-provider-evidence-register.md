# Track B Provider 官方证据登记册

## 1. 证据规则

- 官方资料访问日期统一为 `2026-07-29`。
- 官方证据共 **28 条**：BaoStock 4 条、Tushare Pro 17 条、同花顺 iFinD 7 条。
- 另有 1 条已经验收的 F0 直接 Provider 探针记录 `BS-005`；它不计入 28 条官方页面数量。
- 另有 10 条 B1 Tushare 受控权限探针摘要 `TS-PB-001`—`TS-PB-010`；它们不计入官方页面数量，不包含完整响应或实际市场值。
- 另有 1 条 Tushare 官方企业微信脱敏书面转录 `TS-WP-001` 和 1 条 F1A
  受控联调记录 `TS-F1A-001`；原始截图、完整响应和个人信息均不进入 Git。
- 证据等级：
  - `C1`：官方合同、服务协议或许可条款；
  - `P1`：官方价格、权限或额度页面；
  - `A1`：官方 API 数据字典/函数文档；
  - `A2`：官方产品、平台、FAQ、部署或案例文档；
  - `D1`：官方联系入口；
  - `W1`：Provider 官方书面直接回复的脱敏文字转录；
  - `R1`：已经验收的直接 Provider 响应审计。
- 搜索摘要只用于定位以下 URL；结论依据是对应官方页面，不使用博客、论坛、第三方 GitHub 或代理报价。

## 2. BaoStock

| evidenceId | 页面标题 | URL | 等级 | 支持的结论 | 不支持的结论 | 是否需书面确认 |
|---|---|---|---|---|---|---|
| BS-001 | Baostock 官网 | https://www.baostock.com/ | A2/D1 | 官方称原证券数据平台无需注册，给出技术群与 `baostock@163.com` | 不证明底层数据可长期保存、回测、Agent 或商业使用 | 是 |
| BS-002 | 免责声明 | https://baostock.com/disclaimer | C1/D1 | 信息仅供参考；不保证不中断、无错误；网站内容未经书面许可不得复制、传播或商业使用；提供官方邮箱 | 未明确 API 数据的个人研究、本地保存、回放、回测或 Agent 权利 | 是 |
| BS-003 | Baostock Python API 知识库 | https://www.baostock.com/mainContent?file=pythonAPI.md | A1 | 公开 Python API、raw/QFQ、结果错误码等技术表面；页面当前可能返回知识库加载错误 | 不证明公开函数在固定范围的响应完整性，也不证明许可 | 是 |
| BS-004 | BaoStock 复权因子简介 | https://www.baostock.com/helpdocs/pdf/BaoStock%E5%A4%8D%E6%9D%83%E5%9B%A0%E5%AD%90%E7%AE%80%E4%BB%8B.pdf | A1 | 官方说明涨跌幅复权算法、前后复权因子与除权日概念 | 不证明因子 API 对每个交易日可稳定返回，不证明 revision/旧版本 | 是 |
| BS-005 | F0 直接 Provider 探针记录 | stage-3ar3b-f0-free-provider-qualification-audit.md | R1 | 两证券 raw/QFQ 各观察 6 行、通用日历 8 行、公司行动 1 行、两个证券因子各 0 行；原始响应已删除 | 不证明 Live 终态完整性、`DAILY_EXACT`、许可或 Provider PIT | 是 |

## 3. Tushare Pro

| evidenceId | 页面标题 | URL | 等级 | 支持的结论 | 不支持的结论 | 是否需书面确认 |
|---|---|---|---|---|---|---|
| TS-001 | Tushare 平台介绍 | https://tushare.pro/document/1 | A2 | Pro 从网络聚合转为自研生产/治理，面向个人、中小机构、高校和 AI 场景 | 不构成具体数据用途许可或 SLA | 是 |
| TS-002 | Tushare 数据服务协议 | https://tushare.pro/document/1?doc_id=405 | C1 | 账号付费、非商业个人许可、不可转让、可撤销、有期限、仅供个人查看；不保证准确/完整/及时 | 未明确长期落库、回测、内部 Agent、备份和终止后留存 | 是 |
| TS-003 | 积分与频次权限对应表 | https://tushare.pro/document/1?doc_id=290 | P1 | 120 积分 raw 日线免费；2000 积分 200 元/年、200 次/分、每 API 10 万次/日；个人价格公开，机构 10 倍；公告另购 | 不证明购买即允许 V13 长期保存/回测/Agent | 是 |
| TS-004 | A 股日线行情 | https://tushare.pro/document/1?doc_id=27 | A1 | `daily` 为未复权，15—16 点入库；OHLC、volume=手、amount=千元；按证券/日期查询 | 没有 revision、snapshot 或逐记录 publishedAt | 是 |
| TS-005 | 复权因子 | https://tushare.pro/document/2?doc_id=28 | A1 | `adj_factor` 由 Tushare 自行生产，含 `ts_code/trade_date/adj_factor`；单证券全历史或单日全市场；2000 积分 | 不证明历史修订版本、providerPublishedAt 或 action 关联 ID | 是 |
| TS-006 | 交易日历 | https://tushare.pro/document/2?doc_id=26 | A1 | `trade_cal` 显式区分 SSE/SZSE，含 calendar date/open/pretrade date；2000 积分 | 不证明临时休市修订旧版本可查 | 是 |
| TS-007 | 分红送股 | https://tushare.pro/document/2?doc_id=103 | A1 | `dividend` 提供现金/送股/转增、公告日、登记日、除权日、实施公告日；2000 积分 | 未证明配股、拆并股全覆盖、稳定事件 ID 和 revision 链 | 是 |
| TS-008 | A 股复权行情 | https://tushare.pro/document/2?doc_id=146 | A1 | `pro_bar` 用 `adj_factor` 动态计算；QFQ 锚定查询 `end_date`；公式公开 | 不能把当前 QFQ 序列当历史不可变事实 | 否 |
| TS-009 | 股票基础信息 | https://tushare.pro/document/1?doc_id=25 | A1 | `ts_code`、exchange、上市/退市状态与日期；2000 积分；官方建议本地保存基础表 | 建议本地保存不自动覆盖所有数据类别的长期许可 | 是 |
| TS-010 | 每日停复牌信息 | https://tushare.pro/document/2?doc_id=214 | A1 | `suspend_d` 提供停/复牌类型与交易日期 | 更新不定期；历史修订关系未公开 | 是 |
| TS-011 | ST 股票列表 | https://tushare.pro/document/2?doc_id=397 | A1 | `stock_st` 可按交易日取得历史 ST 列表，2016 年起，3000 积分 | 2016 年前不完整；revision 未公开 | 是 |
| TS-012 | 指数日线行情 | https://tushare.pro/document/1?doc_id=95 | A1 | `index_daily` 与字段/单位、2000 积分 | 不证明所有指数授权可二次展示 | 是 |
| TS-013 | 指数成分和权重 | https://tushare.pro/document/2?doc_id=96 | A1 | `index_weight` 提供指数/成分/日期/权重；来源为指数公司公开数据；2000 积分 | 不替代指数公司对本地保存、展示、衍生使用的授权 | 是 |
| TS-014 | Tushare Pro 数据接口 | https://tushare.pro/document/1?doc_id=40 | A1 | Python SDK 和 HTTP POST；标准 `code/msg/data`，2002 权限错误 | 不提供 SLA、revision 或许可扩展 | 是 |
| TS-015 | Tushare 与 AI 工作流 | https://tushare.pro/document/1?doc_id=473 | A2 | 官方展示 AI/多智能体、回测及本地缓存工作流 | 产品示例不能覆盖 TS-002 合同限制 | 是 |
| TS-016 | 旧版数据存储说明 | https://tushare.pro/document/2?doc_id=302 | A2 | 文档描述可保存到 Excel/关系库，强调该段为旧 Org 版语境 | 不能作为当前 Pro 数据的优先合同授权；与 TS-002 存在适用范围冲突 | 是 |
| TS-017 | API 服务 | https://tushare.pro/document/1?doc_id=11 | D1 | 官方向机构和个人提供 API 需求定制与数据咨询，并公布联系邮箱 `waditu@163.com` | 联系入口不证明任何用途许可、套餐覆盖或服务承诺 | 是 |

### 3.1 Tushare B1 受控权限探针

真实探针日期为 `2026-07-30`，精确时刻为 `PROBE_EXECUTION_TIME=UNKNOWN`；运行环境为 Python `3.11.9`、tushare `1.4.29`、pandas `3.0.5`。环境变量只确认存在，内容未输出或保存。精确执行 10 次业务请求，无重试、权限错误或网络错误；没有保存完整响应、CSV 或 Token，临时环境残留为 0。详细边界见 [B1 阶段记录](stage-3ar3b-track-b1-tushare-probe-review.md)。

| evidenceId | Endpoint | 范围 | 状态 | 行数 | 字段/日期结论 | 不支持的结论 |
|---|---|---|---|---:|---|---|
| TS-PB-001 | `stock_basic` | `600000.SH` | `PASS` | 1 | 普通证券身份、交易所、上市/退市字段返回 | 不证明永久 identity、换码、迁板或重上市 |
| TS-PB-002 | `stock_basic` | `000001.SZ` | `PASS` | 1 | 同上 | 同上 |
| TS-PB-003 | `trade_cal` | SSE，`20250101`—`20250105` | `PASS` | 5 | `exchange,cal_date,is_open,pretrade_date` | 不证明历史修订版本 |
| TS-PB-004 | `trade_cal` | SZSE，`20250101`—`20250105` | `PASS` | 5 | 同上 | 同上 |
| TS-PB-005 | `daily` | `600000.SH`，`20250102`—`20250103` | `PASS` | 2 | raw daily 请求字段和两个交易日返回 | 不证明全历史覆盖、revision 或用途许可 |
| TS-PB-006 | `daily` | `000001.SZ`，`20250102`—`20250103` | `PASS` | 2 | 同上 | 同上 |
| TS-PB-007 | `adj_factor` | `600000.SH`，`20250102`—`20250103` | `PASS` | 2 | 每个请求交易日存在同日因子 | 不证明全历史 `DAILY_EXACT`、修订或 action 关系 |
| TS-PB-008 | `adj_factor` | `000001.SZ`，`20250102`—`20250103` | `PASS` | 2 | 同上 | 同上 |
| TS-PB-009 | `dividend` | `600000.SH` | `PASS` | 51 | 接口可调用，返回公告/登记/除权/实施及现金送转字段 | 不证明配股、拆并股、更正/撤回、稳定事件 ID 或 revision |
| TS-PB-010 | `dividend` | `000001.SZ` | `PASS` | 53 | 同上 | 同上 |

综合技术权限结论为 `TUSHARE_2000_PERMISSION_PROBE=PASS`。但执行前未取得 Provider 对最小自动 API 探针及临时响应、Hash、摘要、夹具保存/删除范围的书面答复，因此 `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`、`TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`、`WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED`、`WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED`。B1 技术权限证据本身不判定本次调用合法或违法，也不独立支持长期本地保存、回测、Agent、Provider PIT、F1 READY 或任何正式门禁升级；后续 `TS-WP-001` 只支持量化数据来源用途，三项具体许可仍未验证。

### 3.2 Tushare 个人用途书面许可与 F1A 联调

| evidenceId | 日期 | 来源/范围 | 等级 | 脱敏内容或结果 | 支持的结论 | 不支持的结论 |
|---|---|---|---|---|---|---|
| TS-WP-001 | `2026-07-30` | Tushare 官方企业微信书面文字回复 | W1 | 问：“这个可以用来当量化数据来源吧”；答：“可以” | 可把 Tushare 作为量化数据来源 | 不逐项支持本地长期存储、策略回测、内部 Agent、原始数据再分发、商业数据服务或服务到期留存 |
| TS-F1A-001 | `2026-07-30` | 两证券、两日、`daily/adj_factor/trade_cal`，精确 6 次、零重试 | R1 | 两只证券的三类响应均非空且日期范围合规；未保存完整响应、CSV、Token 或市场值 fixture | F1A Java HTTPS Adapter、字段映射和受控零重试路径可用 | 不支持公司行动闭环、revision、历史旧版本、永久证券身份或全历史 `DAILY_EXACT` |
| TS-F1A-002 | `2026-07-30` | 两证券、`stock_basic/dividend`，只用阶段剩余 4 次、零重试 | R1 | stock_basic 为 1/1 行；dividend 为固定证券的历史部分证据 51/53 行，不是两日数据；字段集合与冻结 DTO 一致；未保存完整响应、CSV、Token 或市场值 | 普通身份 DTO 与 dividend 部分证据 DTO 的真实受控路径可用；F1A 总调用数为 10 | 不支持永久身份、稳定 action ID、完整公司行动、factor/action 解释关系、revision、Provider PIT 或写入 V13 公司行动表 |

错误转录生成的 SHA-256 已删除；未保存的原始截图不登记新的内容 Hash。原始截图不提交
Git；不记录联系人、微信 ID、头像、手机号、Token、账号或其他个人信息。

据此，有限个人用途当前状态为：

```text
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=UNVERIFIED
USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER
```

上述结论不回溯改变 B1 完整证据探针在执行前的法律前置事实，故继续保持：

```text
POST_EXPIRY_DATA_RETENTION_PERMISSION=UNVERIFIED
RAW_DATA_REDISTRIBUTION_PERMISSION=NOT_GRANTED
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
```

## 4. 同花顺 iFinD

| evidenceId | 页面标题 | URL | 等级 | 支持的结论 | 不支持的结论 | 是否需书面确认 |
|---|---|---|---|---|---|---|
| IF-001 | 免费版/试用版/正式版数据使用量权限说明 | https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center/permission.html | P1 | 免费、试用、正式账号的行情、基础、公告额度与单次限制公开 | 不公开价格、许可、留存或具体 V13 指标权限 | 是 |
| IF-002 | 基础概念与 FAQ | https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center/faq.html | A2 | SDK/HTTP、多语言、QPS、账号差异、总体入库时点、复权因子语义、交易日查询、部分错误码 | 无公开数据字典；具体指标需 SuperCommand；不证明 revision/PIT/授权 | 是 |
| IF-003 | 数据接口产品手册 | https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center/manual.html | A1 | 基础/日期序列/行情/报告等函数、`errorcode/errmsg/dataVol` 和 Python/Java/HTTP 接口 | 具体 V13 指标、单位、历史版本及许可需账号/合同确认 | 是 |
| IF-004 | 数据接口应用示例 | https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/example.html | A1 | HTTP 历史 OHLC、基础数据、日期序列、公告元数据/PDF、交易日查询、多代码能力 | 示例不是字段资格、revision 或许可证明 | 是 |
| IF-005 | 数据接口部署说明 | https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center/deploy.html | A2 | Windows/Linux SDK、HTTP、Python/Java 环境和 SuperCommand 流程 | 不证明个人购买资格或价格 | 是 |
| IF-006 | iFinD 数据接口申请试用 | https://quant.10jqka.com.cn/view/dataplatform/detail/419 | P1/D1 | 官方存在申请试用流程，提交信息后由运营联系 | 不公开报价、合同、具体试用天数、留存权利或个人资格 | 是 |
| IF-007 | 同花顺数据接口帮助中心 | https://quantapi.51ifind.com/gwstatic/static/ds_web/quantapi-web/help-center.html | D1 | 官方客服热线 `952555`、试用入口和公司地址 | 客服入口本身不证明任何授权结论 | 是 |

## 5. 证据冲突与保守解释

1. Tushare TS-015/TS-016 展示本地缓存、回测和 AI 用法；TS-002 是现行协议。
   后续官方书面回复 TS-WP-001 只确认可作为量化数据来源，不能把三项具体用途升级为
   `VERIFIED_ALLOWED`。用户个人实现授权允许 F1A 继续，但不是 Provider 逐项许可。
2. iFinD 公共文档证明接口和额度，不公开专项合同与价格；因此不以技术可导出推断长期保存和回测权利。
3. BaoStock 公开免费与匿名访问不等于数据用途授权；BS-002 反而明确网站内容的复制/传播/商业使用需要书面许可。
4. Provider 的更新时点或本地响应 Hash 都不是 revision、snapshot 或 providerPublishedAt。
