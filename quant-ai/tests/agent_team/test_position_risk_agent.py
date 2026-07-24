from __future__ import annotations

import copy
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
import json
import unittest

from app.agent_team.models import (
    AgentTeamRequest,
    STAGE_2H_POSITION_RISK_RULE_VERSION,
)
from app.agent_team.orchestrator import AgentTeamOrchestrator

from .test_strategy_backtest_agent import stage_2f_payload


MONEY = Decimal("0.01")
PRICE = Decimal("0.0001")
RATIO = Decimal("0.00000001")


def stage_2h_payload() -> dict:
    payload = stage_2f_payload()
    payload["ruleVersion"] = STAGE_2H_POSITION_RISK_RULE_VERSION
    request_date = date.fromisoformat(payload["tradeDate"])
    queried_at = datetime.combine(
        request_date,
        datetime.min.time(),
        tzinfo=timezone(timedelta(hours=8)),
    ).replace(hour=12).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    payload["contextSnapshot"]["portfolioContext"] = {
        "available": True,
        "queriedAt": queried_at,
        "queryScope": {
            "symbol": payload["symbol"],
            "tradeDate": payload["tradeDate"],
        },
        "producer": "AgentPortfolioContextService",
        "producerVersion": "JAVA_PORTFOLIO_CONTEXT_V1",
        "contextProfile": "AGENT_CONTEXT_2H_V1",
        "schemaVersion": "PORTFOLIO_CONTEXT_V1",
        "accountId": 1,
        "requestTradeDate": payload["tradeDate"],
        "analysisDate": payload["tradeDate"],
        "marketTimezone": "Asia/Shanghai",
        "currentStateOnly": True,
        "snapshotFrozenForTask": True,
        "historicalPointInTimeGuaranteed": False,
        "businessTablesReadOnly": True,
        "sourceType": "DATABASE",
        "sourceTables": [
            "portfolio_accounts",
            "positions",
            "manual_orders",
            "daily_bars",
            "account_equity_snapshots",
            "app_settings",
        ],
        "account": {
            "accountId": 1,
            "name": "默认模拟账户",
            "initialCapital": 100000.0,
            "cash": 100000.0,
            "frozenCash": 0.0,
            "availableCash": 100000.0,
            "recomputedMarketValue": 0.0,
            "recomputedTotalAsset": 100000.0,
            "realizedPnl": 0.0,
            "recomputedUnrealizedPnl": 0.0,
            "totalFees": 0.0,
            "positionCount": 0,
            "pendingBuyCount": 0,
            "pendingSellCount": 0,
            "availableCashRatio": 1.0,
            "maxPositions": 5,
            "maxPositionWeight": 0.2,
            "projectedPositionCount": 0,
            "pendingBuyExposure": 0.0,
            "accountDrawdownAvailable": True,
            "accountDrawdown": 0.0,
            "dailyLossAvailable": True,
            "dailyLossPct": 0.0,
        },
        "positions": [],
        "pendingOrders": [],
        "projectedPositions": [],
        "equityHistory": {
            "accountDrawdownAvailable": True,
            "historicalPeakTotalAsset": 100000.0,
            "accountDrawdown": 0.0,
            "dailyLossAvailable": True,
            "previousSnapshotDate": (request_date - timedelta(days=1)).isoformat(),
            "previousTotalAsset": 100000.0,
            "dailyLossPct": 0.0,
            "snapshotCount": 1,
        },
        "completeness": {
            "accountDrawdownAvailable": True,
            "dailyLossAvailable": True,
            "priceFreshnessWarning": False,
            "partial": False,
        },
        "limitations": [
            "CURRENT_SIMULATED_ACCOUNT_STATE_ONLY",
            "NO_HISTORICAL_PORTFOLIO_POINT_IN_TIME_GUARANTEE",
            "LOCAL_QFQ_MARK_PRICE_ON_OR_BEFORE_REQUEST_DATE",
            "RESEARCH_AND_SIMULATION_ONLY",
        ],
    }
    return payload


