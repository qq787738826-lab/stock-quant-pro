package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.AgentError;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentStage2BResponseValidatorTest {

    private static final String RULE_VERSION = "1.4.0-stage-2b-dq-v1";
    private static final String EVIDENCE_ID = "dq-context-" + AgentTestFixtures.HASH;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AgentResponseValidator validator = new AgentResponseValidator();

    @Test
    void validPassWarnBlockAndInvalidMappingsAreAccepted() {
        assertDoesNotThrow(() -> validate(response(
                RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, 100, 100, List.of(), true)));
        assertDoesNotThrow(() -> validate(response(
                RunStatus.COMPLETED, GateStatus.WARN, RunDecision.WARN, 50, 100,
                List.of(finding("REQUEST_DATE_NOT_EXACT", Severity.WARN)), true)));
        assertDoesNotThrow(() -> validate(response(
                RunStatus.COMPLETED, GateStatus.BLOCKED, RunDecision.REJECT, 0, 100,
                List.of(finding("MARKET_DATA_MISSING", Severity.HIGH)), true)));
        assertDoesNotThrow(() -> validate(response(
                RunStatus.INSUFFICIENT_DATA, GateStatus.BLOCKED, RunDecision.REJECT, 0, 0,
                List.of(), false)));
    }

    @Test
    void infoCapabilityAndSecurityScopeFactsDoNotChangePassGate() {
        AgentTeamResponse value = response(
                RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, 100, 100,
                List.of(
                        finding("TRADING_CALENDAR_UNAVAILABLE", Severity.INFO),
                        finding("SOURCE_CONSISTENCY_UNASSESSABLE", Severity.INFO),
                        finding("SECURITY_SCOPE_FACT", Severity.INFO)
                ), true);
        assertDoesNotThrow(() -> validate(value));
    }

    @Test
    void completedMappingMismatchIsRejected() {
        assertRejected(response(
                RunStatus.COMPLETED, GateStatus.WARN, RunDecision.PASS, 100, 100,
                List.of(finding("REQUEST_DATE_NOT_EXACT", Severity.WARN)), true));
    }

    @Test
    void wrongEvidenceSourceOrExtraProjectedFieldIsRejected() {
        AgentTeamResponse source = response(
                RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, 100, 100, List.of(), true);
        Evidence evidence = source.evidence().get(0);
        Evidence wrongSource = new Evidence(
                evidence.evidenceId(), evidence.category(), EvidenceSourceType.PYTHON_RULE_ENGINE,
                evidence.sourceName(), evidence.sourceRef(), evidence.symbol(), evidence.tradeDate(),
                evidence.observedAt(), evidence.collectedAt(), evidence.fields(), evidence.contentHash());
        assertRejected(withEvidence(source, wrongSource));

        ObjectNode extraFields = ((ObjectNode) evidence.fields()).deepCopy();
        extraFields.putObject("marketBreadth").put("available", false);
        Evidence extra = new Evidence(
                evidence.evidenceId(), evidence.category(), evidence.sourceType(), evidence.sourceName(),
                evidence.sourceRef(), evidence.symbol(), evidence.tradeDate(), evidence.observedAt(),
                evidence.collectedAt(), extraFields, evidence.contentHash());
        assertRejected(withEvidence(source, extra));
    }

    @Test
    void conclusionFieldInsideDirectProjectionIsRejected() {
        AgentTeamResponse source = response(
                RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, 100, 100, List.of(), true);
        ObjectNode contaminatedContext = context();
        ((ObjectNode) contaminatedContext.get("dataQualityContext")).put("score", 100);
        AgentTeamRequest contaminatedRequest = request(contaminatedContext);

        Evidence evidence = source.evidence().get(0);
        ObjectNode fields = ((ObjectNode) evidence.fields()).deepCopy();
        ((ObjectNode) fields.get("dataQualityContext")).put("score", 100);
        Evidence contaminatedEvidence = new Evidence(
                evidence.evidenceId(), evidence.category(), evidence.sourceType(), evidence.sourceName(),
                evidence.sourceRef(), evidence.symbol(), evidence.tradeDate(), evidence.observedAt(),
                evidence.collectedAt(), fields, evidence.contentHash());
        AgentTeamResponse contaminatedResponse = withEvidence(source, contaminatedEvidence);

        assertThrows(AgentTeamException.class,
                () -> validator.validate(contaminatedRequest, contaminatedResponse));
    }

    @Test
    void duplicateOutOfOrderOrWrongSeverityFindingIsRejected() {
        assertRejected(response(
                RunStatus.COMPLETED, GateStatus.BLOCKED, RunDecision.REJECT, 0, 100,
                List.of(
                        finding("MARKET_DATA_MISSING", Severity.HIGH),
                        finding("MARKET_DATA_MISSING", Severity.HIGH)
                ), true));
        assertRejected(response(
                RunStatus.COMPLETED, GateStatus.BLOCKED, RunDecision.REJECT, 0, 100,
                List.of(
                        finding("MARKET_DATA_MISSING", Severity.HIGH),
                        finding("SECURITY_RECORD_MISSING", Severity.HIGH)
                ), true));
        assertRejected(response(
                RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, 100, 100,
                List.of(finding("SECURITY_SCOPE_FACT", Severity.WARN)), true));
    }

    @Test
    void passGateCannotClaimDataQualityBlockedOrReturnBlockedFinalDecision() {
        AgentTeamResponse source = response(
                RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS, 100, 100, List.of(), true);
        FinalDecision old = source.finalDecision();
        FinalDecision invalid = new FinalDecision(
                old.schemaVersion(), old.taskId(), FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                GateStatus.BLOCKED, old.vetoed(), old.score(), old.confidence(),
                "数据质量门禁阻断", old.findings(), old.sourceRunIds(), old.vetoIds(),
                old.contextHash(), old.tradeDate(), old.ruleVersion(), old.executionMode(), old.generatedAt());
        assertRejected(new AgentTeamResponse(
                source.schemaVersion(), source.taskId(), source.contextHash(), source.tradeDate(),
                source.ruleVersion(), source.executionMode(), source.agentRuns(), source.evidence(),
                source.vetoes(), invalid, source.generatedAt()));
    }

    private void validate(AgentTeamResponse response) {
        validator.validate(request(), response);
    }

    private void assertRejected(AgentTeamResponse response) {
        assertThrows(AgentTeamException.class, () -> validate(response));
    }

    private AgentTeamRequest request() {
        return request(context());
    }

    private AgentTeamRequest request(ObjectNode value) {
        return new AgentTeamRequest(
                "1.0", 1, RunIds.from(AgentTestFixtures.runs(1)), "600000",
                AgentTestFixtures.TRADE_DATE, AgentTestFixtures.HASH, "1.0", RULE_VERSION,
                ExecutionMode.LOCAL_RULES, value, AgentTestFixtures.NOW);
    }

    private ObjectNode context() {
        ObjectNode context = mapper.createObjectNode();
        context.putObject("security").put("available", true);
        context.putObject("marketData").put("available", true);
        context.putObject("technicalMetrics").put("available", true);
        context.putObject("dataQualityContext")
                .put("available", true)
                .put("queriedAt", AgentTestFixtures.NOW.toString());
        for (String name : List.of(
                "marketBreadth", "scanResult", "backtestContext", "securityEvents", "portfolioContext")) {
            context.putObject(name).put("available", false);
        }
        return context;
    }

    private Evidence evidence() {
        ObjectNode context = context();
        ObjectNode fields = mapper.createObjectNode();
        for (String name : List.of(
                "security", "marketData", "technicalMetrics", "dataQualityContext")) {
            fields.set(name, context.get(name).deepCopy());
        }
        return new Evidence(
                EVIDENCE_ID, EvidenceCategory.DATA_QUALITY, EvidenceSourceType.JAVA_ENGINE,
                "AgentContextSnapshotService", "contextSnapshot", "600000",
                AgentTestFixtures.TRADE_DATE, AgentTestFixtures.NOW, AgentTestFixtures.NOW,
                fields, AgentTestFixtures.HASH);
    }

    private Finding finding(String code, Severity severity) {
        return new Finding(
                "finding-" + code.toLowerCase(), code, severity, code, code, List.of(EVIDENCE_ID));
    }

    private AgentTeamResponse response(
            RunStatus status,
            GateStatus gate,
            RunDecision runDecision,
            int score,
            int confidence,
            List<Finding> findings,
            boolean validContext
    ) {
        Evidence evidence = evidence();
        List<Evidence> evidenceList = validContext ? List.of(evidence) : List.of();
        List<AgentError> errors = validContext
                ? List.of() : List.of(new AgentError("DATA_QUALITY_CONTEXT_INVALID", "无效上下文"));
        List<AgentOutput> runs = new ArrayList<>();
        for (var run : AgentTestFixtures.runs(1)) {
            if (run.agentCode() == AgentCode.DATA_QUALITY) {
                runs.add(new AgentOutput(
                        "1.0", 1, run.id(), run.agentCode(), status, gate, runDecision, false,
                        score, confidence, "DATA_QUALITY结果", findings, evidenceList, errors,
                        AgentTestFixtures.HASH, RULE_VERSION, ExecutionMode.LOCAL_RULES,
                        AgentTestFixtures.NOW));
            } else {
                runs.add(new AgentOutput(
                        "1.0", 1, run.id(), run.agentCode(), RunStatus.INSUFFICIENT_DATA,
                        GateStatus.NOT_APPLICABLE, RunDecision.NOT_APPLICABLE, false,
                        0, 0, "规则尚未实现", List.of(), List.of(), List.of(),
                        AgentTestFixtures.HASH, RULE_VERSION, ExecutionMode.LOCAL_RULES,
                        AgentTestFixtures.NOW));
            }
        }
        boolean blocked = gate == GateStatus.BLOCKED;
        FinalDecision finalDecision = new FinalDecision(
                "1.0", 1,
                blocked ? FinalDecisionCode.BLOCKED_BY_DATA_QUALITY : FinalDecisionCode.INSUFFICIENT_DATA,
                gate, false, 0, blocked ? confidence : 0,
                blocked ? "DATA_QUALITY规则已阻断" : "其余五个专业规则尚未实现",
                findings, runs.stream().map(AgentOutput::runId).toList(), List.of(),
                AgentTestFixtures.HASH, AgentTestFixtures.TRADE_DATE, RULE_VERSION,
                ExecutionMode.LOCAL_RULES, AgentTestFixtures.NOW);
        return new AgentTeamResponse(
                "1.0", 1, AgentTestFixtures.HASH, AgentTestFixtures.TRADE_DATE, RULE_VERSION,
                ExecutionMode.LOCAL_RULES, List.copyOf(runs), evidenceList, List.of(),
                finalDecision, AgentTestFixtures.NOW);
    }

    private AgentTeamResponse withEvidence(AgentTeamResponse source, Evidence changed) {
        List<AgentOutput> runs = source.agentRuns().stream()
                .map(run -> run.agentCode() == AgentCode.DATA_QUALITY
                        ? new AgentOutput(
                                run.schemaVersion(), run.taskId(), run.runId(), run.agentCode(), run.status(),
                                run.gateStatus(), run.decision(), run.veto(), run.score(), run.confidence(),
                                run.summary(), run.findings(), List.of(changed), run.errors(), run.contextHash(),
                                run.ruleVersion(), run.executionMode(), run.generatedAt())
                        : run)
                .toList();
        return new AgentTeamResponse(
                source.schemaVersion(), source.taskId(), source.contextHash(), source.tradeDate(),
                source.ruleVersion(), source.executionMode(), runs, List.of(changed), source.vetoes(),
                source.finalDecision(), source.generatedAt());
    }
}
