package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.BacktestModels;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqAsOfResult;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqBar;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqSourceIdentities;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds the separate V2 backtest context without changing 2F V1. */
@Service
public class AgentBacktestContextV2Service {

    private final ObjectMapper objectMapper;
    private final QfqAsOfEngine qfqEngine;
    private final BacktestCanonicalHashService canonical;
    private final BacktestEngine backtestEngine;
    private final PitMarketFactsV2Properties properties;

    public AgentBacktestContextV2Service(
            ObjectMapper objectMapper,
            QfqAsOfEngine qfqEngine,
            BacktestCanonicalHashService canonical,
            BacktestEngine backtestEngine,
            PitMarketFactsV2Properties properties
    ) {
        this.objectMapper = objectMapper;
        this.qfqEngine = qfqEngine;
        this.canonical = canonical;
        this.backtestEngine = backtestEngine;
        this.properties = properties;
    }

    public ObjectNode create(
            String symbol,
            LocalDate requestTradeDate,
            Instant queriedAt
    ) {
        Instant queryTime = BacktestCanonicalHashService.microsecondInstant(queriedAt);
        Instant decisionTime = requestTradeDate
                .atTime(LocalTime.MAX)
                .atZone(PitMarketFactsContracts.MARKET_ZONE)
                .toInstant()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ObjectNode context = base(symbol, requestTradeDate, queryTime, decisionTime);
        if (!properties.isTestDemoEnabled()) {
            return unavailable(
                    context,
                    PitMarketFactsContracts.TEST_DEMO_PROFILE_DISABLED,
                    "Synthetic PIT market facts V2 are disabled outside an "
                            + "explicit TEST/DEMO runtime");
        }
        LocalDate currentDate = queryTime.atZone(
                PitMarketFactsContracts.MARKET_ZONE).toLocalDate();
        if (requestTradeDate.isAfter(currentDate)) {
            return unavailable(context,
                    PitMarketFactsContracts.FUTURE_REQUEST_DATE,
                    "Request trade date is in the future");
        }
        if (queryTime.isBefore(decisionTime)) {
            return unavailable(context,
                    PitMarketFactsContracts.DECISION_TIME_NOT_REACHED,
                    "Daily close decision time has not been reached");
        }
        String exchange = exchange(symbol);
        QfqSourceIdentities sourceIdentities =
                MockMarketFactProvider.qfqSourceIdentities(symbol, exchange);
        QfqAsOfResult qfq = qfqEngine.calculate(
                symbol, exchange, MockMarketFactProvider.PROVIDER_CODE,
                sourceIdentities, requestTradeDate, decisionTime);
        if (!qfq.available()) {
            return unavailable(context, qfq.reasonCode(), qfq.reason());
        }
        if (qfq.bars().size() < BacktestContracts.MINIMUM_CONTEXT_BARS) {
            context.put("actualBars", qfq.bars().size());
            context.put("requiredBars", BacktestContracts.MINIMUM_CONTEXT_BARS);
            return unavailable(context, PitMarketFactsContracts.SAMPLE_INSUFFICIENT,
                    "V2 QFQ window contains fewer than 120 bars");
        }
        if (qfq.bars().stream().anyMatch(bar ->
                bar.volume().qualification()
                        != MarketFactProviderModels.FieldQualification
                        .PRESENT_VERIFIED
                        || bar.volume().value() == null)) {
            return unavailable(
                    context,
                    PitMarketFactsContracts.REQUIRED_MARKET_FIELD_UNAVAILABLE,
                    "V2 backtest requires PRESENT_VERIFIED volume for every bar");
        }
        List<Bar> bars;
        try {
            bars = qfq.bars().stream().map(this::bar).toList();
        } catch (ArithmeticException error) {
            return unavailable(context, PitMarketFactsContracts.FACT_INVALID,
                    "V2 volume cannot be represented as an integer share count");
        }
        BacktestModels.Request parameters = BacktestContracts.parameters();
        BacktestModels.Result result;
        List<Subperiod> periods;
        try {
            result = backtestEngine.run(bars, parameters);
            periods = subperiods(bars, parameters);
        } catch (IllegalArgumentException error) {
            return unavailable(context, PitMarketFactsContracts.FACT_INVALID,
                    "Backtest engine rejected V2 QFQ facts");
        }
        ObjectNode replayOne = replay(result, periods);
        ObjectNode replayTwo = replay(
                backtestEngine.run(bars, parameters),
                subperiods(bars, parameters));
        if (!canonical.canonicalText(replayOne)
                .equals(canonical.canonicalText(replayTwo))) {
            return unavailable(context, PitMarketFactsContracts.REPLAY_MISMATCH,
                    "Frozen V2 input did not replay deterministically");
        }

        ObjectNode qfqContract = qfqContract();
        ObjectNode dataVersion = dataVersion(qfq);
        ArrayNode contextBars = contextBars(qfq.bars());
        ObjectNode strategy = strategy(parameters);
        ObjectNode resultNode = result(result);
        ArrayNode periodNodes = periodNodes(periods);
        int positive = (int) periods.stream()
                .filter(value -> value.result().totalReturn().signum() > 0)
                .count();
        ObjectNode stability = stability(positive);
        ObjectNode inputPayload = inputPayload(
                context, qfq, qfqContract, dataVersion, contextBars);
        String inputHash = canonical.hash(inputPayload);
        String strategyHash = canonical.hash(strategy);
        ObjectNode resultPayload = objectMapper.createObjectNode();
        resultPayload.put("canonicalContractVersion",
                PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION);
        resultPayload.put("inputDataHash", inputHash);
        resultPayload.put("strategyDefinitionHash", strategyHash);
        resultPayload.set("result", resultNode.deepCopy());
        resultPayload.set("subperiods", periodNodes.deepCopy());
        resultPayload.set("stability", stability.deepCopy());
        String resultHash = canonical.hash(resultPayload);

        context.put("available", true);
        context.put("effectiveTradeDate",
                qfq.requestEffectiveTradeDate().toString());
        context.put("exactTradeDateMatch",
                qfq.requestEffectiveTradeDate().equals(requestTradeDate));
        context.put("inputStartDate",
                qfq.bars().get(0).tradeDate().toString());
        context.put("inputEndDate", qfq.anchorTradeDate().toString());
        context.put("barCount", qfq.bars().size());
        context.put("requiredBars", BacktestContracts.MINIMUM_CONTEXT_BARS);
        context.put("maximumBars", BacktestContracts.MAXIMUM_BARS);
        context.set("qfqContract", qfqContract);
        context.set("dataVersion", dataVersion);
        context.set("bars", contextBars);
        context.set("strategy", strategy);
        context.set("result", resultNode);
        context.set("subperiods", periodNodes);
        context.set("stability", stability);
        context.put("inputDataHash", inputHash);
        context.put("strategyDefinitionHash", strategyHash);
        context.put("backtestResultHash", resultHash);
        context.put("pointInTimeGuaranteed", true);
        context.put("readSelectionFutureExcluded", true);
        context.put("producerInputCutoffGuaranteed", true);
        context.put("futureDataExcluded", true);
        context.put("testDemoOnly", true);
        ArrayNode limitations = context.putArray("limitations");
        limitations.add("TEST_DEMO_SYNTHETIC_FACTS_ONLY");
        limitations.add("SYSTEM_KNOWLEDGE_PIT_IS_NOT_PROVIDER_PIT");
        limitations.add("NO_PRE_CAPTURE_HISTORICAL_CLAIM");
        limitations.add("RESEARCH_AND_SIMULATION_ONLY");
        return context;
    }

