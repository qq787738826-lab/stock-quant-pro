package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.portfolio.PortfolioContracts;
import com.stockquant.server.agent.service.AgentContextHashService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

final class AgentStage2HTestFixtures {

    enum Scenario {
        PASS,
        SINGLE_VETO,
        MULTI_VETO,
        PARTIAL,
        UNAVAILABLE,
        INVALID,
        DATA_QUALITY_BLOCKED_WITH_VETO
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final AgentContextHashService HASHES =
            new AgentContextHashService(MAPPER);

    private AgentStage2HTestFixtures() {
    }

    static AgentTeamRequest request(Scenario scenario) {
        AgentStage2FTestFixtures.Scenario baseScenario =
                scenario == Scenario.DATA_QUALITY_BLOCKED_WITH_VETO
                        ? AgentStage2FTestFixtures.Scenario.BLOCKED
                        : AgentStage2FTestFixtures.Scenario.PASS;
        AgentTeamRequest base = AgentStage2FTestFixtures.request(baseScenario);
        ObjectNode snapshot = base.contextSnapshot().deepCopy();
        ObjectNode portfolio = portfolio(base.symbol(), base.tradeDate());
        switch (scenario) {
            case SINGLE_VETO, DATA_QUALITY_BLOCKED_WITH_VETO -> {
                setCurrentAssets(portfolio, new BigDecimal("88000.00"));
                portfolio.with("equityHistory")
                        .put("previousTotalAsset", new BigDecimal("88000.00"));
                portfolio.with("account").put("dailyLossPct", BigDecimal.ZERO.setScale(8));
                portfolio.with("equityHistory").put(
                        "dailyLossPct", BigDecimal.ZERO.setScale(8));
            }
            case MULTI_VETO -> setCurrentAssets(
                    portfolio, new BigDecimal("85000.00"));
            case PARTIAL -> makePartial(portfolio);
            case UNAVAILABLE -> portfolio = unavailable(
                    base.symbol(), base.tradeDate(), "PORTFOLIO_PRICE_MISSING");
            case INVALID -> portfolio.put("unexpected", true);
            case PASS -> {
            }
        }
        snapshot.set("portfolioContext", portfolio);
        String contextHash = HASHES.hash(snapshot);
        return new AgentTeamRequest(
                base.schemaVersion(),
                base.taskId(),
                base.runIds(),
                base.symbol(),
                base.tradeDate(),
                contextHash,
                base.contextSchemaVersion(),
                PortfolioContracts.RULE_VERSION,
                base.executionMode(),
                snapshot,
                base.requestedAt());
    }

    private static ObjectNode portfolio(String symbol, LocalDate tradeDate) {
        ObjectNode root = base(symbol, tradeDate);
        root.put("available", true);
        ObjectNode account = root.putObject("account");
        account.put("accountId", 1L);
        account.put("name", "默认模拟账户");
        account.put("initialCapital", new BigDecimal("100000.00"));
        account.put("cash", new BigDecimal("100000.00"));
        account.put("frozenCash", new BigDecimal("0.00"));
        account.put("availableCash", new BigDecimal("100000.00"));
        account.put("recomputedMarketValue", new BigDecimal("0.00"));
        account.put("recomputedTotalAsset", new BigDecimal("100000.00"));
        account.put("realizedPnl", new BigDecimal("0.00"));
        account.put("recomputedUnrealizedPnl", new BigDecimal("0.00"));
        account.put("totalFees", new BigDecimal("0.00"));
        account.put("positionCount", 0);
        account.put("pendingBuyCount", 0);
        account.put("pendingSellCount", 0);
        account.put("availableCashRatio", new BigDecimal("1.00000000"));
        account.put("maxPositions", 5);
        account.put("maxPositionWeight", new BigDecimal("0.20000000"));
        account.put("projectedPositionCount", 0);
        account.put("pendingBuyExposure", new BigDecimal("0.00"));
        account.put("accountDrawdownAvailable", true);
        account.put("accountDrawdown", new BigDecimal("0.00000000"));
        account.put("dailyLossAvailable", true);
        account.put("dailyLossPct", new BigDecimal("0.00000000"));
        root.putArray("positions");
        root.putArray("pendingOrders");
        root.putArray("projectedPositions");
        ObjectNode equity = root.putObject("equityHistory");
        equity.put("accountDrawdownAvailable", true);
        equity.put("historicalPeakTotalAsset", new BigDecimal("100000.00"));
        equity.put("accountDrawdown", new BigDecimal("0.00000000"));
        equity.put("dailyLossAvailable", true);
        equity.put("previousSnapshotDate", tradeDate.minusDays(1).toString());
        equity.put("previousTotalAsset", new BigDecimal("100000.00"));
        equity.put("dailyLossPct", new BigDecimal("0.00000000"));
        equity.put("snapshotCount", 1);
        ObjectNode completeness = root.putObject("completeness");
        completeness.put("accountDrawdownAvailable", true);
        completeness.put("dailyLossAvailable", true);
        completeness.put("priceFreshnessWarning", false);
        completeness.put("partial", false);
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("CURRENT_SIMULATED_ACCOUNT_STATE_ONLY");
        limitations.add("NO_HISTORICAL_PORTFOLIO_POINT_IN_TIME_GUARANTEE");
        limitations.add("LOCAL_QFQ_MARK_PRICE_ON_OR_BEFORE_REQUEST_DATE");
        limitations.add("RESEARCH_AND_SIMULATION_ONLY");
        return root;
    }

