package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class AgentStage2FStrategyBacktestValidator {

    private static final Pattern CANONICAL_INSTANT = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z$");
    private static final BacktestCanonicalHashService HASHES =
            new BacktestCanonicalHashService(new ObjectMapper());
    private static final List<String> FINDING_CODES = List.of(
            "STRATEGY_BACKTEST_SAMPLE_SUFFICIENT",
            "STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED",
            "STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED",
            "STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED",
            "STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED"
    );
    private static final List<String> TITLES = List.of(
            "回测交易样本达到规则门槛",
            "回测总收益表现",
            "回测最大回撤风险",
            "回测胜率与盈亏比质量",
            "回测跨时间子区间稳定性"
    );
    private static final Set<String> RESULT_FIELDS = Set.of(
            "initialCapital",
            "finalCapital",
            "totalReturn",
            "maxDrawdown",
            "winRate",
            "profitLossRatio",
            "tradeCount",
            "trades"
    );
    private static final Set<String> BASE_CONTEXT_FIELDS = Set.of(
            "available",
            "queriedAt",
            "queryScope",
            "producer",
            "producerVersion",
            "contextProfile",
            "schemaVersion",
            "canonicalContractVersion",
            "pitModelVersion",
            "symbol",
            "requestTradeDate",
            "decisionTime",
            "knowledgeCutoff",
            "marketTimezone",
            "adjustType",
            "sourceType",
            "sourceTables",
            "sourceStatus",
            "pointInTimeGuaranteed",
            "readSelectionFutureExcluded",
            "producerInputCutoffGuaranteed",
            "futureDataExcluded"
    );
    private static final Set<String> AVAILABLE_CONTEXT_FIELDS = plus(
            BASE_CONTEXT_FIELDS,
            "effectiveTradeDate",
            "exactTradeDateMatch",
            "inputStartDate",
            "inputEndDate",
            "barCount",
            "requiredBars",
            "maximumBars",
            "dataVersion",
            "lineage",
            "bars",
            "strategy",
            "result",
            "subperiods",
            "stability",
            "inputDataHash",
            "strategyDefinitionHash",
            "backtestResultHash",
            "limitations"
    );
    private static final Set<String> UNAVAILABLE_CONTEXT_FIELDS = plus(
            BASE_CONTEXT_FIELDS,
            "reasonCode",
            "reason"
    );
    private static final Set<String> SAMPLE_UNAVAILABLE_CONTEXT_FIELDS = plus(
            UNAVAILABLE_CONTEXT_FIELDS,
            "actualBars",
            "requiredBars"
    );
    private static final Set<String> BAR_FIELDS = Set.of(
            "symbol",
            "tradeDate",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "amount",
            "turnoverRate",
            "sourceCode",
            "sourceRevision",
            "batchVersion",
            "datasetVersion",
            "observationVersion",
            "firstObservedAt",
            "knownAt",
            "canonicalContentHash"
    );
    private static final Set<String> OBSERVATION_LINEAGE_FIELDS = Set.of(
            "observationVersion",
            "tradeDate",
            "sourceCode",
            "sourceRevision",
            "batchVersion",
            "datasetVersion",
            "firstObservedAt",
            "knownAt",
            "recordedAt",
            "canonicalContentHash"
    );
    private static final Set<String> BATCH_LINEAGE_FIELDS = Set.of(
            "batchVersion",
            "datasetVersion",
            "sourceCode",
            "captureType",
            "observedAt",
            "recordedAt",
            "sourceMetadata"
    );
    private static final List<String> AVAILABLE_LIMITATIONS = List.of(
            "RESEARCH_AND_SIMULATION_ONLY",
            "LOCAL_OBSERVATION_TIME_IS_NOT_PROVIDER_PUBLICATION_TIME",
            "CONTENT_HASH_DOES_NOT_REPLACE_KNOWLEDGE_TIME"
    );

    private AgentStage2FStrategyBacktestValidator() {
    }

    static void validateBlocked(AgentOutput run) {
        require(run.agentCode() == AgentCode.STRATEGY_BACKTEST
                        && run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.gateStatus() == GateStatus.BLOCKED
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && !run.veto()
                        && Objects.equals(run.score(), 0)
                        && Objects.equals(run.confidence(), 0)
                        && "因DATA_QUALITY门禁阻断，未解释策略回测事实。"
                        .equals(run.summary())
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().isEmpty()
                        && !containsForbiddenOutput(run),
                "阶段2F DATA_QUALITY阻断时STRATEGY_BACKTEST必须继承阻断且不执行");
    }

    static void validate(
            AgentTeamRequest request,
            AgentOutput run,
            GateStatus dataQualityGate
    ) {
        require(run.agentCode() == AgentCode.STRATEGY_BACKTEST
                        && run.gateStatus() == dataQualityGate
                        && !run.veto()
                        && !containsForbiddenOutput(run),
                "阶段2F STRATEGY_BACKTEST身份、门禁或veto无效");
        JsonNode context = request.contextSnapshot() == null
                ? null : request.contextSnapshot().get("backtestContext");
        if (!validBaseContext(request, context)) {
            validateInputInvalid(run);
            return;
        }
        if (!context.path("available").asBoolean(false)) {
            String reasonCode = text(context, "reasonCode");
            require(BacktestContracts.UNAVAILABLE_REASON_CODES.contains(reasonCode),
                    "阶段2F不可用reasonCode不在白名单");
            require(run.status() == RunStatus.INSUFFICIENT_DATA
                            && run.decision() == RunDecision.NOT_APPLICABLE
                            && Objects.equals(run.score(), 0)
                            && Objects.equals(run.confidence(), 0)
                            && "可靠backtestContext不可用，未形成策略回测评分。"
                            .equals(run.summary())
                            && run.findings().isEmpty()
                            && run.evidence().isEmpty()
                            && run.errors().size() == 1
                            && reasonCode.equals(run.errors().get(0).code()),
                    "阶段2F不可用backtestContext必须安全降级");
            return;
        }
        ParsedContext parsed = parseAvailableContext(request, context);
        if (parsed == null) {
            validateInputInvalid(run);
            return;
        }
        Evidence evidence = validateEvidence(request, run, context, parsed);
        if (parsed.tradeCount() < 10 || parsed.validSubperiodCount() < 2) {
            require(run.status() == RunStatus.INSUFFICIENT_DATA
                            && run.decision() == RunDecision.NOT_APPLICABLE
                            && Objects.equals(run.score(), 0)
                            && Objects.equals(run.confidence(), 0)
                            && "可靠回测事实的交易或子区间样本不足，未形成正常性能评分。"
                            .equals(run.summary())
                            && run.findings().isEmpty()
                            && run.evidence().size() == 1
                            && run.errors().size() == 1
                            && BacktestContracts.STRATEGY_SAMPLE_INSUFFICIENT
                            .equals(run.errors().get(0).code()),
                    "阶段2F回测样本不足不得形成正常评分");
            return;
        }
        int[] impacts = {
                0,
                totalReturnImpact(parsed.totalReturn()),
                drawdownImpact(parsed.maxDrawdown()),
                winRateImpact(parsed.winRate())
                        + profitLossImpact(parsed.profitLossRatio()),
                subperiodImpact(parsed.positiveSubperiodCount())
        };
        int expectedScore = 50;
        for (int impact : impacts) expectedScore += impact;
        expectedScore = Math.max(0, Math.min(100, expectedScore));
        int expectedConfidence = parsed.tradeCount() >= 40
                ? 80 : parsed.tradeCount() >= 20 ? 60 : 40;
        if (dataQualityGate == GateStatus.WARN) {
            expectedConfidence = Math.min(50, expectedConfidence);
        }
        require(run.status() == RunStatus.COMPLETED
                        && run.decision() == RunDecision.WARN
                        && Objects.equals(run.score(), expectedScore)
                        && Objects.equals(run.confidence(), expectedConfidence)
                        && "已按冻结PIT、版本、参数和Hash解释可靠回测事实；结果仅用于研究。"
                        .equals(run.summary())
                        && run.errors().isEmpty()
                        && run.evidence().size() == 1
                        && run.findings().size() == 5,
                "阶段2F有效回测输出状态、score或confidence无效");
        validateFindings(request, run.findings(), evidence, parsed, impacts);
    }

    private static void validateInputInvalid(AgentOutput run) {
        require(run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && Objects.equals(run.score(), 0)
                        && Objects.equals(run.confidence(), 0)
                        && "STRATEGY_BACKTEST输入契约非法，未形成回测评分。"
                        .equals(run.summary())
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().size() == 1
                        && BacktestContracts.STRATEGY_INPUT_INVALID
                        .equals(run.errors().get(0).code()),
                "阶段2F非法回测输入必须使用稳定错误码安全降级");
    }

    private static boolean validBaseContext(
            AgentTeamRequest request,
            JsonNode context
    ) {
        try {
            if (context == null || !context.isObject()
                    || !textEquals(context, "producer", BacktestContracts.PRODUCER)
                    || !textEquals(
                    context, "producerVersion", BacktestContracts.PRODUCER_VERSION)
                    || !textEquals(
                    context, "contextProfile", BacktestContracts.CONTEXT_PROFILE)
                    || !textEquals(
                    context, "schemaVersion", BacktestContracts.CONTEXT_SCHEMA_VERSION)
                    || !textEquals(
                    context,
                    "canonicalContractVersion",
                    BacktestContracts.CANONICAL_CONTRACT_VERSION)
                    || !textEquals(
                    context, "pitModelVersion", BacktestContracts.PIT_MODEL_VERSION)
                    || !textEquals(context, "symbol", request.symbol())
                    || !textEquals(
                    context, "requestTradeDate", request.tradeDate().toString())
                    || !textEquals(
                    context, "marketTimezone", BacktestContracts.MARKET_ZONE.getId())
                    || !textEquals(context, "adjustType", BacktestContracts.ADJUST_TYPE)
                    || !textEquals(context, "sourceType", "DATABASE")
                    || !textEquals(
                    context, "sourceStatus", "LOCAL_PIT_OBSERVATIONS")) {
                return false;
            }
            JsonNode availableNode = context.get("available");
            if (availableNode == null || !availableNode.isBoolean()) return false;
            boolean available = availableNode.booleanValue();
            String reasonCode = context.path("reasonCode").asText("");
            Set<String> expectedFields = available
                    ? AVAILABLE_CONTEXT_FIELDS
                    : BacktestContracts.SAMPLE_INSUFFICIENT.equals(reasonCode)
                    ? SAMPLE_UNAVAILABLE_CONTEXT_FIELDS
                    : UNAVAILABLE_CONTEXT_FIELDS;
            if (!sameFields(context, expectedFields)
                    || !textArrayEquals(
                    context.get("sourceTables"),
                    List.of(
                            "market_data_observation_batches",
                            "daily_bar_observations"))
                    || !validBooleanFlags(context, available)
                    || !available
                    && BacktestContracts.SAMPLE_INSUFFICIENT.equals(reasonCode)
                    && (exactInt(context.get("actualBars")) < 0
                    || exactInt(context.get("actualBars"))
                    >= BacktestContracts.MINIMUM_CONTEXT_BARS
                    || exactInt(context.get("requiredBars"))
                    != BacktestContracts.MINIMUM_CONTEXT_BARS)) {
                return false;
            }
            if (!available) {
                text(context, "reasonCode");
                text(context, "reason");
            }
            JsonNode scope = context.get("queryScope");
            if (scope == null || !sameFields(
                    scope, Set.of("symbol", "tradeDate"))
                    || !textEquals(scope, "symbol", request.symbol())
                    || !textEquals(
                    scope, "tradeDate", request.tradeDate().toString())) {
                return false;
            }
            Instant queriedAt = canonicalInstant(context, "queriedAt");
            Instant decisionTime = canonicalInstant(context, "decisionTime");
            Instant cutoff = canonicalInstant(context, "knowledgeCutoff");
            Instant expected = request.tradeDate().plusDays(1)
                    .atStartOfDay(BacktestContracts.MARKET_ZONE)
                    .toInstant()
                    .minus(1, ChronoUnit.MICROS);
            return decisionTime.equals(cutoff)
                    && decisionTime.equals(expected)
                    && !queriedAt.isBefore(cutoff);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static ParsedContext parseAvailableContext(
            AgentTeamRequest request,
            JsonNode context
    ) {
        try {
            if (!context.path("available").asBoolean(false)
                    || exactInt(context.get("requiredBars"))
                    != BacktestContracts.MINIMUM_CONTEXT_BARS
                    || exactInt(context.get("maximumBars"))
                    != BacktestContracts.MAXIMUM_BARS
                    || !textArrayEquals(
                    context.get("limitations"), AVAILABLE_LIMITATIONS)) return null;
            JsonNode bars = context.get("bars");
            int barCount = exactInt(context.get("barCount"));
            if (bars == null || !bars.isArray()
                    || bars.size() != barCount
                    || barCount < BacktestContracts.MINIMUM_CONTEXT_BARS
                    || barCount > BacktestContracts.MAXIMUM_BARS) return null;
            LocalDate previous = null;
            for (JsonNode bar : bars) {
                if (bar == null || !sameFields(bar, BAR_FIELDS)
                        || !textEquals(bar, "symbol", request.symbol())
                        || !validBar(bar)) return null;
                LocalDate date = LocalDate.parse(text(bar, "tradeDate"));
                Instant knownAt = canonicalInstant(bar, "knownAt");
                Instant firstObservedAt = canonicalInstant(bar, "firstObservedAt");
                if (date.isAfter(request.tradeDate())
                        || previous != null && !date.isAfter(previous)
                        || knownAt.isAfter(canonicalInstant(
                        context, "knowledgeCutoff"))
                        || firstObservedAt.isAfter(knownAt)
                        || !sha(text(bar, "observationVersion"))
                        || !sha(text(bar, "canonicalContentHash"))
                        || !HASHES.hash(contentPayload(bar))
                        .equals(text(bar, "canonicalContentHash"))
                        || !HASHES.hash(observationVersionPayload(bar))
                        .equals(text(bar, "observationVersion"))) return null;
                previous = date;
            }
            if (previous == null
                    || !textEquals(context, "effectiveTradeDate", previous.toString())
                    || !textEquals(
                    context, "inputStartDate", text(bars.get(0), "tradeDate"))
                    || !textEquals(
                    context,
                    "inputEndDate",
                    text(bars.get(bars.size() - 1), "tradeDate"))
                    || !context.path("exactTradeDateMatch").isBoolean()
                    || context.path("exactTradeDateMatch").booleanValue()
                    != previous.equals(request.tradeDate())) return null;
            JsonNode dataVersion = context.get("dataVersion");
            JsonNode lineage = context.get("lineage");
            JsonNode strategy = context.get("strategy");
            JsonNode result = context.get("result");
            JsonNode subperiods = context.get("subperiods");
            JsonNode stability = context.get("stability");
            if (!validDataVersion(dataVersion, bars)
                    || !validLineage(lineage, bars)
                    || !validStrategy(strategy)
                    || !validResult(result, request.tradeDate())
                    || !validSubperiods(subperiods, bars, request.tradeDate())
                    || !validStability(stability, subperiods)) return null;

            String inputDataHash = text(context, "inputDataHash");
            String strategyDefinitionHash = text(context, "strategyDefinitionHash");
            String backtestResultHash = text(context, "backtestResultHash");
            if (!sha(inputDataHash) || !sha(strategyDefinitionHash)
                    || !sha(backtestResultHash)) return null;
            ObjectNode inputPayload = new ObjectMapper().createObjectNode();
            inputPayload.put(
                    "canonicalContractVersion",
                    BacktestContracts.CANONICAL_CONTRACT_VERSION);
            inputPayload.put("contextProfile", BacktestContracts.CONTEXT_PROFILE);
            inputPayload.put(
                    "contextSchemaVersion", BacktestContracts.CONTEXT_SCHEMA_VERSION);
            inputPayload.put("symbol", request.symbol());
            copyText(context, inputPayload, "requestTradeDate");
            copyText(context, inputPayload, "effectiveTradeDate");
            copyText(context, inputPayload, "decisionTime");
            copyText(context, inputPayload, "knowledgeCutoff");
            copyText(context, inputPayload, "marketTimezone");
            copyText(context, inputPayload, "adjustType");
            copyText(context, inputPayload, "inputStartDate");
            copyText(context, inputPayload, "inputEndDate");
            inputPayload.put("barCount", barCount);
            inputPayload.set("dataVersion", dataVersion.deepCopy());
            ArrayNode observationLineage = inputPayload.putArray("observationLineage");
            for (JsonNode observation : lineage.get("observations")) {
                ObjectNode copy = observation.deepCopy();
                copy.remove("recordedAt");
                observationLineage.add(copy);
            }
            inputPayload.set("bars", bars.deepCopy());
            if (!HASHES.hash(inputPayload).equals(inputDataHash)
                    || !HASHES.hash(strategy).equals(strategyDefinitionHash)) return null;
            ObjectNode resultPayload = new ObjectMapper().createObjectNode();
            resultPayload.put(
                    "canonicalContractVersion",
                    BacktestContracts.CANONICAL_CONTRACT_VERSION);
            resultPayload.put("inputDataHash", inputDataHash);
            resultPayload.put("strategyDefinitionHash", strategyDefinitionHash);
            resultPayload.set("result", result.deepCopy());
            resultPayload.set("subperiods", subperiods.deepCopy());
            resultPayload.set("stability", stability.deepCopy());
            if (!HASHES.hash(resultPayload).equals(backtestResultHash)) return null;
            return new ParsedContext(
                    exactInt(result.get("tradeCount")),
                    exactInt(stability.get("validSubperiodCount")),
                    exactInt(stability.get("positiveSubperiodCount")),
                    decimal(result.get("totalReturn")),
                    decimal(result.get("maxDrawdown")),
                    decimal(result.get("winRate")),
                    decimal(result.get("profitLossRatio")),
                    canonicalInstant(lineage, "maximumKnownAt"),
                    backtestResultHash);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Evidence validateEvidence(
            AgentTeamRequest request,
            AgentOutput run,
            JsonNode context,
            ParsedContext parsed
    ) {
        require(run.evidence().size() <= 1, "阶段2F回测证据最多一条");
        if (run.evidence().isEmpty()) return null;
        Evidence evidence = run.evidence().get(0);
        JsonNode projection = evidence.fields() == null
                ? null : evidence.fields().get("backtestContext");
        require(evidence.category() == EvidenceCategory.BACKTEST_RESULT
                        && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && BacktestContracts.PRODUCER.equals(evidence.sourceName())
                        && "contextSnapshot.backtestContext".equals(evidence.sourceRef())
                        && ("sb-context-" + request.contextHash())
                        .equals(evidence.evidenceId())
                        && Objects.equals(evidence.symbol(), request.symbol())
                        && Objects.equals(evidence.tradeDate(), request.tradeDate())
                        && Objects.equals(
                        evidence.contentHash(), parsed.backtestResultHash())
                        && sameInstant(evidence.observedAt(), parsed.maximumKnownAt())
                        && sameInstant(evidence.collectedAt(), request.requestedAt())
                        && evidence.fields().isObject()
                        && evidence.fields().size() == 1
                        && projection != null
                        && HASHES.canonicalText(projection)
                        .equals(HASHES.canonicalText(context)),
                "阶段2F回测证据必须是Java backtestContext的直接完整投影");
        return evidence;
    }

    private static void validateFindings(
            AgentTeamRequest request,
            List<Finding> findings,
            Evidence evidence,
            ParsedContext parsed,
            int[] impacts
    ) {
        require(evidence != null, "阶段2F有效回测必须生成证据");
        Severity[] severities = {
                Severity.INFO,
                parsed.totalReturn().signum() > 0 ? Severity.INFO : Severity.WARN,
                parsed.maxDrawdown().compareTo(new BigDecimal("0.10")) <= 0
                        ? Severity.INFO : Severity.WARN,
                parsed.winRate().compareTo(new BigDecimal("0.55")) >= 0
                        && parsed.profitLossRatio().compareTo(BigDecimal.ONE) >= 0
                        ? Severity.INFO : Severity.WARN,
                parsed.positiveSubperiodCount() >= 2 ? Severity.INFO : Severity.WARN
        };
        for (int index = 0; index < FINDING_CODES.size(); index++) {
            Finding finding = findings.get(index);
            String code = FINDING_CODES.get(index);
            String expectedId = "sb-%02d-%s-%s".formatted(
                    index + 1,
                    code.toLowerCase().replace('_', '-'),
                    request.contextHash());
            String signedImpact = impacts[index] >= 0
                    ? "+" + impacts[index] : Integer.toString(impacts[index]);
            require(code.equals(finding.code())
                            && expectedId.equals(finding.findingId())
                            && TITLES.get(index).equals(finding.title())
                            && severities[index] == finding.severity()
                            && finding.evidenceIds().equals(List.of(evidence.evidenceId()))
                            && finding.detail().contains("evidencePaths=backtestContext.")
                            && finding.detail().contains("observed=")
                            && finding.detail().contains("condition=")
                            && finding.detail().contains(
                            "scoreImpact=" + signedImpact + "。"),
                    "阶段2F五类finding内容、顺序或scoreImpact无效");
        }
    }

    private static boolean validDataVersion(JsonNode value, JsonNode bars) {
        if (value == null || !sameFields(value, Set.of(
                "pitModelVersion",
                "datasetVersions",
                "batchVersions",
                "selectedObservationVersions",
                "sourceRevisions"))
                || !textEquals(
                value, "pitModelVersion", BacktestContracts.PIT_MODEL_VERSION)
                || !arrays(value, "datasetVersions", "batchVersions",
                "selectedObservationVersions", "sourceRevisions")
                || !textArrayEquals(
                value.get("datasetVersions"),
                uniqueSortedText(bars, "datasetVersion"))
                || !textArrayEquals(
                value.get("batchVersions"),
                uniqueSortedText(bars, "batchVersion"))
                || value.get("selectedObservationVersions").size() != bars.size()) {
            return false;
        }
        for (int index = 0; index < bars.size(); index++) {
            if (!Objects.equals(
                    value.get("selectedObservationVersions").get(index).asText(),
                    text(bars.get(index), "observationVersion"))) return false;
        }
        List<SourceRevision> expectedRevisions = new ArrayList<>();
        for (JsonNode bar : bars) {
            SourceRevision revision = new SourceRevision(
                    text(bar, "sourceCode"),
                    text(bar, "sourceRevision"));
            if (!expectedRevisions.contains(revision)) expectedRevisions.add(revision);
        }
        expectedRevisions.sort(Comparator
                .comparing(SourceRevision::sourceCode)
                .thenComparing(SourceRevision::sourceRevision));
        JsonNode revisions = value.get("sourceRevisions");
        if (revisions.size() != expectedRevisions.size()) return false;
        for (int index = 0; index < revisions.size(); index++) {
            JsonNode actual = revisions.get(index);
            SourceRevision expected = expectedRevisions.get(index);
            if (!sameFields(actual, Set.of("sourceCode", "sourceRevision"))
                    || !textEquals(
                    actual, "sourceCode", expected.sourceCode())
                    || !textEquals(
                    actual, "sourceRevision", expected.sourceRevision())) {
                return false;
            }
        }
        return true;
    }

    private static boolean validLineage(JsonNode value, JsonNode bars) {
        if (value == null || !sameFields(value, Set.of(
                "sources", "batches", "observations", "maximumKnownAt"))
                || !arrays(value, "sources", "batches", "observations")
                || !textArrayEquals(
                value.get("sources"),
                uniqueSortedText(bars, "sourceCode"))
                || value.get("observations").size() != bars.size()) return false;
        List<String> expectedBatchVersions = uniqueSortedText(
                bars, "batchVersion");
        JsonNode batches = value.get("batches");
        if (batches.size() != expectedBatchVersions.size()) return false;
        Map<String, JsonNode> batchesByVersion = new HashMap<>();
        for (int index = 0; index < batches.size(); index++) {
            JsonNode batch = batches.get(index);
            if (!sameFields(batch, BATCH_LINEAGE_FIELDS)
                    || !textEquals(
                    batch, "batchVersion", expectedBatchVersions.get(index))
                    || !Set.of(
                    "MARKET_DATA_PERSISTENCE",
                    "BOOTSTRAP_CURRENT_STATE",
                    "TEST_FIXTURE").contains(text(batch, "captureType"))
                    || !batch.path("sourceMetadata").isObject()
                    || canonicalInstant(batch, "observedAt").isAfter(
                    canonicalInstant(batch, "recordedAt"))) return false;
            batchesByVersion.put(text(batch, "batchVersion"), batch);
        }
        Instant maximumKnownAt = null;
        for (int index = 0; index < bars.size(); index++) {
            JsonNode observation = value.get("observations").get(index);
            JsonNode bar = bars.get(index);
            if (!sameFields(observation, OBSERVATION_LINEAGE_FIELDS)) {
                return false;
            }
            for (String field : List.of(
                    "observationVersion",
                    "tradeDate",
                    "sourceCode",
                    "sourceRevision",
                    "batchVersion",
                    "datasetVersion",
                    "firstObservedAt",
                    "knownAt",
                    "canonicalContentHash")) {
                if (!textEquals(observation, field, text(bar, field))) {
                    return false;
                }
            }
            Instant knownAt = canonicalInstant(observation, "knownAt");
            if (canonicalInstant(
                    observation, "recordedAt").isBefore(knownAt)) {
                return false;
            }
            maximumKnownAt = maximumKnownAt == null
                    || knownAt.isAfter(maximumKnownAt)
                    ? knownAt : maximumKnownAt;
            JsonNode batch = batchesByVersion.get(text(bar, "batchVersion"));
            if (batch == null
                    || !textEquals(
                    batch, "datasetVersion", text(bar, "datasetVersion"))
                    || !textEquals(
                    batch, "sourceCode", text(bar, "sourceCode"))) return false;
        }
        return maximumKnownAt != null
                && maximumKnownAt.equals(
                canonicalInstant(value, "maximumKnownAt"));
    }

    private static ObjectNode contentPayload(JsonNode bar) {
        ObjectNode value = new ObjectMapper().createObjectNode();
        value.put(
                "canonicalContractVersion",
                BacktestContracts.CANONICAL_CONTRACT_VERSION);
        for (String field : List.of(
                "sourceCode", "symbol", "tradeDate")) {
            copyText(bar, value, field);
        }
        value.put("adjustType", BacktestContracts.ADJUST_TYPE);
        for (String field : List.of(
                "open",
                "high",
                "low",
                "close",
                "volume",
                "amount",
                "turnoverRate")) {
            value.set(field, bar.get(field).deepCopy());
        }
        return value;
    }

    private static ObjectNode observationVersionPayload(JsonNode bar) {
        ObjectNode value = new ObjectMapper().createObjectNode();
        value.put(
                "canonicalContractVersion",
                BacktestContracts.CANONICAL_CONTRACT_VERSION);
        for (String field : List.of(
                "batchVersion",
                "datasetVersion",
                "sourceCode",
                "sourceRevision",
                "firstObservedAt",
                "knownAt",
                "canonicalContentHash")) {
            copyText(bar, value, field);
        }
        return value;
    }

    private static boolean validStrategy(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 6
                || !textEquals(
                value,
                "canonicalContractVersion",
                BacktestContracts.CANONICAL_CONTRACT_VERSION)
                || !textEquals(value, "strategyCode", BacktestContracts.STRATEGY_CODE)
                || !textEquals(
                value, "strategyVersion", BacktestContracts.STRATEGY_VERSION)
                || !textEquals(value, "engineVersion", BacktestContracts.ENGINE_VERSION)
                || !textEquals(
                value,
                "parameterSchemaVersion",
                BacktestContracts.PARAMETER_SCHEMA_VERSION)) return false;
        JsonNode parameters = value.get("parameters");
        if (parameters == null || !parameters.isObject() || parameters.size() != 7) {
            return false;
        }
        var expected = BacktestContracts.parameters();
        return decimalEquals(parameters.get("initialCapital"), expected.initialCapital())
                && exactInt(parameters.get("maxHoldingDays")) == expected.maxHoldingDays()
                && decimalEquals(parameters.get("stopLossPct"), expected.stopLossPct())
                && decimalEquals(parameters.get("takeProfitPct"), expected.takeProfitPct())
                && decimalEquals(
                parameters.get("trailingStopPct"), expected.trailingStopPct())
                && decimalEquals(
                parameters.get("commissionRate"), expected.commissionRate())
                && decimalEquals(
                parameters.get("stampDutyRate"), expected.stampDutyRate());
    }

    private static boolean validResult(JsonNode value, LocalDate requestTradeDate) {
        if (value == null || !value.isObject()
                || value.size() != RESULT_FIELDS.size()
                || !RESULT_FIELDS.stream().allMatch(value::has)
                || !finite(value, "initialCapital", "finalCapital", "totalReturn",
                "maxDrawdown", "winRate", "profitLossRatio")
                || decimal(value.get("initialCapital")).signum() <= 0
                || decimal(value.get("finalCapital")).signum() < 0
                || decimal(value.get("maxDrawdown")).signum() < 0
                || decimal(value.get("maxDrawdown")).compareTo(BigDecimal.ONE) > 0
                || decimal(value.get("winRate")).signum() < 0
                || decimal(value.get("winRate")).compareTo(BigDecimal.ONE) > 0
                || decimal(value.get("profitLossRatio")).signum() < 0) return false;
        int tradeCount = exactInt(value.get("tradeCount"));
        JsonNode trades = value.get("trades");
        if (tradeCount < 0 || trades == null || !trades.isArray()
                || trades.size() != tradeCount) return false;
        LocalDate previousEntry = null;
        for (int index = 0; index < trades.size(); index++) {
            JsonNode trade = trades.get(index);
            if (trade == null || !trade.isObject() || trade.size() != 9
                    || exactInt(trade.get("sequence")) != index + 1
                    || !finite(
                    trade, "entryPrice", "exitPrice", "pnl", "returnPct")
                    || decimal(trade.get("entryPrice")).signum() <= 0
                    || decimal(trade.get("exitPrice")).signum() <= 0) return false;
            LocalDate entry = LocalDate.parse(text(trade, "entryDate"));
            LocalDate exit = LocalDate.parse(text(trade, "exitDate"));
            int quantity = exactInt(trade.get("quantity"));
            if (exit.isBefore(entry) || exit.isAfter(requestTradeDate)
                    || previousEntry != null && entry.isBefore(previousEntry)
                    || quantity < 100 || quantity % 100 != 0
                    || !Set.of(
                    "STOP_LOSS", "TAKE_PROFIT", "TRAILING_STOP", "MAX_HOLD")
                    .contains(text(trade, "exitReason"))) return false;
            previousEntry = entry;
        }
        return true;
    }

    private static boolean validSubperiods(
            JsonNode value,
            JsonNode bars,
            LocalDate requestTradeDate
    ) {
        if (value == null || !value.isArray() || value.size() != 3) return false;
        List<String> names = List.of("EARLY", "MIDDLE", "LATE");
        int base = bars.size() / 3;
        int remainder = bars.size() % 3;
        int cursor = 0;
        for (int index = 0; index < 3; index++) {
            JsonNode period = value.get(index);
            int count = base + (index < remainder ? 1 : 0);
            if (!textEquals(period, "name", names.get(index))
                    || exactInt(period.get("barCount")) != count
                    || !textEquals(
                    period, "inputStartDate", text(bars.get(cursor), "tradeDate"))
                    || !textEquals(
                    period,
                    "inputEndDate",
                    text(bars.get(cursor + count - 1), "tradeDate"))
                    || !validResult(period.get("result"), requestTradeDate)) return false;
            cursor += count;
        }
        return cursor == bars.size();
    }

    private static boolean validStability(JsonNode value, JsonNode subperiods) {
        if (value == null || !value.isObject()
                || value.size() != 3
                || !textEquals(
                value, "splitAlgorithm", BacktestContracts.SPLIT_ALGORITHM)
                || exactInt(value.get("validSubperiodCount")) != 3) return false;
        int positive = exactInt(value.get("positiveSubperiodCount"));
        if (positive < 0 || positive > 3) return false;
        int actual = 0;
        for (JsonNode subperiod : subperiods) {
            if (decimal(subperiod.get("result").get("totalReturn")).signum() > 0) {
                actual++;
            }
        }
        return positive == actual;
    }

    private static boolean validBar(JsonNode value) {
        if (!finite(value, "open", "high", "low", "close")
                || !value.path("volume").isIntegralNumber()
                || !value.path("volume").canConvertToLong()
                || value.path("volume").longValue() < 0
                || !nullableFinite(value.get("amount"))
                || !nullableFinite(value.get("turnoverRate"))) return false;
        BigDecimal open = decimal(value.get("open"));
        BigDecimal high = decimal(value.get("high"));
        BigDecimal low = decimal(value.get("low"));
        BigDecimal close = decimal(value.get("close"));
        return open.signum() > 0 && high.signum() > 0
                && low.signum() > 0 && close.signum() > 0
                && high.compareTo(open) >= 0
                && high.compareTo(close) >= 0
                && high.compareTo(low) >= 0
                && low.compareTo(open) <= 0
                && low.compareTo(close) <= 0
                && (value.get("amount").isNull()
                || decimal(value.get("amount")).signum() >= 0)
                && (value.get("turnoverRate").isNull()
                || decimal(value.get("turnoverRate")).signum() >= 0);
    }

    private static int totalReturnImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.15")) >= 0) return 15;
        if (value.compareTo(new BigDecimal("0.05")) >= 0) return 10;
        if (value.signum() > 0) return 5;
        if (value.compareTo(new BigDecimal("-0.15")) <= 0) return -20;
        if (value.signum() < 0) return -10;
        return 0;
    }

    private static int drawdownImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.10")) <= 0) return 10;
        if (value.compareTo(new BigDecimal("0.30")) > 0) return -20;
        if (value.compareTo(new BigDecimal("0.20")) > 0) return -10;
        return 0;
    }

    private static int winRateImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.55")) >= 0) return 10;
        if (value.compareTo(new BigDecimal("0.45")) < 0) return -10;
        return 0;
    }

    private static int profitLossImpact(BigDecimal value) {
        if (value.compareTo(new BigDecimal("1.50")) >= 0) return 10;
        if (value.compareTo(BigDecimal.ONE) >= 0) return 5;
        if (value.compareTo(new BigDecimal("0.80")) < 0) return -10;
        return 0;
    }

    private static int subperiodImpact(int value) {
        return switch (value) {
            case 3 -> 10;
            case 2 -> 5;
            case 1 -> -10;
            case 0 -> -20;
            default -> throw new IllegalArgumentException(
                    "positiveSubperiodCount必须在0到3");
        };
    }

    private static Set<String> plus(
            Set<String> source,
            String... values
    ) {
        Set<String> result = new HashSet<>(source);
        result.addAll(List.of(values));
        return Set.copyOf(result);
    }

    private static boolean sameFields(
            JsonNode value,
            Set<String> expected
    ) {
        if (value == null || !value.isObject()) return false;
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static boolean textArrayEquals(
            JsonNode value,
            List<String> expected
    ) {
        if (value == null || !value.isArray()
                || value.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (!value.get(index).isTextual()
                    || !expected.get(index).equals(
                    value.get(index).textValue())) return false;
        }
        return true;
    }

    private static List<String> uniqueSortedText(
            JsonNode values,
            String field
    ) {
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) result.add(text(value, field));
        return result.stream().sorted().toList();
    }

    private static boolean validBooleanFlags(
            JsonNode context,
            boolean expected
    ) {
        for (String field : List.of(
                "pointInTimeGuaranteed",
                "readSelectionFutureExcluded",
                "producerInputCutoffGuaranteed",
                "futureDataExcluded")) {
            JsonNode value = context.get(field);
            if (value == null || !value.isBoolean()
                    || value.booleanValue() != expected) return false;
        }
        return true;
    }

    private static boolean arrays(JsonNode value, String... names) {
        for (String name : names) {
            if (!value.has(name) || !value.get(name).isArray()) return false;
        }
        return true;
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        target.put(field, text(source, field));
    }

    private static boolean textEquals(JsonNode source, String field, String expected) {
        return source != null && source.has(field) && source.get(field).isTextual()
                && Objects.equals(source.get(field).textValue(), expected);
    }

    private static String text(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + "必须是非空文本");
        }
        return value.textValue();
    }

    private static Instant canonicalInstant(JsonNode source, String field) {
        String value = text(source, field);
        if (!CANONICAL_INSTANT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + "必须是UTC Z结尾的微秒精度时间");
        }
        return Instant.parse(value);
    }

    private static int exactInt(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("必须是int");
        }
        return value.intValue();
    }

    private static boolean finite(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode value = source == null ? null : source.get(field);
            if (value == null || !value.isNumber()) return false;
            try {
                value.decimalValue();
            } catch (ArithmeticException | NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    private static boolean nullableFinite(JsonNode value) {
        return value != null && (value.isNull() || finiteNumber(value));
    }

    private static boolean finiteNumber(JsonNode value) {
        if (value == null || !value.isNumber()) return false;
        try {
            value.decimalValue();
            return true;
        } catch (ArithmeticException | NumberFormatException error) {
            return false;
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        return value.decimalValue();
    }

    private static boolean decimalEquals(JsonNode value, BigDecimal expected) {
        return finiteNumber(value) && decimal(value).compareTo(expected) == 0;
    }

    private static boolean sha(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private static boolean sameInstant(Instant left, Instant right) {
        return left != null && right != null
                && left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private static boolean containsForbiddenOutput(AgentOutput run) {
        List<String> values = new ArrayList<>();
        values.add(run.summary());
        run.findings().forEach(finding -> {
            values.add(finding.title());
            values.add(finding.detail());
        });
        run.errors().forEach(error -> values.add(error.message()));
        return values.stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> List.of(
                        "买入",
                        "卖出",
                        "加仓",
                        "减仓",
                        "目标价",
                        "收益承诺").stream().anyMatch(value::contains));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AgentResponseValidationException(
                    "智能体响应校验失败：" + message);
        }
    }

    private record ParsedContext(
            int tradeCount,
            int validSubperiodCount,
            int positiveSubperiodCount,
            BigDecimal totalReturn,
            BigDecimal maxDrawdown,
            BigDecimal winRate,
            BigDecimal profitLossRatio,
            Instant maximumKnownAt,
            String backtestResultHash
    ) {
    }

    private record SourceRevision(
            String sourceCode,
            String sourceRevision
    ) {
    }
}
