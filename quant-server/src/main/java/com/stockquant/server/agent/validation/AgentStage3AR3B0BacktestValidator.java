package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.marketfacts.PitMarketFactsContracts;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Independent Java recomputation for the V2 STRATEGY_BACKTEST response. */
final class AgentStage3AR3B0BacktestValidator {

    private static final BacktestCanonicalHashService CANONICAL =
            new BacktestCanonicalHashService(new ObjectMapper());
    private static final Set<String> BASE_FIELDS = Set.of(
            "available", "queriedAt", "queryScope", "producer",
            "producerVersion", "contextProfile", "schemaVersion",
            "canonicalContractVersion", "pitModelVersion", "symbol",
            "requestTradeDate", "decisionTime", "knowledgeCutoff",
            "marketTimezone", "adjustType", "sourceType", "sourceTables",
            "sourceStatus", "pointInTimeGuaranteed",
            "readSelectionFutureExcluded", "producerInputCutoffGuaranteed",
            "futureDataExcluded");
    private static final Set<String> AVAILABLE_FIELDS = Set.of(
            "effectiveTradeDate", "exactTradeDateMatch", "inputStartDate",
            "inputEndDate", "barCount", "requiredBars", "maximumBars",
            "qfqContract", "dataVersion", "bars", "strategy", "result",
            "subperiods", "stability", "inputDataHash",
            "strategyDefinitionHash", "backtestResultHash", "testDemoOnly",
            "limitations");
    private static final Set<String> DATA_VERSION_FIELDS = Set.of(
            "pitModelVersion", "sourceCode", "sourceInstrumentId",
            "qualification", "testDemoOnly", "batchLineage",
            "rawObservationVersions", "factorObservationVersions",
            "calendarObservationVersions", "calendarLineage",
            "corporateActionObservationVersions",
            "corporateActionLineage");
    private static final Set<String> BATCH_LINEAGE_FIELDS = Set.of(
            "batchVersion", "datasetVersion", "providerDatasetVersion",
            "runNamespace", "sourceCode", "sourceInstrumentId",
            "revisionQualification", "assuranceLevel",
            "usageQualification", "observedAt", "responseComplete");
    private static final Set<String> OBSERVATION_LINEAGE_FIELDS = Set.of(
            "observationVersion", "canonicalContentHash", "naturalKey",
            "knownAt", "revisionQualification");
    private static final List<String> FINDING_CODES = List.of(
            "STRATEGY_BACKTEST_SAMPLE_SUFFICIENT",
            "STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED",
            "STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED",
            "STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED",
            "STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED");

    private AgentStage3AR3B0BacktestValidator() {
    }

    static void validateBlocked(AgentOutput run) {
        require(run.agentCode() == AgentCode.STRATEGY_BACKTEST
                        && run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.gateStatus() == GateStatus.BLOCKED
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && !run.veto()
                        && Objects.equals(run.score(), 0)
                        && Objects.equals(run.confidence(), 0)
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().isEmpty(),
                "V2 STRATEGY_BACKTEST must safely degrade when DQ is blocked");
    }

