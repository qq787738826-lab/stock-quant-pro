# 3A-R3B-F1F-B2-FREEZE 记录：首次真实受控验收参数冻结

## 结论

`2026-08-03` 基于已合入 F1F-B2-RUNNER 的 `213264bc63a2584f0fbb30dca059abf272e62a64` 执行首次冻结清单审计，结论为 `NOT_READY`。该轮没有调用 Provider、没有访问数据库、没有执行 V13/V14，也没有执行真实 F1F-B2。

## 阻断事实

1. 初始清单错误地向 `run-f1f-b2-controlled-acceptance.ps1` 传递 `BuildProof`、数据库 host/port/name/user/schema 等松散参数；实际 Runner 只允许一个 `AuthorizationFile`。
2. 尚无安全的一次性正式准备入口，可从全新专用 PostgreSQL 目标创建数据库、角色、Schema、收紧权限并只执行主 V1—V13。

旧草案 ID `F1FB2_20260803_140506_96C6DFB7` 因冻结未通过而废弃。它从未写入数据库或正式治理证据，不得在后续授权、数据库、JAR、sidecar 或验收中复用。

## 校正后的冻结入口

DBPREP 合入后，必须基于新的集成完整 SHA 重新执行简化 FREEZE：先由用户单独批准正式数据库准备的显式端口与批准引用，准备成功后重新生成该 SHA 的正式 Runner JAR/sidecar/构建摘要，再创建只含非敏感冻结事实的严格授权文件。Runner 启动仍只接受：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\quant-server\scripts\run-f1f-b2-controlled-acceptance.ps1 `
  -AuthorizationFile "<正式非敏感授权文件路径>"
```

不得沿用旧 SHA、旧 JAR、旧 sidecar 或旧授权草案。首次 FREEZE 的 `NOT_READY` 是历史结论，不改变当前 `CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN` 和 `REDUCED_RESEARCH_OPERATIONAL_READY=false`。
