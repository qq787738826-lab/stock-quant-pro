from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any
from zoneinfo import ZoneInfo

from .models import (
    AgentDecision,
    AgentError,
    AgentTeamRequest,
    Evidence,
    EvidenceCategory,
    EvidenceSourceType,
    Finding,
    FormalVeto,
    GateStatus,
    RunStatus,
    Severity,
)


RULE_VERSION = "1.4.0-stage-2h-position-risk-v1"
CONTEXT_PROFILE = "AGENT_CONTEXT_2H_V1"
CONTEXT_SCHEMA_VERSION = "PORTFOLIO_CONTEXT_V1"
PRODUCER = "AgentPortfolioContextService"
PRODUCER_VERSION = "JAVA_PORTFOLIO_CONTEXT_V1"
MARKET_TIMEZONE = "Asia/Shanghai"
ACCOUNT_ID = 1
INPUT_INVALID = "POSITION_RISK_INPUT_INVALID"
UNAVAILABLE_REASON_CODES = frozenset({
    "PORTFOLIO_CONTEXT_NOT_CURRENT_DATE",
    "PORTFOLIO_ACCOUNT_INVALID",
    "PORTFOLIO_SETTINGS_INVALID",
    "PORTFOLIO_POSITION_INVALID",
    "PORTFOLIO_PRICE_MISSING",
    "PORTFOLIO_PRICE_STALE",
    "PORTFOLIO_ORDER_INVALID",
})
FINDING_CODES = (
    "POSITION_RISK_ACCOUNT_LOSS_ASSESSED",
    "POSITION_RISK_CONCENTRATION_ASSESSED",
    "POSITION_RISK_PENDING_EXPOSURE_ASSESSED",
    "POSITION_RISK_EXIT_THRESHOLDS_ASSESSED",
    "POSITION_RISK_CONTEXT_COMPLETENESS_ASSESSED",
)
FINDING_TITLES = (
    "账户回撤和当日损失",
    "持仓数量与集中度",
    "现金和待确认委托暴露",
    "止损、移动止损和目标位",
    "估值价格新鲜度与上下文完整性",
)
BASE_FIELDS = frozenset({
    "available",
    "queriedAt",
    "queryScope",
    "producer",
    "producerVersion",
    "contextProfile",
    "schemaVersion",
    "accountId",
    "requestTradeDate",
    "analysisDate",
    "marketTimezone",
    "currentStateOnly",
    "snapshotFrozenForTask",
    "historicalPointInTimeGuaranteed",
    "businessTablesReadOnly",
    "sourceType",
    "sourceTables",
})
AVAILABLE_FIELDS = BASE_FIELDS | frozenset({
    "account",
    "positions",
    "pendingOrders",
    "projectedPositions",
    "equityHistory",
    "completeness",
    "limitations",
})
UNAVAILABLE_FIELDS = BASE_FIELDS | frozenset({"reasonCode", "reason"})
ACCOUNT_FIELDS = frozenset({
    "accountId",
    "name",
    "initialCapital",
    "cash",
    "frozenCash",
    "availableCash",
    "recomputedMarketValue",
    "recomputedTotalAsset",
    "realizedPnl",
    "recomputedUnrealizedPnl",
    "totalFees",
    "positionCount",
    "pendingBuyCount",
    "pendingSellCount",
    "availableCashRatio",
    "maxPositions",
    "maxPositionWeight",
    "projectedPositionCount",
    "pendingBuyExposure",
    "accountDrawdownAvailable",
    "accountDrawdown",
    "dailyLossAvailable",
    "dailyLossPct",
})
POSITION_FIELDS = frozenset({
    "symbol",
    "quantity",
    "availableQuantity",
    "averageCost",
    "databaseLastPrice",
    "markPrice",
    "markTradeDate",
    "priceAgeDays",
    "marketValue",
    "unrealizedPnl",
    "positionWeight",
    "stopLoss",
    "targetPrice",
    "trailingStopPct",
    "highestPrice",
    "trailingStopPrice",
    "sourcePlanId",
    "lastBuyDate",
})
ORDER_FIELDS = frozenset({
    "orderId",
    "symbol",
    "side",
    "quantity",
    "limitPrice",
    "grossAmount",
    "frozenAmount",
    "frozenQuantity",
    "tradePlanId",
})
PROJECTED_FIELDS = frozenset({
    "symbol",
    "currentPositionPresent",
    "currentPositionValue",
    "pendingBuyExposure",
    "projectedPositionValue",
    "projectedPositionWeight",
})
EQUITY_FIELDS = frozenset({
    "accountDrawdownAvailable",
    "historicalPeakTotalAsset",
    "accountDrawdown",
    "dailyLossAvailable",
    "previousSnapshotDate",
    "previousTotalAsset",
    "dailyLossPct",
    "snapshotCount",
})
COMPLETENESS_FIELDS = frozenset({
    "accountDrawdownAvailable",
    "dailyLossAvailable",
    "priceFreshnessWarning",
    "partial",
})
LIMITATIONS = [
    "CURRENT_SIMULATED_ACCOUNT_STATE_ONLY",
    "NO_HISTORICAL_PORTFOLIO_POINT_IN_TIME_GUARANTEE",
    "LOCAL_QFQ_MARK_PRICE_ON_OR_BEFORE_REQUEST_DATE",
    "RESEARCH_AND_SIMULATION_ONLY",
]
SOURCE_TABLES = [
    "portfolio_accounts",
    "positions",
    "manual_orders",
    "daily_bars",
    "account_equity_snapshots",
    "app_settings",
]
MONEY = Decimal("0.01")
PRICE = Decimal("0.0001")
RATIO = Decimal("0.00000001")


