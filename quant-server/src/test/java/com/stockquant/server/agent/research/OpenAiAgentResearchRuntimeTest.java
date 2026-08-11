package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiAgentResearchRuntimeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T01:02:03Z"), ZoneOffset.UTC);

    @Test
    void responsesAdapterCompletesAllSevenAgentsWithinHardBudget() {
        AtomicInteger networkCalls = new AtomicInteger();
        OpenAiResponsesModelAdapter.Transport transport =
                (uri, key, body, timeout) -> {
                    networkCalls.incrementAndGet();
                    return new OpenAiResponsesModelAdapter.TransportResponse(
                            200, "application/json", responseFor(body));
                };
        var adapter = new OpenAiResponsesModelAdapter(
                "sk-test-only-not-a-real-secret-value".toCharArray(),
                Duration.ofSeconds(10), transport);
        var source = (AgentResearchDatasetSource) ignored ->
                AgentResearchTestFixtures.loadedDataset();
        AgentResearchToolGateway gateway = new AgentResearchToolGateway(
                source, new DefaultStrategyResearchApi(),
                BacktestConfig.standard(), CLOCK);
        AgentResearchModels.ResearchReport report;
        try (AgentResearchRuntime runtime = new AgentResearchRuntime(
                gateway, adapter, new AgentPromptCatalog(), CLOCK)) {
            report = runtime.run(AgentResearchTestFixtures.task());
        }

        assertEquals(13, networkCalls.get());
        assertEquals(13, report.modelCallCount());
        assertEquals(4, report.toolCallCount());
        assertFalse(report.deterministic());
        assertFalse(report.providerCalled());
        assertTrue(report.researchOnly());
        assertTrue(report.totalModelUsage().estimatedCostUsd().compareTo(
                OpenAiResponsesModelAdapter.M3_HARD_COST_LIMIT_USD) < 0);
        assertEquals(13, adapter.telemetry().completedCallCount());
        assertEquals(1_300, adapter.telemetry().inputTokenCount());
        assertEquals(520, adapter.telemetry().outputTokenCount());
        assertTrue(adapter.telemetry().closed());
    }

    private static String responseFor(String requestBody) {
        try {
            JsonNode request = MAPPER.readTree(requestBody);
            JsonNode payload = MAPPER.readTree(request.path("input").get(0)
                    .path("content").get(0).path("text").asText());
            String phase = payload.path("phase").asText();
            String role = payload.path("agentRole").asText();
            ArrayNode allowed = (ArrayNode) payload.path("allowedTools");
            ArrayNode evidence = (ArrayNode) payload.path("evidence");
            ObjectNode structured = MAPPER.createObjectNode();
            ArrayNode requested = structured.putArray("requestedTools");
            boolean selection = "PLAN".equals(phase)
                    || phase.endsWith("_TOOL_SELECTION");
            if (selection) {
                allowed.forEach(value -> requested.add(value.asText()));
            }
            ArrayNode claims = structured.putArray("claims");
            if (!selection && !"FINAL_SYNTHESIS".equals(phase)) {
                ObjectNode claim = claims.addObject();
                claim.put("claimType", claimType(role).name());
                claim.put("statement",
                        "The cited evidence supports this bounded finding.");
                ArrayNode citations = claim.putArray("evidenceIds");
                citations.add(evidence.get(0).path("evidenceId").asText());
                claim.put("confidence", 0.5);
            }
            structured.put("summary", selection
                    ? "The bounded deterministic tool set was selected."
                    : "The evidence-bounded research step completed.");
            ArrayNode issues = structured.putArray("issueCodes");
            boolean critic = "CRITIC_REVIEW".equals(role);
            if (critic) {
                issues.add("PIT_LINEAGE_LIMITATION");
            }
            structured.put("reworkRequested", critic);

            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", AgentResearchModels.REAL_MODEL);
            ArrayNode output = root.putArray("output");
            ObjectNode message = output.addObject();
            message.put("type", "message");
            ObjectNode content = message.putArray("content").addObject();
            content.put("type", "output_text");
            content.put("text", MAPPER.writeValueAsString(structured));
            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 40);
            usage.putObject("input_tokens_details")
                    .put("cached_tokens", 0);
            return MAPPER.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static ClaimType claimType(String role) {
        return switch (AgentResearchModels.AgentRole.valueOf(role)) {
            case DATA_ANALYST -> ClaimType.FACT;
            case STRATEGY_RESEARCH -> ClaimType.HYPOTHESIS;
            case RESEARCH_COORDINATOR -> ClaimType.UNKNOWN;
            default -> ClaimType.INFERENCE;
        };
    }
}