    private static ObjectNode unavailable(
            String symbol,
            LocalDate tradeDate,
            String reasonCode
    ) {
        ObjectNode root = base(symbol, tradeDate);
        root.put("available", false);
        root.put("reasonCode", reasonCode);
        root.put("reason", "stable unavailable test fixture");
        return root;
    }

    private static ObjectNode base(String symbol, LocalDate tradeDate) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("available", false);
        Instant queriedAt = tradeDate.atTime(LocalTime.NOON)
                .atZone(PortfolioContracts.MARKET_ZONE).toInstant();
        root.put("queriedAt", queriedAt.toString());
        ObjectNode scope = root.putObject("queryScope");
        scope.put("symbol", symbol);
        scope.put("tradeDate", tradeDate.toString());
        root.put("producer", PortfolioContracts.PRODUCER);
        root.put("producerVersion", PortfolioContracts.PRODUCER_VERSION);
        root.put("contextProfile", PortfolioContracts.CONTEXT_PROFILE);
        root.put("schemaVersion", PortfolioContracts.CONTEXT_SCHEMA_VERSION);
        root.put("accountId", 1L);
        root.put("requestTradeDate", tradeDate.toString());
        root.put("analysisDate", tradeDate.toString());
        root.put("marketTimezone", PortfolioContracts.MARKET_TIMEZONE);
        root.put("currentStateOnly", true);
        root.put("snapshotFrozenForTask", true);
        root.put("historicalPointInTimeGuaranteed", false);
        root.put("businessTablesReadOnly", true);
        root.put("sourceType", "DATABASE");
        ArrayNode tables = root.putArray("sourceTables");
        tables.add("portfolio_accounts");
        tables.add("positions");
        tables.add("manual_orders");
        tables.add("daily_bars");
        tables.add("account_equity_snapshots");
        tables.add("app_settings");
        return root;
    }

    private static void setCurrentAssets(ObjectNode root, BigDecimal totalAssets) {
        ObjectNode account = root.with("account");
        account.put("cash", totalAssets);
        account.put("availableCash", totalAssets);
        account.put("recomputedTotalAsset", totalAssets);
        account.put(
                "availableCashRatio",
                totalAssets.signum() == 0
                        ? BigDecimal.ZERO.setScale(8)
                        : BigDecimal.ONE.setScale(8));
        BigDecimal peak = root.with("equityHistory")
                .path("historicalPeakTotalAsset").decimalValue();
        BigDecimal previous = root.with("equityHistory")
                .path("previousTotalAsset").decimalValue();
        BigDecimal drawdown = peak.subtract(totalAssets)
                .max(BigDecimal.ZERO)
                .divide(peak, 8, java.math.RoundingMode.HALF_UP);
        BigDecimal dailyLoss = previous.subtract(totalAssets)
                .max(BigDecimal.ZERO)
                .divide(previous, 8, java.math.RoundingMode.HALF_UP);
        account.put("accountDrawdown", drawdown);
        account.put("dailyLossPct", dailyLoss);
        root.with("equityHistory").put("accountDrawdown", drawdown);
        root.with("equityHistory").put("dailyLossPct", dailyLoss);
    }

    private static void makePartial(ObjectNode root) {
        ObjectNode account = root.with("account");
        ObjectNode equity = root.with("equityHistory");
        ObjectNode completeness = root.with("completeness");
        account.put("accountDrawdownAvailable", false);
        account.putNull("accountDrawdown");
        account.put("dailyLossAvailable", false);
        account.putNull("dailyLossPct");
        equity.put("accountDrawdownAvailable", false);
        equity.putNull("historicalPeakTotalAsset");
        equity.putNull("accountDrawdown");
        equity.put("dailyLossAvailable", false);
        equity.putNull("previousSnapshotDate");
        equity.putNull("previousTotalAsset");
        equity.putNull("dailyLossPct");
        equity.put("snapshotCount", 0);
        completeness.put("accountDrawdownAvailable", false);
        completeness.put("dailyLossAvailable", false);
        completeness.put("partial", true);
    }
}
