package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.Evidence;
import com.stockquant.server.agent.research.AgentResearchModels.ModelUsage;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModelResponseValidatorTest {
    private static final String HASH = "a".repeat(64);
    private static final Evidence EVIDENCE = new Evidence(
            "EV_STRATEGY_COMPARE_aaaaaaaaaaaa",
            ToolCode.STRATEGY_COMPARE, HASH, Instant.EPOCH,
            "The deterministic Sharpe value is 1.25.");

    @Test
    void downgradesFabricatedMetricToEvidenceFreeUnknown() {
        var request = request(List.of(ToolCode.STRATEGY_COMPARE));
        var response = new ModelAdapter.ModelResponse(
                List.of(ToolCode.STRATEGY_COMPARE), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "The Sharpe value is 9.99.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.50"))),
                "Structured summary.", List.of(), false, ModelUsage.zero());

        var validated = AgentModelResponseValidator.validate(request,
                response);

        assertEquals(1, validated.claims().size());
        assertEquals(ClaimType.UNKNOWN,
                validated.claims().get(0).claimType());
        assertTrue(validated.claims().get(0).evidenceIds().isEmpty());
        assertTrue(validated.claims().get(0).statement().contains(
                "已被系统拒绝"));
        assertTrue(!validated.claims().get(0).statement().contains("9.99"));
    }

    @Test
    void rejectsToolEscalationAndUnsupportedClaim() {
        var request = request(List.of());
        var escalated = new ModelAdapter.ModelResponse(
                List.of(ToolCode.RESEARCH_DATASET), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "Unsupported assertion.", List.of(),
                        new BigDecimal("0.30"))),
                "Structured summary.", List.of(), false, ModelUsage.zero());

        assertThrows(IllegalArgumentException.class, () ->
                AgentModelResponseValidator.validate(request, escalated));
    }

    @Test
    void discardsExecutableTradingInstructionAsStaticUnknown() {
        var request = request(List.of(ToolCode.STRATEGY_COMPARE));
        var response = new ModelAdapter.ModelResponse(List.of(), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "Execute a trade based on the evidence.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.30"))),
                "Structured summary.", List.of(), false, ModelUsage.zero());

        var validated = AgentModelResponseValidator.validate(request,
                response);

        assertEquals(ClaimType.UNKNOWN,
                validated.claims().get(0).claimType());
        assertTrue(validated.claims().get(0).evidenceIds().isEmpty());
        assertTrue(!validated.claims().get(0).statement().contains(
                "Execute a trade"));
    }

    @Test
    void discardsWrongRoleAndExcessConfidenceWithoutPropagatingText() {
        var criticRequest = new ModelAdapter.ModelRequest(
                "MC_11_CRITIC_REVIEW", AgentRole.CRITIC_REVIEW,
                "CRITIC_CHALLENGE", "M3_CRITIC_REVIEW_V3",
                "System rules are fixed.", "Untrusted objective.",
                List.of(), List.of(EVIDENCE), List.of(), false,
                new BigDecimal("0.70"), HASH);
        var wrongRole = new ModelAdapter.ModelResponse(List.of(), List.of(
                new ModelAdapter.ModelClaim(ClaimType.RECOMMENDATION,
                        "The model supplied an invalid recommendation.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.50"))), "Structured summary.",
                List.of(), false, ModelUsage.zero());
        var excessive = new ModelAdapter.ModelResponse(List.of(), List.of(
                new ModelAdapter.ModelClaim(ClaimType.INFERENCE,
                        "The model supplied excessive confidence.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.90"))), "Structured summary.",
                List.of(), false, ModelUsage.zero());

        for (var response : List.of(wrongRole, excessive)) {
            var validated = AgentModelResponseValidator.validate(
                    criticRequest, response);
            assertEquals(ClaimType.UNKNOWN,
                    validated.claims().get(0).claimType());
            assertTrue(validated.claims().get(0).evidenceIds().isEmpty());
            assertTrue(validated.claims().get(0).statement().contains(
                    "已被系统拒绝"));
            assertTrue(!validated.claims().get(0).statement().contains(
                    "recommendation"));
            assertTrue(!validated.claims().get(0).statement().contains(
                    "excessive confidence"));
        }
    }

    @Test
    void downgradesUnknownEvidenceReferenceWithoutPropagatingClaim() {
        var request = request(List.of());
        var response = new ModelAdapter.ModelResponse(List.of(), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "The strategy result is supported.",
                        List.of("EV_STRATEGY_COMPARE_bbbbbbbbbbbb"),
                        new BigDecimal("0.30"))),
                "Structured summary.", List.of(), false, ModelUsage.zero());

        var validated = AgentModelResponseValidator.validate(request,
                response);

        assertEquals(ClaimType.UNKNOWN,
                validated.claims().get(0).claimType());
        assertTrue(validated.claims().get(0).evidenceIds().isEmpty());
        assertTrue(validated.claims().get(0).statement().contains(
                "已被系统拒绝"));
    }

    @Test
    void zhCnPromptRejectsEnglishProseWithoutExposingIt() {
        var request = new ModelAdapter.ModelRequest(
                "MC_02_DATA_ANALYST", AgentRole.DATA_ANALYST,
                "DATA_QUALITY", "M3_DATA_ANALYST_V3",
                "System rules are fixed.", "中文研究任务。",
                List.of(), List.of(EVIDENCE), List.of(), false,
                new BigDecimal("0.80"), HASH);
        var response = new ModelAdapter.ModelResponse(List.of(), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "This unsupported user-facing prose is English.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.30"))),
                "English role summary.", List.of(), false,
                ModelUsage.zero());

        var validated = AgentModelResponseValidator.validate(request,
                response);

        assertEquals(ClaimType.UNKNOWN,
                validated.claims().get(0).claimType());
        assertEquals("模型输出未按要求使用简体中文，已被系统拒绝。",
                validated.claims().get(0).statement());
        assertEquals("已在确定性证据约束下完成结构化角色分析。",
                validated.summary());
    }

    @Test
    void toolSelectionMustRequestToolWithoutMakingClaims() {
        var request = request("STRATEGY_TOOL_SELECTION",
                List.of(ToolCode.STRATEGY_COMPARE));
        var missing = new ModelAdapter.ModelResponse(List.of(), List.of(),
                "No tool selected.", List.of(), false, ModelUsage.zero());
        var prematureClaim = new ModelAdapter.ModelResponse(
                List.of(ToolCode.STRATEGY_COMPARE), List.of(
                new ModelAdapter.ModelClaim(ClaimType.HYPOTHESIS,
                        "A tool may be useful.", List.of(),
                        new BigDecimal("0.20"))),
                "Tool and claim mixed.", List.of(), false,
                ModelUsage.zero());

        assertThrows(IllegalArgumentException.class, () ->
                AgentModelResponseValidator.validate(request, missing));
        assertThrows(IllegalArgumentException.class, () ->
                AgentModelResponseValidator.validate(request,
                        prematureClaim));
    }

    private static ModelAdapter.ModelRequest request(List<ToolCode> tools) {
        return request("TEST", tools);
    }

    private static ModelAdapter.ModelRequest request(
            String phase,
            List<ToolCode> tools
    ) {
        return new ModelAdapter.ModelRequest("MC_01_STRATEGY_RESEARCH",
                AgentRole.STRATEGY_RESEARCH, phase,
                "M3_STRATEGY_RESEARCH_V1", "System rules are fixed.",
                "Untrusted objective.", tools, List.of(EVIDENCE), List.of(),
                false, new BigDecimal("0.80"), HASH);
    }
}
