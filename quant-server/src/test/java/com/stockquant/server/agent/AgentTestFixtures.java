package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentRun;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
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
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class AgentTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    static final LocalDate TRADE_DATE = LocalDate.parse("2026-07-14");
    static final String HASH = "a".repeat(64);
    static final String EVIDENCE_ID = "evidence-1";

    private AgentTestFixtures() {}

    static ObjectNode context() {
        return new ObjectMapper().createObjectNode().putObject("security").put("available", false);
    }

    static AgentTask task(long id, TaskStatus status) {
        return new AgentTask(
                id, "600000", TRADE_DATE, status, "1.0", context(), NOW, HASH,
                "rules-1", ExecutionMode.LOCAL_RULES, TriggerType.MANUAL, "TEST",
                false, null, null, null, NOW, NOW
        );
    }

    static List<AgentRun> runs(long taskId) {
        List<AgentRun> runs = new ArrayList<>();
        long id = 11;
        for (AgentCode code : AgentCode.PROFESSIONAL_AGENTS) {
            runs.add(new AgentRun(
                    id++, taskId, code, 1, RunStatus.QUEUED, GateStatus.NOT_APPLICABLE,
                    RunDecision.NOT_APPLICABLE, null, null, false, null, null,
                    null, null, null, null, NOW, NOW
            ));
        }
        return List.copyOf(runs);
    }

    static Evidence evidence() {
        ObjectNode fields = new ObjectMapper().createObjectNode().put("available", false);
        return new Evidence(
                EVIDENCE_ID, EvidenceCategory.QUERY_RESULT, EvidenceSourceType.JAVA_ENGINE,
                "agent-test", "test:query", "600000", TRADE_DATE, NOW, NOW, fields, HASH
        );
    }

    static AgentTeamRequest request() {
        return new AgentTeamRequest(
                "1.0", 1, RunIds.from(runs(1)), "600000", TRADE_DATE, HASH,
                "1.0", "rules-1", ExecutionMode.LOCAL_RULES, context(), NOW
        );
    }

    static AgentTeamResponse validResponse() {
        Evidence evidence = evidence();
        Finding finding = new Finding(
                "finding-1", "TEST", Severity.INFO, "测试", "测试发现", List.of(EVIDENCE_ID)
        );
        List<AgentOutput> outputs = new ArrayList<>();
        for (AgentRun run : runs(1)) {
            outputs.add(output(run, finding, evidence, false));
        }
        FinalDecision decision = new FinalDecision(
                "1.0", 1, FinalDecisionCode.WATCH, GateStatus.WARN, false, 50, 80,
                "仅供人工研究", List.of(finding), outputs.stream().map(AgentOutput::runId).toList(),
                List.of(), HASH, TRADE_DATE, "rules-1", ExecutionMode.LOCAL_RULES, NOW
        );
        return new AgentTeamResponse(
                "1.0", 1, HASH, TRADE_DATE, "rules-1", ExecutionMode.LOCAL_RULES,
                List.copyOf(outputs), List.of(evidence), List.of(), decision, NOW
        );
    }

    static AgentOutput output(AgentRun run, Finding finding, Evidence evidence, boolean veto) {
        return new AgentOutput(
                "1.0", run.taskId(), run.id(), run.agentCode(), RunStatus.COMPLETED,
                GateStatus.PASS, veto ? RunDecision.REJECT : RunDecision.PASS,
                veto, 50, 80, "测试结果", List.of(finding), List.of(evidence), List.of(),
                HASH, "rules-1", ExecutionMode.LOCAL_RULES, NOW
        );
    }

    static AgentTeamResponse withRuns(AgentTeamResponse response, List<AgentOutput> runs) {
        return new AgentTeamResponse(
                response.schemaVersion(), response.taskId(), response.contextHash(), response.tradeDate(),
                response.ruleVersion(), response.executionMode(), runs, response.evidence(),
                response.vetoes(), response.finalDecision(), response.generatedAt()
        );
    }

    static AgentTeamResponse withVeto(AgentTeamResponse response, boolean validDecision) {
        List<AgentOutput> outputs = new ArrayList<>(response.agentRuns());
        int index = AgentCode.PROFESSIONAL_AGENTS.indexOf(AgentCode.POSITION_RISK);
        AgentOutput original = outputs.get(index);
        outputs.set(index, new AgentOutput(
                original.schemaVersion(), original.taskId(), original.runId(), original.agentCode(),
                RunStatus.COMPLETED, original.gateStatus(), RunDecision.REJECT, true,
                original.score(), original.confidence(), original.summary(), original.findings(),
                original.evidence(), original.errors(), original.contextHash(), original.ruleVersion(),
                original.executionMode(), original.generatedAt()
        ));
        FormalVeto veto = new FormalVeto(
                "veto-1", 1, original.runId(), AgentCode.POSITION_RISK,
                "POSITION_LIMIT", "仓位风险", List.of(EVIDENCE_ID), NOW
        );
        FinalDecision old = response.finalDecision();
        FinalDecision decision = new FinalDecision(
                old.schemaVersion(), old.taskId(),
                validDecision ? FinalDecisionCode.REJECTED_BY_VETO : FinalDecisionCode.WATCH,
                old.gateStatus(), true, old.score(), old.confidence(), old.summary(), old.findings(),
                old.sourceRunIds(), List.of(veto.vetoId()), old.contextHash(), old.tradeDate(),
                old.ruleVersion(), old.executionMode(), old.generatedAt()
        );
        return new AgentTeamResponse(
                response.schemaVersion(), response.taskId(), response.contextHash(), response.tradeDate(),
                response.ruleVersion(), response.executionMode(), List.copyOf(outputs), response.evidence(),
                List.of(veto), decision, response.generatedAt()
        );
    }
}