    private ObjectNode base(
            String symbol,
            LocalDate requestDate,
            Instant queriedAt,
            Instant decisionTime
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("available", false);
        context.put("queriedAt",
                BacktestCanonicalHashService.formatInstant(queriedAt));
        ObjectNode scope = context.putObject("queryScope");
        scope.put("symbol", symbol);
        scope.put("tradeDate", requestDate.toString());
        context.put("producer", PitMarketFactsContracts.PRODUCER);
        context.put("producerVersion", PitMarketFactsContracts.PRODUCER_VERSION);
        context.put("contextProfile", PitMarketFactsContracts.CONTEXT_PROFILE);
        context.put("schemaVersion",
                PitMarketFactsContracts.BACKTEST_CONTEXT_VERSION);
        context.put("canonicalContractVersion",
                PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION);
        context.put("pitModelVersion",
                PitMarketFactsContracts.MARKET_FACTS_VERSION);
        context.put("symbol", symbol);
        context.put("requestTradeDate", requestDate.toString());
        context.put("decisionTime",
                BacktestCanonicalHashService.formatInstant(decisionTime));
        context.put("knowledgeCutoff",
                BacktestCanonicalHashService.formatInstant(decisionTime));
        context.put("marketTimezone",
                PitMarketFactsContracts.MARKET_ZONE.getId());
        context.put("adjustType", "QFQ");
        context.put("sourceType", "DATABASE");
        ArrayNode tables = context.putArray("sourceTables");
        tables.add("pit_market_fact_batches");
        tables.add("pit_market_fact_observations");
        tables.add("raw_daily_bar_facts_v2");
        tables.add("adjustment_factor_facts_v1");
        tables.add("trading_calendar_facts_v1");
        tables.add("corporate_action_facts_v1");
        context.put("sourceStatus", "TEST_DEMO_PIT_MARKET_FACTS_V2");
        context.put("pointInTimeGuaranteed", false);
        context.put("readSelectionFutureExcluded", false);
        context.put("producerInputCutoffGuaranteed", false);
        context.put("futureDataExcluded", false);
        return context;
    }