def add_position(
    payload: dict,
    *,
    symbol: str = "600001",
    quantity: int = 100,
    average: Decimal = Decimal("80"),
    mark: Decimal = Decimal("100"),
    highest: Decimal = Decimal("110"),
    age: int = 0,
    stop_loss: Decimal | None = None,
    target: Decimal | None = None,
    trailing_pct: Decimal = Decimal("0.04"),
) -> None:
    context = payload["contextSnapshot"]["portfolioContext"]
    request_date = date.fromisoformat(payload["tradeDate"])
    market_value = _money(mark * quantity)
    pnl = _money((mark - average) * quantity)
    context["positions"].append({
        "symbol": symbol,
        "quantity": quantity,
        "availableQuantity": quantity,
        "averageCost": float(average),
        "databaseLastPrice": float(mark),
        "markPrice": float(mark),
        "markTradeDate": (request_date - timedelta(days=age)).isoformat(),
        "priceAgeDays": age,
        "marketValue": float(market_value),
        "unrealizedPnl": float(pnl),
        "positionWeight": 0.0,
        "stopLoss": None if stop_loss is None else float(stop_loss),
        "targetPrice": None if target is None else float(target),
        "trailingStopPct": float(trailing_pct),
        "highestPrice": float(highest),
        "trailingStopPrice": float(_price(highest * (Decimal(1) - trailing_pct))),
        "sourcePlanId": None,
        "lastBuyDate": (request_date - timedelta(days=10)).isoformat(),
    })
    _recompute(payload)


def add_pending_buy(
    payload: dict,
    *,
    order_id: int = 1,
    symbol: str = "600002",
    quantity: int = 100,
    price: Decimal = Decimal("100"),
    fee: Decimal = Decimal("5"),
) -> None:
    context = payload["contextSnapshot"]["portfolioContext"]
    gross = _money(price * quantity)
    context["pendingOrders"].append({
        "orderId": order_id,
        "symbol": symbol,
        "side": "BUY",
        "quantity": quantity,
        "limitPrice": float(price),
        "grossAmount": float(gross),
        "frozenAmount": float(_money(gross + fee)),
        "frozenQuantity": 0,
        "tradePlanId": None,
    })
    _recompute(payload)


def add_pending_sell(
    payload: dict,
    *,
    order_id: int = 1,
    symbol: str = "600001",
    quantity: int = 100,
    price: Decimal = Decimal("100"),
) -> None:
    context = payload["contextSnapshot"]["portfolioContext"]
    position = next(item for item in context["positions"] if item["symbol"] == symbol)
    position["availableQuantity"] -= quantity
    gross = _money(price * quantity)
    context["pendingOrders"].append({
        "orderId": order_id,
        "symbol": symbol,
        "side": "SELL",
        "quantity": quantity,
        "limitPrice": float(price),
        "grossAmount": float(gross),
        "frozenAmount": 0.0,
        "frozenQuantity": quantity,
        "tradePlanId": None,
    })
    _recompute(payload)


