package com.stockquant.server.agent.chief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.chief.AgentChiefDecisionRules.Evaluation;
import com.stockquant.server.agent.model.AgentModels.AgentError;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentChiefDecisionRulesTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final AgentChiefDecisionRules RULES =
            new AgentChiefDecisionRules();
    private static final Instant NOW =
            Instant.parse("2026-07-25T08:00:00Z");
    private static final LocalDate TRADE_DATE =
            LocalDate.of(2026, 7, 25);
    private static final String HASH = "9".repeat(64);

    @Test
    void sharedGoldenVectorsFreezeWeightsRoundingAndDecisions()
            throws Exception {
        JsonNode fixture = fixture();
        assertEquals(
                ChiefDecisionContracts.CONTRACT_VERSION,
                fixture.path("contractVersion").asText());
        assertEquals(
                ChiefDecisionContracts.WEIGHT_CONTRACT_VERSION,
                fixture.path("weightContractVersion").asText());
        ChiefDecisionContracts.WEIGHTS.forEach((code, weight) ->
                assertEquals(
                        weight.intValue(),
                        fixture.path("weights").path(code.name()).asInt()));
        assertEquals(
                100,
                ChiefDecisionContracts.WEIGHTS.values().stream()
                        .mapToInt(Integer::intValue)
                        .sum());

        for (JsonNode vector : fixture.withArray("vectors")) {
            List<AgentOutput> runs = runs(vector);
            List<FormalVeto> vetoes = vetoes(vector);
            Evaluation actual = RULES.evaluate(runs, vetoes);
            JsonNode expected = vector.path("expected");
            assertEquals(
                    expected.path("decision").asText(),
                    actual.decision().name(),
                    vector.path("name").asText());
            assertEquals(
                    expected.path("gateStatus").asText(),
                    actual.gateStatus().name(),
                    vector.path("name").asText());
            assertEquals(
                    expected.path("vetoed").asBoolean(),
                    actual.vetoed(),
                    vector.path("name").asText());
            assertEquals(expected.path("score").asInt(), actual.score());
            assertEquals(
                    expected.path("confidence").asInt(),
                    actual.confidence());
            assertEquals(expected.path("summary").asText(), actual.summary());
            assertNullableInteger(
                    expected.path("weightedScoreSum"),
                    actual.weightedScoreSum());
            assertNullableInteger(
                    expected.path("weightedConfidenceSum"),
                    actual.weightedConfidenceSum());
            assertEquals(
                    expected.path("highestRiskSeverity").isNull()
                            ? null
                            : expected.path("highestRiskSeverity").asText(),
                    actual.highestRiskSeverity() == null
                            ? null
                            : actual.highestRiskSeverity().name());
        }
    }

    @Test
    void marketRegimeHasZeroWeightButMustRemainAvailable()
            throws Exception {
        List<AgentOutput> runs = passRuns();
        Evaluation baseline = RULES.evaluate(runs, List.of());
        replace(runs, AgentCode.MARKET_REGIME, run ->
                withScoreConfidence(run, 100, 0));
        assertEquals(baseline, RULES.evaluate(runs, List.of()));

        replace(runs, AgentCode.MARKET_REGIME, run -> withState(
                run,
                RunStatus.INSUFFICIENT_DATA,
                GateStatus.NOT_APPLICABLE,
                RunDecision.NOT_APPLICABLE,
                0,
                0,
                false,
                List.of(),
                List.of(),
                List.of(new AgentError("UNAVAILABLE", "fixture"))));
        assertEquals(
                FinalDecisionCode.INSUFFICIENT_DATA,
                RULES.evaluate(runs, List.of()).decision());
    }

    @Test
    void scoreBoundariesAre49_50_69And70() throws Exception {
        assertScoreCase(
                new int[]{40, 40, 50, 75},
                49,
                FinalDecisionCode.RESEARCH_ONLY);
        assertScoreCase(
                new int[]{50, 50, 50, 50},
                50,
                FinalDecisionCode.WATCH);
        assertScoreCase(
                new int[]{60, 60, 100, 65},
                69,
                FinalDecisionCode.WATCH);
        assertScoreCase(
                new int[]{70, 70, 70, 70},
                70,
                FinalDecisionCode.PASS_TO_MANUAL_REVIEW);

        List<AgentOutput> lowTechnical = passRuns();
        replace(lowTechnical, AgentCode.TECHNICAL_ANALYSIS,
                run -> withScoreConfidence(run, 59, run.confidence()));
        assertEquals(
                FinalDecisionCode.WATCH,
                RULES.evaluate(lowTechnical, List.of()).decision());
    }

    @Test
    void confidenceBoundariesAre39_40_59And60() throws Exception {
        assertConfidenceCase(
                new int[]{40, 40, 40, 35},
                39,
                FinalDecisionCode.RESEARCH_ONLY);
        assertConfidenceCase(
                new int[]{40, 40, 40, 40},
                40,
                FinalDecisionCode.WATCH);
        assertConfidenceCase(
                new int[]{60, 60, 40, 75},
                59,
                FinalDecisionCode.WATCH);
        assertConfidenceCase(
                new int[]{60, 60, 40, 80},
                60,
                FinalDecisionCode.PASS_TO_MANUAL_REVIEW);
    }

    @Test
    void riskSeverityInfoWarnHighAndCriticalHasFrozenClassification()
            throws Exception {
        Map<Severity, FinalDecisionCode> expected = Map.of(
                Severity.INFO, FinalDecisionCode.PASS_TO_MANUAL_REVIEW,
                Severity.WARN, FinalDecisionCode.WATCH,
                Severity.HIGH, FinalDecisionCode.RESEARCH_ONLY,
                Severity.CRITICAL, FinalDecisionCode.RESEARCH_ONLY);
        for (Map.Entry<Severity, FinalDecisionCode> entry
                : expected.entrySet()) {
            List<AgentOutput> runs = passRuns();
            replace(runs, AgentCode.ANNOUNCEMENT_RISK, run -> {
                AgentOutput changed = withFindingSeverity(
                        run, entry.getKey());
                return withState(
                        changed,
                        changed.status(),
                        entry.getKey() == Severity.INFO
                                ? GateStatus.PASS : GateStatus.WARN,
                        entry.getKey() == Severity.INFO
                                ? RunDecision.PASS : RunDecision.WARN,
                        changed.score(),
                        changed.confidence(),
                        false,
                        changed.findings(),
                        changed.evidence(),
                        changed.errors());
            });
            Evaluation actual = RULES.evaluate(runs, List.of());
            assertEquals(entry.getKey(), actual.highestRiskSeverity());
            assertEquals(entry.getValue(), actual.decision());
        }
    }

    @Test
    void dqWarnAndPositionPartialCapConfidenceAt50()
            throws Exception {
        List<AgentOutput> warned = passRuns();
        replace(warned, AgentCode.DATA_QUALITY, run -> withState(
                run,
                run.status(),
                GateStatus.WARN,
                RunDecision.WARN,
                run.score(),
                run.confidence(),
                false,
                run.findings(),
                run.evidence(),
                run.errors()));
        Evaluation dq = RULES.evaluate(warned, List.of());
        assertEquals(50, dq.confidence());
        assertEquals(FinalDecisionCode.RESEARCH_ONLY, dq.decision());

        List<AgentOutput> partial = passRuns();
        replace(partial, AgentCode.POSITION_RISK, run -> withState(
                run,
                RunStatus.PARTIAL,
                GateStatus.WARN,
                RunDecision.WARN,
                run.score(),
                run.confidence(),
                false,
                run.findings(),
                run.evidence(),
                run.errors()));
        Evaluation position = RULES.evaluate(partial, List.of());
        assertEquals(50, position.confidence());
        assertEquals(
                FinalDecisionCode.RESEARCH_ONLY,
                position.decision());
    }

    @Test
    void everyRequiredRunCanForceInsufficientData() throws Exception {
        for (AgentCode code : List.of(
                AgentCode.MARKET_REGIME,
                AgentCode.TECHNICAL_ANALYSIS,
                AgentCode.STRATEGY_BACKTEST,
                AgentCode.ANNOUNCEMENT_RISK,
                AgentCode.POSITION_RISK)) {
            List<AgentOutput> runs = passRuns();
            replace(runs, code, run -> withState(
                    run,
                    RunStatus.INSUFFICIENT_DATA,
                    GateStatus.NOT_APPLICABLE,
                    RunDecision.NOT_APPLICABLE,
                    0,
                    0,
                    false,
                    List.of(),
                    List.of(),
                    List.of(new AgentError("UNAVAILABLE", "fixture"))));
            Evaluation actual = RULES.evaluate(runs, List.of());
            assertEquals(
                    FinalDecisionCode.INSUFFICIENT_DATA,
                    actual.decision(),
                    code.name());
            assertEquals(0, actual.score());
            assertEquals(0, actual.confidence());
        }
    }

    @Test
    void positionVetoPrecedesDqBlockAndInsufficientRuns()
            throws Exception {
        JsonNode vector = fixture().withArray("vectors").get(5);
        Evaluation actual = RULES.evaluate(
                runs(vector),
                vetoes(vector));
        assertEquals(
                FinalDecisionCode.REJECTED_BY_VETO,
                actual.decision());
        assertEquals(List.of("position-risk-veto-01"), actual.vetoIds());
        assertEquals(80, actual.confidence());
    }

    @Test
    void fixedRunOrderIsMandatory() throws Exception {
        List<AgentOutput> reversed = new ArrayList<>(passRuns());
        java.util.Collections.reverse(reversed);
        assertThrows(
                IllegalArgumentException.class,
                () -> RULES.evaluate(reversed, List.of()));
    }

    @Test
    void summaryIsFixedAndContainsNoExecutionLanguage()
            throws Exception {
        String summary = RULES.evaluate(passRuns(), List.of()).summary();
        assertTrue(summary.contains("MARKET_REGIME V1"));
        assertTrue(summary.contains("research or manual review only"));
        for (String forbidden : List.of(
                "立即买入", "立即卖出", "自动下单", "清仓", "加仓", "减仓",
                "保证收益", "必涨", "必跌")) {
            assertTrue(!summary.contains(forbidden), forbidden);
        }
    }

    private static void assertScoreCase(
            int[] values,
            int score,
            FinalDecisionCode decision
    ) throws Exception {
        List<AgentOutput> runs = passRuns();
        for (int index = 0;
             index < ChiefDecisionContracts.CONTRIBUTOR_ORDER.size();
             index++) {
            AgentCode code =
                    ChiefDecisionContracts.CONTRIBUTOR_ORDER.get(index);
            int value = values[index];
            replace(runs, code,
                    run -> withScoreConfidence(
                            run, value, run.confidence()));
        }
        Evaluation actual = RULES.evaluate(runs, List.of());
        assertEquals(score, actual.score());
        assertEquals(decision, actual.decision());
    }

    private static void assertConfidenceCase(
            int[] values,
            int confidence,
            FinalDecisionCode decision
    ) throws Exception {
        List<AgentOutput> runs = passRuns();
        for (int index = 0;
             index < ChiefDecisionContracts.CONTRIBUTOR_ORDER.size();
             index++) {
            AgentCode code =
                    ChiefDecisionContracts.CONTRIBUTOR_ORDER.get(index);
            int value = values[index];
            replace(runs, code,
                    run -> withScoreConfidence(
                            run, run.score(), value));
        }
        Evaluation actual = RULES.evaluate(runs, List.of());
        assertEquals(confidence, actual.confidence());
        assertEquals(decision, actual.decision());
    }

    private static List<AgentOutput> passRuns() throws Exception {
        return runs(fixture().withArray("vectors").get(0));
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream input = AgentChiefDecisionRulesTest.class
                .getResourceAsStream(
                        "/agent/chief-decision-v1-vectors.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "missing chief decision vectors");
            }
            return MAPPER.readTree(input);
        }
    }

    private static List<AgentOutput> runs(JsonNode vector) {
        List<AgentOutput> values = new ArrayList<>();
        int index = 0;
        for (JsonNode raw : vector.withArray("runs")) {
            AgentCode code = AgentCode.valueOf(
                    raw.path("agentCode").asText());
            RunStatus status = RunStatus.valueOf(
                    raw.path("status").asText());
            String evidenceId = "chief-vector-evidence-" + index;
            List<Evidence> evidence = List.of();
            List<Finding> findings = List.of();
            List<AgentError> errors = List.of();
            if (status == RunStatus.COMPLETED
                    || status == RunStatus.PARTIAL) {
                ObjectNode fields = MAPPER.createObjectNode();
                fields.put("fixture", code.name());
                evidence = List.of(new Evidence(
                        evidenceId,
                        EvidenceCategory.QUERY_RESULT,
                        EvidenceSourceType.JAVA_ENGINE,
                        "ChiefDecisionVectorFixture",
                        "agentRuns[" + index + "]",
                        "600000",
                        TRADE_DATE,
                        NOW,
                        NOW,
                        fields,
                        HASH));
                findings = List.of(new Finding(
                        "chief-vector-finding-" + index,
                        findingCode(code),
                        Severity.valueOf(
                                raw.path("riskSeverity").asText()),
                        code.name() + " vector finding",
                        "fixed cross-language golden vector",
                        List.of(evidenceId)));
            } else if (status == RunStatus.INSUFFICIENT_DATA) {
                errors = List.of(new AgentError(
                        code.name() + "_UNAVAILABLE",
                        "fixture"));
            }
            values.add(new AgentOutput(
                    "1.0",
                    77,
                    101 + index,
                    code,
                    status,
                    GateStatus.valueOf(
                            raw.path("gateStatus").asText()),
                    RunDecision.valueOf(
                            raw.path("decision").asText()),
                    raw.path("veto").asBoolean(),
                    raw.path("score").asInt(),
                    raw.path("confidence").asInt(),
                    code.name() + " fixed vector",
                    findings,
                    evidence,
                    errors,
                    HASH,
                    ChiefDecisionContracts.RULE_VERSION,
                    ExecutionMode.LOCAL_RULES,
                    NOW));
            index++;
        }
        return values;
    }

    private static List<FormalVeto> vetoes(JsonNode vector) {
        List<FormalVeto> values = new ArrayList<>();
        for (JsonNode item : vector.withArray("vetoIds")) {
            values.add(new FormalVeto(
                    item.asText(),
                    77,
                    106,
                    AgentCode.POSITION_RISK,
                    "POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
                    "fixed vector veto",
                    List.of("chief-vector-evidence-5"),
                    NOW));
        }
        return values;
    }

    private static String findingCode(AgentCode code) {
        return switch (code) {
            case ANNOUNCEMENT_RISK ->
                    "ANNOUNCEMENT_REGULATORY_DELISTING_ASSESSED";
            case POSITION_RISK ->
                    "POSITION_RISK_ACCOUNT_LOSS_ASSESSED";
            default -> code.name() + "_TEST_FINDING";
        };
    }

    private static AgentOutput withScoreConfidence(
            AgentOutput source,
            int score,
            int confidence
    ) {
        return withState(
                source,
                source.status(),
                source.gateStatus(),
                source.decision(),
                score,
                confidence,
                source.veto(),
                source.findings(),
                source.evidence(),
                source.errors());
    }

    private static AgentOutput withFindingSeverity(
            AgentOutput source,
            Severity severity
    ) {
        Finding finding = source.findings().get(0);
        Finding changed = new Finding(
                finding.findingId(),
                finding.code(),
                severity,
                finding.title(),
                finding.detail(),
                finding.evidenceIds());
        return withState(
                source,
                source.status(),
                source.gateStatus(),
                source.decision(),
                source.score(),
                source.confidence(),
                source.veto(),
                List.of(changed),
                source.evidence(),
                source.errors());
    }

    private static AgentOutput withState(
            AgentOutput source,
            RunStatus status,
            GateStatus gateStatus,
            RunDecision decision,
            int score,
            int confidence,
            boolean veto,
            List<Finding> findings,
            List<Evidence> evidence,
            List<AgentError> errors
    ) {
        return new AgentOutput(
                source.schemaVersion(),
                source.taskId(),
                source.runId(),
                source.agentCode(),
                status,
                gateStatus,
                decision,
                veto,
                score,
                confidence,
                source.summary(),
                findings,
                evidence,
                errors,
                source.contextHash(),
                source.ruleVersion(),
                source.executionMode(),
                source.generatedAt());
    }

    private static void replace(
            List<AgentOutput> runs,
            AgentCode code,
            java.util.function.UnaryOperator<AgentOutput> operation
    ) {
        int index = AgentCode.PROFESSIONAL_AGENTS.indexOf(code);
        runs.set(index, operation.apply(runs.get(index)));
    }

    private static void assertNullableInteger(
            JsonNode expected,
            Integer actual
    ) {
        assertEquals(expected.isNull() ? null : expected.asInt(), actual);
    }
}