    private static ObjectNode unavailable(
            ObjectNode context,
            String code,
            String reason
    ) {
        context.put("available", false);
        context.put("reasonCode", code);
        context.put("reason", reason);
        return context;
    }

    private Bar bar(QfqBar value) {
        return new Bar(
                value.symbol(), value.tradeDate(), value.open(), value.high(),
                value.low(), value.close(),
                value.volume().value().longValueExact(),
                value.amount().value(), value.turnoverRate().value());
    }

    private ObjectNode qfqContract() {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("engineVersion", PitMarketFactsContracts.QFQ_ENGINE_VERSION);
        value.put("factorType", PitMarketFactsContracts.FACTOR_TYPE);
        value.put("factorCoverageMode",
                PitMarketFactsContracts.FACTOR_COVERAGE_MODE);
        value.put("formula",
                "rawPrice(t)*factor(t)/factor(anchorTradeDate)");
        value.put("divisionScale", 16);
        value.put("divisionRoundingMode", "HALF_UP");
        value.put("outputPriceScale", 4);
        value.put("outputRoundingMode", "HALF_UP");
        value.put("forwardFillAllowed", false);
        value.put("crossProviderAllowed", false);
        return value;
    }

    private ObjectNode dataVersion(QfqAsOfResult value) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("pitModelVersion",
                PitMarketFactsContracts.MARKET_FACTS_VERSION);
        result.put("sourceCode", value.sourceCode());
        result.set("sourceIdentities",
                objectMapper.valueToTree(value.sourceIdentities()));
        String qualification = value.batchLineage().stream().allMatch(
                batch -> batch.assuranceLevel()
                        == com.stockquant.server.agent.marketfacts
                        .MarketFactProviderModels.AssuranceLevel
                        .PROVIDER_PIT_VERIFIED)
                ? "PROVIDER_PIT_VERIFIED" : "SYSTEM_KNOWLEDGE_PIT";
        result.put("qualification", qualification);
        boolean testDemoOnly = value.batchLineage().stream().allMatch(
                batch -> batch.runNamespace()
                        != com.stockquant.server.agent.marketfacts
                        .MarketFactProviderModels.RunNamespace.FORMAL
                        && batch.usageQualification()
                        == com.stockquant.server.agent.marketfacts
                        .MarketFactProviderModels.UsageQualification
                        .TEST_DEMO_ONLY);
        result.put("testDemoOnly", testDemoOnly);
        ArrayNode batches = result.putArray("batchLineage");
        value.batchLineage().stream()
                .sorted(Comparator.comparing(
                        PitMarketFactModels.BatchLineage::batchVersion))
                .forEach(batch -> {
                    ObjectNode node = batches.addObject();
                    node.put("batchVersion", batch.batchVersion());
                    node.put("datasetVersion", batch.datasetVersion());
                    if (batch.providerDatasetVersion() == null) {
                        node.putNull("providerDatasetVersion");
                    } else {
                        node.put("providerDatasetVersion",
                                batch.providerDatasetVersion());
                    }
                    node.put("runNamespace", batch.runNamespace().name());
                    node.put("sourceCode", batch.sourceCode());
                    node.put("requestSourceIdentity",
                            batch.sourceInstrumentId());
                    node.put("revisionQualification",
                            batch.revisionQualification().name());
                    node.put("assuranceLevel",
                            batch.assuranceLevel().name());
                    node.put("usageQualification",
                            batch.usageQualification().name());
                    node.put("observedAt",
                            BacktestCanonicalHashService.formatInstant(
                                    batch.observedAt()));
                    node.put("responseComplete",
                            batch.responseComplete());
                });
        ArrayNode raw = result.putArray("rawObservationVersions");
        ArrayNode factor = result.putArray("factorObservationVersions");
        value.bars().forEach(bar -> {
            raw.add(bar.rawObservationVersion());
            factor.add(bar.factorObservationVersion());
        });
        ArrayNode rawLineage = result.putArray("rawLineage");
        value.rawLineage().stream()
                .sorted(Comparator.comparing(
                        PitMarketFactModels.RawDailyBarObservation::tradeDate))
                .forEach(item ->
                        appendObservationLineage(
                                rawLineage, item.envelope()));
        ArrayNode factorLineage = result.putArray("factorLineage");
        value.factorLineage().stream()
                .sorted(Comparator.comparing(
                        PitMarketFactModels.AdjustmentFactorObservation
                                ::factorEffectiveTradeDate))
                .forEach(item ->
                        appendObservationLineage(
                                factorLineage, item.envelope()));
        List<PitMarketFactModels.TradingCalendarObservation> calendarFacts =
                value.calendarLineage().stream()
                        .sorted(Comparator.comparing(item ->
                                item.envelope().naturalKey()))
                        .toList();
        ArrayNode calendars = result.putArray("calendarObservationVersions");
        calendarFacts.forEach(item ->
                calendars.add(item.envelope().observationVersion()));
        ArrayNode calendarLineage = result.putArray("calendarLineage");
        calendarFacts.forEach(item ->
                appendObservationLineage(calendarLineage, item.envelope()));
        List<PitMarketFactModels.CorporateActionObservation> actionFacts =
                value.corporateActionLineage().stream()
                        .sorted(Comparator.comparing(item ->
                                item.envelope().naturalKey()))
                        .toList();
        ArrayNode actions = result.putArray("corporateActionObservationVersions");
        actionFacts.forEach(item ->
                actions.add(item.envelope().observationVersion()));
        ArrayNode actionLineage = result.putArray("corporateActionLineage");
        actionFacts.forEach(item ->
                appendObservationLineage(actionLineage, item.envelope()));
        return result;
    }

    private static void appendObservationLineage(
            ArrayNode values,
            PitMarketFactModels.FactEnvelope envelope
    ) {
        ObjectNode node = values.addObject();
        node.put("observationVersion", envelope.observationVersion());
        node.put("canonicalContentHash",
                envelope.canonicalContentHash());
        node.put("naturalKey", envelope.naturalKey());
        node.put("sourceIdentity", envelope.sourceInstrumentId());
        node.put("knownAt",
                BacktestCanonicalHashService.formatInstant(
                        envelope.knownAt()));
        node.put("revisionQualification",
                envelope.revisionQualification().name());
        node.put("assuranceLevel", envelope.assuranceLevel().name());
        node.put("usageQualification",
                envelope.usageQualification().name());
        node.put("formalEligible", envelope.formalEligible());
        node.put("localPersistenceAllowed",
                envelope.localPersistenceAllowed());
        node.put("historicalReplayAllowed",
                envelope.historicalReplayAllowed());
        node.put("backtestAllowed", envelope.backtestAllowed());
        node.put("agentUseAllowed", envelope.agentUseAllowed());
    }

    private ArrayNode contextBars(List<QfqBar> bars) {
        ArrayNode result = objectMapper.createArrayNode();
        bars.forEach(value -> {
            ObjectNode node = result.addObject();
            node.put("symbol", value.symbol());
            node.put("tradeDate", value.tradeDate().toString());
            decimal(node, "open", value.open());
            decimal(node, "high", value.high());
            decimal(node, "low", value.low());
            decimal(node, "close", value.close());
            qualifiedField(node, "volume", value.volume());
            qualifiedField(node, "amount", value.amount());
            qualifiedField(node, "turnoverRate", value.turnoverRate());
            node.put("rawObservationVersion", value.rawObservationVersion());
            node.put("rawContentHash", value.rawContentHash());
            node.put("factorObservationVersion",
                    value.factorObservationVersion());
            node.put("factorContentHash", value.factorContentHash());
        });
        return result;
    }

    private ObjectNode strategy(BacktestModels.Request parameters) {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("canonicalContractVersion",
                PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION);
        strategy.put("strategyCode", BacktestContracts.STRATEGY_CODE);
        strategy.put("strategyVersion", BacktestContracts.STRATEGY_VERSION);
        strategy.put("engineVersion", BacktestContracts.ENGINE_VERSION);
        strategy.put("parameterSchemaVersion",
                BacktestContracts.PARAMETER_SCHEMA_VERSION);
        ObjectNode values = strategy.putObject("parameters");
        decimal(values, "initialCapital", parameters.initialCapital());
        values.put("maxHoldingDays", parameters.maxHoldingDays());
        decimal(values, "stopLossPct", parameters.stopLossPct());
        decimal(values, "takeProfitPct", parameters.takeProfitPct());
        decimal(values, "trailingStopPct", parameters.trailingStopPct());
        decimal(values, "commissionRate", parameters.commissionRate());
        decimal(values, "stampDutyRate", parameters.stampDutyRate());
        return strategy;
    }

    private ObjectNode result(BacktestModels.Result result) {
        ObjectNode value = objectMapper.createObjectNode();
        decimal(value, "initialCapital", result.initialCapital());
        decimal(value, "finalCapital", result.finalCapital());
        decimal(value, "totalReturn", result.totalReturn());
        decimal(value, "maxDrawdown", result.maxDrawdown());
        decimal(value, "winRate", result.winRate());
        decimal(value, "profitLossRatio", result.profitLossRatio());
        value.put("tradeCount", result.tradeCount());
        ArrayNode trades = value.putArray("trades");
        for (int index = 0; index < result.trades().size(); index++) {
            BacktestModels.Trade trade = result.trades().get(index);
            ObjectNode node = trades.addObject();
            node.put("sequence", index + 1);
            node.put("entryDate", trade.entryDate().toString());
            node.put("exitDate", trade.exitDate().toString());
            decimal(node, "entryPrice", trade.entryPrice());
            decimal(node, "exitPrice", trade.exitPrice());
            node.put("quantity", trade.quantity());
            decimal(node, "pnl", trade.pnl());
            decimal(node, "returnPct", trade.returnPct());
            node.put("exitReason", trade.exitReason());
        }
        return value;
    }

    private List<Subperiod> subperiods(
            List<Bar> bars,
            BacktestModels.Request parameters
    ) {
        int base = bars.size() / 3;
        int remainder = bars.size() % 3;
        List<String> names = List.of("EARLY", "MIDDLE", "LATE");
        List<Subperiod> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < 3; index++) {
            int length = base + (index < remainder ? 1 : 0);
            List<Bar> window = List.copyOf(
                    bars.subList(start, start + length));
            result.add(new Subperiod(
                    names.get(index), window.get(0).tradeDate(),
                    window.get(window.size() - 1).tradeDate(),
                    window.size(), backtestEngine.run(window, parameters)));
            start += length;
        }
        return List.copyOf(result);
    }

    private ArrayNode periodNodes(List<Subperiod> periods) {
        ArrayNode values = objectMapper.createArrayNode();
        periods.forEach(period -> {
            ObjectNode node = values.addObject();
            node.put("name", period.name());
            node.put("inputStartDate", period.start().toString());
            node.put("inputEndDate", period.end().toString());
            node.put("barCount", period.barCount());
            node.set("result", result(period.result()));
        });
        return values;
    }

    private ObjectNode stability(int positive) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("splitAlgorithm", BacktestContracts.SPLIT_ALGORITHM);
        value.put("validSubperiodCount", 3);
        value.put("positiveSubperiodCount", positive);
        return value;
    }

    private ObjectNode replay(
            BacktestModels.Result result,
            List<Subperiod> periods
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.set("result", result(result));
        value.set("subperiods", periodNodes(periods));
        value.set("stability", stability((int) periods.stream()
                .filter(period -> period.result().totalReturn().signum() > 0)
                .count()));
        return value;
    }

    private ObjectNode inputPayload(
            ObjectNode context,
            QfqAsOfResult qfq,
            ObjectNode qfqContract,
            ObjectNode dataVersion,
            ArrayNode bars
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("canonicalContractVersion",
                PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION);
        value.put("contextProfile", PitMarketFactsContracts.CONTEXT_PROFILE);
        value.put("contextSchemaVersion",
                PitMarketFactsContracts.BACKTEST_CONTEXT_VERSION);
        value.put("symbol", qfq.symbol());
        value.put("requestTradeDate", qfq.requestTradeDate().toString());
        value.put("requestEffectiveTradeDate",
                qfq.requestEffectiveTradeDate().toString());
        value.put("anchorTradeDate", qfq.anchorTradeDate().toString());
        value.put("decisionTime", context.get("decisionTime").asText());
        value.put("knowledgeCutoff", context.get("knowledgeCutoff").asText());
        value.set("qfqContract", qfqContract.deepCopy());
        value.set("dataVersion", dataVersion.deepCopy());
        value.set("bars", bars.deepCopy());
        return value;
    }

    private static String exchange(String symbol) {
        return symbol.startsWith("6") ? "SSE" : "SZSE";
    }

    private static void decimal(
            ObjectNode node,
            String field,
            BigDecimal value
    ) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private static void qualifiedField(
            ObjectNode node,
            String field,
            MarketFactProviderModels.QualifiedMarketField value
    ) {
        decimal(node, field, value.value());
        node.put(field + "Qualification", value.qualification().name());
        node.put(field + "UnitCode", value.unitCode().name());
        node.put(field + "SemanticCode", value.semanticCode().name());
    }

    private record Subperiod(
            String name,
            LocalDate start,
            LocalDate end,
            int barCount,
            BacktestModels.Result result
    ) {
    }
}
