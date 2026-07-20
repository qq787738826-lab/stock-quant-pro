package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentModels.RunIds;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentStage2DResponseValidatorTest {

    private static final String RULE_VERSION = "1.4.0-stage-2d-market-regime-v1";
    private static final String HASH = AgentTestFixtures.HASH;
    private static final String DQ_EVIDENCE_ID = "dq-context-" + HASH;
    private static final String MR_EVIDENCE_ID = "mr-breadth-" + HASH;
    private static final Instant NOW = AgentTestFixtures.NOW;
    private static final LocalDate DATE = AgentTestFixtures.TRADE_DATE;
    private static final List<String> BREADTH_FIELDS = List.of(
            "available", "reasonCode", "sourceType", "sourceTables", "sourceStatus",
            "producer", "producerVersion", "versionAvailable", "requestedTradeDate",
            "effectiveTradeDate", "previousEffectiveTradeDate", "exactTradeDateMatch",
            "pointInTimeGuaranteed", "barFutureDataExcluded", "universePointInTimeGuaranteed",
            "futureDataExcluded", "timestampTimezoneSemantics", "adjustType", "selectionRule",
            "universeCount", "coveredSymbolCount", "comparableSymbolCount", "advancingCount",
            "decliningCount", "unchangedCount", "missingCurrentBarCount",
            "missingPreviousBarCount", "coverageRatio", "limitations");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AgentResponseValidator validator = new AgentResponseValidator();

    @Test
    void validPositiveMixedNegativeBlockedAndInsufficientResponsesAreAccepted() {
        assertAccepted(caseValue(3, 1, GateStatus.PASS));
        assertAccepted(caseValue(2, 2, GateStatus.WARN));
        assertAccepted(caseValue(1, 3, GateStatus.PASS));
        assertAccepted(blockedCase());

        ObjectNode lowCoverage = breadth(3, 1);
        lowCoverage.put("universeCount", 5);
        lowCoverage.put("coveredSymbolCount", 4);
        lowCoverage.put("comparableSymbolCount", 4);
        lowCoverage.put("missingCurrentBarCount", 1);
        lowCoverage.put("coverageRatio", new java.math.BigDecimal("0.80000000"));
        assertAccepted(insufficientCase(lowCoverage, "MARKET_BREADTH_LOW_COVERAGE"));
    }

    @Test
    void scoreConfidenceStatusDecisionGateAndVetoMismatchesAreRejected() {
        AgentTeamResponse source = caseValue(3, 1, GateStatus.PASS).response();
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), run.decision(), run.veto(),
                run.score() - 1, run.confidence(), run.findings(), run.evidence(), run.errors()));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), run.decision(), run.veto(),
                run.score(), 1, run.findings(), run.evidence(), run.errors()));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, RunStatus.INSUFFICIENT_DATA, run.gateStatus(), run.decision(), run.veto(),
                run.score(), run.confidence(), run.findings(), run.evidence(), run.errors()));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), GateStatus.BLOCKED, run.decision(), run.veto(),
                run.score(), run.confidence(), run.findings(), run.evidence(), run.errors()));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), RunDecision.PASS, run.veto(),
                run.score(), run.confidence(), run.findings(), run.evidence(), run.errors()));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), RunDecision.REJECT, true,
                run.score(), run.confidence(), run.findings(), run.evidence(), run.errors()));
    }

    @Test
    void unknownOutOfOrderOrWrongSeverityFindingsAreRejected() {
        AgentTeamResponse source = caseValue(3, 1, GateStatus.PASS).response();
        AgentOutput market = marketRegime(source);
        Finding pit = market.findings().get(0);
        Finding direction = market.findings().get(1);
        Finding unknown = new Finding(
                "mr-99-unknown-" + HASH, "UNKNOWN", Severity.INFO,
                "unknown", "unknown", List.of(MR_EVIDENCE_ID));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), run.decision(), false,
                run.score(), run.confidence(), List.of(pit, unknown), run.evidence(), run.errors()));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), run.decision(), false,
                run.score(), run.confidence(), List.of(direction, pit), run.evidence(), run.errors()));
        Finding wrongSeverity = new Finding(
                direction.findingId(), direction.code(), Severity.WARN,
                direction.title(), direction.detail(), direction.evidenceIds());
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), run.decision(), false,
                run.score(), run.confidence(), List.of(pit, wrongSeverity), run.evidence(), run.errors()));
    }

    @Test
    void evidenceMetadataHashWhitelistAndNoScanLeakAreEnforced() {
        AgentTeamResponse source = caseValue(3, 1, GateStatus.PASS).response();
        Evidence evidence = marketRegime(source).evidence().get(0);
        assertEvidenceRejected(source, copyEvidence(evidence, EvidenceSourceType.PYTHON_RULE_ENGINE,
                evidence.sourceName(), evidence.contentHash(), evidence.fields()));
        assertEvidenceRejected(source, copyEvidence(evidence, evidence.sourceType(),
                "other", evidence.contentHash(), evidence.fields()));
        assertEvidenceRejected(source, copyEvidence(evidence, evidence.sourceType(),
                evidence.sourceName(), "0".repeat(64), evidence.fields()));

        ObjectNode extra = ((ObjectNode) evidence.fields()).deepCopy();
        extra.set("scanResult", mapper.createObjectNode().put("sourceScanScore", 99));
        assertEvidenceRejected(source, copyEvidence(evidence, evidence.sourceType(),
                evidence.sourceName(), evidence.contentHash(), extra));

        ObjectNode missing = ((ObjectNode) evidence.fields()).deepCopy();
        ((ObjectNode) missing.get("marketBreadth")).remove("coverageRatio");
        assertEvidenceRejected(source, copyEvidence(evidence, evidence.sourceType(),
                evidence.sourceName(), evidence.contentHash(), missing));
    }

    @Test
    void conflictingEvidenceAndWrongTopLevelOrderAreRejected() {
        AgentTeamResponse source = caseValue(3, 1, GateStatus.PASS).response();
        Evidence evidence = marketRegime(source).evidence().get(0);
        Evidence conflict = copyEvidence(
                evidence, evidence.sourceType(), evidence.sourceName(), evidence.contentHash(),
                mapper.createObjectNode().putObject("marketBreadth").put("available", true));
        assertMarketRegimeRejected(source, run -> copyRun(
                run, run.status(), run.gateStatus(), run.decision(), false,
                run.score(), run.confidence(), run.findings(), List.of(conflict), run.errors()));

        List<Evidence> reversed = new ArrayList<>(source.evidence());
        java.util.Collections.reverse(reversed);
        assertRejected(copyResponse(source, source.agentRuns(), reversed, source.finalDecision()));
    }

    @Test
    void finalFindingRunOrderAndPrematureUpgradeAreRejected() {
        AgentTeamResponse source = caseValue(3, 1, GateStatus.PASS).response();
        FinalDecision decision = source.finalDecision();
        List<Finding> reversed = new ArrayList<>(decision.findings());
        java.util.Collections.reverse(reversed);
        assertRejected(copyResponse(source, source.agentRuns(), source.evidence(), copyDecision(
                decision, decision.decision(), decision.gateStatus(), decision.confidence(),
                reversed, decision.sourceRunIds())));

        List<Long> reversedIds = new ArrayList<>(decision.sourceRunIds());
        java.util.Collections.reverse(reversedIds);
        assertRejected(copyResponse(source, source.agentRuns(), source.evidence(), copyDecision(
                decision, decision.decision(), decision.gateStatus(), decision.confidence(),
                decision.findings(), reversedIds)));
        assertRejected(copyResponse(source, source.agentRuns(), source.evidence(), copyDecision(
                decision, FinalDecisionCode.WATCH, decision.gateStatus(), decision.confidence(),
                decision.findings(), decision.sourceRunIds())));
    }

    @Test
    void upgradingAnyOtherProfessionalRunIsRejected() {
        AgentTeamResponse source = caseValue(3, 1, GateStatus.PASS).response();
        List<AgentOutput> runs = new ArrayList<>(source.agentRuns());
        int index = AgentCode.PROFESSIONAL_AGENTS.indexOf(AgentCode.TECHNICAL_ANALYSIS);
        AgentOutput run = runs.get(index);
        runs.set(index, copyRun(
                run, RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, false,
                100, 100, List.of(), List.of(), List.of()));
        assertRejected(copyResponse(source, runs, source.evidence(), source.finalDecision()));
    }

    @Test
    void oldStage2BSharedFixturesRemainAccepted() throws IOException {
        for (String responseName : List.of(
                "stage-2b-pass-response.json",
                "stage-2b-warn-response.json",
                "stage-2b-blocked-response.json")) {
            AgentTeamRequest request = readFixture("stage-2b-valid-request.json", AgentTeamRequest.class);
            AgentTeamResponse response = readFixture(responseName, AgentTeamResponse.class);
            assertDoesNotThrow(() -> validator.validate(request, response));
        }
    }

    private void assertEvidenceRejected(AgentTeamResponse source, Evidence changed) {
        List<AgentOutput> runs = source.agentRuns().stream()
                .map(run -> run.agentCode() == AgentCode.MARKET_REGIME
                        ? copyRun(run, run.status(), run.gateStatus(), run.decision(), run.veto(),
                        run.score(), run.confidence(), run.findings(), List.of(changed), run.errors())
                        : run)
                .toList();
        List<Evidence> top = new ArrayList<>(source.evidence());
        top.set(1, changed);
        assertRejected(copyResponse(source, runs, top, source.finalDecision()));
    }

    private void assertMarketRegimeRejected(
            AgentTeamResponse source,
            UnaryOperator<AgentOutput> mutation
    ) {
        List<AgentOutput> runs = source.agentRuns().stream()
                .map(run -> run.agentCode() == AgentCode.MARKET_REGIME ? mutation.apply(run) : run)
                .toList();
        assertRejected(copyResponse(source, runs, source.evidence(), source.finalDecision()));
    }

    private void assertAccepted(Scenario scenario) {
        assertDoesNotThrow(() -> validator.validate(scenario.request(), scenario.response()));
    }

    private void assertRejected(AgentTeamResponse response) {
        validatorRejects(request(context(breadth(3, 1))), response);
    }

    private void validatorRejects(AgentTeamRequest request, AgentTeamResponse response) {
        assertThrows(AgentTeamException.class, () -> validator.validate(request, response));
    }

    private Scenario caseValue(int advancing, int declining, GateStatus dqGate) {
        ObjectNode breadth = breadth(advancing, declining);
        ObjectNode context = context(breadth);
        AgentTeamRequest request = request(context);
        Evidence dqEvidence = dqEvidence(context);
        Evidence mrEvidence = mrEvidence(context);
        List<Finding> mrFindings = List.of(
                finding("MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED", Severity.WARN),
                finding(directionCode(advancing, declining), Severity.INFO));
        int score = java.math.BigDecimal.valueOf(advancing - declining)
                .divide(java.math.BigDecimal.valueOf(4), 8, java.math.RoundingMode.HALF_UP)
                .add(java.math.BigDecimal.ONE)
                .multiply(java.math.BigDecimal.valueOf(50))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .intValueExact();
        AgentOutput dq = dqOutput(dqGate, dqEvidence);
        AgentOutput mr = marketOutput(
                RunStatus.COMPLETED, dqGate, RunDecision.WARN, score,
                "当前证券池市场宽度状态" + stateText(advancing, declining)
                        + "；证券池时点属性不可验证，confidence固定为0。",
                mrFindings, List.of(mrEvidence), List.of());
        return new Scenario(request, response(dq, mr, List.of(dqEvidence, mrEvidence)));
    }

    private Scenario blockedCase() {
        ObjectNode context = context(breadth(3, 1));
        AgentTeamRequest request = request(context);
        Evidence dqEvidence = dqEvidence(context);
        Finding dqFinding = new Finding(
                "finding-market-data-missing", "MARKET_DATA_MISSING", Severity.HIGH,
                "MARKET_DATA_MISSING", "MARKET_DATA_MISSING", List.of(DQ_EVIDENCE_ID));
        AgentOutput dq = dqOutput(
                RunStatus.COMPLETED, GateStatus.BLOCKED, RunDecision.REJECT,
                0, 100, List.of(dqFinding), List.of(dqEvidence), List.of());
        AgentOutput mr = marketOutput(
                RunStatus.INSUFFICIENT_DATA, GateStatus.NOT_APPLICABLE,
                RunDecision.NOT_APPLICABLE, 0,
                "DATA_QUALITY门禁已阻断，未执行当前证券池市场宽度状态规则。",
                List.of(), List.of(), List.of());
        return new Scenario(request, response(dq, mr, List.of(dqEvidence)));
    }

    private Scenario insufficientCase(ObjectNode breadth, String code) {
        ObjectNode context = context(breadth);
        AgentTeamRequest request = request(context);
        Evidence dqEvidence = dqEvidence(context);
        Evidence mrEvidence = mrEvidence(context);
        AgentOutput dq = dqOutput(GateStatus.PASS, dqEvidence);
        AgentOutput mr = marketOutput(
                RunStatus.INSUFFICIENT_DATA, GateStatus.PASS, RunDecision.NOT_APPLICABLE, 0,
                insufficientSummary(code),
                List.of(finding(code, "MARKET_BREADTH_FACT_INCONSISTENT".equals(code)
                        ? Severity.HIGH : Severity.WARN)),
                List.of(mrEvidence), List.of());
        return new Scenario(request, response(dq, mr, List.of(dqEvidence, mrEvidence)));
    }

    private AgentTeamResponse response(
            AgentOutput dataQuality,
            AgentOutput marketRegime,
            List<Evidence> topEvidence
    ) {
        List<AgentOutput> runs = new ArrayList<>();
        for (var run : AgentTestFixtures.runs(1)) {
            if (run.agentCode() == AgentCode.DATA_QUALITY) runs.add(dataQuality);
            else if (run.agentCode() == AgentCode.MARKET_REGIME) runs.add(marketRegime);
            else runs.add(new AgentOutput(
                    "1.0", 1, run.id(), run.agentCode(), RunStatus.INSUFFICIENT_DATA,
                    GateStatus.NOT_APPLICABLE, RunDecision.NOT_APPLICABLE, false,
                    0, 0, "规则尚未实现", List.of(), List.of(), List.of(), HASH,
                    RULE_VERSION, ExecutionMode.LOCAL_RULES, NOW));
        }
        List<Finding> finalFindings = new ArrayList<>(dataQuality.findings());
        if (dataQuality.gateStatus() != GateStatus.BLOCKED) {
            finalFindings.addAll(marketRegime.findings());
        }
        FinalDecision decision = new FinalDecision(
                "1.0", 1,
                dataQuality.gateStatus() == GateStatus.BLOCKED
                        ? FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                        : FinalDecisionCode.INSUFFICIENT_DATA,
                dataQuality.gateStatus(), false, 0,
                dataQuality.gateStatus() == GateStatus.BLOCKED ? dataQuality.confidence() : 0,
                dataQuality.gateStatus() == GateStatus.BLOCKED
                        ? "DATA_QUALITY规则发现阻断事实，总控已停止后续分析。"
                        : "DATA_QUALITY检查未阻断，当前证券池市场宽度状态规则已按受限边界执行；"
                        + "其余四个专业规则尚未实现，无法形成团队结论。",
                List.copyOf(finalFindings), runs.stream().map(AgentOutput::runId).toList(),
                List.of(), HASH, DATE, RULE_VERSION, ExecutionMode.LOCAL_RULES, NOW);
        return new AgentTeamResponse(
                "1.0", 1, HASH, DATE, RULE_VERSION, ExecutionMode.LOCAL_RULES,
                List.copyOf(runs), topEvidence, List.of(), decision, NOW);
    }

    private AgentOutput dqOutput(GateStatus gate, Evidence evidence) {
        List<Finding> findings = gate == GateStatus.WARN
                ? List.of(new Finding(
                "finding-request-date-not-exact", "REQUEST_DATE_NOT_EXACT", Severity.WARN,
                "REQUEST_DATE_NOT_EXACT", "REQUEST_DATE_NOT_EXACT", List.of(DQ_EVIDENCE_ID)))
                : List.of();
        return dqOutput(
                RunStatus.COMPLETED, gate,
                gate == GateStatus.PASS ? RunDecision.PASS : RunDecision.WARN,
                gate == GateStatus.PASS ? 100 : 50, 100,
                findings, List.of(evidence), List.of());
    }

    private AgentOutput dqOutput(
            RunStatus status,
            GateStatus gate,
            RunDecision decision,
            int score,
            int confidence,
            List<Finding> findings,
            List<Evidence> evidence,
            List<com.stockquant.server.agent.model.AgentModels.AgentError> errors
    ) {
        return new AgentOutput(
                "1.0", 1, 11, AgentCode.DATA_QUALITY, status, gate, decision, false,
                score, confidence, "DATA_QUALITY结果", findings, evidence, errors, HASH,
                RULE_VERSION, ExecutionMode.LOCAL_RULES, NOW);
    }

    private AgentOutput marketOutput(
            RunStatus status,
            GateStatus gate,
            RunDecision decision,
            int score,
            String summary,
            List<Finding> findings,
            List<Evidence> evidence,
            List<com.stockquant.server.agent.model.AgentModels.AgentError> errors
    ) {
        return new AgentOutput(
                "1.0", 1, 12, AgentCode.MARKET_REGIME, status, gate, decision, false,
                score, 0, summary, findings, evidence, errors, HASH,
                RULE_VERSION, ExecutionMode.LOCAL_RULES, NOW);
    }

    private AgentTeamRequest request(ObjectNode context) {
        return new AgentTeamRequest(
                "1.0", 1, RunIds.from(AgentTestFixtures.runs(1)), "600000", DATE, HASH,
                "1.0", RULE_VERSION, ExecutionMode.LOCAL_RULES, context, NOW);
    }

    private ObjectNode context(ObjectNode breadth) {
        ObjectNode context = mapper.createObjectNode();
        context.putObject("security").put("available", true);
        context.putObject("marketData").put("available", true);
        context.putObject("technicalMetrics").put("available", true);
        context.putObject("dataQualityContext").put("available", true).put("queriedAt", NOW.toString());
        context.set("marketBreadth", breadth);
        context.putObject("scanResult").put("available", false);
        context.putObject("backtestContext").put("available", false);
        context.putObject("securityEvents").put("available", false);
        context.putObject("portfolioContext").put("available", false);
        return context;
    }

    private ObjectNode breadth(int advancing, int declining) {
        int comparable = 4;
        ObjectNode value = mapper.createObjectNode();
        value.put("available", true);
        value.put("queriedAt", NOW.toString());
        value.putObject("queryScope").put("symbol", "600000").put("tradeDate", DATE.toString());
        value.putNull("reasonCode");
        value.putNull("reason");
        value.put("sourceType", "DATABASE");
        value.putArray("sourceTables").add("daily_bars").add("securities");
        value.put("sourceStatus", "AVAILABLE");
        value.put("producer", "AgentMarketBreadthContextService");
        value.put("producerVersion", "MARKET_BREADTH_V1");
        value.put("versionAvailable", true);
        value.put("requestedTradeDate", DATE.toString());
        value.put("effectiveTradeDate", DATE.toString());
        value.put("previousEffectiveTradeDate", DATE.minusDays(1).toString());
        value.put("exactTradeDateMatch", true);
        value.put("pointInTimeGuaranteed", false);
        value.put("barFutureDataExcluded", true);
        value.put("universePointInTimeGuaranteed", false);
        value.put("futureDataExcluded", false);
        value.put("timestampTimezoneSemantics", "TRADE_DATES_ARE_LOCAL_DATE_QUERIED_AT_IS_UTC_INSTANT");
        value.put("adjustType", "QFQ");
        value.put("selectionRule", "CURRENT_MAIN_ACTIVE_NON_ST_UNIVERSE_UNIFIED_EFFECTIVE_DATE");
        value.put("universeCount", comparable);
        value.put("coveredSymbolCount", comparable);
        value.put("comparableSymbolCount", comparable);
        value.put("advancingCount", advancing);
        value.put("decliningCount", declining);
        value.put("unchangedCount", comparable - advancing - declining);
        value.put("missingCurrentBarCount", 0);
        value.put("missingPreviousBarCount", 0);
        value.put("coverageRatio", new java.math.BigDecimal("1.00000000"));
        value.putArray("limitations").add("CURRENT_SECURITIES_ATTRIBUTES_ARE_NOT_HISTORICALLY_VERSIONED");
        return value;
    }

    private Evidence dqEvidence(ObjectNode context) {
        ObjectNode fields = mapper.createObjectNode();
        for (String name : List.of("security", "marketData", "technicalMetrics", "dataQualityContext")) {
            fields.set(name, context.get(name).deepCopy());
        }
        return new Evidence(
                DQ_EVIDENCE_ID, EvidenceCategory.DATA_QUALITY, EvidenceSourceType.JAVA_ENGINE,
                "AgentContextSnapshotService", "contextSnapshot", "600000", DATE,
                NOW, NOW, fields, HASH);
    }

    private Evidence mrEvidence(ObjectNode context) {
        ObjectNode projected = mapper.createObjectNode();
        ObjectNode source = (ObjectNode) context.get("marketBreadth");
        for (String name : BREADTH_FIELDS) projected.set(name, source.get(name).deepCopy());
        ObjectNode fields = mapper.createObjectNode();
        fields.set("marketBreadth", projected);
        return new Evidence(
                MR_EVIDENCE_ID, EvidenceCategory.MARKET_BREADTH, EvidenceSourceType.JAVA_ENGINE,
                "AgentMarketBreadthContextService", "contextSnapshot.marketBreadth", "600000", DATE,
                NOW, NOW, fields, HASH);
    }

    private Finding finding(String code, Severity severity) {
        int rank = switch (code) {
            case "MARKET_BREADTH_FACT_INCONSISTENT" -> 1;
            case "MARKET_BREADTH_UNAVAILABLE" -> 2;
            case "MARKET_BREADTH_LOW_COVERAGE" -> 3;
            case "MARKET_BREADTH_DATE_NOT_EXACT" -> 4;
            case "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED" -> 5;
            case "MARKET_BREADTH_POSITIVE" -> 6;
            case "MARKET_BREADTH_MIXED" -> 7;
            case "MARKET_BREADTH_NEGATIVE" -> 8;
            default -> throw new IllegalArgumentException(code);
        };
        return new Finding(
                "mr-%02d-%s-%s".formatted(rank, code.toLowerCase().replace('_', '-'), HASH),
                code, severity, title(code), detail(code), List.of(MR_EVIDENCE_ID));
    }

    private static String title(String code) {
        return switch (code) {
            case "MARKET_BREADTH_FACT_INCONSISTENT" -> "当前证券池宽度事实不一致";
            case "MARKET_BREADTH_UNAVAILABLE" -> "当前证券池宽度事实不可用";
            case "MARKET_BREADTH_LOW_COVERAGE" -> "当前证券池宽度覆盖不足";
            case "MARKET_BREADTH_DATE_NOT_EXACT" -> "当前证券池宽度日期未精确命中";
            case "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED" -> "当前证券池宽度时点属性不可验证";
            case "MARKET_BREADTH_POSITIVE" -> "当前证券池宽度偏正向";
            case "MARKET_BREADTH_MIXED" -> "当前证券池宽度混合";
            case "MARKET_BREADTH_NEGATIVE" -> "当前证券池宽度偏负向";
            default -> throw new IllegalArgumentException(code);
        };
    }

    private static String detail(String code) {
        return switch (code) {
            case "MARKET_BREADTH_FACT_INCONSISTENT" -> "市场宽度来源、版本、日期、计数或比例事实无法互相验证。";
            case "MARKET_BREADTH_UNAVAILABLE" -> "本地只读市场宽度上下文未提供可比较事实，未形成宽度方向。";
            case "MARKET_BREADTH_LOW_COVERAGE" -> "当前证券池宽度覆盖率未达到1.00000000或可比较证券少于2只，未形成宽度方向。";
            case "MARKET_BREADTH_DATE_NOT_EXACT" -> "有效交易日未精确匹配请求交易日，未形成宽度方向。";
            case "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED" -> "当前证券池不是历史版本，结果仅描述冻结请求时的当前证券池宽度状态。";
            case "MARKET_BREADTH_POSITIVE" -> "可比较证券中上涨数量多于下跌数量，仅描述冻结的当前证券池宽度事实。";
            case "MARKET_BREADTH_MIXED" -> "可比较证券中上涨数量等于下跌数量，仅描述冻结的当前证券池宽度事实。";
            case "MARKET_BREADTH_NEGATIVE" -> "可比较证券中下跌数量多于上涨数量，仅描述冻结的当前证券池宽度事实。";
            default -> throw new IllegalArgumentException(code);
        };
    }

    private static String insufficientSummary(String code) {
        return switch (code) {
            case "MARKET_BREADTH_FACT_INCONSISTENT" -> "当前证券池市场宽度事实不一致，未形成宽度方向。";
            case "MARKET_BREADTH_UNAVAILABLE" -> "当前证券池市场宽度事实不可用，未形成宽度方向。";
            case "MARKET_BREADTH_LOW_COVERAGE" -> "当前证券池市场宽度覆盖不足，未形成宽度方向。";
            case "MARKET_BREADTH_DATE_NOT_EXACT" -> "当前证券池市场宽度日期未精确命中，未形成宽度方向。";
            case "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED" ->
                    "请求日期不是冻结请求时的上海自然日，历史或未来日期未形成宽度方向。";
            default -> throw new IllegalArgumentException(code);
        };
    }

    private static String directionCode(int advancing, int declining) {
        if (advancing > declining) return "MARKET_BREADTH_POSITIVE";
        if (advancing < declining) return "MARKET_BREADTH_NEGATIVE";
        return "MARKET_BREADTH_MIXED";
    }

    private static String stateText(int advancing, int declining) {
        if (advancing > declining) return "偏正向";
        if (advancing < declining) return "偏负向";
        return "混合";
    }

    private <T> T readFixture(String name, Class<T> type) throws IOException {
        try (var input = getClass().getResourceAsStream("/agent-team-contract/" + name)) {
            if (input == null) throw new IOException("fixture不存在：" + name);
            return mapper.readValue(input, type);
        }
    }

    private static AgentOutput marketRegime(AgentTeamResponse response) {
        return response.agentRuns().stream()
                .filter(run -> run.agentCode() == AgentCode.MARKET_REGIME)
                .findFirst().orElseThrow();
    }

    private static AgentOutput copyRun(
            AgentOutput source,
            RunStatus status,
            GateStatus gate,
            RunDecision decision,
            boolean veto,
            int score,
            int confidence,
            List<Finding> findings,
            List<Evidence> evidence,
            List<com.stockquant.server.agent.model.AgentModels.AgentError> errors
    ) {
        return new AgentOutput(
                source.schemaVersion(), source.taskId(), source.runId(), source.agentCode(), status,
                gate, decision, veto, score, confidence, source.summary(), findings, evidence, errors,
                source.contextHash(), source.ruleVersion(), source.executionMode(), source.generatedAt());
    }

    private static Evidence copyEvidence(
            Evidence source,
            EvidenceSourceType sourceType,
            String sourceName,
            String hash,
            JsonNode fields
    ) {
        return new Evidence(
                source.evidenceId(), source.category(), sourceType, sourceName, source.sourceRef(),
                source.symbol(), source.tradeDate(), source.observedAt(), source.collectedAt(), fields, hash);
    }

    private static FinalDecision copyDecision(
            FinalDecision source,
            FinalDecisionCode code,
            GateStatus gate,
            int confidence,
            List<Finding> findings,
            List<Long> sourceRunIds
    ) {
        return new FinalDecision(
                source.schemaVersion(), source.taskId(), code, gate, source.vetoed(), source.score(),
                confidence, source.summary(), findings, sourceRunIds, source.vetoIds(),
                source.contextHash(), source.tradeDate(), source.ruleVersion(), source.executionMode(),
                source.generatedAt());
    }

    private static AgentTeamResponse copyResponse(
            AgentTeamResponse source,
            List<AgentOutput> runs,
            List<Evidence> evidence,
            FinalDecision decision
    ) {
        return new AgentTeamResponse(
                source.schemaVersion(), source.taskId(), source.contextHash(), source.tradeDate(),
                source.ruleVersion(), source.executionMode(), runs, evidence, source.vetoes(),
                decision, source.generatedAt());
    }

    private record Scenario(AgentTeamRequest request, AgentTeamResponse response) {}
}
