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

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentModelResponseValidatorTest {
    private static final String HASH = "a".repeat(64);
    private static final Evidence EVIDENCE = new Evidence(
            "EV_STRATEGY_COMPARE_aaaaaaaaaaaa",
            ToolCode.STRATEGY_COMPARE, HASH, Instant.EPOCH,
            "The deterministic Sharpe value is 1.25.");

    @Test
    void rejectsFabricatedMetricNotPresentInCitedEvidence() {
        var request = request(List.of(ToolCode.STRATEGY_COMPARE));
        var response = new ModelAdapter.ModelResponse(
                List.of(ToolCode.STRATEGY_COMPARE), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "The Sharpe value is 9.99.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.50"))),
                "Structured summary.", List.of(), false, ModelUsage.zero());

        assertThrows(IllegalArgumentException.class, () ->
                AgentModelResponseValidator.validate(request, response));
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
    void rejectsExecutableTradingInstruction() {
        var request = request(List.of(ToolCode.STRATEGY_COMPARE));
        var response = new ModelAdapter.ModelResponse(List.of(), List.of(
                new ModelAdapter.ModelClaim(ClaimType.FACT,
                        "Execute a trade based on the evidence.",
                        List.of(EVIDENCE.evidenceId()),
                        new BigDecimal("0.30"))),
                "Structured summary.", List.of(), false, ModelUsage.zero());

        assertThrows(IllegalArgumentException.class, () ->
                AgentModelResponseValidator.validate(request, response));
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