def _recompute(payload: dict) -> None:
    context = payload["contextSnapshot"]["portfolioContext"]
    account = context["account"]
    positions = sorted(context["positions"], key=lambda item: item["symbol"])
    orders = sorted(context["pendingOrders"], key=lambda item: item["orderId"])
    context["positions"] = positions
    context["pendingOrders"] = orders
    market = sum((_value(item["marketValue"]) for item in positions), Decimal(0))
    unrealized = sum((_value(item["unrealizedPnl"]) for item in positions), Decimal(0))
    pending: dict[str, Decimal] = {}
    frozen = Decimal(0)
    for order in orders:
        if order["side"] == "BUY":
            pending[order["symbol"]] = (
                pending.get(order["symbol"], Decimal(0))
                + _value(order["grossAmount"])
            )
            frozen += _value(order["frozenAmount"])
    account["frozenCash"] = float(_money(frozen))
    cash = _value(account["cash"])
    account["availableCash"] = float(_money(cash - frozen))
    account["recomputedMarketValue"] = float(_money(market))
    account["recomputedUnrealizedPnl"] = float(_money(unrealized))
    total = _money(cash + market)
    account["recomputedTotalAsset"] = float(total)
    account["positionCount"] = len(positions)
    account["pendingBuyCount"] = sum(order["side"] == "BUY" for order in orders)
    account["pendingSellCount"] = sum(order["side"] == "SELL" for order in orders)
    account["availableCashRatio"] = float(_ratio(cash - frozen, total))
    account["pendingBuyExposure"] = float(_money(sum(pending.values(), Decimal(0))))
    current_values: dict[str, Decimal] = {}
    for position in positions:
        value = _value(position["marketValue"])
        current_values[position["symbol"]] = value
        position["positionWeight"] = float(_ratio(value, total))
    projected = []
    for symbol in sorted(set(current_values) | set(pending)):
        current = current_values.get(symbol, Decimal(0))
        exposure = _money(pending.get(symbol, Decimal(0)))
        value = _money(current + exposure)
        projected.append({
            "symbol": symbol,
            "currentPositionPresent": symbol in current_values,
            "currentPositionValue": float(current),
            "pendingBuyExposure": float(exposure),
            "projectedPositionValue": float(value),
            "projectedPositionWeight": float(_ratio(value, total)),
        })
    context["projectedPositions"] = projected
    account["projectedPositionCount"] = len(projected)
    context["completeness"]["priceFreshnessWarning"] = any(
        item["priceAgeDays"] >= 4 for item in positions
    )
    peak = context["equityHistory"]["historicalPeakTotalAsset"]
    previous = context["equityHistory"]["previousTotalAsset"]
    if peak is not None:
        drawdown = max(Decimal(0), (_value(peak) - total) / _value(peak))
        drawdown = drawdown.quantize(RATIO, rounding=ROUND_HALF_UP)
        account["accountDrawdown"] = float(drawdown)
        context["equityHistory"]["accountDrawdown"] = float(drawdown)
    if previous is not None:
        daily = max(Decimal(0), (_value(previous) - total) / _value(previous))
        daily = daily.quantize(RATIO, rounding=ROUND_HALF_UP)
        account["dailyLossPct"] = float(daily)
        context["equityHistory"]["dailyLossPct"] = float(daily)


def loss_payload(
    *,
    total_asset: Decimal,
    peak: Decimal,
    previous: Decimal,
) -> dict:
    payload = stage_2h_payload()
    context = payload["contextSnapshot"]["portfolioContext"]
    context["account"]["cash"] = float(total_asset)
    context["equityHistory"]["historicalPeakTotalAsset"] = float(peak)
    context["equityHistory"]["previousTotalAsset"] = float(previous)
    _recompute(payload)
    return payload


def set_equity_availability(
    payload: dict,
    *,
    drawdown: bool,
    daily_loss: bool,
) -> None:
    context = payload["contextSnapshot"]["portfolioContext"]
    account = context["account"]
    equity = context["equityHistory"]
    account["accountDrawdownAvailable"] = drawdown
    equity["accountDrawdownAvailable"] = drawdown
    if not drawdown:
        account["accountDrawdown"] = None
        equity["accountDrawdown"] = None
        equity["historicalPeakTotalAsset"] = None
    account["dailyLossAvailable"] = daily_loss
    equity["dailyLossAvailable"] = daily_loss
    if not daily_loss:
        account["dailyLossPct"] = None
        equity["dailyLossPct"] = None
        equity["previousSnapshotDate"] = None
        equity["previousTotalAsset"] = None
    if not drawdown and not daily_loss:
        equity["snapshotCount"] = 0
    context["completeness"].update({
        "accountDrawdownAvailable": drawdown,
        "dailyLossAvailable": daily_loss,
        "partial": not drawdown or not daily_loss,
    })


def analyze(payload: dict) -> dict:
    request = AgentTeamRequest.model_validate(payload)
    return AgentTeamOrchestrator().analyze(request).model_dump(mode="json")


