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
        return _require_unique(values, "Finding.evidenceIds")


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
        return _require_unique(values, "FormalVeto.evidenceIds")


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
        return _require_unique(values, "FinalDecision.vetoIds")


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


def _require_unique(values: list[Any], field_name: str) -> list[Any]:
    if len(values) != len(set(values)):
        raise ValueError(f"{field_name}不允许重复元素")
    return values