@dataclass(frozen=True)
class ParsedPortfolioContext:
    raw: dict[str, Any]
    queried_at: datetime
    account: dict[str, Any]
    positions: list[dict[str, Any]]
    orders: list[dict[str, Any]]
    projected: list[dict[str, Any]]
    partial: bool
    stale_price_warning: bool


@dataclass(frozen=True)
class PositionRiskEvaluation:
    status: RunStatus
    gate_status: GateStatus
    decision: AgentDecision
    veto: bool
    score: int
    confidence: int
    summary: str
    findings: tuple[Finding, ...]
    evidence: tuple[Evidence, ...]
    errors: tuple[AgentError, ...]
    vetoes: tuple[FormalVeto, ...]


class PositionRiskRuleEngine:

    def evaluate(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
    ) -> PositionRiskEvaluation:
        raw = deepcopy(request.contextSnapshot.portfolioContext)
        try:
            self._validate_base(raw, request)
        except (KeyError, TypeError, ValueError, InvalidOperation):
            return self._invalid()
        if raw.get("available") is not True:
            reason_code = raw.get("reasonCode")
            if reason_code not in UNAVAILABLE_REASON_CODES:
                return self._invalid()
            return PositionRiskEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=GateStatus.NOT_APPLICABLE,
                decision=AgentDecision.NOT_APPLICABLE,
                veto=False,
                score=0,
                confidence=0,
                summary="可靠portfolioContext不可用，未形成账户风险结论。",
                findings=(),
                evidence=(),
                errors=(AgentError(
                    code=reason_code,
                    message="Java提供的当前模拟账户事实上下文不可用。",
                ),),
                vetoes=(),
            )
        try:
            parsed = self._validate_available(raw, request)
        except (KeyError, TypeError, ValueError, InvalidOperation):
            return self._invalid()

        evidence = self._evidence(request, parsed, generated_at)
        veto_specs = self._veto_specs(parsed)
        vetoes = tuple(
            FormalVeto(
                vetoId=(
                    f"position-risk-veto-{index:02d}-"
                    f"{code.lower().replace('_', '-')}-{request.contextHash}"
                ),
                taskId=request.taskId,
                runId=request.runIds.positionRisk,
                agentCode="POSITION_RISK",
                vetoCode=code,
                reason=reason,
                evidenceIds=[evidence.evidenceId],
                createdAt=generated_at,
            )
            for index, (code, reason) in enumerate(veto_specs, start=1)
        )
        score, warning_facts = self._score(parsed, bool(vetoes))
        confidence = self._confidence(parsed)
        findings = self._findings(
            request,
            parsed,
            evidence.evidenceId,
            veto_specs,
            warning_facts,
        )
        status = RunStatus.PARTIAL if parsed.partial else RunStatus.COMPLETED
        if vetoes:
            gate = GateStatus.BLOCKED
            decision = AgentDecision.REJECT
            summary = "当前模拟账户事实触发正式仓位风险否决；结果仅用于研究与模拟风控。"
        elif parsed.partial or warning_facts:
            gate = GateStatus.WARN
            decision = AgentDecision.WARN
            summary = "当前模拟账户未触发正式否决，但存在风险提示或上下文完整性限制。"
        else:
            gate = GateStatus.PASS
            decision = AgentDecision.PASS
            summary = "当前模拟账户在冻结的POSITION_RISK V1阈值内未发现风险警告。"
        return PositionRiskEvaluation(
            status=status,
            gate_status=gate,
            decision=decision,
            veto=bool(vetoes),
            score=score,
            confidence=confidence,
            summary=summary,
            findings=findings,
            evidence=(evidence,),
            errors=(),
            vetoes=vetoes,
        )

    @staticmethod
    def _invalid() -> PositionRiskEvaluation:
        return PositionRiskEvaluation(
            status=RunStatus.INSUFFICIENT_DATA,
            gate_status=GateStatus.NOT_APPLICABLE,
            decision=AgentDecision.NOT_APPLICABLE,
            veto=False,
            score=0,
            confidence=0,
            summary="POSITION_RISK输入契约非法，未形成账户风险结论。",
            findings=(),
            evidence=(),
            errors=(AgentError(
                code=INPUT_INVALID,
                message="portfolioContext的Schema、版本、字段、顺序或重算事实非法。",
            ),),
            vetoes=(),
        )

    @staticmethod
    def _validate_base(raw: dict[str, Any], request: AgentTeamRequest) -> None:
        if not isinstance(raw, dict):
            raise ValueError("portfolioContext必须是对象")
        available = raw.get("available")
        expected_fields = AVAILABLE_FIELDS if available is True else UNAVAILABLE_FIELDS
        if available not in (True, False) or set(raw) != expected_fields:
            raise ValueError("portfolioContext字段白名单无效")
        expected = {
            "producer": PRODUCER,
            "producerVersion": PRODUCER_VERSION,
            "contextProfile": CONTEXT_PROFILE,
            "schemaVersion": CONTEXT_SCHEMA_VERSION,
            "accountId": ACCOUNT_ID,
            "requestTradeDate": request.tradeDate.isoformat(),
            "analysisDate": request.tradeDate.isoformat(),
            "marketTimezone": MARKET_TIMEZONE,
            "currentStateOnly": True,
            "snapshotFrozenForTask": True,
            "historicalPointInTimeGuaranteed": False,
            "businessTablesReadOnly": True,
            "sourceType": "DATABASE",
            "sourceTables": SOURCE_TABLES,
            "queryScope": {
                "symbol": request.symbol,
                "tradeDate": request.tradeDate.isoformat(),
            },
        }
        for field, value in expected.items():
            if raw.get(field) != value:
                raise ValueError(f"portfolioContext.{field}不一致")
        queried_at = _instant(raw.get("queriedAt"))
        if queried_at.astimezone(ZoneInfo(MARKET_TIMEZONE)).date() != request.tradeDate:
            raise ValueError("queriedAt与当前analysisDate不一致")
        if available is False:
            if (raw.get("reasonCode") not in UNAVAILABLE_REASON_CODES
                    or not isinstance(raw.get("reason"), str)
                    or not raw["reason"].strip()):
                raise ValueError("不可用portfolioContext reason无效")

    def _validate_available(
        self,
        raw: dict[str, Any],
        request: AgentTeamRequest,
    ) -> ParsedPortfolioContext:
        if raw.get("limitations") != LIMITATIONS:
            raise ValueError("portfolioContext限制说明无效")
        account = _object(raw.get("account"), ACCOUNT_FIELDS)
        positions = _object_list(raw.get("positions"), POSITION_FIELDS)
        orders = _object_list(raw.get("pendingOrders"), ORDER_FIELDS)
        projected = _object_list(raw.get("projectedPositions"), PROJECTED_FIELDS)
        equity = _object(raw.get("equityHistory"), EQUITY_FIELDS)
        completeness = _object(raw.get("completeness"), COMPLETENESS_FIELDS)

        if (account.get("accountId") != ACCOUNT_ID
                or not isinstance(account.get("name"), str)
                or not account["name"].strip()):
            raise ValueError("账户身份无效")
        initial = _decimal(account, "initialCapital", positive=True)
        cash = _decimal(account, "cash", non_negative=True)
        frozen = _decimal(account, "frozenCash", non_negative=True)
        available_cash = _decimal(account, "availableCash", non_negative=True)
        market_value = _decimal(account, "recomputedMarketValue", non_negative=True)
        total_asset = _decimal(account, "recomputedTotalAsset", positive=True)
        unrealized = _decimal(account, "recomputedUnrealizedPnl")
        _decimal(account, "realizedPnl")
        _decimal(account, "totalFees", non_negative=True)
        if initial <= 0 or frozen > cash:
            raise ValueError("账户资金无效")
        if _money(cash - frozen) != available_cash or _money(cash + market_value) != total_asset:
            raise ValueError("账户资金重算不一致")

        max_positions = _integer(account, "maxPositions", minimum=1)
        max_weight = _decimal(account, "maxPositionWeight", positive=True)
        if max_weight > 1:
            raise ValueError("maxPositionWeight无效")
        if _integer(account, "positionCount", minimum=0) != len(positions):
            raise ValueError("positionCount不一致")

        position_symbols: list[str] = []
        recomputed_market = Decimal(0)
        recomputed_unrealized = Decimal(0)
        stale_price_warning = False
        for position in positions:
            symbol = _symbol(position.get("symbol"))
            position_symbols.append(symbol)
            quantity = _integer(position, "quantity", minimum=1)
            available_quantity = _integer(position, "availableQuantity", minimum=0)
            if available_quantity > quantity:
                raise ValueError("availableQuantity超过quantity")
            average = _decimal(position, "averageCost", positive=True)
            database_last = _decimal(position, "databaseLastPrice", positive=True)
            mark = _decimal(position, "markPrice", positive=True)
            highest = _decimal(position, "highestPrice", positive=True)
            if highest < mark:
                raise ValueError("highestPrice低于markPrice")
            mark_date = _date(position.get("markTradeDate"))
            age = (request.tradeDate - mark_date).days
            if mark_date > request.tradeDate or not 0 <= age <= 7:
                raise ValueError("markTradeDate或priceAgeDays无效")
            if _integer(position, "priceAgeDays", minimum=0) != age:
                raise ValueError("priceAgeDays重算不一致")
            stale_price_warning = stale_price_warning or age >= 4
            market = _decimal(position, "marketValue", non_negative=True)
            pnl = _decimal(position, "unrealizedPnl")
            if market != _money(mark * quantity) or pnl != _money((mark - average) * quantity):
                raise ValueError("持仓市值或未实现损益重算不一致")
            weight = _decimal(position, "positionWeight", non_negative=True)
            if weight != _ratio(market, total_asset):
                raise ValueError("positionWeight重算不一致")
            trailing_pct = _decimal(position, "trailingStopPct", non_negative=True)
            if trailing_pct >= 1:
                raise ValueError("trailingStopPct无效")
            trailing_price = _decimal(position, "trailingStopPrice", positive=True)
            if trailing_price != _price(highest * (Decimal(1) - trailing_pct)):
                raise ValueError("trailingStopPrice重算不一致")
            _optional_positive_decimal(position, "stopLoss")
            _optional_positive_decimal(position, "targetPrice")
            _optional_positive_int(position.get("sourcePlanId"))
            _optional_date(position.get("lastBuyDate"))
            if database_last <= 0:
                raise ValueError("databaseLastPrice无效")
            recomputed_market += market
            recomputed_unrealized += pnl
        if position_symbols != sorted(position_symbols) or len(position_symbols) != len(set(position_symbols)):
            raise ValueError("positions必须按symbol稳定升序且唯一")
        if _money(recomputed_market) != market_value or _money(recomputed_unrealized) != unrealized:
            raise ValueError("账户持仓汇总不一致")

        previous_order_id = 0
        buy_count = 0
        sell_count = 0
        pending_buy_exposure: dict[str, Decimal] = {}
        pending_sell_quantity: dict[str, int] = {}
        pending_buy_frozen = Decimal(0)
        for order in orders:
            order_id = _integer(order, "orderId", minimum=1)
            if order_id <= previous_order_id:
                raise ValueError("pendingOrders必须按ID升序")
            previous_order_id = order_id
            symbol = _symbol(order.get("symbol"))
            side = order.get("side")
            if side not in ("BUY", "SELL"):
                raise ValueError("委托side无效")
            quantity = _integer(order, "quantity", minimum=1)
            limit_price = _decimal(order, "limitPrice", positive=True)
            gross = _decimal(order, "grossAmount", positive=True)
            frozen_amount = _decimal(order, "frozenAmount", non_negative=True)
            frozen_quantity = _integer(order, "frozenQuantity", minimum=0)
            _optional_positive_int(order.get("tradePlanId"))
            if gross != _money(limit_price * quantity):
                raise ValueError("委托grossAmount重算不一致")
            if side == "BUY":
                buy_count += 1
                if frozen_amount < gross or frozen_quantity != 0:
                    raise ValueError("BUY委托冻结事实无效")
                pending_buy_frozen += frozen_amount
                pending_buy_exposure[symbol] = (
                    pending_buy_exposure.get(symbol, Decimal(0)) + gross
                )
            else:
                sell_count += 1
                pending_sell_quantity[symbol] = (
                    pending_sell_quantity.get(symbol, 0) + frozen_quantity
                )
                if frozen_amount != 0 or not 0 < frozen_quantity <= quantity:
                    raise ValueError("SELL委托冻结事实无效")
        if (_integer(account, "pendingBuyCount", minimum=0) != buy_count
                or _integer(account, "pendingSellCount", minimum=0) != sell_count
                or _money(pending_buy_frozen) != frozen):
            raise ValueError("待确认委托汇总不一致")
        total_pending_exposure = _money(sum(pending_buy_exposure.values(), Decimal(0)))
        if _decimal(account, "pendingBuyExposure", non_negative=True) != total_pending_exposure:
            raise ValueError("pendingBuyExposure不一致")

        positions_by_symbol = {item["symbol"]: item for item in positions}
        for symbol, frozen_quantity in pending_sell_quantity.items():
            position = positions_by_symbol.get(symbol)
            if position is None or frozen_quantity > (
                _integer(position, "quantity", minimum=1)
                - _integer(position, "availableQuantity", minimum=0)
            ):
                raise ValueError("pending SELL frozen quantity is inconsistent with position")

        projected_symbols: list[str] = []
        current_values = {
            position["symbol"]: _decimal(position, "marketValue", non_negative=True)
            for position in positions
        }
        expected_symbols = sorted(set(current_values) | set(pending_buy_exposure))
        for item in projected:
            symbol = _symbol(item.get("symbol"))
            projected_symbols.append(symbol)
            current = current_values.get(symbol, Decimal(0))
            pending = _money(pending_buy_exposure.get(symbol, Decimal(0)))
            expected_value = _money(current + pending)
            if (item.get("currentPositionPresent") is not (symbol in current_values)
                    or _decimal(item, "currentPositionValue", non_negative=True) != current
                    or _decimal(item, "pendingBuyExposure", non_negative=True) != pending
                    or _decimal(item, "projectedPositionValue", non_negative=True) != expected_value
                    or _decimal(item, "projectedPositionWeight", non_negative=True)
                    != _ratio(expected_value, total_asset)):
                raise ValueError("projectedPosition重算不一致")
        if projected_symbols != expected_symbols:
            raise ValueError("projectedPositions集合或顺序无效")
        if (_integer(account, "projectedPositionCount", minimum=0) != len(projected)
                or len(projected) < len(positions)):
            raise ValueError("projectedPositionCount不一致")
        if _decimal(account, "availableCashRatio", non_negative=True) != _ratio(
                available_cash, total_asset
        ):
            raise ValueError("availableCashRatio重算不一致")

        drawdown_available = _boolean(account.get("accountDrawdownAvailable"))
        daily_available = _boolean(account.get("dailyLossAvailable"))
        if (equity.get("accountDrawdownAvailable") is not drawdown_available
                or equity.get("dailyLossAvailable") is not daily_available):
            raise ValueError("权益可用状态不一致")
        snapshot_count = _integer(equity, "snapshotCount", minimum=0)
        drawdown = _optional_ratio(account.get("accountDrawdown"), drawdown_available)
        daily_loss = _optional_ratio(account.get("dailyLossPct"), daily_available)
        if _optional_ratio(
            equity.get("accountDrawdown"), drawdown_available
        ) != drawdown or _optional_ratio(
            equity.get("dailyLossPct"), daily_available
        ) != daily_loss:
            raise ValueError("权益比例不一致")
        peak = _optional_positive_decimal_value(
            equity.get("historicalPeakTotalAsset"), drawdown_available
        )
        previous = _optional_positive_decimal_value(
            equity.get("previousTotalAsset"), daily_available
        )
        previous_date = _optional_date_required(
            equity.get("previousSnapshotDate"), daily_available
        )
        if drawdown_available:
            expected_drawdown = (
                Decimal(0) if total_asset >= peak else _ratio(peak - total_asset, peak)
            )
            if drawdown != expected_drawdown:
                raise ValueError("accountDrawdown重算不一致")
        if daily_available:
            if previous_date >= request.tradeDate:
                raise ValueError("previousSnapshotDate必须早于请求日期")
            expected_loss = (
                Decimal(0)
                if total_asset >= previous
                else _ratio(previous - total_asset, previous)
            )
            if daily_loss != expected_loss:
                raise ValueError("dailyLossPct重算不一致")
        if snapshot_count == 0 and (drawdown_available or daily_available):
            raise ValueError("空权益历史不得声明指标可用")

        partial = not drawdown_available or not daily_available
        if (completeness.get("accountDrawdownAvailable") is not drawdown_available
                or completeness.get("dailyLossAvailable") is not daily_available
                or completeness.get("priceFreshnessWarning") is not stale_price_warning
                or completeness.get("partial") is not partial):
            raise ValueError("completeness重算不一致")
        return ParsedPortfolioContext(
            raw=raw,
            queried_at=_instant(raw["queriedAt"]),
            account=account,
            positions=positions,
            orders=orders,
            projected=projected,
            partial=partial,
            stale_price_warning=stale_price_warning,
        )

    @staticmethod
    def _veto_specs(parsed: ParsedPortfolioContext) -> list[tuple[str, str]]:
        account = parsed.account
        max_weight = _decimal(account, "maxPositionWeight", positive=True)
        values: list[tuple[str, str]] = []
        if account["accountDrawdownAvailable"] is True:
            drawdown = _decimal(account, "accountDrawdown", non_negative=True)
            if drawdown >= Decimal("0.12"):
                values.append((
                    "POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
                    f"accountId=1 accountDrawdown={_plain(drawdown)} threshold=0.12",
                ))
        if account["dailyLossAvailable"] is True:
            daily_loss = _decimal(account, "dailyLossPct", non_negative=True)
            if daily_loss >= Decimal("0.03"):
                values.append((
                    "POSITION_RISK_DAILY_LOSS_LIMIT",
                    f"accountId=1 dailyLossPct={_plain(daily_loss)} threshold=0.03",
                ))
        projected_count = _integer(account, "projectedPositionCount", minimum=0)
        max_positions = _integer(account, "maxPositions", minimum=1)
        if max(_integer(account, "positionCount", minimum=0), projected_count) > max_positions:
            values.append((
                "POSITION_RISK_MAX_POSITIONS_EXCEEDED",
                f"accountId=1 projectedPositionCount={projected_count} threshold={max_positions}",
            ))
        for position in parsed.positions:
            weight = _decimal(position, "positionWeight", non_negative=True)
            if weight > max_weight:
                symbol = position["symbol"]
                values.append((
                    f"POSITION_RISK_POSITION_WEIGHT_LIMIT_{symbol}",
                    f"symbol={symbol} positionWeight={_plain(weight)} threshold={_plain(max_weight)}",
                ))
        for projected in parsed.projected:
            weight = _decimal(projected, "projectedPositionWeight", non_negative=True)
            if (
                _decimal(projected, "pendingBuyExposure", non_negative=True) > 0
                and weight > max_weight
            ):
                symbol = projected["symbol"]
                values.append((
                    f"POSITION_RISK_PROJECTED_WEIGHT_LIMIT_{symbol}",
                    f"symbol={symbol} projectedPositionWeight={_plain(weight)} "
                    f"threshold={_plain(max_weight)}",
                ))
        for position in parsed.positions:
            stop_loss = position.get("stopLoss")
            mark = _decimal(position, "markPrice", positive=True)
            if stop_loss is not None and mark <= _value(stop_loss):
                symbol = position["symbol"]
                values.append((
                    f"POSITION_RISK_STOP_LOSS_TRIGGERED_{symbol}",
                    f"symbol={symbol} markPrice={_plain(mark)} "
                    f"stopLoss={_plain(_value(stop_loss))}",
                ))
        for position in parsed.positions:
            highest = _decimal(position, "highestPrice", positive=True)
            average = _decimal(position, "averageCost", positive=True)
            mark = _decimal(position, "markPrice", positive=True)
            trailing = _decimal(position, "trailingStopPrice", positive=True)
            if highest > average and mark <= trailing:
                symbol = position["symbol"]
                values.append((
                    f"POSITION_RISK_TRAILING_STOP_TRIGGERED_{symbol}",
                    f"symbol={symbol} markPrice={_plain(mark)} "
                    f"trailingStopPrice={_plain(trailing)}",
                ))
        return values

    @staticmethod
    def _score(
        parsed: ParsedPortfolioContext,
        veto: bool,
    ) -> tuple[int, tuple[str, ...]]:
        if veto:
            return 0, ("FORMAL_VETO_PRESENT",)
        account = parsed.account
        deductions = 0
        warnings: list[str] = []
        if account["accountDrawdownAvailable"] is True:
            value = _decimal(account, "accountDrawdown", non_negative=True)
            if Decimal("0.08") <= value < Decimal("0.12"):
                deductions += 20
                warnings.append("ACCOUNT_DRAWDOWN_8_TO_12")
            elif Decimal("0.05") <= value < Decimal("0.08"):
                deductions += 10
                warnings.append("ACCOUNT_DRAWDOWN_5_TO_8")
        if account["dailyLossAvailable"] is True:
            value = _decimal(account, "dailyLossPct", non_negative=True)
            if Decimal("0.02") <= value < Decimal("0.03"):
                deductions += 20
                warnings.append("DAILY_LOSS_2_TO_3")
            elif Decimal("0.01") <= value < Decimal("0.02"):
                deductions += 10
                warnings.append("DAILY_LOSS_1_TO_2")
        max_positions = _integer(account, "maxPositions", minimum=1)
        if max(
            _integer(account, "positionCount", minimum=0),
            _integer(account, "projectedPositionCount", minimum=0),
        ) == max_positions:
            deductions += 10
            warnings.append("POSITION_COUNT_AT_LIMIT")
        max_weight = _decimal(account, "maxPositionWeight", positive=True)
        near_threshold = max_weight * Decimal("0.9")
        concentration_symbols = {
            item["symbol"]
            for item in parsed.positions
            if near_threshold
            <= _decimal(item, "positionWeight", non_negative=True)
            <= max_weight
        }
        concentration_symbols.update(
            item["symbol"]
            for item in parsed.projected
            if _decimal(item, "pendingBuyExposure", non_negative=True) > 0
            and near_threshold
            <= _decimal(item, "projectedPositionWeight", non_negative=True)
            <= max_weight
        )
        concentration_count = len(concentration_symbols)
        concentration_deduction = min(20, concentration_count * 10)
        if concentration_deduction:
            deductions += concentration_deduction
            warnings.append(f"CONCENTRATION_NEAR_LIMIT_COUNT_{concentration_count}")
        if _decimal(account, "availableCashRatio", non_negative=True) < Decimal("0.10"):
            deductions += 10
            warnings.append("AVAILABLE_CASH_RATIO_LOW")
        if parsed.stale_price_warning:
            deductions += 10
            warnings.append("MARK_PRICE_AGE_4_TO_7")
        target_symbols = [
            item["symbol"]
            for item in parsed.positions
            if item.get("targetPrice") is not None
            and _decimal(item, "markPrice", positive=True) >= _value(item["targetPrice"])
        ]
        if target_symbols:
            warnings.append("TARGET_LEVEL_REACHED_" + "_".join(target_symbols))
        if parsed.partial:
            warnings.append("EQUITY_HISTORY_PARTIAL")
        return max(0, min(100, 100 - deductions)), tuple(warnings)

    @staticmethod
    def _confidence(parsed: ParsedPortfolioContext) -> int:
        value = 100
        if parsed.account["accountDrawdownAvailable"] is not True:
            value -= 20
        if parsed.account["dailyLossAvailable"] is not True:
            value -= 20
        if parsed.stale_price_warning:
            value -= 20
        return max(40, value)

    @staticmethod
    def _findings(
        request: AgentTeamRequest,
        parsed: ParsedPortfolioContext,
        evidence_id: str,
        veto_specs: list[tuple[str, str]],
        warning_facts: tuple[str, ...],
    ) -> tuple[Finding, ...]:
        veto_codes = [code for code, _ in veto_specs]
        account = parsed.account
        target_symbols = [
            item["symbol"]
            for item in parsed.positions
            if item.get("targetPrice") is not None
            and _decimal(item, "markPrice", positive=True) >= _value(item["targetPrice"])
        ]
        details = (
            "accountDrawdownAvailable="
            f"{str(account['accountDrawdownAvailable']).lower()},"
            f"accountDrawdown={_nullable_plain(account.get('accountDrawdown'))},"
            "dailyLossAvailable="
            f"{str(account['dailyLossAvailable']).lower()},"
            f"dailyLossPct={_nullable_plain(account.get('dailyLossPct'))}",
            f"positionCount={account['positionCount']},"
            f"projectedPositionCount={account['projectedPositionCount']},"
            f"maxPositions={account['maxPositions']},"
            f"maxPositionWeight={_plain(_value(account['maxPositionWeight']))}",
            f"availableCashRatio={_plain(_value(account['availableCashRatio']))},"
            f"pendingBuyExposure={_plain(_value(account['pendingBuyExposure']))},"
            f"pendingBuyCount={account['pendingBuyCount']},"
            f"pendingSellCount={account['pendingSellCount']}",
            f"stopOrTrailingVetoCount={sum('STOP_' in code for code in veto_codes)},"
            f"targetReachedSymbols={target_symbols}",
            f"currentStateOnly=true,historicalPointInTimeGuaranteed=false,"
            f"partial={str(parsed.partial).lower()},"
            f"priceFreshnessWarning={str(parsed.stale_price_warning).lower()}",
        )
        severities = (
            Severity.CRITICAL if any(
                code.startswith("POSITION_RISK_ACCOUNT_")
                or code.startswith("POSITION_RISK_DAILY_")
                for code in veto_codes
            ) else Severity.WARN if parsed.partial or any(
                fact.startswith(("ACCOUNT_", "DAILY_")) for fact in warning_facts
            ) else Severity.INFO,
            Severity.CRITICAL if any(
                "POSITIONS_EXCEEDED" in code or "WEIGHT_LIMIT" in code
                for code in veto_codes
            ) else Severity.WARN if any(
                fact.startswith(("POSITION_COUNT_", "CONCENTRATION_"))
                for fact in warning_facts
            ) else Severity.INFO,
            Severity.WARN if any(
                fact == "AVAILABLE_CASH_RATIO_LOW" for fact in warning_facts
            ) else Severity.INFO,
            Severity.CRITICAL if any(
                "STOP_LOSS" in code or "TRAILING_STOP" in code
                for code in veto_codes
            ) else Severity.WARN if target_symbols else Severity.INFO,
            Severity.WARN if parsed.partial or parsed.stale_price_warning else Severity.INFO,
        )
        return tuple(
            Finding(
                findingId=(
                    f"position-risk-finding-{index:02d}-"
                    f"{code.lower().replace('_', '-')}-{request.contextHash}"
                ),
                code=code,
                severity=severity,
                title=title,
                detail=detail,
                evidenceIds=[evidence_id],
            )
            for index, (code, title, detail, severity) in enumerate(
                zip(FINDING_CODES, FINDING_TITLES, details, severities, strict=True),
                start=1,
            )
        )

    @staticmethod
    def _evidence(
        request: AgentTeamRequest,
        parsed: ParsedPortfolioContext,
        generated_at: datetime,
    ) -> Evidence:
        return Evidence(
            evidenceId=f"position-risk-portfolio-{request.contextHash}",
            category=EvidenceCategory.PORTFOLIO_STATE,
            sourceType=EvidenceSourceType.JAVA_ENGINE,
            sourceName=PRODUCER,
            sourceRef="contextSnapshot.portfolioContext",
            symbol=request.symbol,
            tradeDate=request.tradeDate,
            observedAt=parsed.queried_at,
            collectedAt=generated_at,
            fields={"portfolioContext": parsed.raw},
            contentHash=request.contextHash,
        )