    static void validate(
            AgentTeamRequest request,
            AgentOutput run,
            GateStatus dataQualityGate
    ) {
        JsonNode context = request.contextSnapshot().get("backtestContext");
        validateBase(request, context);
        if (!context.path("available").asBoolean()) {
            String reasonCode = text(context, "reasonCode");
            require(PitMarketFactsContracts.UNAVAILABLE_REASON_CODES
                            .contains(reasonCode),
                    "V2 unavailable reasonCode is not frozen");
            require(run.status() == RunStatus.INSUFFICIENT_DATA
                            && run.gateStatus() == dataQualityGate
                            && run.decision() == RunDecision.NOT_APPLICABLE
                            && !run.veto()
                            && Objects.equals(run.score(), 0)
                            && Objects.equals(run.confidence(), 0)
                            && run.findings().isEmpty()
                            && run.evidence().isEmpty()
                            && run.errors().size() == 1
                            && reasonCode.equals(run.errors().get(0).code()),
                    "V2 unavailable context response mismatch");
            return;
        }
        Metrics metrics = validateAvailable(request, context);
        require(run.agentCode() == AgentCode.STRATEGY_BACKTEST && !run.veto(),
                "V2 STRATEGY_BACKTEST identity or veto mismatch");
        Evidence evidence = validateEvidence(request, run, context, metrics);
        if (metrics.tradeCount() < 10 || metrics.validSubperiodCount() < 2) {
            require(run.status() == RunStatus.INSUFFICIENT_DATA
                            && run.gateStatus() == dataQualityGate
                            && run.decision() == RunDecision.NOT_APPLICABLE
                            && Objects.equals(run.score(), 0)
                            && Objects.equals(run.confidence(), 0)
                            && run.findings().isEmpty()
                            && run.evidence().equals(List.of(evidence))
                            && run.errors().size() == 1
                            && BacktestContracts.STRATEGY_SAMPLE_INSUFFICIENT
                            .equals(run.errors().get(0).code()),
                    "V2 backtest sample-insufficient response mismatch");
            return;
        }
        int score = Math.max(0, Math.min(100,
                50 + totalReturnImpact(metrics.totalReturn())
                        + drawdownImpact(metrics.maxDrawdown())
                        + winRateImpact(metrics.winRate())
                        + profitLossImpact(metrics.profitLossRatio())
                        + subperiodImpact(metrics.positiveSubperiodCount())));
        int confidence = metrics.tradeCount() >= 40 ? 80
                : metrics.tradeCount() >= 20 ? 60 : 40;
        if (dataQualityGate == GateStatus.WARN) confidence = Math.min(50, confidence);
        require(run.status() == RunStatus.COMPLETED
                        && run.gateStatus() == dataQualityGate
                        && run.decision() == RunDecision.WARN
                        && Objects.equals(run.score(), score)
                        && Objects.equals(run.confidence(), confidence)
                        && run.errors().isEmpty()
                        && run.evidence().equals(List.of(evidence))
                        && run.findings().size() == 5,
                "V2 STRATEGY_BACKTEST score/confidence/status mismatch");
        for (int index = 0; index < FINDING_CODES.size(); index++) {
            require(FINDING_CODES.get(index)
                            .equals(run.findings().get(index).code())
                            && run.findings().get(index).evidenceIds()
                            .equals(List.of(evidence.evidenceId())),
                    "V2 STRATEGY_BACKTEST finding order/evidence mismatch");
        }
    }

    private static void validateBase(AgentTeamRequest request, JsonNode value) {
        require(value != null && value.isObject(), "V2 backtestContext missing");
        boolean available = value.path("available").asBoolean(false);
        Set<String> fields = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        Set<String> expected = new java.util.HashSet<>(BASE_FIELDS);
        if (available) {
            expected.addAll(AVAILABLE_FIELDS);
        } else {
            expected.add("reasonCode");
            expected.add("reason");
            if (PitMarketFactsContracts.SAMPLE_INSUFFICIENT
                    .equals(value.path("reasonCode").asText())) {
                expected.add("actualBars");
                expected.add("requiredBars");
            }
        }
        require(fields.equals(expected), "V2 backtestContext field whitelist mismatch");
        require(PitMarketFactsContracts.PRODUCER.equals(text(value, "producer"))
                        && PitMarketFactsContracts.PRODUCER_VERSION
                        .equals(text(value, "producerVersion"))
                        && PitMarketFactsContracts.CONTEXT_PROFILE
                        .equals(text(value, "contextProfile"))
                        && PitMarketFactsContracts.BACKTEST_CONTEXT_VERSION
                        .equals(text(value, "schemaVersion"))
                        && PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION
                        .equals(text(value, "canonicalContractVersion"))
                        && PitMarketFactsContracts.MARKET_FACTS_VERSION
                        .equals(text(value, "pitModelVersion"))
                        && request.symbol().equals(text(value, "symbol"))
                        && request.tradeDate().toString()
                        .equals(text(value, "requestTradeDate"))
                        && "Asia/Shanghai".equals(text(value, "marketTimezone"))
                        && "QFQ".equals(text(value, "adjustType"))
                        && "DATABASE".equals(text(value, "sourceType"))
                        && "TEST_DEMO_PIT_MARKET_FACTS_V2"
                        .equals(text(value, "sourceStatus")),
                "V2 backtestContext identity mismatch");
        Instant queriedAt = Instant.parse(text(value, "queriedAt"));
        Instant decisionTime = Instant.parse(text(value, "decisionTime"));
        Instant cutoff = Instant.parse(text(value, "knowledgeCutoff"));
        require(decisionTime.equals(cutoff) && !queriedAt.isBefore(cutoff),
                "V2 backtestContext time ordering mismatch");
    }

