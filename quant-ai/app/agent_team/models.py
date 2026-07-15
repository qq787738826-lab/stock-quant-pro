from __future__ import annotations

from datetime import date
from enum import Enum
from typing import Any, Annotated, Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator


PositiveId = Annotated[int, Field(gt=0)]
Score = Annotated[int, Field(ge=0, le=100)]
Sha256 = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
Symbol = Annotated[str, Field(pattern=r"^[0-9]{6}$")]


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
        return self


def _require_unique(values: list[Any], field_name: str) -> list[Any]:
    if len(values) != len(set(values)):
        raise ValueError(f"{field_name}不允许重复元素")
    return values


def _require_non_blank(values: list[str], field_name: str) -> list[str]:
    if any(not value.strip() for value in values):
        raise ValueError(f"{field_name}不能为空")
    return values
