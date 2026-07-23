package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.RunIds;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AgentStage2ETestFixtures {

    static final String RULE_VERSION = "1.4.0-stage-2e-technical-analysis-v1";
    static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    static final Instant QUERIED_AT = Instant.parse("2026-07-14T05:00:00Z");
    static final Instant REQUESTED_AT = Instant.parse("2026-07-14T05:01:00Z");

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AgentStage2ETestFixtures() {}

    static AgentTeamRequest request(Scenario scenario) {
        String hash = switch (scenario) {
            case PASS -> "e".repeat(64);
            case WARN -> "d".repeat(64);
            case BLOCKED -> "c".repeat(64);
            case INVALID_TECHNICAL_INPUT -> "a".repeat(64);
        };
        return new AgentTeamRequest(
                "1.0",
                901 + scenario.ordinal(),
                new RunIds(911, 912, 913, 914, 915, 916),
                "600000",
                TRADE_DATE,
                hash,
                "1.0",
                RULE_VERSION,
                ExecutionMode.LOCAL_RULES,
                context(scenario),
                REQUESTED_AT);
    }

    private static ObjectNode context(Scenario scenario) {
        ObjectNode context = MAPPER.createObjectNode();
        boolean pointInTime = scenario != Scenario.WARN;
        context.set("security", security(pointInTime));
        context.set("marketData", marketData());
        context.set("marketBreadth", marketBreadth());
        context.set("scanResult", unavailable("阶段2E-1未接入"));
        context.set("technicalMetrics", technicalMetrics(scenario));
        context.set("backtestContext", unavailable("阶段2E-1未接入"));
        context.set("securityEvents", unavailable("阶段2E-1未接入"));
        context.set("portfolioContext", unavailable("阶段2E-1未接入"));
        context.set("dataQualityContext", dataQualityContext(pointInTime));
        return context;
    }

    private static ObjectNode security(boolean pointInTime) {
        ObjectNode value = scoped();
        value.put("available", true);
        value.put("symbol", "600000");
        value.put("name", "阶段2E测试证券");
        value.put("board", "MAIN");
        value.put("industry", "银行");
        value.put("listDate", "1999-11-10");
        value.put("isSt", false);
        value.put("isActive", true);
        value.put("dataSource", "LOCAL");
        ObjectNode quality = value.putObject("qualityFacts");
        quality.put("placeholderSuspected", false);
        quality.put("sourceKnown", true);
        quality.put("pointInTimeGuaranteed", pointInTime);
        return value;
    }

    private static ObjectNode marketData() {
        ObjectNode value = scoped();
        value.put("available", true);
        value.put("adjustType", "QFQ");
        value.put("requestedTradeDate", TRADE_DATE.toString());
        value.put("effectiveTradeDate", TRADE_DATE.toString());
        value.put("exactTradeDateMatch", true);
        value.put("actualBars", 61);
        ArrayNode bars = value.putArray("bars");
        List<LocalDate> dates = tradingDates(TRADE_DATE, 61);
        for (int index = 0; index < dates.size(); index++) {
            BigDecimal close = index == dates.size() - 1
                    ? new BigDecimal("105.000000") : new BigDecimal("100.000000");
            ObjectNode bar = bars.addObject();
            bar.put("symbol", "600000");
            bar.put("tradeDate", dates.get(index).toString());
            bar.put("open", close);
            bar.put("high", close.add(BigDecimal.ONE));
            bar.put("low", close.subtract(BigDecimal.ONE));
            bar.put("close", close);
            bar.put("volume", 1000L + index);
            bar.put("amount", new BigDecimal("100000.000000").add(BigDecimal.valueOf(index)));
            bar.put("turnoverRate", new BigDecimal("0.500000"));
        }
        return value;
    }

    private static ObjectNode technicalMetrics(Scenario scenario) {
        ObjectNode value = scoped();
        value.put("available", true);
        value.put("formulaVersion", scenario == Scenario.BLOCKED
                ? "UNAPPROVED_FORMULA" : "JAVA_INDICATORS_V1");
        value.put("adjustType", "QFQ");
        value.put("requestedTradeDate", TRADE_DATE.toString());
        value.put("effectiveTradeDate", TRADE_DATE.toString());
        value.put("requiredBars", 61);
        value.put("actualBars", 61);
        ObjectNode windows = value.putObject("windows");
        windows.put("ma5", 5);
        windows.put("ma20", 20);
        windows.put("ma60", 60);
        windows.put("rsi14", 14);
        windows.put("atr14", 14);
        windows.put("averageVolume20", 20);
        windows.put("highestClose20", 20);
        ObjectNode values = value.putObject("values");
        if (scenario == Scenario.INVALID_TECHNICAL_INPUT) {
            values.putNull("ma5");
        } else {
            values.put("ma5", new BigDecimal("105.000000"));
        }
        values.put("ma20", new BigDecimal("100.000000"));
        values.put("ma60", new BigDecimal("95.000000"));
        values.put("rsi14", new BigDecimal("60.000000"));
        values.put("atr14", new BigDecimal("4.000000"));
        values.put("averageVolume20", new BigDecimal("1020.000000"));
        values.put("highestClose20", new BigDecimal("105.000000"));
        return value;
    }

    private static ObjectNode marketBreadth() {
        ObjectNode value = scoped();
        value.put("available", true);
        value.putNull("reasonCode");
        value.putNull("reason");
        value.put("sourceType", "DATABASE");
        value.putArray("sourceTables").add("daily_bars").add("securities");
        value.put("sourceStatus", "AVAILABLE");
        value.put("producer", "AgentMarketBreadthContextService");
        value.put("producerVersion", "MARKET_BREADTH_V1");
        value.put("versionAvailable", true);
        value.put("requestedTradeDate", TRADE_DATE.toString());
        value.put("effectiveTradeDate", TRADE_DATE.toString());
        value.put("previousEffectiveTradeDate", TRADE_DATE.minusDays(1).toString());
        value.put("exactTradeDateMatch", true);
        value.put("pointInTimeGuaranteed", false);
        value.put("barFutureDataExcluded", true);
        value.put("universePointInTimeGuaranteed", false);
        value.put("futureDataExcluded", false);
        value.put("timestampTimezoneSemantics",
                "TRADE_DATES_ARE_LOCAL_DATE_QUERIED_AT_IS_UTC_INSTANT");
        value.put("adjustType", "QFQ");
        value.put("selectionRule", "CURRENT_MAIN_ACTIVE_NON_ST_UNIVERSE_UNIFIED_EFFECTIVE_DATE");
        value.put("universeCount", 4);
        value.put("coveredSymbolCount", 4);
        value.put("comparableSymbolCount", 4);
        value.put("advancingCount", 3);
        value.put("decliningCount", 1);
        value.put("unchangedCount", 0);
        value.put("missingCurrentBarCount", 0);
        value.put("missingPreviousBarCount", 0);
        value.put("coverageRatio", new BigDecimal("1.00000000"));
        value.putArray("limitations")
                .add("CURRENT_SECURITIES_ATTRIBUTES_ARE_NOT_HISTORICALLY_VERSIONED");
        return value;
    }

    private static ObjectNode dataQualityContext(boolean pointInTime) {
        ObjectNode value = scoped();
        value.put("available", true);
        ObjectNode facts = value.putObject("facts");
        facts.put("securityRecordPresent", true);
        facts.put("securityPlaceholderSuspected", false);
        facts.put("securitySourceKnown", true);
        facts.put("securityPointInTimeGuaranteed", pointInTime);
        facts.put("loadedBarCount", 61);
        facts.put("requiredBarsForTechnicalMetrics", 61);
        facts.put("exactTradeDatePresent", true);
        facts.put("requestedTradeDate", TRADE_DATE.toString());
        facts.put("effectiveTradeDate", TRADE_DATE.toString());
        facts.put("naturalDayLag", 0);
        facts.put("tradingCalendarAvailable", true);
        facts.put("missingAmountCount", 0);
        facts.put("missingTurnoverRateCount", 0);
        facts.put("invalidBarCount", 0);
        facts.putArray("invalidBarDates");
        facts.put("maximumObservedNaturalDayGap", 3);
        facts.put("duplicateProtection", "PRIMARY_KEY_SYMBOL_TRADE_DATE_ADJUST_TYPE");
        facts.put("sourceConsistencyAssessable", true);
        facts.putArray("adjustTypesObserved").add("QFQ");
        facts.putArray("missingSecurityFields");
        return value;
    }

    private static ObjectNode unavailable(String reason) {
        ObjectNode value = scoped();
        value.put("available", false);
        value.put("reason", reason);
        return value;
    }

    private static ObjectNode scoped() {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("queriedAt", QUERIED_AT.toString());
        ObjectNode scope = value.putObject("queryScope");
        scope.put("symbol", "600000");
        scope.put("tradeDate", TRADE_DATE.toString());
        return value;
    }

    private static List<LocalDate> tradingDates(LocalDate end, int count) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate candidate = end;
        while (result.size() < count) {
            if (candidate.getDayOfWeek().getValue() <= 5) result.add(candidate);
            candidate = candidate.minusDays(1);
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    enum Scenario {
        PASS,
        WARN,
        BLOCKED,
        INVALID_TECHNICAL_INPUT
    }
}
