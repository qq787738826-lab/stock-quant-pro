from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    StrictBool,
    StrictInt,
    StrictStr,
    ValidationError,
)

from .models import (
    AgentDecision,
    AgentError,
    AgentTeamRequest,
    Evidence,
    EvidenceCategory,
    EvidenceSourceType,
    Finding,
    GateStatus,
    RunStatus,
    Severity,
)


EXPECTED_FORMULA_VERSION = "JAVA_INDICATORS_V1"
EXPECTED_ADJUST_TYPE = "QFQ"
EXPECTED_REQUIRED_BARS = 61
EXPECTED_WINDOWS = {
    "ma5": 5,
    "ma20": 20,
    "ma60": 60,
    "rsi14": 14,
    "atr14": 14,
    "averageVolume20": 20,
    "highestClose20": 20,
}
TECHNICAL_VALUE_FIELDS = (
    "ma5",
    "ma20",
    "ma60",
    "rsi14",
    "atr14",
    "averageVolume20",
    "highestClose20",
)
TECHNICAL_METRICS_EVIDENCE_FIELDS = (
    "available",
    "formulaVersion",
    "adjustType",
    "requestedTradeDate",
    "effectiveTradeDate",
    "requiredBars",
    "actualBars",
    "windows",
    "values",
)
MARKET_DATA_EVIDENCE_FIELDS = (
    "available",
    "adjustType",
    "requestedTradeDate",
    "effectiveTradeDate",
    "exactTradeDateMatch",
    "actualBars",
    "latestBar",
)

RSI_OVERBOUGHT = Decimal("70")
RSI_MIDPOINT = Decimal("50")
RSI_OVERSOLD = Decimal("30")
PRICE_DEVIATION_LIMIT = Decimal("0.10")
ATR_RATIO_LIMIT = Decimal("0.05")
PRICE_QUANTUM = Decimal("0.000001")
PERCENT_QUANTUM = Decimal("0.0001")