    private static Metrics validateAvailable(
            AgentTeamRequest request,
            JsonNode context
    ) {
        for (String field : List.of(
                "pointInTimeGuaranteed", "readSelectionFutureExcluded",
                "producerInputCutoffGuaranteed", "futureDataExcluded",
                "testDemoOnly")) {
            require(context.path(field).isBoolean()
                            && context.path(field).asBoolean(),
                    "V2 reliable flag is false: " + field);
        }
        JsonNode qfq = context.get("qfqContract");
        require(qfq != null
                        && PitMarketFactsContracts.QFQ_ENGINE_VERSION
                        .equals(text(qfq, "engineVersion"))
                        && PitMarketFactsContracts.FACTOR_COVERAGE_MODE
                        .equals(text(qfq, "factorCoverageMode"))
                        && !qfq.path("forwardFillAllowed").asBoolean(true)
                        && !qfq.path("crossProviderAllowed").asBoolean(true),
                "V2 QFQ contract mismatch");
        JsonNode bars = context.get("bars");
        int count = context.path("barCount").asInt(-1);
        require(bars != null && bars.isArray() && bars.size() == count
                        && count >= 120 && count <= 500,
                "V2 bar window size mismatch");
        LocalDate previous = null;
        for (JsonNode bar : bars) {
            require(request.symbol().equals(text(bar, "symbol")),
                    "V2 bar symbol mismatch");
            LocalDate current = LocalDate.parse(text(bar, "tradeDate"));
            require(!current.isAfter(request.tradeDate())
                            && (previous == null || current.isAfter(previous)),
                    "V2 bar date order mismatch");
            previous = current;
            BigDecimal open = decimal(bar, "open");
            BigDecimal high = decimal(bar, "high");
            BigDecimal low = decimal(bar, "low");
            BigDecimal close = decimal(bar, "close");
            require(open.signum() > 0 && high.signum() > 0
                            && low.signum() > 0 && close.signum() > 0
                            && high.compareTo(open.max(low).max(close)) >= 0
                            && low.compareTo(open.min(high).min(close)) <= 0,
                    "V2 bar OHLC mismatch");
            sha(text(bar, "rawObservationVersion"));
            sha(text(bar, "factorObservationVersion"));
            sha(text(bar, "rawContentHash"));
            sha(text(bar, "factorContentHash"));
        }
        require(previous != null
                        && previous.toString().equals(
                        text(context, "effectiveTradeDate"))
                        && previous.toString().equals(
                        text(context, "inputEndDate")),
                "V2 anchor/effective date mismatch");
        validateDataVersion(context.get("dataVersion"), bars,
                Instant.parse(text(context, "knowledgeCutoff")));

        JsonNode strategy = context.get("strategy");
        validateStrategy(strategy);
        JsonNode result = context.get("result");
        int tradeCount = result.path("tradeCount").asInt(-1);
        require(tradeCount >= 0
                        && result.path("trades").isArray()
                        && result.path("trades").size() == tradeCount,
                "V2 backtest result tradeCount mismatch");
        JsonNode periods = context.get("subperiods");
        JsonNode stability = context.get("stability");
        require(periods != null && periods.isArray() && periods.size() == 3
                        && BacktestContracts.SPLIT_ALGORITHM.equals(
                        text(stability, "splitAlgorithm"))
                        && stability.path("validSubperiodCount").asInt(-1) == 3,
                "V2 subperiod/stability mismatch");
        int actualPositive = 0;
        for (JsonNode period : periods) {
            if (decimal(period.path("result"), "totalReturn").signum() > 0) {
                actualPositive++;
            }
        }
        int positive = stability.path("positiveSubperiodCount").asInt(-1);
        require(positive == actualPositive, "V2 positive subperiod count mismatch");
        validateHashes(request, context);
        return new Metrics(
                tradeCount, 3, positive,
                decimal(result, "totalReturn"),
                decimal(result, "maxDrawdown"),
                decimal(result, "winRate"),
                decimal(result, "profitLossRatio"),
                text(context, "backtestResultHash"));
    }

