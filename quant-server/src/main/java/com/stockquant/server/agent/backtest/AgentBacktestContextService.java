package com.stockquant.server.agent.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.BacktestModels;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.agent.backtest.MarketDataObservationRepository.ObservedDailyBar;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Service
public class AgentBacktestContextService {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final ObjectMapper objectMapper;
    private final MarketDataObservationRepository repository;
    private final BacktestCanonicalHashService hashService;
    private final BacktestEngine backtestEngine;

    public AgentBacktestContextService(
            ObjectMapper objectMapper,
            MarketDataObservationRepository repository,
            BacktestCanonicalHashService hashService,
            BacktestEngine backtestEngine
    ) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.hashService = hashService;
        this.backtestEngine = backtestEngine;
    }

    public ObjectNode create(String symbol, LocalDate requestTradeDate, Instant queriedAt) {
        Instant stableQueriedAt = BacktestCanonicalHashService.microsecondInstant(queriedAt);
        Instant decisionTime = decisionTime(requestTradeDate);
        ObjectNode context = baseContext(
                symbol,
                requestTradeDate,
                stableQueriedAt,
                decisionTime);
        LocalDate currentMarketDate = stableQueriedAt
                .atZone(BacktestContracts.MARKET_ZONE)
                .toLocalDate();
        if (requestTradeDate.isAfter(currentMarketDate)) {
            return unavailable(
                    context,
                    BacktestContracts.FUTURE_REQUEST_DATE,
                    "请求日期晚于当前上海市场日期");
        }
        if (stableQueriedAt.isBefore(decisionTime)) {
            return unavailable(
                    context,
                    BacktestContracts.DECISION_TIME_NOT_REACHED,
                    "请求日期的上海市场日终决策时点尚未到达");
        }

        List<ObservedDailyBar> observations = repository.findAsOf(
                symbol,
                requestTradeDate,
                decisionTime,
                BacktestContracts.MAXIMUM_BARS);
        if (observations.isEmpty()) {
            if (repository.countOnOrBefore(symbol, requestTradeDate) > 0) {
                return unavailable(
                        context,
                        BacktestContracts.KNOWLEDGE_TIME_UNVERIFIABLE,
                        "请求日期以前存在日线，但没有在knowledgeCutoff前可验证的观察版本");
            }
            return unavailable(
                    context,
                    BacktestContracts.NO_TRUSTED_PIT_DAILY_BARS,
                    "请求日期以前没有可信PIT QFQ日线观察");
        }
        if (!validObservations(observations, symbol, requestTradeDate, decisionTime)) {
            return unavailable(
                    context,
                    BacktestContracts.DAILY_BAR_INVALID,
                    "PIT日线存在非法字段、顺序、日期或lineage");
        }
        if (!hashesMatch(observations)) {
            return unavailable(
                    context,
                    BacktestContracts.HASH_MISMATCH,
                    "PIT日线canonical内容或观察版本Hash校验失败");
        }
        if (observations.stream().anyMatch(
                value -> value.sourceRevision() == null)) {
            return unavailable(
                    context,
                    BacktestContracts.SOURCE_REVISION_UNVERIFIABLE,
                    "来源没有提供可验证的revision标识，不能声明可靠回测输入");
        }
        if (observations.size() < BacktestContracts.MINIMUM_CONTEXT_BARS) {
            context.put("actualBars", observations.size());
            context.put("requiredBars", BacktestContracts.MINIMUM_CONTEXT_BARS);
            return unavailable(
                    context,
                    BacktestContracts.SAMPLE_INSUFFICIENT,
                    "可靠PIT日线不足120条");
        }

        List<Bar> bars = observations.stream().map(ObservedDailyBar::toBar).toList();
        BacktestModels.Request parameters = BacktestContracts.parameters();
        if (!validFrozenParameters(parameters)) {
            return unavailable(
                    context,
                    BacktestContracts.PARAMS_INVALID,
                    "冻结回测参数不完整或非法");
        }

        BacktestModels.Result fullResult;
        List<Subperiod> subperiods;
        try {
            fullResult = backtestEngine.run(bars, parameters);
            subperiods = runSubperiods(bars, parameters);
        } catch (IllegalArgumentException error) {
            return unavailable(
                    context,
                    BacktestContracts.DAILY_BAR_INVALID,
                    "冻结回测引擎拒绝PIT输入");
        }
        ObjectNode firstReplay = replayPayload(fullResult, subperiods);
        BacktestModels.Result replayResult = backtestEngine.run(bars, parameters);
        List<Subperiod> replaySubperiods = runSubperiods(bars, parameters);
        ObjectNode secondReplay = replayPayload(replayResult, replaySubperiods);
        if (!hashService.canonicalText(firstReplay)
                .equals(hashService.canonicalText(secondReplay))) {
            return unavailable(
                    context,
                    BacktestContracts.REPLAY_MISMATCH,
                    "相同冻结输入的回测重放结果不一致");
        }

        ObjectNode dataVersion = dataVersion(observations);
        ObjectNode lineage = lineage(observations);
        ArrayNode contextBars = contextBars(observations);
        ObjectNode strategy = strategy(parameters);
        ObjectNode result = resultNode(fullResult);
        ArrayNode subperiodNodes = subperiodNodes(subperiods);
        int positiveSubperiodCount = (int) subperiods.stream()
                .filter(value -> value.result().totalReturn().signum() > 0)
                .count();
        ObjectNode stability = stability(positiveSubperiodCount);

        ObjectNode inputHashPayload = inputHashPayload(
                symbol,
                requestTradeDate,
                observations,
                decisionTime,
                dataVersion);
        String inputDataHash = hashService.hash(inputHashPayload);
        String strategyDefinitionHash = hashService.hash(strategy.deepCopy());
        ObjectNode resultHashPayload = resultHashPayload(
                inputDataHash,
                strategyDefinitionHash,
                result,
                subperiodNodes,
                stability);
        String backtestResultHash = hashService.hash(resultHashPayload);
        if (!SHA_256.matcher(inputDataHash).matches()
                || !SHA_256.matcher(strategyDefinitionHash).matches()
                || !SHA_256.matcher(backtestResultHash).matches()) {
            return unavailable(
                    context,
                    BacktestContracts.HASH_MISMATCH,
                    "回测领域Hash格式无效");
        }

        context.put("available", true);
        context.put("effectiveTradeDate",
                observations.get(observations.size() - 1).tradeDate().toString());
        context.put("exactTradeDateMatch",
                requestTradeDate.equals(observations.get(observations.size() - 1).tradeDate()));
        context.put("inputStartDate", observations.get(0).tradeDate().toString());
        context.put("inputEndDate",
                observations.get(observations.size() - 1).tradeDate().toString());
        context.put("barCount", observations.size());
        context.put("requiredBars", BacktestContracts.MINIMUM_CONTEXT_BARS);
        context.put("maximumBars", BacktestContracts.MAXIMUM_BARS);
        context.set("dataVersion", dataVersion);
        context.set("lineage", lineage);
        context.set("bars", contextBars);
        context.set("strategy", strategy);
        context.set("result", result);
        context.set("subperiods", subperiodNodes);
        context.set("stability", stability);
        context.put("inputDataHash", inputDataHash);
        context.put("strategyDefinitionHash", strategyDefinitionHash);
        context.put("backtestResultHash", backtestResultHash);
        context.put("pointInTimeGuaranteed", true);
        context.put("readSelectionFutureExcluded", true);
        context.put("producerInputCutoffGuaranteed", true);
        context.put("futureDataExcluded", true);
        ArrayNode limitations = context.putArray("limitations");
        limitations.add("RESEARCH_AND_SIMULATION_ONLY");
        limitations.add("LOCAL_OBSERVATION_TIME_IS_NOT_PROVIDER_PUBLICATION_TIME");
        limitations.add("CONTENT_HASH_DOES_NOT_REPLACE_KNOWLEDGE_TIME");
        return context;
    }

    private ObjectNode baseContext(
            String symbol,
            LocalDate requestTradeDate,
            Instant queriedAt,
            Instant decisionTime
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("available", false);
        context.put("queriedAt", BacktestCanonicalHashService.formatInstant(queriedAt));
        ObjectNode queryScope = context.putObject("queryScope");
        queryScope.put("symbol", symbol);
        queryScope.put("tradeDate", requestTradeDate.toString());
        context.put("producer", BacktestContracts.PRODUCER);
        context.put("producerVersion", BacktestContracts.PRODUCER_VERSION);
        context.put("contextProfile", BacktestContracts.CONTEXT_PROFILE);
        context.put("schemaVersion", BacktestContracts.CONTEXT_SCHEMA_VERSION);
        context.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        context.put("pitModelVersion", BacktestContracts.PIT_MODEL_VERSION);
        context.put("symbol", symbol);
        context.put("requestTradeDate", requestTradeDate.toString());
        context.put("decisionTime", BacktestCanonicalHashService.formatInstant(decisionTime));
        context.put("knowledgeCutoff", BacktestCanonicalHashService.formatInstant(decisionTime));
        context.put("marketTimezone", BacktestContracts.MARKET_ZONE.getId());
        context.put("adjustType", BacktestContracts.ADJUST_TYPE);
        context.put("sourceType", "DATABASE");
        ArrayNode sourceTables = context.putArray("sourceTables");
        sourceTables.add("market_data_observation_batches");
        sourceTables.add("daily_bar_observations");
        context.put("sourceStatus", "LOCAL_PIT_OBSERVATIONS");
        context.put("pointInTimeGuaranteed", false);
        context.put("readSelectionFutureExcluded", false);
        context.put("producerInputCutoffGuaranteed", false);
        context.put("futureDataExcluded", false);
        return context;
    }

    private static ObjectNode unavailable(
            ObjectNode context,
            String reasonCode,
            String reason
    ) {
        context.put("available", false);
        context.put("reasonCode", reasonCode);
        context.put("reason", reason);
        return context;
    }

    private boolean hashesMatch(List<ObservedDailyBar> observations) {
        for (ObservedDailyBar value : observations) {
            String contentHash = hashService.hash(contentPayload(value));
            if (!contentHash.equals(value.canonicalContentHash())) return false;
            String observationVersion = hashService.hash(observationVersionPayload(value));
            if (!observationVersion.equals(value.observationVersion())) return false;
        }
        return true;
    }

    private ObjectNode contentPayload(ObservedDailyBar value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        node.put("sourceCode", value.sourceCode());
        node.put("symbol", value.symbol());
        node.put("tradeDate", value.tradeDate().toString());
        node.put("adjustType", value.adjustType());
        putDecimal(node, "open", value.open());
        putDecimal(node, "high", value.high());
        putDecimal(node, "low", value.low());
        putDecimal(node, "close", value.close());
        node.put("volume", value.volume());
        putDecimal(node, "amount", value.amount());
        putDecimal(node, "turnoverRate", value.turnoverRate());
        return node;
    }

    private ObjectNode observationVersionPayload(ObservedDailyBar value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        node.put("batchVersion", value.batchVersion());
        node.put("datasetVersion", value.datasetVersion());
        node.put("sourceCode", value.sourceCode());
        putNullable(node, "sourceRevision", value.sourceRevision());
        node.put("firstObservedAt",
                BacktestCanonicalHashService.formatInstant(value.firstObservedAt()));
        node.put("knownAt", BacktestCanonicalHashService.formatInstant(value.knownAt()));
        node.put("canonicalContentHash", value.canonicalContentHash());
        return node;
    }

    private ObjectNode dataVersion(List<ObservedDailyBar> observations) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("pitModelVersion", BacktestContracts.PIT_MODEL_VERSION);
        addSortedStrings(
                value.putArray("datasetVersions"),
                observations.stream().map(ObservedDailyBar::datasetVersion).toList());
        addSortedStrings(
                value.putArray("batchVersions"),
                observations.stream().map(ObservedDailyBar::batchVersion).toList());
        ArrayNode selected = value.putArray("selectedObservationVersions");
        observations.forEach(item -> selected.add(item.observationVersion()));
        ArrayNode revisions = value.putArray("sourceRevisions");
        observations.stream()
                .map(item -> new SourceRevision(item.sourceCode(), item.sourceRevision()))
                .distinct()
                .sorted(Comparator.comparing(SourceRevision::sourceCode)
                        .thenComparing(
                                SourceRevision::sourceRevision,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(revision -> {
                    ObjectNode node = revisions.addObject();
                    node.put("sourceCode", revision.sourceCode());
                    putNullable(node, "sourceRevision", revision.sourceRevision());
                });
        return value;
    }

    private ObjectNode lineage(List<ObservedDailyBar> observations) {
        ObjectNode value = objectMapper.createObjectNode();
        addSortedStrings(
                value.putArray("sources"),
                observations.stream().map(ObservedDailyBar::sourceCode).toList());
        Map<String, ObservedDailyBar> batches = new LinkedHashMap<>();
        observations.stream()
                .sorted(Comparator.comparing(ObservedDailyBar::batchVersion))
                .forEach(item -> batches.putIfAbsent(item.batchVersion(), item));
        ArrayNode batchNodes = value.putArray("batches");
        batches.values().forEach(item -> {
            ObjectNode node = batchNodes.addObject();
            node.put("batchVersion", item.batchVersion());
            node.put("datasetVersion", item.datasetVersion());
            node.put("sourceCode", item.sourceCode());
            node.put("captureType", item.captureType());
            node.put("observedAt",
                    BacktestCanonicalHashService.formatInstant(item.batchObservedAt()));
            node.put("recordedAt",
                    BacktestCanonicalHashService.formatInstant(item.batchRecordedAt()));
            try {
                node.set("sourceMetadata", objectMapper.readTree(item.sourceMetadataJson()));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("观察批次sourceMetadata不是合法JSON", error);
            }
        });
        ArrayNode observationNodes = value.putArray("observations");
        observations.forEach(item -> observationNodes.add(observationLineageNode(item, true)));
        Instant maximumKnownAt = observations.stream()
                .map(ObservedDailyBar::knownAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        value.put("maximumKnownAt",
                BacktestCanonicalHashService.formatInstant(maximumKnownAt));
        return value;
    }

    private ArrayNode contextBars(List<ObservedDailyBar> observations) {
        ArrayNode bars = objectMapper.createArrayNode();
        observations.forEach(item -> {
            ObjectNode node = bars.addObject();
            node.put("symbol", item.symbol());
            node.put("tradeDate", item.tradeDate().toString());
            putDecimal(node, "open", item.open());
            putDecimal(node, "high", item.high());
            putDecimal(node, "low", item.low());
            putDecimal(node, "close", item.close());
            node.put("volume", item.volume());
            putDecimal(node, "amount", item.amount());
            putDecimal(node, "turnoverRate", item.turnoverRate());
            node.put("sourceCode", item.sourceCode());
            putNullable(node, "sourceRevision", item.sourceRevision());
            node.put("batchVersion", item.batchVersion());
            node.put("datasetVersion", item.datasetVersion());
            node.put("observationVersion", item.observationVersion());
            node.put("firstObservedAt",
                    BacktestCanonicalHashService.formatInstant(item.firstObservedAt()));
            node.put("knownAt",
                    BacktestCanonicalHashService.formatInstant(item.knownAt()));
            node.put("canonicalContentHash", item.canonicalContentHash());
        });
        return bars;
    }

    private ObjectNode strategy(BacktestModels.Request parameters) {
        ObjectNode strategy = objectMapper.createObjectNode();
        strategy.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        strategy.put("strategyCode", BacktestContracts.STRATEGY_CODE);
        strategy.put("strategyVersion", BacktestContracts.STRATEGY_VERSION);
        strategy.put("engineVersion", BacktestContracts.ENGINE_VERSION);
        strategy.put("parameterSchemaVersion", BacktestContracts.PARAMETER_SCHEMA_VERSION);
        ObjectNode values = strategy.putObject("parameters");
        putDecimal(values, "initialCapital", parameters.initialCapital());
        values.put("maxHoldingDays", parameters.maxHoldingDays());
        putDecimal(values, "stopLossPct", parameters.stopLossPct());
        putDecimal(values, "takeProfitPct", parameters.takeProfitPct());
        putDecimal(values, "trailingStopPct", parameters.trailingStopPct());
        putDecimal(values, "commissionRate", parameters.commissionRate());
        putDecimal(values, "stampDutyRate", parameters.stampDutyRate());
        return strategy;
    }

    private ObjectNode resultNode(BacktestModels.Result result) {
        ObjectNode value = objectMapper.createObjectNode();
        putDecimal(value, "initialCapital", result.initialCapital());
        putDecimal(value, "finalCapital", result.finalCapital());
        putDecimal(value, "totalReturn", result.totalReturn());
        putDecimal(value, "maxDrawdown", result.maxDrawdown());
        putDecimal(value, "winRate", result.winRate());
        putDecimal(value, "profitLossRatio", result.profitLossRatio());
        value.put("tradeCount", result.tradeCount());
        ArrayNode trades = value.putArray("trades");
        for (int index = 0; index < result.trades().size(); index++) {
            BacktestModels.Trade trade = result.trades().get(index);
            ObjectNode node = trades.addObject();
            node.put("sequence", index + 1);
            node.put("entryDate", trade.entryDate().toString());
            node.put("exitDate", trade.exitDate().toString());
            putDecimal(node, "entryPrice", trade.entryPrice());
            putDecimal(node, "exitPrice", trade.exitPrice());
            node.put("quantity", trade.quantity());
            putDecimal(node, "pnl", trade.pnl());
            putDecimal(node, "returnPct", trade.returnPct());
            node.put("exitReason", trade.exitReason());
        }
        return value;
    }

    private List<Subperiod> runSubperiods(
            List<Bar> bars,
            BacktestModels.Request parameters
    ) {
        int baseSize = bars.size() / 3;
        int remainder = bars.size() % 3;
        List<String> names = List.of("EARLY", "MIDDLE", "LATE");
        List<Subperiod> result = new ArrayList<>(3);
        int start = 0;
        for (int index = 0; index < 3; index++) {
            int length = baseSize + (index < remainder ? 1 : 0);
            int end = start + length;
            List<Bar> window = List.copyOf(bars.subList(start, end));
            result.add(new Subperiod(
                    names.get(index),
                    window.get(0).tradeDate(),
                    window.get(window.size() - 1).tradeDate(),
                    window.size(),
                    backtestEngine.run(window, parameters)));
            start = end;
        }
        return List.copyOf(result);
    }

    private ArrayNode subperiodNodes(List<Subperiod> subperiods) {
        ArrayNode values = objectMapper.createArrayNode();
        subperiods.forEach(period -> {
            ObjectNode node = values.addObject();
            node.put("name", period.name());
            node.put("inputStartDate", period.inputStartDate().toString());
            node.put("inputEndDate", period.inputEndDate().toString());
            node.put("barCount", period.barCount());
            node.set("result", resultNode(period.result()));
        });
        return values;
    }

    private ObjectNode stability(int positiveSubperiodCount) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("splitAlgorithm", BacktestContracts.SPLIT_ALGORITHM);
        value.put("validSubperiodCount", 3);
        value.put("positiveSubperiodCount", positiveSubperiodCount);
        return value;
    }

    private ObjectNode replayPayload(
            BacktestModels.Result result,
            List<Subperiod> subperiods
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.set("result", resultNode(result));
        value.set("subperiods", subperiodNodes(subperiods));
        int positive = (int) subperiods.stream()
                .filter(period -> period.result().totalReturn().signum() > 0)
                .count();
        value.set("stability", stability(positive));
        return value;
    }

    private ObjectNode inputHashPayload(
            String symbol,
            LocalDate requestTradeDate,
            List<ObservedDailyBar> observations,
            Instant decisionTime,
            ObjectNode dataVersion
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        value.put("contextProfile", BacktestContracts.CONTEXT_PROFILE);
        value.put("contextSchemaVersion", BacktestContracts.CONTEXT_SCHEMA_VERSION);
        value.put("symbol", symbol);
        value.put("requestTradeDate", requestTradeDate.toString());
        value.put("effectiveTradeDate",
                observations.get(observations.size() - 1).tradeDate().toString());
        value.put("decisionTime", BacktestCanonicalHashService.formatInstant(decisionTime));
        value.put("knowledgeCutoff", BacktestCanonicalHashService.formatInstant(decisionTime));
        value.put("marketTimezone", BacktestContracts.MARKET_ZONE.getId());
        value.put("adjustType", BacktestContracts.ADJUST_TYPE);
        value.put("inputStartDate", observations.get(0).tradeDate().toString());
        value.put("inputEndDate",
                observations.get(observations.size() - 1).tradeDate().toString());
        value.put("barCount", observations.size());
        value.set("dataVersion", dataVersion.deepCopy());
        ArrayNode lineage = value.putArray("observationLineage");
        observations.forEach(item -> lineage.add(observationLineageNode(item, false)));
        value.set("bars", contextBars(observations));
        return value;
    }

    private ObjectNode observationLineageNode(
            ObservedDailyBar item,
            boolean includeRecordedAt
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("observationVersion", item.observationVersion());
        node.put("tradeDate", item.tradeDate().toString());
        node.put("sourceCode", item.sourceCode());
        putNullable(node, "sourceRevision", item.sourceRevision());
        node.put("batchVersion", item.batchVersion());
        node.put("datasetVersion", item.datasetVersion());
        node.put("firstObservedAt",
                BacktestCanonicalHashService.formatInstant(item.firstObservedAt()));
        node.put("knownAt", BacktestCanonicalHashService.formatInstant(item.knownAt()));
        if (includeRecordedAt) {
            node.put("recordedAt",
                    BacktestCanonicalHashService.formatInstant(item.recordedAt()));
        }
        node.put("canonicalContentHash", item.canonicalContentHash());
        return node;
    }

    private ObjectNode resultHashPayload(
            String inputDataHash,
            String strategyDefinitionHash,
            ObjectNode result,
            ArrayNode subperiods,
            ObjectNode stability
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        value.put("inputDataHash", inputDataHash);
        value.put("strategyDefinitionHash", strategyDefinitionHash);
        value.set("result", result.deepCopy());
        value.set("subperiods", subperiods.deepCopy());
        value.set("stability", stability.deepCopy());
        return value;
    }

    private static boolean validObservations(
            List<ObservedDailyBar> values,
            String symbol,
            LocalDate requestTradeDate,
            Instant knowledgeCutoff
    ) {
        LocalDate previous = null;
        for (ObservedDailyBar value : values) {
            if (value == null
                    || !Objects.equals(symbol, value.symbol())
                    || !BacktestContracts.ADJUST_TYPE.equals(value.adjustType())
                    || value.tradeDate() == null
                    || value.tradeDate().isAfter(requestTradeDate)
                    || previous != null && !value.tradeDate().isAfter(previous)
                    || value.knownAt() == null
                    || value.knownAt().isAfter(knowledgeCutoff)
                    || value.firstObservedAt() == null
                    || value.firstObservedAt().isAfter(value.knownAt())
                    || value.recordedAt() == null
                    || value.knownAt().isAfter(value.recordedAt())
                    || blank(value.sourceCode())
                    || blank(value.datasetVersion())
                    || blank(value.batchVersion())
                    || !sha(value.observationVersion())
                    || !sha(value.canonicalContentHash())
                    || !validBar(value)) {
                return false;
            }
            previous = value.tradeDate();
        }
        return true;
    }

    private static boolean validBar(ObservedDailyBar value) {
        return value.open() != null && value.high() != null
                && value.low() != null && value.close() != null
                && value.open().signum() > 0 && value.high().signum() > 0
                && value.low().signum() > 0 && value.close().signum() > 0
                && value.volume() >= 0
                && (value.amount() == null || value.amount().signum() >= 0)
                && (value.turnoverRate() == null || value.turnoverRate().signum() >= 0)
                && value.high().compareTo(value.open()) >= 0
                && value.high().compareTo(value.close()) >= 0
                && value.high().compareTo(value.low()) >= 0
                && value.low().compareTo(value.open()) <= 0
                && value.low().compareTo(value.close()) <= 0;
    }

    private static boolean validFrozenParameters(BacktestModels.Request value) {
        BacktestModels.Request expected = BacktestContracts.parameters();
        return value != null
                && decimalEquals(value.initialCapital(), expected.initialCapital())
                && value.maxHoldingDays() == expected.maxHoldingDays()
                && decimalEquals(value.stopLossPct(), expected.stopLossPct())
                && decimalEquals(value.takeProfitPct(), expected.takeProfitPct())
                && decimalEquals(value.trailingStopPct(), expected.trailingStopPct())
                && decimalEquals(value.commissionRate(), expected.commissionRate())
                && decimalEquals(value.stampDutyRate(), expected.stampDutyRate());
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean sha(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Instant decisionTime(LocalDate requestTradeDate) {
        return requestTradeDate.plusDays(1)
                .atStartOfDay(BacktestContracts.MARKET_ZONE)
                .toInstant()
                .minus(1, ChronoUnit.MICROS);
    }

    private static void addSortedStrings(ArrayNode target, List<String> values) {
        new TreeSet<>(values).forEach(target::add);
    }

    private static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private record SourceRevision(String sourceCode, String sourceRevision) {
    }

    private record Subperiod(
            String name,
            LocalDate inputStartDate,
            LocalDate inputEndDate,
            int barCount,
            BacktestModels.Result result
    ) {
    }
}