def _object(value: Any, fields: frozenset[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError("对象字段白名单无效")
    return value


def _object_list(value: Any, fields: frozenset[str]) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise ValueError("列表字段无效")
    return [_object(item, fields) for item in value]


def _integer(
    value: dict[str, Any],
    field: str,
    minimum: int | None = None,
) -> int:
    item = value.get(field)
    if isinstance(item, bool) or not isinstance(item, int):
        raise ValueError(f"{field}必须是整数")
    if minimum is not None and item < minimum:
        raise ValueError(f"{field}低于最小值")
    return item


def _optional_positive_int(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError("可空ID无效")
    return value


def _decimal(
    value: dict[str, Any],
    field: str,
    *,
    positive: bool = False,
    non_negative: bool = False,
) -> Decimal:
    item = _value(value.get(field))
    if positive and item <= 0:
        raise ValueError(f"{field}必须大于0")
    if non_negative and item < 0:
        raise ValueError(f"{field}不得小于0")
    return item


def _value(value: Any) -> Decimal:
    if value is None or isinstance(value, bool):
        raise ValueError("十进制数不能为空或布尔值")
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError("十进制数必须有限")
    return result


def _optional_positive_decimal(value: dict[str, Any], field: str) -> Decimal | None:
    item = value.get(field)
    if item is None:
        return None
    result = _value(item)
    if result <= 0:
        raise ValueError(f"{field}必须大于0")
    return result


def _optional_ratio(value: Any, available: bool) -> Decimal | None:
    if not available:
        if value is not None:
            raise ValueError("不可用比例必须为null")
        return None
    result = _value(value)
    if not Decimal(0) <= result <= Decimal(1):
        raise ValueError("比例必须位于[0,1]")
    return result


def _optional_positive_decimal_value(value: Any, available: bool) -> Decimal | None:
    if not available:
        if value is not None:
            raise ValueError("不可用金额必须为null")
        return None
    result = _value(value)
    if result <= 0:
        raise ValueError("金额必须大于0")
    return result


def _boolean(value: Any) -> bool:
    if not isinstance(value, bool):
        raise ValueError("字段必须是布尔值")
    return value


def _symbol(value: Any) -> str:
    if not isinstance(value, str) or len(value) != 6 or not value.isdigit():
        raise ValueError("symbol必须为6位数字")
    return value


def _date(value: Any) -> date:
    if not isinstance(value, str):
        raise ValueError("日期必须是ISO字符串")
    return date.fromisoformat(value)


def _optional_date(value: Any) -> date | None:
    return None if value is None else _date(value)


def _optional_date_required(value: Any, available: bool) -> date | None:
    if not available:
        if value is not None:
            raise ValueError("不可用日期必须为null")
        return None
    if value is None:
        raise ValueError("可用日期不能为空")
    return _date(value)


def _instant(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("时间必须是ISO字符串")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("时间必须带时区")
    return result


def _money(value: Decimal) -> Decimal:
    return value.quantize(MONEY, rounding=ROUND_HALF_UP)


def _price(value: Decimal) -> Decimal:
    return value.quantize(PRICE, rounding=ROUND_HALF_UP)


def _ratio(numerator: Decimal, denominator: Decimal) -> Decimal:
    if denominator <= 0:
        raise ValueError("比例分母必须大于0")
    return (numerator / denominator).quantize(RATIO, rounding=ROUND_HALF_UP)


def _plain(value: Decimal) -> str:
    normalized = value.normalize()
    return "0" if normalized == 0 else format(normalized, "f")


def _nullable_plain(value: Any) -> str:
    return "null" if value is None else _plain(_value(value))