    private static void validateDataVersion(
            JsonNode value,
            JsonNode bars,
            Instant knowledgeCutoff
    ) {
        require(value != null && value.isObject()
                        && exactFields(value, DATA_VERSION_FIELDS)
                        && PitMarketFactsContracts.MARKET_FACTS_VERSION
                        .equals(text(value, "pitModelVersion"))
                        && "MOCK_PIT_MARKET_FACTS_V2"
                        .equals(text(value, "sourceCode"))
                        && !text(value, "sourceInstrumentId").isBlank()
                        && "SYSTEM_KNOWLEDGE_PIT"
                        .equals(text(value, "qualification"))
                        && value.path("testDemoOnly").isBoolean()
                        && value.path("testDemoOnly").asBoolean(),
                "V2 dataVersion qualification mismatch");

        JsonNode rawVersions = value.get("rawObservationVersions");
        JsonNode factorVersions = value.get("factorObservationVersions");
        require(rawVersions != null && rawVersions.isArray()
                        && factorVersions != null && factorVersions.isArray()
                        && rawVersions.size() == bars.size()
                        && factorVersions.size() == bars.size(),
                "V2 bar lineage length mismatch");
        for (int index = 0; index < bars.size(); index++) {
            require(rawVersions.get(index).isTextual()
                            && factorVersions.get(index).isTextual()
                            && sha(rawVersions.get(index).asText()).equals(
                            text(bars.get(index), "rawObservationVersion"))
                            && sha(factorVersions.get(index).asText()).equals(
                            text(bars.get(index), "factorObservationVersion")),
                    "V2 bar lineage version mismatch");
        }

        JsonNode batches = value.get("batchLineage");
        require(batches != null && batches.isArray() && !batches.isEmpty(),
                "V2 batch lineage missing");
        String sourceInstrumentId = text(value, "sourceInstrumentId");
        String previousBatchVersion = null;
        for (JsonNode batch : batches) {
            require(batch.isObject()
                            && exactFields(batch, BATCH_LINEAGE_FIELDS),
                    "V2 batch lineage field mismatch");
            String batchVersion = sha(text(batch, "batchVersion"));
            require(previousBatchVersion == null
                            || batchVersion.compareTo(previousBatchVersion) > 0,
                    "V2 batch lineage order mismatch");
            previousBatchVersion = batchVersion;
            String datasetVersion = text(batch, "datasetVersion");
            String runNamespace = text(batch, "runNamespace");
            require(datasetVersion.matches(
                            "LOCAL_PIT_DATASET_V2-[0-9a-f]{64}")
                            && ("TEST".equals(runNamespace)
                            || "DEMO".equals(runNamespace))
                            && "MOCK_PIT_MARKET_FACTS_V2"
                            .equals(text(batch, "sourceCode"))
                            && sourceInstrumentId.equals(
                            text(batch, "sourceInstrumentId"))
                            && "SYSTEM_KNOWLEDGE_ONLY".equals(
                            text(batch, "revisionQualification"))
                            && "SYSTEM_KNOWLEDGE_PIT".equals(
                            text(batch, "assuranceLevel"))
                            && "TEST_DEMO_ONLY".equals(
                            text(batch, "usageQualification"))
                            && batch.path("responseComplete").isBoolean()
                            && batch.path("responseComplete").asBoolean()
                            && !Instant.parse(text(batch, "observedAt"))
                            .isAfter(knowledgeCutoff),
                    "V2 batch lineage qualification mismatch");
            JsonNode providerDatasetVersion =
                    batch.get("providerDatasetVersion");
            require(providerDatasetVersion != null
                            && (providerDatasetVersion.isNull()
                            || (providerDatasetVersion.isTextual()
                            && !providerDatasetVersion.asText().isBlank())),
                    "V2 provider dataset version invalid");
        }

        validateObservationLineage(
                value.get("calendarObservationVersions"),
                value.get("calendarLineage"), knowledgeCutoff, "calendar");
        validateObservationLineage(
                value.get("corporateActionObservationVersions"),
                value.get("corporateActionLineage"), knowledgeCutoff,
                "corporate action");
    }

