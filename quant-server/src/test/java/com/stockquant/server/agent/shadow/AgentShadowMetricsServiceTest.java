package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.MetricsFilter;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentShadowMetricsServiceTest {

    private final AgentShadowRepository repository =
            mock(AgentShadowRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentShadowMetricsService service =
            new AgentShadowMetricsService(repository);

    @Test
    void derivesMetricsFromPersistedOutcomeSnapshots() {
        MetricsFilter filter = new MetricsFilter(
                null, null, null, null, null);
        when(repository.findMetricBatches(any()))
                .thenReturn(List.of(mock(
                        AgentShadowModels.ShadowBatch.class)));
        when(repository.findMetricReviews(any()))
                .thenReturn(List.of());
        when(repository.findMetricItems(any())).thenReturn(List.of(
                item(1, OutcomeClass.INSUFFICIENT,
                        FinalDecisionCode.INSUFFICIENT_DATA,
                        false, false, "BACKTEST_SAMPLE_INSUFFICIENT",
                        snapshot("INSUFFICIENT_DATA",
                                "BACKTEST_SAMPLE_INSUFFICIENT")),
                item(2, OutcomeClass.DETERMINED,
                        FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                        true, true, null,
                        snapshot("COMPLETED", null))));

        var metrics = service.metrics(filter);

        assertEquals(1, metrics.batchCount());
        assertEquals(2, metrics.itemCount());
        assertEquals(1,
                metrics.outcomeDistribution().get("INSUFFICIENT"));
        assertEquals(1, metrics.dataQualityBlockedCount());
        assertEquals(1, metrics.vetoCount());
        assertEquals(1, metrics.cacheHitCount());
        assertEquals(0.5, metrics.cacheHitRate());
        assertEquals(1,
                metrics.primaryReasonCodeDistribution()
                        .get("BACKTEST_SAMPLE_INSUFFICIENT"));
        assertEquals(1,
                metrics.agentErrorDistribution()
                        .get(AgentCode.DATA_QUALITY.name())
                        .get("BACKTEST_SAMPLE_INSUFFICIENT"));
        assertEquals(2, metrics.unreviewedItemCount());
    }

    private ShadowItem item(
            long id,
            OutcomeClass outcome,
            FinalDecisionCode decision,
            boolean cacheHit,
            boolean vetoed,
            String reason,
            ObjectNode snapshot
    ) {
        return new ShadowItem(
                id,
                1,
                (int) id,
                "60000" + id,
                SelectionSource.EXPLICIT,
                "explicit",
                id,
                !cacheHit,
                cacheHit,
                outcome == OutcomeClass.INSUFFICIENT
                        ? TaskStatus.PARTIAL
                        : TaskStatus.COMPLETED,
                decision,
                outcome == OutcomeClass.INSUFFICIENT
                        ? GateStatus.NOT_APPLICABLE
                        : GateStatus.BLOCKED,
                0,
                0,
                vetoed,
                outcome,
                reason,
                mapper.valueToTree(
                        reason == null ? List.of() : List.of(reason)),
                snapshot,
                "a".repeat(64),
                100L * id,
                id == 2 ? 1L : null,
                id == 2,
                id == 2,
                id == 2 ? 5 : null,
                id == 2 ? -5 : null,
                mapper.createArrayNode(),
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private ObjectNode snapshot(String status, String errorCode) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode runs = root.putArray("runs");
        for (AgentCode code : AgentCode.PROFESSIONAL_AGENTS) {
            ObjectNode run = runs.addObject();
            run.put("agentCode", code.name());
            run.put("status", status);
            ArrayNode errors = run.putArray("errors");
            if (errorCode != null && code == AgentCode.DATA_QUALITY) {
                errors.addObject().put("code", errorCode);
            }
        }
        return root;
    }
}
