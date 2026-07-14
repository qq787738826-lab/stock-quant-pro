package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentResponseValidatorTest {

    private final AgentResponseValidator validator = new AgentResponseValidator();

    @Test
    void validTeamResponsePasses() {
        assertDoesNotThrow(() -> validator.validate(
                AgentTestFixtures.request(), AgentTestFixtures.validResponse()
        ));
    }

    @Test
    void missingProfessionalAgentIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        assertRejected(AgentTestFixtures.withRuns(response, response.agentRuns().subList(0, 5)));
    }

    @Test
    void duplicateAgentCodeIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        AgentOutput first = runs.get(0);
        AgentOutput second = runs.get(1);
        runs.set(1, copy(second, first.agentCode(), second.runId(), second.gateStatus(), false,
                second.decision(), second.findings(), second.evidence(), second.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void mismatchedPreallocatedRunIdIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        AgentOutput first = runs.get(0);
        runs.set(0, copy(first, first.agentCode(), 999, first.gateStatus(), false,
                first.decision(), first.findings(), first.evidence(), first.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void findingWithUnknownEvidenceIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        AgentOutput first = response.agentRuns().get(0);
        Finding invalid = new Finding("bad", "BAD", first.findings().get(0).severity(), "bad", "bad", List.of("missing"));
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        runs.set(0, copy(first, first.agentCode(), first.runId(), first.gateStatus(), false,
                first.decision(), List.of(invalid), first.evidence(), first.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void emptyFindingEvidenceReferencesAreRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        AgentOutput first = response.agentRuns().get(0);
        Finding invalid = new Finding("bad", "BAD", first.findings().get(0).severity(), "bad", "bad", List.of());
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        runs.set(0, copy(first, first.agentCode(), first.runId(), first.gateStatus(), false,
                first.decision(), List.of(invalid), first.evidence(), first.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void duplicateTopLevelEvidenceIdIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        AgentTeamResponse invalid = copyResponse(
                response, response.agentRuns(), List.of(AgentTestFixtures.evidence(), AgentTestFixtures.evidence()),
                response.finalDecision()
        );
        assertRejected(invalid);
    }

    @Test
    void conflictingAgentEvidenceSubsetIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        Evidence original = AgentTestFixtures.evidence();
        Evidence conflict = new Evidence(
                original.evidenceId(), original.category(), original.sourceType(), "conflicting-source",
                original.sourceRef(), original.symbol(), original.tradeDate(), original.observedAt(),
                original.collectedAt(), original.fields(), original.contentHash()
        );
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        AgentOutput first = runs.get(0);
        runs.set(0, copy(first, first.agentCode(), first.runId(), first.gateStatus(), false,
                first.decision(), first.findings(), List.of(conflict), first.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void announcementRiskVetoIsRejected() {
        assertAgentVetoRejected(AgentCode.ANNOUNCEMENT_RISK);
    }

    @Test
    void dataQualityVetoIsRejected() {
        assertAgentVetoRejected(AgentCode.DATA_QUALITY);
    }

    @Test
    void validPositionRiskVetoPasses() {
        assertDoesNotThrow(() -> validator.validate(
                AgentTestFixtures.request(), AgentTestFixtures.withVeto(AgentTestFixtures.validResponse(), true)
        ));
    }

    @Test
    void vetoWithoutRejectedByVetoDecisionIsRejected() {
        assertRejected(AgentTestFixtures.withVeto(AgentTestFixtures.validResponse(), false));
    }

    @Test
    void rejectedByVetoWithoutFormalVetoIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        FinalDecision old = response.finalDecision();
        FinalDecision invalid = copyDecision(old, FinalDecisionCode.REJECTED_BY_VETO, false, old.sourceRunIds());
        assertRejected(copyResponse(response, response.agentRuns(), response.evidence(), invalid));
    }

    @Test
    void unknownSourceRunIdIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        FinalDecision old = response.finalDecision();
        FinalDecision invalid = copyDecision(old, old.decision(), false, List.of(999L));
        assertRejected(copyResponse(response, response.agentRuns(), response.evidence(), invalid));
    }

    @Test
    void dataQualityBlockRequiresBlockedDecisionWithoutFormalVeto() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        AgentOutput dataQuality = runs.get(0);
        runs.set(0, copy(dataQuality, dataQuality.agentCode(), dataQuality.runId(), GateStatus.BLOCKED,
                false, dataQuality.decision(), dataQuality.findings(), dataQuality.evidence(), dataQuality.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void outOfRangeScoreIsRejected() {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        AgentOutput first = runs.get(0);
        runs.set(0, copy(first, first.agentCode(), first.runId(), first.gateStatus(), false,
                first.decision(), first.findings(), first.evidence(), 101));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    @Test
    void buyAndSellCannotEnterFinalDecisionEnum() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.readValue("\"BUY\"", FinalDecisionCode.class));
        assertThrows(Exception.class, () -> mapper.readValue("\"SELL\"", FinalDecisionCode.class));
    }

    @Test
    void queuedPythonResultIsRejected() {
        assertRunStatusRejected(RunStatus.QUEUED);
    }

    @Test
    void runningPythonResultIsRejected() {
        assertRunStatusRejected(RunStatus.RUNNING);
    }

    @Test
    void skippedPythonResultIsRejected() {
        assertRunStatusRejected(RunStatus.SKIPPED);
    }

    @Test
    void allFourPythonResultStatusesAreAccepted() {
        for (RunStatus status : List.of(RunStatus.COMPLETED, RunStatus.PARTIAL,
                RunStatus.INSUFFICIENT_DATA, RunStatus.FAILED)) {
            AgentTeamResponse response = AgentTestFixtures.validResponse();
            List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
            runs.set(0, withStatus(runs.get(0), status));
            assertDoesNotThrow(() -> validator.validate(
                    AgentTestFixtures.request(), AgentTestFixtures.withRuns(response, runs)));
        }
    }

    @Test
    void emptyEvidenceSymbolIsRejected() {
        assertEvidenceSymbolRejected("");
    }

    @Test
    void blankEvidenceSymbolIsRejected() {
        assertEvidenceSymbolRejected("   ");
    }

    @Test
    void invalidEvidenceSymbolIsRejected() {
        assertEvidenceSymbolRejected("ABC123");
    }

    @Test
    void sixDigitEvidenceSymbolIsAccepted() {
        assertDoesNotThrow(() -> validator.validate(
                AgentTestFixtures.request(), responseWithEvidenceSymbol("600000")));
    }

    private void assertRunStatusRejected(RunStatus status) {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        runs.set(0, withStatus(runs.get(0), status));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    private void assertEvidenceSymbolRejected(String symbol) {
        assertRejected(responseWithEvidenceSymbol(symbol));
    }

    private static AgentTeamResponse responseWithEvidenceSymbol(String symbol) {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        Evidence source = response.evidence().get(0);
        Evidence changed = new Evidence(
                source.evidenceId(), source.category(), source.sourceType(), source.sourceName(),
                source.sourceRef(), symbol, source.tradeDate(), source.observedAt(), source.collectedAt(),
                source.fields(), source.contentHash()
        );
        List<AgentOutput> runs = response.agentRuns().stream()
                .map(run -> copy(run, run.agentCode(), run.runId(), run.gateStatus(), run.veto(),
                        run.decision(), run.findings(), List.of(changed), run.score()))
                .toList();
        return copyResponse(response, runs, List.of(changed), response.finalDecision());
    }

    private static AgentOutput withStatus(AgentOutput source, RunStatus status) {
        return new AgentOutput(
                source.schemaVersion(), source.taskId(), source.runId(), source.agentCode(), status,
                source.gateStatus(), source.decision(), source.veto(), source.score(), source.confidence(),
                source.summary(), source.findings(), source.evidence(), source.errors(), source.contextHash(),
                source.ruleVersion(), source.executionMode(), source.generatedAt()
        );
    }

    private void assertAgentVetoRejected(AgentCode code) {
        AgentTeamResponse response = AgentTestFixtures.validResponse();
        List<AgentOutput> runs = new ArrayList<>(response.agentRuns());
        int index = AgentCode.PROFESSIONAL_AGENTS.indexOf(code);
        AgentOutput output = runs.get(index);
        runs.set(index, copy(output, code, output.runId(), output.gateStatus(), true,
                RunDecision.REJECT, output.findings(), output.evidence(), output.score()));
        assertRejected(AgentTestFixtures.withRuns(response, runs));
    }

    private void assertRejected(AgentTeamResponse response) {
        assertThrows(AgentTeamException.class, () -> validator.validate(AgentTestFixtures.request(), response));
    }

    private static AgentOutput copy(
            AgentOutput source,
            AgentCode code,
            long runId,
            GateStatus gateStatus,
            boolean veto,
            RunDecision decision,
            List<Finding> findings,
            List<Evidence> evidence,
            Integer score
    ) {
        return new AgentOutput(
                source.schemaVersion(), source.taskId(), runId, code, source.status(), gateStatus,
                decision, veto, score, source.confidence(), source.summary(), findings, evidence,
                source.errors(), source.contextHash(), source.ruleVersion(), source.executionMode(), source.generatedAt()
        );
    }

    private static FinalDecision copyDecision(
            FinalDecision source,
            FinalDecisionCode code,
            boolean vetoed,
            List<Long> sourceRunIds
    ) {
        return new FinalDecision(
                source.schemaVersion(), source.taskId(), code, source.gateStatus(), vetoed,
                source.score(), source.confidence(), source.summary(), source.findings(), sourceRunIds,
                source.vetoIds(), source.contextHash(), source.tradeDate(), source.ruleVersion(),
                source.executionMode(), source.generatedAt()
        );
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
                decision, source.generatedAt()
        );
    }
}