def position_run(response: dict) -> dict:
    return next(
        item for item in response["agentRuns"]
        if item["agentCode"] == "POSITION_RISK"
    )


class PositionRiskAgentTest(unittest.TestCase):

    def test_no_risk_account_generates_five_findings_without_veto(self):
        response = analyze(stage_2h_payload())
        run = position_run(response)
        self.assertEqual(
            ("COMPLETED", "PASS", "PASS", False, 100, 100),
            (
                run["status"], run["gateStatus"], run["decision"], run["veto"],
                run["score"], run["confidence"],
            ),
        )
        self.assertEqual(5, len(run["findings"]))
        self.assertEqual([], response["vetoes"])
        self.assertEqual("INSUFFICIENT_DATA", response["finalDecision"]["decision"])
        self.assertEqual(6, len(response["agentRuns"]))

    def test_account_drawdown_veto_precedes_data_quality_block(self):
        payload = stage_2h_payload()
        context = payload["contextSnapshot"]["portfolioContext"]
        context["account"]["cash"] = 88000.0
        context["equityHistory"]["previousTotalAsset"] = 88000.0
        _recompute(payload)
        payload["contextSnapshot"]["security"]["available"] = False
        response = analyze(payload)
        run = position_run(response)
        self.assertEqual(
            ["POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT"],
            [item["vetoCode"] for item in response["vetoes"]],
        )
        self.assertEqual(("COMPLETED", "BLOCKED", "REJECT", True, 0), (
            run["status"], run["gateStatus"], run["decision"], run["veto"], run["score"],
        ))
        self.assertEqual("REJECTED_BY_VETO", response["finalDecision"]["decision"])
        self.assertTrue(response["finalDecision"]["vetoed"])

    def test_all_symbol_vetoes_have_stable_order_and_unique_ids(self):
        payload = stage_2h_payload()
        payload["contextSnapshot"]["portfolioContext"]["account"]["maxPositionWeight"] = 0.05
        add_position(
            payload,
            symbol="600001",
            quantity=100,
            average=Decimal("120"),
            mark=Decimal("90"),
            highest=Decimal("130"),
            stop_loss=Decimal("95"),
        )
        add_pending_buy(payload, symbol="600002", quantity=100, price=Decimal("100"))
        response = analyze(payload)
        codes = [item["vetoCode"] for item in response["vetoes"]]
        self.assertEqual([
            "POSITION_RISK_POSITION_WEIGHT_LIMIT_600001",
            "POSITION_RISK_PROJECTED_WEIGHT_LIMIT_600002",
            "POSITION_RISK_STOP_LOSS_TRIGGERED_600001",
            "POSITION_RISK_TRAILING_STOP_TRIGGERED_600001",
        ], codes)
        ids = [item["vetoId"] for item in response["vetoes"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(ids, response["finalDecision"]["vetoIds"])

    def test_unchanged_projected_position_does_not_duplicate_veto_or_score_penalty(self):
        payload = stage_2h_payload()
        payload["contextSnapshot"]["portfolioContext"]["account"]["maxPositionWeight"] = 0.5
        add_position(
            payload,
            quantity=900,
            average=Decimal("90"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        response = analyze(payload)
        run = position_run(response)
        self.assertEqual([], response["vetoes"])
        self.assertEqual(90, run["score"])

    def test_daily_loss_and_position_count_vetoes(self):
        payload = stage_2h_payload()
        context = payload["contextSnapshot"]["portfolioContext"]
        context["account"]["cash"] = 96000.0
        context["account"]["maxPositions"] = 1
        add_pending_buy(payload, symbol="600001", price=Decimal("10"))
        add_pending_buy(payload, order_id=2, symbol="600002", price=Decimal("10"))
        response = analyze(payload)
        self.assertEqual([
            "POSITION_RISK_DAILY_LOSS_LIMIT",
            "POSITION_RISK_MAX_POSITIONS_EXCEEDED",
        ], [item["vetoCode"] for item in response["vetoes"]])

    def test_pending_sell_is_cross_checked_without_projected_buy_exposure(self):
        payload = stage_2h_payload()
        add_position(
            payload,
            symbol="600001",
            quantity=100,
            average=Decimal("80"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        add_pending_sell(payload)
        response = analyze(payload)
        run = position_run(response)
        self.assertEqual("PASS", run["gateStatus"])
        self.assertEqual([], response["vetoes"])
        self.assertEqual(0.0, payload["contextSnapshot"]["portfolioContext"]
                         ["account"]["pendingBuyExposure"])

        invalid = copy.deepcopy(payload)
        invalid["contextSnapshot"]["portfolioContext"]["pendingOrders"][0][
            "frozenQuantity"
        ] = 101
        invalid_run = position_run(analyze(invalid))
        self.assertEqual("POSITION_RISK_INPUT_INVALID", invalid_run["errors"][0]["code"])

    def test_score_and_confidence_boundaries(self):
        score_cases = (
            ("drawdown_below", Decimal("95001"), Decimal("100000"),
             Decimal("95001"), 100, False),
            ("drawdown_five", Decimal("95000"), Decimal("100000"),
             Decimal("95000"), 90, False),
            ("drawdown_eight", Decimal("92000"), Decimal("100000"),
             Decimal("92000"), 80, False),
            ("drawdown_twelve", Decimal("88000"), Decimal("100000"),
             Decimal("88000"), 0, True),
            ("daily_below", Decimal("99001"), Decimal("99001"),
             Decimal("100000"), 100, False),
            ("daily_one", Decimal("99000"), Decimal("99000"),
             Decimal("100000"), 90, False),
            ("daily_two", Decimal("98000"), Decimal("98000"),
             Decimal("100000"), 80, False),
            ("daily_three", Decimal("97000"), Decimal("97000"),
             Decimal("100000"), 0, True),
        )
        for name, total, peak, previous, score, veto in score_cases:
            with self.subTest(name=name):
                run = position_run(analyze(loss_payload(
                    total_asset=total,
                    peak=peak,
                    previous=previous,
                )))
                self.assertEqual(score, run["score"])
                self.assertEqual(veto, run["veto"])

        at_count_limit = stage_2h_payload()
        at_count_limit["contextSnapshot"]["portfolioContext"]["account"][
            "maxPositions"
        ] = 1
        add_pending_buy(
            at_count_limit,
            symbol="600001",
            quantity=1,
            price=Decimal("100"),
            fee=Decimal("0"),
        )
        self.assertEqual(90, position_run(analyze(at_count_limit))["score"])

        one_near_limit = stage_2h_payload()
        one_near_context = one_near_limit["contextSnapshot"]["portfolioContext"]
        one_near_context["account"]["cash"] = 82000.0
        add_position(
            one_near_limit,
            quantity=180,
            average=Decimal("100"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        self.assertEqual(90, position_run(analyze(one_near_limit))["score"])

        two_near_limit = stage_2h_payload()
        two_near_context = two_near_limit["contextSnapshot"]["portfolioContext"]
        two_near_context["account"]["cash"] = 64000.0
        add_position(
            two_near_limit,
            symbol="600001",
            quantity=180,
            average=Decimal("100"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        add_position(
            two_near_limit,
            symbol="600002",
            quantity=180,
            average=Decimal("100"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        self.assertEqual(80, position_run(analyze(two_near_limit))["score"])

        low_cash = stage_2h_payload()
        low_cash_context = low_cash["contextSnapshot"]["portfolioContext"]
        low_cash_context["account"]["cash"] = 9000.0
        low_cash_context["account"]["maxPositionWeight"] = 0.6
        add_position(
            low_cash,
            symbol="600001",
            quantity=455,
            average=Decimal("100"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        add_position(
            low_cash,
            symbol="600002",
            quantity=455,
            average=Decimal("100"),
            mark=Decimal("100"),
            highest=Decimal("100"),
        )
        self.assertEqual(90, position_run(analyze(low_cash))["score"])

        partial = stage_2h_payload()
        set_equity_availability(partial, drawdown=False, daily_loss=False)
        partial_run = position_run(analyze(partial))
        self.assertEqual(("PARTIAL", "WARN", 60), (
            partial_run["status"], partial_run["gateStatus"], partial_run["confidence"],
        ))

        missing_drawdown = stage_2h_payload()
        set_equity_availability(
            missing_drawdown, drawdown=False, daily_loss=True)
        self.assertEqual(
            80, position_run(analyze(missing_drawdown))["confidence"])

        missing_daily = stage_2h_payload()
        set_equity_availability(
            missing_daily, drawdown=True, daily_loss=False)
        self.assertEqual(80, position_run(analyze(missing_daily))["confidence"])

        stale = stage_2h_payload()
        add_position(stale, age=4, quantity=10, highest=Decimal("100"))
        self.assertEqual(80, position_run(analyze(stale))["confidence"])

        minimum_confidence = stage_2h_payload()
        add_position(
            minimum_confidence,
            age=4,
            quantity=10,
            highest=Decimal("100"),
        )
        set_equity_availability(
            minimum_confidence, drawdown=False, daily_loss=False)
        self.assertEqual(
            40, position_run(analyze(minimum_confidence))["confidence"])

    def test_target_is_warning_only_and_no_execution_instruction_is_emitted(self):
        payload = stage_2h_payload()
        payload["contextSnapshot"]["portfolioContext"]["account"]["maxPositionWeight"] = 1.0
        add_position(
            payload,
            quantity=10,
            average=Decimal("80"),
            mark=Decimal("100"),
            highest=Decimal("100"),
            target=Decimal("95"),
        )
        response = analyze(payload)
        run = position_run(response)
        self.assertFalse(run["veto"])
        self.assertEqual("WARN", run["gateStatus"])
        self.assertEqual([], response["vetoes"])
        encoded = json.dumps(response, ensure_ascii=False)
        for forbidden in ("立即买入", "立即卖出", "清仓", "自动下单", "收益承诺"):
            self.assertNotIn(forbidden, encoded)

    def test_unavailable_and_invalid_context_are_safe(self):
        unavailable = stage_2h_payload()
        context = unavailable["contextSnapshot"]["portfolioContext"]
        base_keys = {
            "available", "queriedAt", "queryScope", "producer", "producerVersion",
            "contextProfile", "schemaVersion", "accountId", "requestTradeDate",
            "analysisDate", "marketTimezone", "currentStateOnly",
            "snapshotFrozenForTask", "historicalPointInTimeGuaranteed",
            "businessTablesReadOnly", "sourceType", "sourceTables",
        }
        unavailable["contextSnapshot"]["portfolioContext"] = {
            key: value for key, value in context.items() if key in base_keys
        }
        unavailable["contextSnapshot"]["portfolioContext"].update({
            "available": False,
            "reasonCode": "PORTFOLIO_PRICE_MISSING",
            "reason": "missing local mark",
        })
        run = position_run(analyze(unavailable))
        self.assertEqual("PORTFOLIO_PRICE_MISSING", run["errors"][0]["code"])
        self.assertEqual((0, 0, False), (run["score"], run["confidence"], run["veto"]))

        invalid = stage_2h_payload()
        invalid["contextSnapshot"]["portfolioContext"]["unexpected"] = True
        invalid_run = position_run(analyze(invalid))
        self.assertEqual("POSITION_RISK_INPUT_INVALID", invalid_run["errors"][0]["code"])
        self.assertEqual([], invalid_run["evidence"])


def _value(value: object) -> Decimal:
    return Decimal(str(value))


def _money(value: Decimal) -> Decimal:
    return value.quantize(MONEY, rounding=ROUND_HALF_UP)


def _price(value: Decimal) -> Decimal:
    return value.quantize(PRICE, rounding=ROUND_HALF_UP)


def _ratio(value: Decimal, total: Decimal) -> Decimal:
    return (value / total).quantize(RATIO, rounding=ROUND_HALF_UP)


if __name__ == "__main__":
    unittest.main()