FINDING_ORDER = (
    "TECH_TREND_BULLISH_ALIGNED",
    "TECH_TREND_MIXED",
    "TECH_TREND_BEARISH_ALIGNED",
    "TECH_RSI_OVERBOUGHT_RISK",
    "TECH_RSI_POSITIVE_MOMENTUM",
    "TECH_RSI_NEUTRAL",
    "TECH_RSI_NEGATIVE_MOMENTUM",
    "TECH_RSI_OVERSOLD_RISK",
    "TECH_PRICE_ABOVE_MA20_EXTENDED",
    "TECH_PRICE_NEAR_MA20",
    "TECH_PRICE_BELOW_MA20_EXTENDED",
    "TECH_VOLATILITY_ELEVATED",
    "TECH_VOLATILITY_NORMAL",
    "TECH_INDICATORS_BULLISH_CONFIRMED",
    "TECH_INDICATORS_BEARISH_CONFIRMED",
    "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED",
)
_FINDING_RANK = {code: index + 1 for index, code in enumerate(FINDING_ORDER)}
FINDING_TITLES = {
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
FINDING_SEVERITIES = {
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
SCORE_IMPACTS = {
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


class _FrozenInput(BaseModel):
    model_config = ConfigDict(extra="allow", frozen=True)


class _QueryScope(_FrozenInput):
    symbol: StrictStr
    tradeDate: date


class _MarketBar(_FrozenInput):
    symbol: StrictStr
    tradeDate: date
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: StrictInt
    amount: Decimal | None
    turnoverRate: Decimal | None


class _MarketDataInput(_FrozenInput):
    available: StrictBool
    queriedAt: AwareDatetime
    queryScope: _QueryScope
    adjustType: StrictStr
    requestedTradeDate: date
    effectiveTradeDate: date | None
    exactTradeDateMatch: StrictBool
    actualBars: StrictInt
    bars: list[_MarketBar]


class _Windows(_FrozenInput):
    ma5: StrictInt
    ma20: StrictInt
    ma60: StrictInt
    rsi14: StrictInt
    atr14: StrictInt
    averageVolume20: StrictInt
    highestClose20: StrictInt


class _TechnicalValues(_FrozenInput):
    ma5: Decimal
    ma20: Decimal
    ma60: Decimal
    rsi14: Decimal
    atr14: Decimal
    averageVolume20: Decimal
    highestClose20: Decimal


class _TechnicalMetricsInput(_FrozenInput):
    available: StrictBool
    queriedAt: AwareDatetime
    queryScope: _QueryScope
    formulaVersion: StrictStr
    adjustType: StrictStr
    requestedTradeDate: date
    effectiveTradeDate: date | None
    requiredBars: StrictInt
    actualBars: StrictInt
    windows: _Windows
    values: _TechnicalValues


@dataclass(frozen=True)
class TechnicalAnalysisInput:
    market_data: _MarketDataInput
    technical_metrics: _TechnicalMetricsInput

    @property
    def latest_bar(self) -> _MarketBar:
        return self.market_data.bars[-1]


@dataclass(frozen=True)
class TechnicalAnalysisEvaluation:
    status: RunStatus
    gate_status: GateStatus
    decision: AgentDecision
    score: int
    confidence: int
    summary: str
    findings: tuple[Finding, ...]
    evidence: tuple[Evidence, ...]
    errors: tuple[AgentError, ...]


@dataclass(frozen=True)
class _SelectedFinding:
    code: str
    detail: str
    evidence_ids: tuple[str, ...]


class TechnicalAnalysisRuleEngine:
    def evaluate(
        self,
        request: AgentTeamRequest,
        data_quality_gate: GateStatus,
    ) -> TechnicalAnalysisEvaluation:
        if data_quality_gate is GateStatus.BLOCKED:
            return self.blocked_by_data_quality()

        try:
            value = self._parse(request)
            metrics_evidence, market_evidence = self._evidence(request, value)
            selected = self._classify(value, metrics_evidence, market_evidence)
            findings = tuple(self._finding(item, request.contextHash) for item in selected)
            score = _bounded_score(selected)
        except (ValidationError, ValueError, TypeError, InvalidOperation, ArithmeticError):
            return TechnicalAnalysisEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=data_quality_gate,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="技术指标或行情输入无法安全解析，未形成技术状态评分。",
                findings=(),
                evidence=(),
                errors=(AgentError(
                    code="TECHNICAL_ANALYSIS_INPUT_INVALID",
                    message="technicalMetrics或marketData未满足阶段2E-1冻结输入契约。",
                ),),
            )

        confidence = 100 if data_quality_gate is GateStatus.PASS else 50
        return TechnicalAnalysisEvaluation(
            status=RunStatus.COMPLETED,
            gate_status=data_quality_gate,
            decision=AgentDecision.WARN,
            score=score,
            confidence=confidence,
            summary=(f"技术指标确定性规则执行完成，technicalStateScore={score}；"
                     "仅描述冻结技术状态，不构成交易指令或收益判断。"),
            findings=findings,
            evidence=(metrics_evidence, market_evidence),
            errors=(),
        )

    @staticmethod
    def blocked_by_data_quality() -> TechnicalAnalysisEvaluation:
        return TechnicalAnalysisEvaluation(
            status=RunStatus.INSUFFICIENT_DATA,
            gate_status=GateStatus.NOT_APPLICABLE,
            decision=AgentDecision.NOT_APPLICABLE,
            score=0,
            confidence=0,
            summary="DATA_QUALITY门禁已阻断，未执行技术指标确定性规则。",
            findings=(),
            evidence=(),
            errors=(),
        )

    @staticmethod
    def _parse(request: AgentTeamRequest) -> TechnicalAnalysisInput:
        raw_market = request.contextSnapshot.marketData
        raw_metrics = request.contextSnapshot.technicalMetrics
        if not isinstance(raw_market, dict) or not isinstance(raw_metrics, dict):
            raise TypeError("technicalMetrics和marketData必须为JSON对象")

        required_market = {
            "available", "queriedAt", "queryScope", "adjustType",
            "requestedTradeDate", "effectiveTradeDate", "exactTradeDateMatch",
            "actualBars", "bars",
        }
        required_metrics = {
            "available", "queriedAt", "queryScope", "formulaVersion", "adjustType",
            "requestedTradeDate", "effectiveTradeDate", "requiredBars", "actualBars",
            "windows", "values",
        }
        if required_market.difference(raw_market) or required_metrics.difference(raw_metrics):
            raise ValueError("技术分析输入缺少冻结字段")
        if not isinstance(raw_metrics["windows"], dict) \
                or set(raw_metrics["windows"]) != set(EXPECTED_WINDOWS):
            raise ValueError("technicalMetrics.windows字段集合不一致")
        if not isinstance(raw_metrics["values"], dict) \
                or set(raw_metrics["values"]) != set(TECHNICAL_VALUE_FIELDS):
            raise ValueError("technicalMetrics.values字段集合不一致")
        for field in TECHNICAL_VALUE_FIELDS:
            _finite_json_decimal(raw_metrics["values"][field], f"technicalMetrics.values.{field}")

        raw_bars = raw_market["bars"]
        if not isinstance(raw_bars, list):
            raise TypeError("marketData.bars必须为数组")
        for index, raw_bar in enumerate(raw_bars):
            if not isinstance(raw_bar, dict):
                raise TypeError("marketData.bars元素必须为对象")
            required_bar = {
                "symbol", "tradeDate", "open", "high", "low", "close",
                "volume", "amount", "turnoverRate",
            }
            if set(raw_bar) != required_bar:
                raise ValueError("marketData.bars元素字段集合不符合冻结契约")
            for field in ("open", "high", "low", "close"):
                _finite_json_decimal(raw_bar[field], f"marketData.bars[{index}].{field}")
            if isinstance(raw_bar["volume"], bool) or not isinstance(raw_bar["volume"], int):
                raise TypeError("marketData.bars.volume必须为整数")
            for field in ("amount", "turnoverRate"):
                if raw_bar[field] is not None:
                    _finite_json_decimal(raw_bar[field], f"marketData.bars[{index}].{field}")

        market = _MarketDataInput.model_validate(raw_market)
        metrics = _TechnicalMetricsInput.model_validate(raw_metrics)
        value = TechnicalAnalysisInput(market, metrics)
        TechnicalAnalysisRuleEngine._validate_facts(request, value)
        return value

    @staticmethod
    def _validate_facts(
        request: AgentTeamRequest,
        value: TechnicalAnalysisInput,
    ) -> None:
        market = value.market_data
        metrics = value.technical_metrics
        if not market.available or not metrics.available:
            raise ValueError("技术分析输入必须可用")
        if market.queryScope.symbol != request.symbol \
                or metrics.queryScope.symbol != request.symbol \
                or market.queryScope.tradeDate != request.tradeDate \
                or metrics.queryScope.tradeDate != request.tradeDate:
            raise ValueError("技术分析queryScope与请求不一致")
        if market.requestedTradeDate != request.tradeDate \
                or metrics.requestedTradeDate != request.tradeDate:
            raise ValueError("技术分析requestedTradeDate与请求不一致")
        if market.adjustType != EXPECTED_ADJUST_TYPE \
                or metrics.adjustType != EXPECTED_ADJUST_TYPE \
                or metrics.formulaVersion != EXPECTED_FORMULA_VERSION:
            raise ValueError("技术分析来源版本或复权类型不一致")
        if metrics.windows.model_dump() != EXPECTED_WINDOWS:
            raise ValueError("技术指标窗口不一致")
        if metrics.requiredBars != EXPECTED_REQUIRED_BARS \
                or metrics.actualBars != EXPECTED_REQUIRED_BARS \
                or market.actualBars != EXPECTED_REQUIRED_BARS \
                or len(market.bars) != EXPECTED_REQUIRED_BARS:
            raise ValueError("技术分析样本数必须精确为61")
        if market.queriedAt != metrics.queriedAt:
            raise ValueError("technicalMetrics与marketData查询时间不一致")

        previous_date: date | None = None
        for bar in market.bars:
            if bar.symbol != request.symbol or bar.tradeDate > request.tradeDate:
                raise ValueError("bar证券或日期超出冻结范围")
            if previous_date is not None and bar.tradeDate <= previous_date:
                raise ValueError("bars必须按日期严格递增")
            previous_date = bar.tradeDate
            if min(bar.open, bar.high, bar.low, bar.close) <= 0 or bar.volume < 0:
                raise ValueError("bar价格或成交量非法")
            if bar.high < max(bar.open, bar.close, bar.low) \
                    or bar.low > min(bar.open, bar.close, bar.high):
                raise ValueError("bar OHLC关系非法")

        latest = value.latest_bar
        if market.effectiveTradeDate != latest.tradeDate \
                or metrics.effectiveTradeDate != latest.tradeDate:
            raise ValueError("技术分析effectiveTradeDate与最后bar不一致")
        if market.exactTradeDateMatch != (latest.tradeDate == request.tradeDate):
            raise ValueError("exactTradeDateMatch与最后bar不一致")

        values = metrics.values
        if min(values.ma5, values.ma20, values.ma60, values.highestClose20) <= 0:
            raise ValueError("价格类技术指标必须为正")
        if values.rsi14 < 0 or values.rsi14 > 100:
            raise ValueError("rsi14必须在0到100")
        if values.atr14 < 0 or values.averageVolume20 < 0:
            raise ValueError("atr14和averageVolume20不得为负")
        if values.highestClose20 < latest.close:
            raise ValueError("highestClose20不得小于最后close")

    @staticmethod
    def _evidence(
        request: AgentTeamRequest,
        value: TechnicalAnalysisInput,
    ) -> tuple[Evidence, Evidence]:
        raw_metrics = request.contextSnapshot.technicalMetrics
        raw_market = request.contextSnapshot.marketData
        metrics_fields = {
            "technicalMetrics": {
                field: deepcopy(raw_metrics[field])
                for field in TECHNICAL_METRICS_EVIDENCE_FIELDS
            }
        }
        market_projection = {
            field: deepcopy(raw_market[field])
            for field in MARKET_DATA_EVIDENCE_FIELDS
            if field != "latestBar"
        }
        market_projection["latestBar"] = deepcopy(raw_market["bars"][-1])
        market_fields = {"marketData": market_projection}
        return (
            Evidence(
                evidenceId=f"ta-metrics-{request.contextHash}",
                category=EvidenceCategory.TECHNICAL_INDICATOR,
                sourceType=EvidenceSourceType.JAVA_ENGINE,
                sourceName="AgentTechnicalMetricsService",
                sourceRef="contextSnapshot.technicalMetrics",
                symbol=request.symbol,
                tradeDate=request.tradeDate,
                observedAt=value.technical_metrics.queriedAt,
                collectedAt=request.requestedAt,
                fields=metrics_fields,
                contentHash=request.contextHash,
            ),
            Evidence(
                evidenceId=f"ta-market-{request.contextHash}",
                category=EvidenceCategory.MARKET_DATA,
                sourceType=EvidenceSourceType.JAVA_ENGINE,
                sourceName="AgentContextSnapshotService",
                sourceRef="contextSnapshot.marketData",
                symbol=request.symbol,
                tradeDate=request.tradeDate,
                observedAt=value.market_data.queriedAt,
                collectedAt=request.requestedAt,
                fields=market_fields,
                contentHash=request.contextHash,
            ),
        )

    @staticmethod
    def _classify(
        value: TechnicalAnalysisInput,
        metrics_evidence: Evidence,
        market_evidence: Evidence,
    ) -> tuple[_SelectedFinding, ...]:
        values = value.technical_metrics.values
        latest_close = value.latest_bar.close
        metrics_id = metrics_evidence.evidenceId
        both_ids = (metrics_id, market_evidence.evidenceId)

        if values.ma5 > values.ma20 > values.ma60:
            trend_code = "TECH_TREND_BULLISH_ALIGNED"
            trend_state = "BULLISH_ALIGNED"
            trend_condition = "ma5>ma20>ma60"
        elif values.ma5 < values.ma20 < values.ma60:
            trend_code = "TECH_TREND_BEARISH_ALIGNED"
            trend_state = "BEARISH_ALIGNED"
            trend_condition = "ma5<ma20<ma60"
        else:
            trend_code = "TECH_TREND_MIXED"
            trend_state = "MIXED"
            trend_condition = "not(ma5>ma20>ma60 or ma5<ma20<ma60)"
        trend_detail = (
            "evidencePaths=technicalMetrics.values.ma5,technicalMetrics.values.ma20,"
            "technicalMetrics.values.ma60；"
            f"observed=ma5:{_fixed6(values.ma5)},ma20:{_fixed6(values.ma20)},"
            f"ma60:{_fixed6(values.ma60)}；condition={trend_condition}；"
            f"scoreImpact={_signed(SCORE_IMPACTS[trend_code])}。"
        )

        rsi = values.rsi14
        if rsi >= RSI_OVERBOUGHT:
            rsi_code = "TECH_RSI_OVERBOUGHT_RISK"
            rsi_condition = "rsi14>=70.000000"
        elif rsi > RSI_MIDPOINT:
            rsi_code = "TECH_RSI_POSITIVE_MOMENTUM"
            rsi_condition = "50.000000<rsi14<70.000000"
        elif rsi == RSI_MIDPOINT:
            rsi_code = "TECH_RSI_NEUTRAL"
            rsi_condition = "rsi14=50.000000"
        elif rsi > RSI_OVERSOLD:
            rsi_code = "TECH_RSI_NEGATIVE_MOMENTUM"
            rsi_condition = "30.000000<rsi14<50.000000"
        else:
            rsi_code = "TECH_RSI_OVERSOLD_RISK"
            rsi_condition = "rsi14<=30.000000"
        rsi_detail = (
            "evidencePath=technicalMetrics.values.rsi14；"
            f"observed=rsi14:{_fixed6(rsi)}；condition={rsi_condition}；"
            f"scoreImpact={_signed(SCORE_IMPACTS[rsi_code])}。"
        )

        deviation = (latest_close - values.ma20) / values.ma20
        if deviation >= PRICE_DEVIATION_LIMIT:
            deviation_code = "TECH_PRICE_ABOVE_MA20_EXTENDED"
            deviation_condition = "deviation>=10.0000%"
        elif deviation <= -PRICE_DEVIATION_LIMIT:
            deviation_code = "TECH_PRICE_BELOW_MA20_EXTENDED"
            deviation_condition = "deviation<=-10.0000%"
        else:
            deviation_code = "TECH_PRICE_NEAR_MA20"
            deviation_condition = "-10.0000%<deviation<10.0000%"
        deviation_detail = (
            "evidencePaths=marketData.bars[-1].close,technicalMetrics.values.ma20；"
            f"observed=latestClose:{_fixed6(latest_close)},ma20:{_fixed6(values.ma20)},"
            f"deviationPct:{_percent4(deviation)}%；condition={deviation_condition}；"
            f"scoreImpact={_signed(SCORE_IMPACTS[deviation_code])}。"
        )

        atr_ratio = values.atr14 / latest_close
        if atr_ratio >= ATR_RATIO_LIMIT:
            volatility_code = "TECH_VOLATILITY_ELEVATED"
            volatility_condition = "atrRatio>=5.0000%"
        else:
            volatility_code = "TECH_VOLATILITY_NORMAL"
            volatility_condition = "atrRatio<5.0000%"
        volatility_detail = (
            "evidencePaths=technicalMetrics.values.atr14,marketData.bars[-1].close；"
            f"observed=atr14:{_fixed6(values.atr14)},latestClose:{_fixed6(latest_close)},"
            f"atrRatioPct:{_percent4(atr_ratio)}%；condition={volatility_condition}；"
            f"scoreImpact={_signed(SCORE_IMPACTS[volatility_code])}。"
        )

        if trend_code == "TECH_TREND_BULLISH_ALIGNED" \
                and RSI_MIDPOINT < rsi < RSI_OVERBOUGHT \
                and latest_close > values.ma20:
            confirmation_code = "TECH_INDICATORS_BULLISH_CONFIRMED"
            confirmation_condition = "bullishTrend and 50.000000<rsi14<70.000000 and latestClose>ma20"
        elif trend_code == "TECH_TREND_BEARISH_ALIGNED" \
                and RSI_OVERSOLD < rsi < RSI_MIDPOINT \
                and latest_close < values.ma20:
            confirmation_code = "TECH_INDICATORS_BEARISH_CONFIRMED"
            confirmation_condition = "bearishTrend and 30.000000<rsi14<50.000000 and latestClose<ma20"
        else:
            confirmation_code = "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED"
            confirmation_condition = "neither frozen confirmation condition is satisfied"
        confirmation_detail = (
            "evidencePaths=technicalMetrics.values.ma5,technicalMetrics.values.ma20,"
            "technicalMetrics.values.ma60,technicalMetrics.values.rsi14,"
            "marketData.bars[-1].close；"
            f"observed=trend:{trend_state},rsi14:{_fixed6(rsi)},"
            f"latestClose:{_fixed6(latest_close)},ma20:{_fixed6(values.ma20)}；"
            f"condition={confirmation_condition}；"
            f"scoreImpact={_signed(SCORE_IMPACTS[confirmation_code])}。"
        )

        return (
            _SelectedFinding(trend_code, trend_detail, (metrics_id,)),
            _SelectedFinding(rsi_code, rsi_detail, (metrics_id,)),
            _SelectedFinding(deviation_code, deviation_detail, both_ids),
            _SelectedFinding(volatility_code, volatility_detail, both_ids),
            _SelectedFinding(confirmation_code, confirmation_detail, both_ids),
        )

    @staticmethod
    def _finding(selected: _SelectedFinding, context_hash: str) -> Finding:
        rank = _FINDING_RANK[selected.code]
        return Finding(
            findingId=(f"ta-{rank:02d}-{selected.code.lower().replace('_', '-')}"
                       f"-{context_hash}"),
            code=selected.code,
            severity=FINDING_SEVERITIES[selected.code],
            title=FINDING_TITLES[selected.code],
            detail=selected.detail,
            evidenceIds=list(selected.evidence_ids),
        )


def _finite_json_decimal(value: Any, path: str) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise TypeError(f"{path}必须为JSON数值")
    converted = Decimal(str(value))
    if not converted.is_finite():
        raise ValueError(f"{path}必须为有限数值")
    return converted


def _fixed6(value: Decimal) -> str:
    return format(value.quantize(PRICE_QUANTUM, rounding=ROUND_HALF_UP), "f")


def _percent4(ratio: Decimal) -> str:
    return format(
        (ratio * Decimal("100")).quantize(PERCENT_QUANTUM, rounding=ROUND_HALF_UP),
        "f",
    )


def _signed(value: int) -> str:
    return f"{value:+d}" if value else "0"


def _bounded_score(selected: tuple[_SelectedFinding, ...]) -> int:
    raw = 50 + sum(SCORE_IMPACTS[item.code] for item in selected)
    return min(100, max(0, raw))
