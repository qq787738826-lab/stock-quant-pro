from __future__ import annotations

from datetime import date
from enum import Enum
from typing import Any, Annotated, Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator


PositiveId = Annotated[int, Field(gt=0)]
Score = Annotated[int, Field(ge=0, le=100)]
Sha256 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[0-9]{6}$")]
STAGE_2B_DATA_QUALITY_RULE_VERSION = "1.4.0-stage-2b-dq-v1"
STAGE_2D_MARKET_REGIME_RULE_VERSION = "1.4.0-stage-2d-market-regime-v1"
STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION = "1.4.0-stage-2e-technical-analysis-v1"
STAGE_2F_STRATEGY_BACKTEST_RULE_VERSION = "1.4.0-stage-2f-strategy-backtest-v1"
STAGE_2H_POSITION_RISK_RULE_VERSION = "1.4.0-stage-2h-position-risk-v1"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class AgentCode(str, Enum):
    DATA_QUALITY = "DATA_QUALITY"
    MARKET_REGIME = "MARKET_REGIME"
    TECHNICAL_ANALYSIS = "TECHNICAL_ANALYSIS"
    STRATEGY_BACKTEST = "STRATEGY_BACKTEST"
    ANNOUNCEMENT_RISK = "ANNOUNCEMENT_RISK"
    POSITION_RISK = "POSITION_RISK"


class RunStatus(str, Enum):
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    FAILED = "FAILED"


class GateStatus(str, Enum):
    PASS = "PASS"
    WARN = "WARN"
    BLOCKED = "BLOCKED"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class AgentDecision(str, Enum):
    PASS = "PASS"
    WARN = "WARN"
    REJECT = "REJECT"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class FinalDecisionCode(str, Enum):
    REJECTED_BY_VETO = "REJECTED_BY_VETO"
    BLOCKED_BY_DATA_QUALITY = "BLOCKED_BY_DATA_QUALITY"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    RESEARCH_ONLY = "RESEARCH_ONLY"
    WATCH = "WATCH"
    PASS_TO_MANUAL_REVIEW = "PASS_TO_MANUAL_REVIEW"


class Severity(str, Enum):
    INFO = "INFO"
    WARN = "WARN"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class EvidenceCategory(str, Enum):
    MARKET_DATA = "MARKET_DATA"
    MARKET_BREADTH = "MARKET_BREADTH"
    TECHNICAL_INDICATOR = "TECHNICAL_INDICATOR"
    SCAN_RESULT = "SCAN_RESULT"
    BACKTEST_RESULT = "BACKTEST_RESULT"
    SECURITY_EVENT = "SECURITY_EVENT"
    PORTFOLIO_STATE = "PORTFOLIO_STATE"
    DATA_QUALITY = "DATA_QUALITY"
    QUERY_RESULT = "QUERY_RESULT"


class EvidenceSourceType(str, Enum):
    DATABASE = "DATABASE"
    LOCAL_CACHE = "LOCAL_CACHE"
    CONFIGURED_PROVIDER = "CONFIGURED_PROVIDER"
    JAVA_ENGINE = "JAVA_ENGINE"
    PYTHON_RULE_ENGINE = "PYTHON_RULE_ENGINE"


class RunIds(StrictModel):
    dataQuality: PositiveId
    marketRegime: PositiveId
    technicalAnalysis: PositiveId
    strategyBacktest: PositiveId
    announcementRisk: PositiveId
    positionRisk: PositiveId

    @model_validator(mode="after")
    def require_unique_run_ids(self) -> RunIds:
        values = [run_id for _, run_id in self.ordered()]
        if len(values) != len(set(values)):
            raise ValueError("六个专业智能体runId必须彼此唯一")
        return self

    def ordered(self) -> tuple[tuple[AgentCode, int], ...]:
        return (
            (AgentCode.DATA_QUALITY, self.dataQuality),
            (AgentCode.MARKET_REGIME, self.marketRegime),
            (AgentCode.TECHNICAL_ANALYSIS, self.technicalAnalysis),
            (AgentCode.STRATEGY_BACKTEST, self.strategyBacktest),
            (AgentCode.ANNOUNCEMENT_RISK, self.announcementRisk),
            (AgentCode.POSITION_RISK, self.positionRisk),
        )


class ContextSnapshot(BaseModel):
    model_config = ConfigDict(extra="allow", frozen=True)

    security: dict[str, Any]
    marketData: dict[str, Any]
    marketBreadth: dict[str, Any]
    scanResult: dict[str, Any]
    technicalMetrics: dict[str, Any]
    backtestContext: dict[str, Any]
    securityEvents: dict[str, Any]
    portfolioContext: dict[str, Any]
    dataQualityContext: dict[str, Any]


class AgentTeamRequest(StrictModel):
    schemaVersion: Literal["1.0"]
    taskId: PositiveId
    runIds: RunIds
    symbol: Symbol
    tradeDate: date
    contextHash: Sha256
    contextSchemaVersion: Annotated[str, Field(min_length=1)]
    ruleVersion: Annotated[str, Field(min_length=1)]
    executionMode: Literal["LOCAL_RULES"]
    contextSnapshot: ContextSnapshot
    requestedAt: AwareDatetime


class Finding(StrictModel):
    findingId: Annotated[str, Field(min_length=1)]
    code: Annotated[str, Field(min_length=1)]
    severity: Severity
    title: Annotated[str, Field(min_length=1)]
    detail: Annotated[str, Field(min_length=1)]
    evidenceIds: Annotated[list[Annotated[str, Field(min_length=1)]], Field(min_length=1)]

    @field_validator("evidenceIds")
    @classmethod
    def require_unique_evidence_ids(cls, values: list[str]) -> list[str]:
        return _require_unique(_require_non_blank(values, "Finding.evidenceIds"),
                               "Finding.evidenceIds")

    @model_validator(mode="after")
    def require_non_blank_fields(self) -> Finding:
        _require_non_blank(
            [self.findingId, self.code, self.title, self.detail], "Finding字段"
        )
        return self


class Evidence(StrictModel):
    evidenceId: Annotated[str, Field(min_length=1)]
    category: EvidenceCategory
    sourceType: EvidenceSourceType
    sourceName: Annotated[str, Field(min_length=1)]
    sourceRef: Annotated[str, Field(min_length=1)]
    symbol: Symbol
    tradeDate: date
    observedAt: AwareDatetime
    collectedAt: AwareDatetime
    fields: Annotated[dict[str, Any], Field(min_length=1)]
    contentHash: Sha256

    @model_validator(mode="after")
    def require_non_blank_fields(self) -> Evidence:
        _require_non_blank(
            [self.evidenceId, self.sourceName, self.sourceRef], "Evidence字段"
        )
        return self


class AgentError(StrictModel):
    code: Annotated[str, Field(min_length=1)]
    message: Annotated[str, Field(min_length=1)]


class AgentOutput(StrictModel):
    schemaVersion: Literal["1.0"] = "1.0"
    taskId: PositiveId
    runId: PositiveId
    agentCode: AgentCode
    status: RunStatus
    gateStatus: GateStatus
    decision: AgentDecision
    veto: bool
    score: Score
    confidence: Score
    summary: str
    findings: list[Finding]
    evidence: list[Evidence]
    errors: list[AgentError]
    contextHash: Sha256
    ruleVersion: Annotated[str, Field(min_length=1)]
    executionMode: Literal["LOCAL_RULES"]
    generatedAt: AwareDatetime

    @model_validator(mode="after")
    def validate_veto_semantics(self) -> AgentOutput:
        if not self.summary.strip():
            raise ValueError("AgentOutput.summary不能为空")
        if not self.veto:
            return self
        if self.agentCode is not AgentCode.POSITION_RISK:
            raise ValueError("只有POSITION_RISK可以返回veto=true")
        if self.status not in (RunStatus.COMPLETED, RunStatus.PARTIAL):
            raise ValueError("veto=true时status必须为COMPLETED或PARTIAL")
        if self.decision is not AgentDecision.REJECT:
            raise ValueError("veto=true时decision必须为REJECT")
        return self


