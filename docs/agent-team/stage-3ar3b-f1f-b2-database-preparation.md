# 3A-R3B-F1F-B2-DBPREP 阶段记录：专用研究数据库准备链与冻结清单校正

## 阶段结论

F1F-B2-RUNNER 四提交链已经纯 fast-forward 合入集成提交 `213264bc63a2584f0fbb30dca059abf272e62a64`。首次 B2-FREEZE 随后判为 `NOT_READY`：冻结清单错误地使用 Runner 不存在的松散数据库/构建参数，且仓库没有从全新目标安全创建专用数据库并只执行主 V1—V13 的正式准备入口。

本任务在 `codex/1.4.0-stage-3ar3b-f1f-b2-database-preparation` 完成上述两个缺口的实现与测试，待 ChatGPT 基于实际 Git 提交复验，尚未合入。任务分支文档中的当前集成基线仍为 `213264bc...`，不得预写未来合入 SHA。

## 实现摘要

- 新增非 Spring 一次性数据库准备 `main` 与 PowerShell 包装脚本；默认 `PREPARATION_ONLY` 不连接数据库，正式模式必须显式选择并带用户批准引用。
- 固定本机、专用数据库/角色/Schema/search path 与显式端口；同名目标或含其他业务数据库的非专用实例在 DDL 前拒绝。
- 管理员创建受限角色和数据库并收紧 PUBLIC；一次性 bootstrap 专用用户在临时最小 `CREATE` 授权下创建自身专用 Schema 和 `btree_gist`，随后管理员撤销数据库级 `CREATE`、关闭连接并清零秘密，才读取最终专用用户密码，再以专用用户执行主 V1—V13。
- 主 Flyway 只扫描 `classpath:db/migration`，禁用 baseline、out-of-order、clean 和 repair；回读精确 V1—V13、数据库身份、角色权限、Schema owner、search path、PUBLIC 权限、零事实及零治理对象。
- 输出审计覆盖计划、秘密、DataSource、Flyway 与失败异常；候选报告不含密码、Token、完整 JDBC URL、原始 SQL 或完整响应。发生部分修改后不自动回滚，状态为 `INCOMPLETE_NOT_APPROVED`。
- Runner 授权文件增加构建证明路径与固定数据库 host，并使用严格逐行解析拒绝重复字段。正式启动仍只接受一个 `AuthorizationFile`。
- 旧草案 `F1FB2_20260803_140506_96C6DFB7` 已在类型化授权模型中显式拒绝。

## 验证记录

- DBPREP 定向：`24 / 0 / 0 / 0`；覆盖默认模式、正式显式授权、固定目标、秘密/审计时序、Flyway 隔离、严格授权字段、旧 ID、输出脱敏和错误路径。
- Runner/可信机制与 F1F-B1 边界：`55 / 0 / 0 / 0`；与 DBPREP 合并定向为 `79 / 0 / 0 / 0`。
- Provider V2、F1A/F1B/F1C/F1E 与 QFQ 相关回归：`76 / 0 / 0 / 0`；其中 QFQ 权威引擎 `19 / 0 / 0 / 0`，18 个黄金向量保持不变。
- `quant-core` 全量：`4 / 0 / 0 / 0`；命令级排除 `IntegrationTest/Postgres/CrossLanguage/Live` 的 `quant-server` 安全离线全量：`516 / 0 / 0 / 0`。
- PostgreSQL `16.13` 全新随机实例：`15 / 0 / 0 / 0`；验证角色、数据库、专用用户创建并拥有 Schema、最终数据库级 `CREATE` 撤销、PUBLIC 收紧、精确主 V1—V13、无 baseline、无治理 history/V14/验收表、零事实、目标复用拒绝及零残留，核心数据库测试 `Skipped=0`。
- Java clean compile、PowerShell 语法、`git diff --check`、Markdown 链接/表格/UTF-8/换行、敏感信息和精确范围检查通过。

## 未执行事项

本任务没有执行真实数据库正式准备，没有读取真实数据库密码或 Tushare Token，没有调用 Tushare/iFinD，没有执行治理 V14，没有创建/消费 acceptance ID，没有签发正式构建证明或真实授权，也没有运行 F1F-B2、scheduler、Agent、回测、Shadow、Day 002、F2B、F3、3A-R3B-1 或 3B。

七项正式状态保持不变；任务合入后必须基于新的集成 SHA 重新执行简化 B2-FREEZE，届时只冻结正式数据库端口、正式构建摘要和最终参数表。