    private static void validateObservationLineage(
            JsonNode versions,
            JsonNode lineage,
            Instant knowledgeCutoff,
            String label
    ) {
        require(versions != null && versions.isArray()
                        && lineage != null && lineage.isArray()
                        && versions.size() == lineage.size(),
                "V2 " + label + " lineage length mismatch");
        String previousNaturalKey = null;
        for (int index = 0; index < lineage.size(); index++) {
            JsonNode item = lineage.get(index);
            require(item.isObject()
                            && exactFields(item, OBSERVATION_LINEAGE_FIELDS),
                    "V2 " + label + " lineage field mismatch");
            String observationVersion =
                    sha(text(item, "observationVersion"));
            require(versions.get(index).isTextual()
                            && observationVersion.equals(
                            sha(versions.get(index).asText()))
                            && sha(text(item, "canonicalContentHash"))
                            .length() == 64
                            && "SYSTEM_KNOWLEDGE_ONLY".equals(
                            text(item, "revisionQualification"))
                            && !Instant.parse(text(item, "knownAt"))
                            .isAfter(knowledgeCutoff),
                    "V2 " + label + " lineage value mismatch");
            String naturalKey = text(item, "naturalKey");
            require(previousNaturalKey == null
                            || naturalKey.compareTo(previousNaturalKey) > 0,
                    "V2 " + label + " lineage order mismatch");
            previousNaturalKey = naturalKey;
        }
    }

    private static boolean exactFields(JsonNode value, Set<String> expected) {
        Set<String> actual = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static void validateStrategy(JsonNode strategy) {
        require(strategy != null
                        && PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION
                        .equals(text(strategy, "canonicalContractVersion"))
                        && BacktestContracts.STRATEGY_CODE.equals(
                        text(strategy, "strategyCode"))
                        && BacktestContracts.STRATEGY_VERSION.equals(
                        text(strategy, "strategyVersion"))
                        && BacktestContracts.ENGINE_VERSION.equals(
                        text(strategy, "engineVersion"))
                        && BacktestContracts.PARAMETER_SCHEMA_VERSION.equals(
                        text(strategy, "parameterSchemaVersion")),
                "V2 strategy identity mismatch");
        JsonNode parameters = strategy.get("parameters");
        require(parameters != null
                        && decimal(parameters, "initialCapital")
                        .compareTo(new BigDecimal("100000")) == 0
                        && parameters.path("maxHoldingDays").asInt(-1) == 10
                        && decimal(parameters, "stopLossPct")
                        .compareTo(new BigDecimal("0.05")) == 0
                        && decimal(parameters, "takeProfitPct")
                        .compareTo(new BigDecimal("0.08")) == 0
                        && decimal(parameters, "trailingStopPct")
                        .compareTo(new BigDecimal("0.04")) == 0
                        && decimal(parameters, "commissionRate")
                        .compareTo(new BigDecimal("0.0003")) == 0
                        && decimal(parameters, "stampDutyRate")
                        .compareTo(new BigDecimal("0.0005")) == 0,
                "V2 frozen parameters mismatch");
    }

    private static void validateHashes(
            AgentTeamRequest request,
            JsonNode context
    ) {
        String inputHash = sha(text(context, "inputDataHash"));
        String strategyHash = sha(text(context, "strategyDefinitionHash"));
        String resultHash = sha(text(context, "backtestResultHash"));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode input = mapper.createObjectNode();
        input.put("canonicalContractVersion",
                PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION);
        input.put("contextProfile", PitMarketFactsContracts.CONTEXT_PROFILE);
        input.put("contextSchemaVersion",
                PitMarketFactsContracts.BACKTEST_CONTEXT_VERSION);
        input.put("symbol", request.symbol());
        input.put("requestTradeDate", request.tradeDate().toString());
        input.put("requestEffectiveTradeDate",
                text(context, "effectiveTradeDate"));
        input.put("anchorTradeDate", text(context, "inputEndDate"));
        input.put("decisionTime", text(context, "decisionTime"));
        input.put("knowledgeCutoff", text(context, "knowledgeCutoff"));
        input.set("qfqContract", context.get("qfqContract").deepCopy());
        input.set("dataVersion", context.get("dataVersion").deepCopy());
        input.set("bars", context.get("bars").deepCopy());
        require(inputHash.equals(CANONICAL.hash(input)),
                "V2 inputDataHash mismatch");
        require(strategyHash.equals(CANONICAL.hash(context.get("strategy"))),
                "V2 strategyDefinitionHash mismatch");
        ObjectNode result = mapper.createObjectNode();
        result.put("canonicalContractVersion",
                PitMarketFactsContracts.BACKTEST_CANONICAL_VERSION);
        result.put("inputDataHash", inputHash);
        result.put("strategyDefinitionHash", strategyHash);
        result.set("result", context.get("result").deepCopy());
        result.set("subperiods", context.get("subperiods").deepCopy());
        result.set("stability", context.get("stability").deepCopy());
        require(resultHash.equals(CANONICAL.hash(result)),
                "V2 backtestResultHash mismatch");
    }

    private static Evidence validateEvidence(
            AgentTeamRequest request,
            AgentOutput run,
            JsonNode context,
            Metrics metrics
    ) {
        require(run.evidence().size() == 1, "V2 backtest evidence count mismatch");
        Evidence evidence = run.evidence().get(0);
        require(evidence.category() == EvidenceCategory.BACKTEST_RESULT,
                "V2 backtest evidence category mismatch");
        require(evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE,
                "V2 backtest evidence sourceType mismatch");
        require(PitMarketFactsContracts.PRODUCER.equals(evidence.sourceName())
                        && "contextSnapshot.backtestContext".equals(
                        evidence.sourceRef()),
                "V2 backtest evidence source identity mismatch");
        require(request.symbol().equals(evidence.symbol())
                        && request.tradeDate().equals(evidence.tradeDate()),
                "V2 backtest evidence request scope mismatch");
        require(sameMicrosecond(
                        Instant.parse(text(context, "knowledgeCutoff")),
                        evidence.observedAt())
                        && sameMicrosecond(
                        request.requestedAt(), evidence.collectedAt()),
                "V2 backtest evidence timestamps mismatch");
        require(metrics.resultHash().equals(evidence.contentHash()),
                "V2 backtest evidence contentHash mismatch");
        require(evidence.fields().size() == 1
                        && evidence.fields().get("backtestContext")
                        .equals(context),
                "V2 backtest evidence fields mismatch");
        return evidence;
    }

    private static int totalReturnImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.15")) >= 0) return 15;
        if (value.compareTo(new BigDecimal("0.05")) >= 0) return 10;
        if (value.signum() > 0) return 5;
        if (value.compareTo(new BigDecimal("-0.15")) <= 0) return -20;
        return value.signum() < 0 ? -10 : 0;
    }