class FormalVeto(StrictModel):
    vetoId: Annotated[str, Field(min_length=1)]
    taskId: PositiveId
    runId: PositiveId
    agentCode: Literal[AgentCode.POSITION_RISK]
    vetoCode: Annotated[str, Field(min_length=1)]
    reason: Annotated[str, Field(min_length=1)]
    evidenceIds: Annotated[list[Annotated[str, Field(min_length=1)]], Field(min_length=1)]
    createdAt: AwareDatetime

    @field_validator("evidenceIds")
    @classmethod
    def require_unique_evidence_ids(cls, values: list[str]) -> list[str]:
        return _require_unique(_require_non_blank(values, "FormalVeto.evidenceIds"),
                               "FormalVeto.evidenceIds")

    @model_validator(mode="after")
    def require_non_blank_fields(self) -> FormalVeto:
        _require_non_blank(
            [self.vetoId, self.vetoCode, self.reason], "FormalVeto字段"
        )
        return self


class FinalDecision(StrictModel):
    schemaVersion: Literal["1.0"] = "1.0"
    taskId: PositiveId
    decision: FinalDecisionCode
    gateStatus: GateStatus
    vetoed: bool
    score: Score
    confidence: Score
    summary: str
    findings: list[Finding]
    sourceRunIds: Annotated[list[PositiveId], Field(min_length=1)]
    vetoIds: list[Annotated[str, Field(min_length=1)]]
    contextHash: Sha256
    tradeDate: date
    ruleVersion: Annotated[str, Field(min_length=1)]
    executionMode: Literal["LOCAL_RULES"]
    generatedAt: AwareDatetime

    @field_validator("sourceRunIds")
    @classmethod
    def require_unique_source_run_ids(cls, values: list[int]) -> list[int]:
        return _require_unique(values, "FinalDecision.sourceRunIds")

    @field_validator("vetoIds")
    @classmethod
    def require_unique_veto_ids(cls, values: list[str]) -> list[str]:
        return _require_unique(_require_non_blank(values, "FinalDecision.vetoIds"),
                               "FinalDecision.vetoIds")

    @model_validator(mode="after")
    def require_non_blank_summary(self) -> FinalDecision:
        if not self.summary.strip():
            raise ValueError("FinalDecision.summary不能为空")
        return self


