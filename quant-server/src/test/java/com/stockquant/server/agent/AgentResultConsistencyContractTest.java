package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResultConsistencyContractTest {

    private final AgentResponseValidator validator = new AgentResponseValidator();
    private ObjectMapper mapper;
    private AgentTeamRequest request;

    @BeforeEach
    void setUp() throws IOException {
        mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        request = read("valid-agent-team-request.json", AgentTeamRequest.class);
    }

    @Test
    void allThreeSharedLegalScenariosPass() {
        assertValid("valid-agent-team-response.json");
        assertValid("valid-agent-team-evidence-response.json");
        assertValid("valid-agent-team-veto-response.json");
    }

    @Test
    void evidenceReferencesAndUniquenessAreEnforced() {
        assertInvalidEvidence(root -> finding(root, 0).withArray("evidenceIds").add("missing-evidence"));
        assertInvalidVeto(root -> veto(root).withArray("evidenceIds").set(0, mapper.getNodeFactory().textNode("missing-evidence")));
        assertInvalidEvidence(root -> root.withArray("evidence").add(root.withArray("evidence").get(0).deepCopy()));
        assertInvalidEvidence(root -> finding(root, 0).withArray("evidenceIds").add("e-market"));
    }

    @Test
    void exactAgentEvidenceSubsetPassesButCollectedAtConflictIsRejected() throws Exception {
        ObjectNode valid = fixtureTree("valid-agent-team-evidence-response.json");
        ObjectNode topLevel = (ObjectNode) valid.withArray("evidence").get(0);
        run(valid, 0).withArray("evidence").add(topLevel.deepCopy());
        assertDoesNotThrow(() -> validator.validate(
                request, mapper.treeToValue(valid, AgentTeamResponse.class)));
        org.junit.jupiter.api.Assertions.assertEquals(3, valid.withArray("evidence").size());

        AgentResponseValidationException error = assertInvalidEvidence(root -> {
            ObjectNode subset = ((ObjectNode) root.withArray("evidence").get(0)).deepCopy();
            subset.put("collectedAt", "2026-07-14T05:01:01Z");
            run(root, 0).withArray("evidence").add(subset);
        });
        assertTrue(error.getMessage().contains("内容冲突"));
    }

    @Test
    void onlyPositionRiskCanOwnAConsistentFormalVeto() {
        assertInvalidEvidence(root -> {
            ObjectNode run = run(root, 1);
            run.put("veto", true).put("decision", "REJECT");
        });
        assertInvalidVeto(root -> veto(root).put("agentCode", "ANNOUNCEMENT_RISK").put("runId", 105));
        assertInvalidVeto(root -> root.withArray("vetoes").removeAll());
        assertInvalidVeto(root -> run(root, 5).put("veto", false));
        assertInvalidVeto(root -> veto(root).put("runId", 105));
        assertInvalidVeto(root -> veto(root).put("agentCode", "DATA_QUALITY").put("runId", 101));
    }

    @Test
    void finalDecisionMustExactlyRepresentRunsAndVetoes() {
        assertInvalidEvidence(root -> decision(root).put("vetoed", true));
        assertInvalidVeto(root -> decision(root).put("vetoed", false));
        assertInvalidVeto(root -> decision(root).put("decision", "WATCH"));
        assertInvalidEvidence(root -> decision(root).put("decision", "REJECTED_BY_VETO"));
        assertInvalidEvidence(root -> decision(root).withArray("sourceRunIds").remove(5));
        assertInvalidEvidence(root -> decision(root).withArray("sourceRunIds").set(5, mapper.getNodeFactory().numberNode(105)));
        assertInvalidEvidence(root -> decision(root).withArray("sourceRunIds").set(5, mapper.getNodeFactory().numberNode(999)));
        assertInvalidVeto(root -> decision(root).withArray("vetoIds").set(0, mapper.getNodeFactory().textNode("unknown-veto")));
        assertInvalidVeto(root -> decision(root).withArray("vetoIds").removeAll());
    }

    @Test
    void scoreAndConfidenceBoundariesMatchTheFrozenContract() {
        assertValid("valid-agent-team-evidence-response.json");
        assertInvalidEvidence(root -> run(root, 0).put("score", -1));
        assertInvalidEvidence(root -> run(root, 0).put("score", 101));
        assertInvalidEvidence(root -> run(root, 0).put("confidence", -1));
        assertInvalidEvidence(root -> run(root, 0).put("confidence", 101));
    }

    @Test
    void identityAndNonBlankLogicalIdsAreEnforced() {
        assertInvalidEvidence(root -> decision(root).put("taskId", 78));
        assertInvalidEvidence(root -> ((ObjectNode) root.withArray("evidence").get(0)).put("evidenceId", "   "));
        assertInvalidVeto(root -> veto(root).put("reason", "   "));
    }

    @Test
    void dataQualityBlockRequiresBlockedFinalGateStatus() {
        assertValid("valid-agent-team-response.json");
        for (String gateStatus : new String[]{"WARN", "PASS"}) {
            AgentResponseValidationException error = assertInvalid(
                    "valid-agent-team-response.json",
                    root -> decision(root).put("gateStatus", gateStatus)
            );
            assertTrue(error.getMessage().contains("数据质量阻断"));
        }
    }

    @Test
    void validationErrorsRemainSafeAndDoNotContainWholePayloads() throws Exception {
        ObjectNode root = fixtureTree("valid-agent-team-evidence-response.json");
        finding(root, 0).withArray("evidenceIds").set(0, mapper.getNodeFactory().textNode("missing-evidence"));
        AgentResponseValidationException error = assertThrows(
                AgentResponseValidationException.class,
                () -> validator.validate(request, mapper.treeToValue(root, AgentTeamResponse.class))
        );
        assertTrue(error.getMessage().contains("evidenceId"));
        assertFalse(error.getMessage().contains("contextSnapshot"));
        assertFalse(error.getMessage().contains("agentRuns"));
        assertFalse(error.getMessage().contains("password"));
        assertFalse(error.getMessage().contains("token"));
    }

    private void assertValid(String name) {
        assertDoesNotThrow(() -> validator.validate(request, read(name, AgentTeamResponse.class)));
    }

    private AgentResponseValidationException assertInvalidEvidence(Consumer<ObjectNode> mutation) {
        return assertInvalid("valid-agent-team-evidence-response.json", mutation);
    }

    private AgentResponseValidationException assertInvalidVeto(Consumer<ObjectNode> mutation) {
        return assertInvalid("valid-agent-team-veto-response.json", mutation);
    }

    private AgentResponseValidationException assertInvalid(String name, Consumer<ObjectNode> mutation) {
        return assertThrows(AgentResponseValidationException.class, () -> {
            ObjectNode root = fixtureTree(name);
            mutation.accept(root);
            validator.validate(request, mapper.treeToValue(root, AgentTeamResponse.class));
        });
    }

    private ObjectNode fixtureTree(String name) throws IOException {
        try (InputStream input = resource(name)) {
            return (ObjectNode) mapper.readTree(input);
        }
    }

    private <T> T read(String name, Class<T> type) throws IOException {
        try (InputStream input = resource(name)) {
            return mapper.readValue(input, type);
        }
    }

    private InputStream resource(String name) {
        InputStream input = getClass().getResourceAsStream("/agent-team-contract/" + name);
        if (input == null) {
            throw new IllegalArgumentException("缺少共享契约夹具：" + name);
        }
        return input;
    }

    private static ObjectNode run(ObjectNode root, int index) {
        return (ObjectNode) root.withArray("agentRuns").get(index);
    }

    private static ObjectNode finding(ObjectNode root, int runIndex) {
        return (ObjectNode) run(root, runIndex).withArray("findings").get(0);
    }

    private static ObjectNode veto(ObjectNode root) {
        return (ObjectNode) root.withArray("vetoes").get(0);
    }

    private static ObjectNode decision(ObjectNode root) {
        return (ObjectNode) root.get("finalDecision");
    }
}
