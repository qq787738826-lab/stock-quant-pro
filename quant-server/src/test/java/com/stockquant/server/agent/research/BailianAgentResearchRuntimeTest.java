package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianAgentResearchRuntimeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T01:02:03Z"), ZoneOffset.UTC);

    @Test
    void bailianTransportCompletesSevenAgentFlowWithinFixedBudget() {
        AtomicInteger transportCalls = new AtomicInteger();
        OpenAiResponsesModelAdapter.Transport transport =
                (uri, key, body, timeout) -> {
                    transportCalls.incrementAndGet();
                    return new OpenAiResponsesModelAdapter.TransportResponse(
                            200, "application/json", responseFor(body));
                };
        var adapter = OpenAiResponsesModelAdapter.bailian(
                "sk-bailian-test-only-not-a-real-secret-value".toCharArray(),
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

        assertEquals(13, transportCalls.get());
        assertEquals(13, report.modelCallCount());
        assertEquals(4, report.toolCallCount());
        assertFalse(report.deterministic());
        assertFalse(report.providerCalled());
        assertTrue(report.researchOnly());
        assertEquals("CNY", report.totalModelUsage().costCurrency());
        assertEquals(new BigDecimal("0.078000000000"),
                report.totalModelUsage().estimatedCost());
        assertTrue(report.totalModelUsage().estimatedCost().compareTo(
                OpenAiResponsesModelAdapter.M3_BAILIAN_HARD_COST_LIMIT_CNY)
                < 0);
        assertEquals(13, adapter.telemetry().completedCallCount());
        assertEquals(1_300, adapter.telemetry().inputTokenCount());
        assertEquals(520, adapter.telemetry().outputTokenCount());
        assertEquals("CNY", adapter.telemetry().costCurrency());
        assertTrue(adapter.telemetry().closed());
    }

    private static String responseFor(String requestBody) {
        try {
            JsonNode request = MAPPER.readTree(requestBody);
            JsonNode payload = MAPPER.readTree(request.path("messages").get(1)
                    .path("content").asText());
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
                claim.putArray("evidenceIds").add(
                        evidence.get(0).path("evidenceId").asText());
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
            root.put("model", OpenAiResponsesModelAdapter.BAILIAN_MODEL);
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("message").put("role", "assistant")
                    .put("content", MAPPER.writeValueAsString(structured));
            choice.put("finish_reason", "stop");
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", 100);
            usage.put("completion_tokens", 40);
            usage.put("total_tokens", 140);
            usage.putObject("prompt_tokens_details")
                    .put("cached_tokens", 20);
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