    private static int drawdownImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.10")) <= 0) return 10;
        if (value.compareTo(new BigDecimal("0.30")) > 0) return -20;
        return value.compareTo(new BigDecimal("0.20")) > 0 ? -10 : 0;
    }

    private static int winRateImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.55")) >= 0) return 10;
        return value.compareTo(new BigDecimal("0.45")) < 0 ? -10 : 0;
    }

    private static int profitLossImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("1.50")) >= 0) return 10;
        if (value.compareTo(BigDecimal.ONE) >= 0) return 5;
        return value.compareTo(new BigDecimal("0.80")) < 0 ? -10 : 0;
    }

    private static int subperiodImpact(int value) {
        return switch (value) {
            case 3 -> 10;
            case 2 -> 5;
            case 1 -> -10;
            case 0 -> -20;
            default -> throw new AgentResponseValidationException(
                    "V2 positiveSubperiodCount invalid");
        };
    }

    private static BigDecimal decimal(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isNumber(), "V2 decimal missing: " + field);
        return node.decimalValue();
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isTextual() && !node.asText().isBlank(),
                "V2 text missing: " + field);
        return node.asText();
    }

    private static String sha(String value) {
        require(value.matches("[0-9a-f]{64}"), "V2 SHA-256 invalid");
        return value;
    }

    private static boolean sameMicrosecond(Instant left, Instant right) {
        return left != null
                && right != null
                && left.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .equals(right.truncatedTo(
                        java.time.temporal.ChronoUnit.MICROS));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AgentResponseValidationException(message);
    }

    private record Metrics(
            int tradeCount,
            int validSubperiodCount,
            int positiveSubperiodCount,
            BigDecimal totalReturn,
            BigDecimal maxDrawdown,
            BigDecimal winRate,
            BigDecimal profitLossRatio,
            String resultHash
    ) {
    }
}