class AgentTeamResponse(StrictModel):
    schemaVersion: Literal["1.0"] = "1.0"
    taskId: PositiveId
    contextHash: Sha256
    tradeDate: date
    ruleVersion: Annotated[str, Field(min_length=1)]
    executionMode: Literal["LOCAL_RULES"]
    agentRuns: Annotated[list[AgentOutput], Field(min_length=6, max_length=6)]
    evidence: list[Evidence]
    vetoes: list[FormalVeto]
    finalDecision: FinalDecision
    generatedAt: AwareDatetime

    @model_validator(mode="after")
    def validate_team_consistency(self) -> AgentTeamResponse:
        expected_codes = set(AgentCode)
        codes = [run.agentCode for run in self.agentRuns]
        if len(set(codes)) != len(codes) or set(codes) != expected_codes:
            raise ValueError("agentRuns必须恰好包含六个不同的专业智能体")

        run_ids = [run.runId for run in self.agentRuns]
        if len(set(run_ids)) != len(run_ids):
            raise ValueError("agentRuns.runId必须彼此唯一")
        runs_by_code = {run.agentCode: run for run in self.agentRuns}

        for run in self.agentRuns:
            if (run.taskId != self.taskId or run.contextHash != self.contextHash
                    or run.ruleVersion != self.ruleVersion
                    or run.executionMode != self.executionMode):
                raise ValueError("agentRun身份字段与团队响应不一致")

        evidence_by_id: dict[str, Evidence] = {}
        for item in self.evidence:
            if item.evidenceId in evidence_by_id:
                raise ValueError("顶层evidenceId必须唯一")
            evidence_by_id[item.evidenceId] = item

        def validate_findings(findings: list[Finding]) -> None:
            for finding in findings:
                if any(evidence_id not in evidence_by_id for evidence_id in finding.evidenceIds):
                    raise ValueError("finding引用的evidenceId必须存在于顶层evidence")

        for run in self.agentRuns:
            validate_findings(run.findings)
            subset_ids: set[str] = set()
            for item in run.evidence:
                if item.evidenceId in subset_ids:
                    raise ValueError("单智能体evidenceId不能重复")
                subset_ids.add(item.evidenceId)
                if evidence_by_id.get(item.evidenceId) != item:
                    raise ValueError("单智能体evidence必须是顶层权威evidence的相同子集")

        veto_ids: set[str] = set()
        position_run = runs_by_code[AgentCode.POSITION_RISK]
        for veto in self.vetoes:
            if veto.vetoId in veto_ids:
                raise ValueError("顶层vetoId必须唯一")
            veto_ids.add(veto.vetoId)
            if (veto.taskId != self.taskId or veto.runId != position_run.runId
                    or veto.agentCode is not AgentCode.POSITION_RISK):
                raise ValueError("正式veto必须属于当前POSITION_RISK运行")
            if any(evidence_id not in evidence_by_id for evidence_id in veto.evidenceIds):
                raise ValueError("正式veto引用的evidenceId必须存在于顶层evidence")

        if position_run.veto != bool(self.vetoes):
            raise ValueError("POSITION_RISK输出veto与正式veto集合不一致")

        final = self.finalDecision
        if (final.taskId != self.taskId or final.contextHash != self.contextHash
                or final.tradeDate != self.tradeDate or final.ruleVersion != self.ruleVersion
                or final.executionMode != self.executionMode):
            raise ValueError("finalDecision身份字段与团队响应不一致")
        validate_findings(final.findings)
        if len(final.sourceRunIds) != 6 or set(final.sourceRunIds) != set(run_ids):
            raise ValueError("sourceRunIds必须恰好包含六个专业智能体runId")
        if set(final.vetoIds) != veto_ids:
            raise ValueError("finalDecision.vetoIds必须完整引用正式veto集合")

        if self.vetoes:
            if not final.vetoed or final.decision is not FinalDecisionCode.REJECTED_BY_VETO:
                raise ValueError("存在正式veto时总控必须REJECTED_BY_VETO且vetoed=true")
        elif final.vetoed or final.vetoIds or final.decision is FinalDecisionCode.REJECTED_BY_VETO:
            raise ValueError("不存在正式veto时总控不得处于正式否决状态")

        data_quality = runs_by_code[AgentCode.DATA_QUALITY]
        if data_quality.gateStatus is GateStatus.BLOCKED and not self.vetoes:
            if (final.decision is not FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                    or final.gateStatus is not GateStatus.BLOCKED or final.vetoed):
                raise ValueError("数据质量阻断且无正式veto时总控决策不一致")
        if self.ruleVersion == STAGE_2B_DATA_QUALITY_RULE_VERSION:
            self._validate_stage_2b_data_quality(runs_by_code)
        elif self.ruleVersion == STAGE_2D_MARKET_REGIME_RULE_VERSION:
            self._validate_stage_2d_market_regime(runs_by_code)
        elif self.ruleVersion == STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION:
            self._validate_stage_2e_technical_analysis(runs_by_code)
        elif self.ruleVersion == STAGE_2F_STRATEGY_BACKTEST_RULE_VERSION:
            self._validate_stage_2f_strategy_backtest(runs_by_code)
        elif self.ruleVersion == STAGE_2H_POSITION_RISK_RULE_VERSION:
            self._validate_stage_2h_position_risk(runs_by_code)
        return self

    def _validate_stage_2h_position_risk(
        self,
        runs_by_code: dict[AgentCode, AgentOutput],
    ) -> None:
        data_quality = runs_by_code[AgentCode.DATA_QUALITY]
        market_regime = runs_by_code[AgentCode.MARKET_REGIME]
        technical_analysis = runs_by_code[AgentCode.TECHNICAL_ANALYSIS]
        strategy_backtest = runs_by_code[AgentCode.STRATEGY_BACKTEST]
        announcement_risk = runs_by_code[AgentCode.ANNOUNCEMENT_RISK]
        position_risk = runs_by_code[AgentCode.POSITION_RISK]
        final = self.finalDecision

        if (announcement_risk.status is not RunStatus.INSUFFICIENT_DATA
                or announcement_risk.gateStatus is not GateStatus.NOT_APPLICABLE
                or announcement_risk.decision is not AgentDecision.NOT_APPLICABLE
                or announcement_risk.veto or announcement_risk.score != 0
                or announcement_risk.confidence != 0 or announcement_risk.findings
                or announcement_risk.evidence or announcement_risk.errors):
            raise ValueError("阶段2H ANNOUNCEMENT_RISK必须保持安全未实现")

        expected_source_run_ids = [runs_by_code[code].runId for code in AgentCode]
        if final.sourceRunIds != expected_source_run_ids:
            raise ValueError("阶段2H sourceRunIds必须保持六智能体固定顺序")

        if data_quality.gateStatus is GateStatus.BLOCKED:
            for run in (market_regime, technical_analysis):
                if (run.status is not RunStatus.INSUFFICIENT_DATA
                        or run.gateStatus is not GateStatus.NOT_APPLICABLE
                        or run.decision is not AgentDecision.NOT_APPLICABLE
                        or run.veto or run.score != 0 or run.confidence != 0
                        or run.findings or run.evidence or run.errors):
                    raise ValueError("阶段2H DATA_QUALITY阻断时既有分析规则必须安全降级")
            if (strategy_backtest.status is not RunStatus.INSUFFICIENT_DATA
                    or strategy_backtest.gateStatus is not GateStatus.BLOCKED
                    or strategy_backtest.decision is not AgentDecision.NOT_APPLICABLE
                    or strategy_backtest.veto or strategy_backtest.score != 0
                    or strategy_backtest.confidence != 0 or strategy_backtest.findings
                    or strategy_backtest.evidence or strategy_backtest.errors):
                raise ValueError("阶段2H DATA_QUALITY阻断时回测规则必须继承阻断")
        else:
            self._validate_stage_2d_market_regime_run(market_regime)
            self._validate_stage_2e_technical_analysis_run(
                technical_analysis, data_quality.gateStatus
            )
            self._validate_stage_2f_strategy_backtest_run(
                strategy_backtest, data_quality.gateStatus
            )

        if position_risk.status is RunStatus.INSUFFICIENT_DATA:
            if (position_risk.gateStatus is not GateStatus.NOT_APPLICABLE
                    or position_risk.decision is not AgentDecision.NOT_APPLICABLE
                    or position_risk.veto or position_risk.score != 0
                    or position_risk.confidence != 0 or position_risk.findings
                    or position_risk.evidence or len(position_risk.errors) != 1
                    or position_risk.errors[0].code not in {
                        "POSITION_RISK_INPUT_INVALID",
                        "PORTFOLIO_CONTEXT_NOT_CURRENT_DATE",
                        "PORTFOLIO_ACCOUNT_INVALID",
                        "PORTFOLIO_SETTINGS_INVALID",
                        "PORTFOLIO_POSITION_INVALID",
                        "PORTFOLIO_PRICE_MISSING",
                        "PORTFOLIO_PRICE_STALE",
                        "PORTFOLIO_ORDER_INVALID",
                    }
                    or self.vetoes):
                raise ValueError("阶段2H不可用POSITION_RISK状态无效")
        elif position_risk.status in (RunStatus.COMPLETED, RunStatus.PARTIAL):
            if (len(position_risk.findings) != 5
                    or [finding.code for finding in position_risk.findings]
                    != [
                        "POSITION_RISK_ACCOUNT_LOSS_ASSESSED",
                        "POSITION_RISK_CONCENTRATION_ASSESSED",
                        "POSITION_RISK_PENDING_EXPOSURE_ASSESSED",
                        "POSITION_RISK_EXIT_THRESHOLDS_ASSESSED",
                        "POSITION_RISK_CONTEXT_COMPLETENESS_ASSESSED",
                    ]
                    or len(position_risk.evidence) != 1
                    or position_risk.errors
                    or position_risk.confidence not in (40, 60, 80, 100)):
                raise ValueError("阶段2H有效POSITION_RISK输出无效")
            evidence = position_risk.evidence[0]
            if (evidence.category is not EvidenceCategory.PORTFOLIO_STATE
                    or evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                    or evidence.sourceName != "AgentPortfolioContextService"
                    or evidence.sourceRef != "contextSnapshot.portfolioContext"
                    or evidence.evidenceId
                    != f"position-risk-portfolio-{self.contextHash}"
                    or evidence.contentHash != self.contextHash
                    or set(evidence.fields) != {"portfolioContext"}):
                raise ValueError("阶段2H portfolio evidence契约无效")
            if self.vetoes:
                if (position_risk.gateStatus is not GateStatus.BLOCKED
                        or position_risk.decision is not AgentDecision.REJECT
                        or not position_risk.veto or position_risk.score != 0):
                    raise ValueError("阶段2H正式veto运行状态无效")
            elif (position_risk.gateStatus not in (GateStatus.PASS, GateStatus.WARN)
                  or position_risk.decision not in (AgentDecision.PASS, AgentDecision.WARN)
                  or position_risk.veto):
                raise ValueError("阶段2H无veto运行状态无效")
        else:
            raise ValueError("阶段2H POSITION_RISK终态无效")

        expected_evidence = [
            *data_quality.evidence,
            *market_regime.evidence,
            *technical_analysis.evidence,
            *strategy_backtest.evidence,
            *position_risk.evidence,
        ]
        expected_findings = [
            *data_quality.findings,
            *market_regime.findings,
            *technical_analysis.findings,
            *strategy_backtest.findings,
            *position_risk.findings,
        ]
        if self.evidence != expected_evidence or final.findings != expected_findings:
            raise ValueError("阶段2H顶层evidence或总控finding顺序无效")
        if self.vetoes:
            if (final.decision is not FinalDecisionCode.REJECTED_BY_VETO
                    or final.gateStatus is not GateStatus.BLOCKED
                    or not final.vetoed or final.score != 0
                    or final.confidence != position_risk.confidence
                    or final.vetoIds != [veto.vetoId for veto in self.vetoes]):
                raise ValueError("阶段2H正式veto总控优先级无效")
        elif data_quality.gateStatus is GateStatus.BLOCKED:
            if (final.decision is not FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                    or final.gateStatus is not GateStatus.BLOCKED
                    or final.vetoed or final.score != 0):
                raise ValueError("阶段2H DATA_QUALITY阻断总控状态无效")
        elif (final.decision is not FinalDecisionCode.INSUFFICIENT_DATA
              or final.vetoed or final.score != 0 or final.confidence != 0):
            raise ValueError("阶段2H无正式veto时总控必须因公告风险保持不足")

    def _validate_stage_2b_data_quality(
        self,
        runs_by_code: dict[AgentCode, AgentOutput],
    ) -> None:
        data_quality = runs_by_code[AgentCode.DATA_QUALITY]
        final = self.finalDecision

        if data_quality.veto or self.vetoes or final.vetoed or final.vetoIds:
            raise ValueError("阶段2B DATA_QUALITY规则不得产生正式veto")

        if data_quality.status is RunStatus.INSUFFICIENT_DATA:
            expected = (GateStatus.BLOCKED, AgentDecision.REJECT, 0, 0)
            if data_quality.evidence or data_quality.findings or not data_quality.errors:
                raise ValueError("阶段2B无效上下文不得生成证据或finding且必须返回错误")
        elif data_quality.status is RunStatus.COMPLETED:
            expected_by_gate = {
                GateStatus.BLOCKED: (AgentDecision.REJECT, 0, 100),
                GateStatus.WARN: (AgentDecision.WARN, 50, 100),
                GateStatus.PASS: (AgentDecision.PASS, 100, 100),
            }
            if data_quality.gateStatus not in expected_by_gate:
                raise ValueError("阶段2B有效DATA_QUALITY gateStatus必须为PASS、WARN或BLOCKED")
            decision, score, confidence = expected_by_gate[data_quality.gateStatus]
            expected = (data_quality.gateStatus, decision, score, confidence)
            if (len(data_quality.evidence) != 1 or len(self.evidence) != 1
                    or self.evidence != data_quality.evidence or data_quality.errors):
                raise ValueError("阶段2B有效上下文必须生成唯一质量证据且不得返回错误")
            evidence = data_quality.evidence[0]
            if (evidence.category is not EvidenceCategory.DATA_QUALITY
                    or evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                    or evidence.sourceName != "AgentContextSnapshotService"
                    or evidence.sourceRef != "contextSnapshot"
                    or evidence.evidenceId != f"dq-context-{self.contextHash}"
                    or evidence.contentHash != self.contextHash
                    or evidence.tradeDate != self.tradeDate
                    or set(evidence.fields) != {
                        "security", "marketData", "technicalMetrics", "dataQualityContext"
                    }):
                raise ValueError("阶段2B质量证据元数据或四类事实投影不一致")
        else:
            raise ValueError("阶段2B DATA_QUALITY status必须为COMPLETED或INSUFFICIENT_DATA")

        actual = (
            data_quality.gateStatus,
            data_quality.decision,
            data_quality.score,
            data_quality.confidence,
        )
        if actual != expected:
            raise ValueError("阶段2B DATA_QUALITY状态映射不一致")

        for code, run in runs_by_code.items():
            if code is AgentCode.DATA_QUALITY:
                continue
            if (run.status is not RunStatus.INSUFFICIENT_DATA
                    or run.gateStatus is not GateStatus.NOT_APPLICABLE
                    or run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.veto or run.score != 0 or run.confidence != 0
                    or run.findings or run.evidence):
                raise ValueError("阶段2B其余五个未实现规则必须保持数据不足状态")

        if data_quality.gateStatus is GateStatus.BLOCKED:
            final_expected = (
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                GateStatus.BLOCKED,
                0,
                data_quality.confidence,
            )
        else:
            final_expected = (
                FinalDecisionCode.INSUFFICIENT_DATA,
                data_quality.gateStatus,
                0,
                0,
            )
            if "数据质量门禁阻断" in final.summary:
                raise ValueError("DATA_QUALITY未阻断时总控摘要不得声称数据质量阻断")
        final_actual = (
            final.decision,
            final.gateStatus,
            final.score,
            final.confidence,
        )
        if final_actual != final_expected:
            raise ValueError("阶段2B finalDecision状态映射不一致")

    def _validate_stage_2d_market_regime(
        self,
        runs_by_code: dict[AgentCode, AgentOutput],
    ) -> None:
        data_quality = runs_by_code[AgentCode.DATA_QUALITY]
        market_regime = runs_by_code[AgentCode.MARKET_REGIME]
        final = self.finalDecision

        if self.vetoes or final.vetoed or final.vetoIds \
                or data_quality.veto or market_regime.veto:
            raise ValueError("阶段2D-1规则不得产生正式veto")

        if data_quality.status is RunStatus.INSUFFICIENT_DATA:
            expected_dq = (GateStatus.BLOCKED, AgentDecision.REJECT, 0, 0)
            if data_quality.evidence or data_quality.findings or not data_quality.errors:
                raise ValueError("阶段2D-1无效DATA_QUALITY上下文必须安全阻断")
        elif data_quality.status is RunStatus.COMPLETED:
            expected_by_gate = {
                GateStatus.BLOCKED: (AgentDecision.REJECT, 0, 100),
                GateStatus.WARN: (AgentDecision.WARN, 50, 100),
                GateStatus.PASS: (AgentDecision.PASS, 100, 100),
            }
            if data_quality.gateStatus not in expected_by_gate:
                raise ValueError("阶段2D-1必须完整复用阶段2B DATA_QUALITY门禁")
            decision, score, confidence = expected_by_gate[data_quality.gateStatus]
            expected_dq = (data_quality.gateStatus, decision, score, confidence)
            if len(data_quality.evidence) != 1 or data_quality.errors:
                raise ValueError("阶段2D-1有效DATA_QUALITY必须保留唯一质量证据")
            evidence = data_quality.evidence[0]
            if (evidence.category is not EvidenceCategory.DATA_QUALITY
                    or evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                    or evidence.sourceName != "AgentContextSnapshotService"
                    or evidence.sourceRef != "contextSnapshot"
                    or evidence.evidenceId != f"dq-context-{self.contextHash}"
                    or evidence.contentHash != self.contextHash
                    or evidence.tradeDate != self.tradeDate
                    or set(evidence.fields) != {
                        "security", "marketData", "technicalMetrics", "dataQualityContext"
                    }):
                raise ValueError("阶段2D-1 DATA_QUALITY证据必须保持阶段2B直接投影契约")
        else:
            raise ValueError("阶段2D-1 DATA_QUALITY状态无效")

        actual_dq = (
            data_quality.gateStatus,
            data_quality.decision,
            data_quality.score,
            data_quality.confidence,
        )
        if actual_dq != expected_dq:
            raise ValueError("阶段2D-1 DATA_QUALITY状态映射不一致")

        for code in (
            AgentCode.TECHNICAL_ANALYSIS,
            AgentCode.STRATEGY_BACKTEST,
            AgentCode.ANNOUNCEMENT_RISK,
            AgentCode.POSITION_RISK,
        ):
            run = runs_by_code[code]
            if (run.status is not RunStatus.INSUFFICIENT_DATA
                    or run.gateStatus is not GateStatus.NOT_APPLICABLE
                    or run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.veto or run.score != 0 or run.confidence != 0
                    or run.findings or run.evidence):
                raise ValueError("阶段2D-1其余四个专业智能体必须保持未实现状态")

        expected_source_run_ids = [runs_by_code[code].runId for code in AgentCode]
        if final.sourceRunIds != expected_source_run_ids:
            raise ValueError("阶段2D-1 sourceRunIds必须保持六智能体固定顺序")

        if data_quality.gateStatus is GateStatus.BLOCKED:
            if (market_regime.status is not RunStatus.INSUFFICIENT_DATA
                    or market_regime.gateStatus is not GateStatus.NOT_APPLICABLE
                    or market_regime.decision is not AgentDecision.NOT_APPLICABLE
                    or market_regime.score != 0 or market_regime.confidence != 0
                    or market_regime.findings or market_regime.evidence or market_regime.errors):
                raise ValueError("DATA_QUALITY阻断时阶段2D-1宽度规则不得执行")
            if self.evidence != data_quality.evidence:
                raise ValueError("DATA_QUALITY阻断时顶层仅允许质量证据")
            expected_final = (
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                GateStatus.BLOCKED,
                0,
                data_quality.confidence,
            )
            if final.findings != data_quality.findings:
                raise ValueError("DATA_QUALITY阻断时总控不得增加宽度finding")
        else:
            self._validate_stage_2d_market_regime_run(market_regime)
            expected_evidence = [*data_quality.evidence, *market_regime.evidence]
            if self.evidence != expected_evidence:
                raise ValueError("阶段2D-1顶层证据必须按DATA_QUALITY、MARKET_REGIME排序")
            expected_final = (
                FinalDecisionCode.INSUFFICIENT_DATA,
                data_quality.gateStatus,
                0,
                0,
            )
            if final.findings != [*data_quality.findings, *market_regime.findings]:
                raise ValueError("阶段2D-1总控finding必须按DATA_QUALITY、MARKET_REGIME拼接")

        actual_final = (
            final.decision,
            final.gateStatus,
            final.score,
            final.confidence,
        )
        if actual_final != expected_final:
            raise ValueError("阶段2D-1 finalDecision必须保持安全不足状态")

    def _validate_stage_2d_market_regime_run(self, run: AgentOutput) -> None:
        if run.gateStatus not in (GateStatus.PASS, GateStatus.WARN):
            raise ValueError("阶段2D-1 MARKET_REGIME必须继承DATA_QUALITY PASS或WARN")

        input_error = (
            run.status is RunStatus.INSUFFICIENT_DATA
            and not run.findings
            and not run.evidence
            and len(run.errors) == 1
            and run.errors[0].code == "MARKET_BREADTH_INPUT_INVALID"
        )
        if input_error:
            if (run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.score != 0 or run.confidence != 0):
                raise ValueError("阶段2D-1非法输入必须安全降级")
            return

        if len(run.evidence) != 1 or run.errors:
            raise ValueError("阶段2D-1可解析宽度输入必须生成唯一证据且不得返回错误")
        evidence = run.evidence[0]
        expected_fields = {
            "available", "reasonCode", "sourceType", "sourceTables", "sourceStatus",
            "producer", "producerVersion", "versionAvailable", "requestedTradeDate",
            "effectiveTradeDate", "previousEffectiveTradeDate", "exactTradeDateMatch",
            "pointInTimeGuaranteed", "barFutureDataExcluded",
            "universePointInTimeGuaranteed", "futureDataExcluded",
            "timestampTimezoneSemantics", "adjustType", "selectionRule", "universeCount",
            "coveredSymbolCount", "comparableSymbolCount", "advancingCount",
            "decliningCount", "unchangedCount", "missingCurrentBarCount",
            "missingPreviousBarCount", "coverageRatio", "limitations",
        }
        market_breadth = evidence.fields.get("marketBreadth")
        if (evidence.evidenceId != f"mr-breadth-{self.contextHash}"
                or evidence.category is not EvidenceCategory.MARKET_BREADTH
                or evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                or evidence.sourceName != "AgentMarketBreadthContextService"
                or evidence.sourceRef != "contextSnapshot.marketBreadth"
                or evidence.tradeDate != self.tradeDate
                or evidence.contentHash != self.contextHash
                or set(evidence.fields) != {"marketBreadth"}
                or not isinstance(market_breadth, dict)
                or set(market_breadth) != expected_fields):
            raise ValueError("阶段2D-1 MARKET_REGIME证据元数据或字段白名单不一致")

        severities = {
            "MARKET_BREADTH_FACT_INCONSISTENT": Severity.HIGH,
            "MARKET_BREADTH_UNAVAILABLE": Severity.WARN,
            "MARKET_BREADTH_LOW_COVERAGE": Severity.WARN,
            "MARKET_BREADTH_DATE_NOT_EXACT": Severity.WARN,
            "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED": Severity.WARN,
            "MARKET_BREADTH_POSITIVE": Severity.INFO,
            "MARKET_BREADTH_MIXED": Severity.INFO,
            "MARKET_BREADTH_NEGATIVE": Severity.INFO,
        }
        order = tuple(severities)
        codes = [finding.code for finding in run.findings]
        if (len(codes) != len(set(codes))
                or any(code not in severities for code in codes)
                or codes != sorted(codes, key=order.index)):
            raise ValueError("阶段2D-1 MARKET_REGIME finding白名单、唯一性或顺序无效")
        for finding in run.findings:
            rank = order.index(finding.code) + 1
            expected_id = (
                f"mr-{rank:02d}-{finding.code.lower().replace('_', '-')}"
                f"-{self.contextHash}"
            )
            if (finding.severity is not severities[finding.code]
                    or finding.findingId != expected_id
                    or finding.evidenceIds != [evidence.evidenceId]):
                raise ValueError("阶段2D-1 MARKET_REGIME finding内容与证据引用无效")

        direction_codes = {
            "MARKET_BREADTH_POSITIVE",
            "MARKET_BREADTH_MIXED",
            "MARKET_BREADTH_NEGATIVE",
        }
        if run.status is RunStatus.COMPLETED:
            directions = direction_codes.intersection(codes)
            if (run.decision is not AgentDecision.WARN
                    or run.confidence != 0
                    or codes[:1] != ["MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED"]
                    or len(directions) != 1 or len(codes) != 2):
                raise ValueError("阶段2D-1有效宽度分类状态不一致")
            direction = next(iter(directions))
            if ((direction == "MARKET_BREADTH_POSITIVE" and run.score <= 50)
                    or (direction == "MARKET_BREADTH_MIXED" and run.score != 50)
                    or (direction == "MARKET_BREADTH_NEGATIVE" and run.score >= 50)):
                raise ValueError("阶段2D-1宽度方向与score不一致")
        elif run.status is RunStatus.INSUFFICIENT_DATA:
            blockers = set(order[:5])
            if (run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.score != 0 or run.confidence != 0
                    or len(codes) != 1 or codes[0] not in blockers
                    or direction_codes.intersection(codes)):
                raise ValueError("阶段2D-1宽度资格不足不得产生方向或低分映射")
        else:
            raise ValueError("阶段2D-1 MARKET_REGIME status必须为COMPLETED或INSUFFICIENT_DATA")

    def _validate_stage_2e_technical_analysis(
        self,
        runs_by_code: dict[AgentCode, AgentOutput],
    ) -> None:
        data_quality = runs_by_code[AgentCode.DATA_QUALITY]
        market_regime = runs_by_code[AgentCode.MARKET_REGIME]
        technical_analysis = runs_by_code[AgentCode.TECHNICAL_ANALYSIS]
        final = self.finalDecision

        if (self.vetoes or final.vetoed or final.vetoIds
                or any(run.veto for run in runs_by_code.values())):
            raise ValueError("阶段2E-1规则不得产生正式veto")

        if data_quality.status is RunStatus.INSUFFICIENT_DATA:
            expected_dq = (GateStatus.BLOCKED, AgentDecision.REJECT, 0, 0)
            if data_quality.evidence or data_quality.findings or not data_quality.errors:
                raise ValueError("阶段2E-1无效DATA_QUALITY上下文必须安全阻断")
        elif data_quality.status is RunStatus.COMPLETED:
            expected_by_gate = {
                GateStatus.BLOCKED: (AgentDecision.REJECT, 0, 100),
                GateStatus.WARN: (AgentDecision.WARN, 50, 100),
                GateStatus.PASS: (AgentDecision.PASS, 100, 100),
            }
            if data_quality.gateStatus not in expected_by_gate:
                raise ValueError("阶段2E-1必须完整复用阶段2B DATA_QUALITY门禁")
            decision, score, confidence = expected_by_gate[data_quality.gateStatus]
            expected_dq = (data_quality.gateStatus, decision, score, confidence)
            if len(data_quality.evidence) != 1 or data_quality.errors:
                raise ValueError("阶段2E-1有效DATA_QUALITY必须保留唯一质量证据")
            evidence = data_quality.evidence[0]
            if (evidence.category is not EvidenceCategory.DATA_QUALITY
                    or evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                    or evidence.sourceName != "AgentContextSnapshotService"
                    or evidence.sourceRef != "contextSnapshot"
                    or evidence.evidenceId != f"dq-context-{self.contextHash}"
                    or evidence.contentHash != self.contextHash
                    or evidence.tradeDate != self.tradeDate
                    or set(evidence.fields) != {
                        "security", "marketData", "technicalMetrics", "dataQualityContext"
                    }):
                raise ValueError("阶段2E-1 DATA_QUALITY证据必须保持阶段2B直接投影契约")
        else:
            raise ValueError("阶段2E-1 DATA_QUALITY状态无效")

        actual_dq = (
            data_quality.gateStatus,
            data_quality.decision,
            data_quality.score,
            data_quality.confidence,
        )
        if actual_dq != expected_dq:
            raise ValueError("阶段2E-1 DATA_QUALITY状态映射不一致")

        for code in (
            AgentCode.STRATEGY_BACKTEST,
            AgentCode.ANNOUNCEMENT_RISK,
            AgentCode.POSITION_RISK,
        ):
            run = runs_by_code[code]
            if (run.status is not RunStatus.INSUFFICIENT_DATA
                    or run.gateStatus is not GateStatus.NOT_APPLICABLE
                    or run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.veto or run.score != 0 or run.confidence != 0
                    or run.findings or run.evidence or run.errors):
                raise ValueError("阶段2E-1其余三个专业智能体必须保持未实现状态")

        expected_source_run_ids = [runs_by_code[code].runId for code in AgentCode]
        if final.sourceRunIds != expected_source_run_ids:
            raise ValueError("阶段2E-1 sourceRunIds必须保持六智能体固定顺序")

        if data_quality.gateStatus is GateStatus.BLOCKED:
            for run in (market_regime, technical_analysis):
                if (run.status is not RunStatus.INSUFFICIENT_DATA
                        or run.gateStatus is not GateStatus.NOT_APPLICABLE
                        or run.decision is not AgentDecision.NOT_APPLICABLE
                        or run.score != 0 or run.confidence != 0
                        or run.findings or run.evidence or run.errors):
                    raise ValueError("DATA_QUALITY阻断时阶段2D-1和2E-1规则不得执行")
            if self.evidence != data_quality.evidence:
                raise ValueError("DATA_QUALITY阻断时顶层仅允许质量证据")
            expected_final = (
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                GateStatus.BLOCKED,
                0,
                data_quality.confidence,
            )
            if final.findings != data_quality.findings:
                raise ValueError("DATA_QUALITY阻断时总控不得增加技术finding")
        else:
            self._validate_stage_2d_market_regime_run(market_regime)
            self._validate_stage_2e_technical_analysis_run(
                technical_analysis,
                data_quality.gateStatus,
            )
            expected_evidence = [
                *data_quality.evidence,
                *market_regime.evidence,
                *technical_analysis.evidence,
            ]
            if self.evidence != expected_evidence:
                raise ValueError(
                    "阶段2E-1顶层证据必须按DATA_QUALITY、MARKET_REGIME、"
                    "TECHNICAL_ANALYSIS排序"
                )
            expected_final = (
                FinalDecisionCode.INSUFFICIENT_DATA,
                data_quality.gateStatus,
                0,
                0,
            )
            expected_findings = [
                *data_quality.findings,
                *market_regime.findings,
                *technical_analysis.findings,
            ]
            if final.findings != expected_findings:
                raise ValueError(
                    "阶段2E-1总控finding必须按DATA_QUALITY、MARKET_REGIME、"
                    "TECHNICAL_ANALYSIS拼接"
                )

        actual_final = (
            final.decision,
            final.gateStatus,
            final.score,
            final.confidence,
        )
        if actual_final != expected_final:
            raise ValueError("阶段2E-1 finalDecision必须保持安全不足状态")

    def _validate_stage_2f_strategy_backtest(
        self,
        runs_by_code: dict[AgentCode, AgentOutput],
    ) -> None:
        data_quality = runs_by_code[AgentCode.DATA_QUALITY]
        market_regime = runs_by_code[AgentCode.MARKET_REGIME]
        technical_analysis = runs_by_code[AgentCode.TECHNICAL_ANALYSIS]
        strategy_backtest = runs_by_code[AgentCode.STRATEGY_BACKTEST]
        final = self.finalDecision
        if (self.vetoes or final.vetoed or final.vetoIds
                or any(run.veto for run in runs_by_code.values())):
            raise ValueError("阶段2F不得产生正式veto")

        if data_quality.status is RunStatus.INSUFFICIENT_DATA:
            expected_dq = (GateStatus.BLOCKED, AgentDecision.REJECT, 0, 0)
            if data_quality.evidence or data_quality.findings or not data_quality.errors:
                raise ValueError("阶段2F无效DATA_QUALITY上下文必须安全阻断")
        elif data_quality.status is RunStatus.COMPLETED:
            expected_by_gate = {
                GateStatus.BLOCKED: (AgentDecision.REJECT, 0, 100),
                GateStatus.WARN: (AgentDecision.WARN, 50, 100),
                GateStatus.PASS: (AgentDecision.PASS, 100, 100),
            }
            if data_quality.gateStatus not in expected_by_gate:
                raise ValueError("阶段2F必须复用DATA_QUALITY门禁")
            decision, score, confidence = expected_by_gate[data_quality.gateStatus]
            expected_dq = (data_quality.gateStatus, decision, score, confidence)
            if len(data_quality.evidence) != 1 or data_quality.errors:
                raise ValueError("阶段2F有效DATA_QUALITY必须保留唯一质量证据")
        else:
            raise ValueError("阶段2F DATA_QUALITY状态无效")
        if (
            data_quality.gateStatus,
            data_quality.decision,
            data_quality.score,
            data_quality.confidence,
        ) != expected_dq:
            raise ValueError("阶段2F DATA_QUALITY状态映射不一致")

        for code in (AgentCode.ANNOUNCEMENT_RISK, AgentCode.POSITION_RISK):
            run = runs_by_code[code]
            if (run.status is not RunStatus.INSUFFICIENT_DATA
                    or run.gateStatus is not GateStatus.NOT_APPLICABLE
                    or run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.veto or run.score != 0 or run.confidence != 0
                    or run.findings or run.evidence or run.errors):
                raise ValueError("阶段2F其余两个专业智能体必须保持未实现状态")

        expected_source_run_ids = [runs_by_code[code].runId for code in AgentCode]
        if final.sourceRunIds != expected_source_run_ids:
            raise ValueError("阶段2F sourceRunIds必须保持六智能体固定顺序")

        if data_quality.gateStatus is GateStatus.BLOCKED:
            for run in (market_regime, technical_analysis):
                if (run.status is not RunStatus.INSUFFICIENT_DATA
                        or run.gateStatus is not GateStatus.NOT_APPLICABLE
                        or run.decision is not AgentDecision.NOT_APPLICABLE
                        or run.score != 0 or run.confidence != 0
                        or run.findings or run.evidence or run.errors):
                    raise ValueError("DATA_QUALITY阻断时既有分析规则不得执行")
            if (strategy_backtest.status is not RunStatus.INSUFFICIENT_DATA
                    or strategy_backtest.gateStatus is not GateStatus.BLOCKED
                    or strategy_backtest.decision is not AgentDecision.NOT_APPLICABLE
                    or strategy_backtest.score != 0
                    or strategy_backtest.confidence != 0
                    or strategy_backtest.findings
                    or strategy_backtest.evidence
                    or strategy_backtest.errors):
                raise ValueError("DATA_QUALITY阻断时STRATEGY_BACKTEST必须继承阻断")
            expected_evidence = data_quality.evidence
            expected_findings = data_quality.findings
            expected_final = (
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                GateStatus.BLOCKED,
                0,
                data_quality.confidence,
            )
        else:
            self._validate_stage_2d_market_regime_run(market_regime)
            self._validate_stage_2e_technical_analysis_run(
                technical_analysis,
                data_quality.gateStatus,
            )
            self._validate_stage_2f_strategy_backtest_run(
                strategy_backtest,
                data_quality.gateStatus,
            )
            expected_evidence = [
                *data_quality.evidence,
                *market_regime.evidence,
                *technical_analysis.evidence,
                *strategy_backtest.evidence,
            ]
            expected_findings = [
                *data_quality.findings,
                *market_regime.findings,
                *technical_analysis.findings,
                *strategy_backtest.findings,
            ]
            expected_final = (
                FinalDecisionCode.INSUFFICIENT_DATA,
                data_quality.gateStatus,
                0,
                0,
            )
        if self.evidence != expected_evidence or final.findings != expected_findings:
            raise ValueError("阶段2F顶层evidence或final finding顺序不一致")
        if (
            final.decision,
            final.gateStatus,
            final.score,
            final.confidence,
        ) != expected_final:
            raise ValueError("阶段2F总控必须保持安全不足状态")

    def _validate_stage_2f_strategy_backtest_run(
        self,
        run: AgentOutput,
        data_quality_gate: GateStatus,
    ) -> None:
        if run.gateStatus is not data_quality_gate:
            raise ValueError("阶段2F STRATEGY_BACKTEST必须继承DATA_QUALITY门禁")
        if run.status is RunStatus.COMPLETED:
            expected_confidence = (
                50 if data_quality_gate is GateStatus.WARN
                else run.confidence
            )
            if (run.decision is not AgentDecision.WARN
                    or len(run.findings) != 5
                    or len(run.evidence) != 1
                    or run.errors
                    or run.confidence not in (40, 50, 60, 80)
                    or data_quality_gate is GateStatus.WARN
                    and run.confidence > expected_confidence):
                raise ValueError("阶段2F有效STRATEGY_BACKTEST状态无效")
            evidence = run.evidence[0]
            if (evidence.category is not EvidenceCategory.BACKTEST_RESULT
                    or evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                    or evidence.sourceName != "AgentBacktestContextService"
                    or evidence.sourceRef != "contextSnapshot.backtestContext"
                    or set(evidence.fields) != {"backtestContext"}):
                raise ValueError("阶段2F回测证据契约无效")
            if [finding.code for finding in run.findings] != [
                "STRATEGY_BACKTEST_SAMPLE_SUFFICIENT",
                "STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED",
                "STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED",
                "STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED",
                "STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED",
            ]:
                raise ValueError("阶段2F必须输出固定五类回测finding")
        elif run.status is RunStatus.INSUFFICIENT_DATA:
            if (run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.score != 0 or run.confidence != 0
                    or run.findings or len(run.errors) != 1
                    or run.errors[0].code not in {
                        "STRATEGY_BACKTEST_INPUT_INVALID",
                        "STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT",
                        "BACKTEST_NO_TRUSTED_PIT_DAILY_BARS",
                        "BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE",
                        "BACKTEST_SOURCE_REVISION_UNVERIFIABLE",
                        "BACKTEST_CUTOFF_POLLUTION_UNRESOLVED",
                        "BACKTEST_SAMPLE_INSUFFICIENT",
                        "BACKTEST_DAILY_BAR_INVALID",
                        "BACKTEST_STRATEGY_VERSION_UNVERIFIABLE",
                        "BACKTEST_PARAMS_INVALID",
                        "BACKTEST_HASH_MISMATCH",
                        "BACKTEST_REPLAY_MISMATCH",
                        "BACKTEST_FUTURE_REQUEST_DATE",
                        "BACKTEST_DECISION_TIME_NOT_REACHED",
                    }):
                raise ValueError("阶段2F回测安全降级状态无效")
            if (run.errors[0].code == "STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT"
                    and len(run.evidence) != 1):
                raise ValueError("阶段2F交易样本不足必须保留可靠事实证据")
            if (run.errors[0].code != "STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT"
                    and run.evidence):
                raise ValueError("阶段2F非法或不可用输入不得生成回测证据")
        else:
            raise ValueError("阶段2F STRATEGY_BACKTEST终态无效")

    def _validate_stage_2e_technical_analysis_run(
        self,
        run: AgentOutput,
        data_quality_gate: GateStatus,
    ) -> None:
        if run.gateStatus is not data_quality_gate \
                or data_quality_gate not in (GateStatus.PASS, GateStatus.WARN):
            raise ValueError("阶段2E-1 TECHNICAL_ANALYSIS必须继承DATA_QUALITY PASS或WARN")

        input_error = (
            run.status is RunStatus.INSUFFICIENT_DATA
            and not run.findings
            and not run.evidence
            and len(run.errors) == 1
            and run.errors[0].code == "TECHNICAL_ANALYSIS_INPUT_INVALID"
        )
        if input_error:
            if (run.decision is not AgentDecision.NOT_APPLICABLE
                    or run.score != 0 or run.confidence != 0):
                raise ValueError("阶段2E-1非法输入必须安全降级且不得形成技术评分")
            return

        if (run.status is not RunStatus.COMPLETED
                or run.decision is not AgentDecision.WARN
                or len(run.evidence) != 2 or run.errors
                or len(run.findings) != 5):
            raise ValueError("阶段2E-1有效技术输入必须生成两份证据和五类finding")
        expected_confidence = 100 if data_quality_gate is GateStatus.PASS else 50
        if run.confidence != expected_confidence:
            raise ValueError("阶段2E-1 TECHNICAL_ANALYSIS confidence未继承质量门禁语义")

        metrics_evidence, market_evidence = run.evidence
        metrics = metrics_evidence.fields.get("technicalMetrics")
        latest_bar = market_evidence.fields.get("marketData")
        if (metrics_evidence.evidenceId != f"ta-metrics-{self.contextHash}"
                or metrics_evidence.category is not EvidenceCategory.TECHNICAL_INDICATOR
                or metrics_evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                or metrics_evidence.sourceName != "AgentTechnicalMetricsService"
                or metrics_evidence.sourceRef != "contextSnapshot.technicalMetrics"
                or metrics_evidence.tradeDate != self.tradeDate
                or metrics_evidence.contentHash != self.contextHash
                or set(metrics_evidence.fields) != {"technicalMetrics"}
                or not isinstance(metrics, dict)
                or set(metrics) != {
                    "available", "formulaVersion", "adjustType", "requestedTradeDate",
                    "effectiveTradeDate", "requiredBars", "actualBars", "windows", "values",
                }
                or not isinstance(metrics.get("windows"), dict)
                or set(metrics["windows"]) != {
                    "ma5", "ma20", "ma60", "rsi14", "atr14",
                    "averageVolume20", "highestClose20",
                }
                or not isinstance(metrics.get("values"), dict)
                or set(metrics["values"]) != {
                    "ma5", "ma20", "ma60", "rsi14", "atr14",
                    "averageVolume20", "highestClose20",
                }):
            raise ValueError("阶段2E-1 technicalMetrics证据元数据或字段白名单不一致")

        market_data = latest_bar
        if (market_evidence.evidenceId != f"ta-market-{self.contextHash}"
                or market_evidence.category is not EvidenceCategory.MARKET_DATA
                or market_evidence.sourceType is not EvidenceSourceType.JAVA_ENGINE
                or market_evidence.sourceName != "AgentContextSnapshotService"
                or market_evidence.sourceRef != "contextSnapshot.marketData"
                or market_evidence.tradeDate != self.tradeDate
                or market_evidence.contentHash != self.contextHash
                or set(market_evidence.fields) != {"marketData"}
                or not isinstance(market_data, dict)
                or set(market_data) != {
                    "available", "adjustType", "requestedTradeDate", "effectiveTradeDate",
                    "exactTradeDateMatch", "actualBars", "latestBar",
                }
                or not isinstance(market_data.get("latestBar"), dict)
                or set(market_data["latestBar"]) != {
                    "symbol", "tradeDate", "open", "high", "low", "close",
                    "volume", "amount", "turnoverRate",
                }):
            raise ValueError("阶段2E-1 marketData证据元数据或字段白名单不一致")
        if (metrics_evidence.observedAt != market_evidence.observedAt
                or metrics_evidence.collectedAt != market_evidence.collectedAt):
            raise ValueError("阶段2E-1两份技术证据时间语义必须一致")

        titles = {
            "TECH_TREND_BULLISH_ALIGNED": "均线多头排列",
            "TECH_TREND_MIXED": "均线状态混合",
            "TECH_TREND_BEARISH_ALIGNED": "均线空头排列",
            "TECH_RSI_OVERBOUGHT_RISK": "RSI达到超买风险阈值",
            "TECH_RSI_POSITIVE_MOMENTUM": "RSI处于正向动量区间",
            "TECH_RSI_NEUTRAL": "RSI位于中点",
            "TECH_RSI_NEGATIVE_MOMENTUM": "RSI处于负向动量区间",
            "TECH_RSI_OVERSOLD_RISK": "RSI达到超卖风险阈值",
            "TECH_PRICE_ABOVE_MA20_EXTENDED": "价格高于MA20偏离阈值",
            "TECH_PRICE_NEAR_MA20": "价格位于MA20偏离阈值内",
            "TECH_PRICE_BELOW_MA20_EXTENDED": "价格低于MA20偏离阈值",
            "TECH_VOLATILITY_ELEVATED": "ATR相对波动达到升高阈值",
            "TECH_VOLATILITY_NORMAL": "ATR相对波动低于升高阈值",
            "TECH_INDICATORS_BULLISH_CONFIRMED": "趋势与动量正向确认",
            "TECH_INDICATORS_BEARISH_CONFIRMED": "趋势与动量负向确认",
            "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED": "技术指标冲突或未确认",
        }
        severities = {
            "TECH_TREND_BULLISH_ALIGNED": Severity.INFO,
            "TECH_TREND_MIXED": Severity.WARN,
            "TECH_TREND_BEARISH_ALIGNED": Severity.WARN,
            "TECH_RSI_OVERBOUGHT_RISK": Severity.WARN,
            "TECH_RSI_POSITIVE_MOMENTUM": Severity.INFO,
            "TECH_RSI_NEUTRAL": Severity.INFO,
            "TECH_RSI_NEGATIVE_MOMENTUM": Severity.WARN,
            "TECH_RSI_OVERSOLD_RISK": Severity.WARN,
            "TECH_PRICE_ABOVE_MA20_EXTENDED": Severity.WARN,
            "TECH_PRICE_NEAR_MA20": Severity.INFO,
            "TECH_PRICE_BELOW_MA20_EXTENDED": Severity.WARN,
            "TECH_VOLATILITY_ELEVATED": Severity.WARN,
            "TECH_VOLATILITY_NORMAL": Severity.INFO,
            "TECH_INDICATORS_BULLISH_CONFIRMED": Severity.INFO,
            "TECH_INDICATORS_BEARISH_CONFIRMED": Severity.WARN,
            "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED": Severity.WARN,
        }
        impacts = {
            "TECH_TREND_BULLISH_ALIGNED": 20,
            "TECH_TREND_MIXED": 0,
            "TECH_TREND_BEARISH_ALIGNED": -20,
            "TECH_RSI_OVERBOUGHT_RISK": -10,
            "TECH_RSI_POSITIVE_MOMENTUM": 15,
            "TECH_RSI_NEUTRAL": 0,
            "TECH_RSI_NEGATIVE_MOMENTUM": -15,
            "TECH_RSI_OVERSOLD_RISK": -10,
            "TECH_PRICE_ABOVE_MA20_EXTENDED": -10,
            "TECH_PRICE_NEAR_MA20": 0,
            "TECH_PRICE_BELOW_MA20_EXTENDED": -10,
            "TECH_VOLATILITY_ELEVATED": -10,
            "TECH_VOLATILITY_NORMAL": 0,
            "TECH_INDICATORS_BULLISH_CONFIRMED": 15,
            "TECH_INDICATORS_BEARISH_CONFIRMED": -15,
            "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED": 0,
        }
        order = tuple(titles)
        groups = (
            set(order[0:3]),
            set(order[3:8]),
            set(order[8:11]),
            set(order[11:13]),
            set(order[13:16]),
        )
        codes = [finding.code for finding in run.findings]
        if any(code not in titles for code in codes) \
                or any(code not in group for code, group in zip(codes, groups, strict=True)):
            raise ValueError("阶段2E-1 TECHNICAL_ANALYSIS五类reasonCode无效")
        for index, finding in enumerate(run.findings):
            rank = order.index(finding.code) + 1
            expected_id = (
                f"ta-{rank:02d}-{finding.code.lower().replace('_', '-')}"
                f"-{self.contextHash}"
            )
            expected_evidence_ids = (
                [metrics_evidence.evidenceId]
                if index < 2
                else [metrics_evidence.evidenceId, market_evidence.evidenceId]
            )
            impact = impacts[finding.code]
            score_impact = f"scoreImpact={impact:+d}" if impact else "scoreImpact=0"
            if (finding.findingId != expected_id
                    or finding.title != titles[finding.code]
                    or finding.severity is not severities[finding.code]
                    or finding.evidenceIds != expected_evidence_ids
                    or score_impact not in finding.detail):
                raise ValueError("阶段2E-1 TECHNICAL_ANALYSIS finding契约无效")

        expected_score = min(100, max(0, 50 + sum(impacts[code] for code in codes)))
        if run.score != expected_score:
            raise ValueError("阶段2E-1 TECHNICAL_ANALYSIS score与reasonCode影响不一致")


def _require_unique(values: list[Any], field_name: str) -> list[Any]:
    if len(values) != len(set(values)):
        raise ValueError(f"{field_name}不允许重复元素")
    return values


def _require_non_blank(values: list[str], field_name: str) -> list[str]:
    if any(not value.strip() for value in values):
        raise ValueError(f"{field_name}不能为空")
    return values
