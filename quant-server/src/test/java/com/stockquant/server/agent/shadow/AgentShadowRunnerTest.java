package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.config.AgentShadowProperties;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.service.AgentTaskService;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.TriggerMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentShadowRunnerTest {

    @Test
    void circuitBreaksAfterConsecutiveItemCreationFailures() {
        AgentShadowRepository repository =
                mock(AgentShadowRepository.class);
        AgentTaskRepository taskRepository =
                mock(AgentTaskRepository.class);
        AgentTaskService taskService = mock(AgentTaskService.class);
        AgentShadowLifecycleService lifecycle =
                mock(AgentShadowLifecycleService.class);
        AgentShadowProperties properties = new AgentShadowProperties();
        properties.setMaxConcurrency(2);
        ShadowBatch batch = batch();
        ShadowItem first = item(1, "600001");
        ShadowItem second = item(2, "600002");

        when(lifecycle.start(batch.id())).thenReturn(batch);
        when(repository.findBatch(batch.id()))
                .thenReturn(java.util.Optional.of(batch));
        when(repository.findUnstartedItems(batch.id()))
                .thenReturn(List.of(first, second), List.of());
        when(taskService.create(any(), eq("shadow:" + batch.id())))
                .thenThrow(new IllegalStateException("creation failed"));

        AgentShadowRunner runner = new AgentShadowRunner(
                repository,
                taskRepository,
                taskService,
                lifecycle,
                properties,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        runner.run(batch.id());

        verify(lifecycle, times(2))
                .failLaunch(any(ShadowItem.class), any(String.class));
        verify(lifecycle).finish(
                batch.id(),
                "SHADOW_MORE_THAN_HALF_ITEMS_FAILED_TO_START");
    }

    private static ShadowBatch batch() {
        return new ShadowBatch(
                7,
                AgentShadowContracts.RUN_CONTROL_VERSION,
                BatchStatus.RUNNING,
                TriggerMode.MANUAL,
                LocalDate.of(2026, 7, 27),
                AgentShadowContracts.RULE_VERSION,
                SelectionMode.EXPLICIT,
                "a".repeat(64),
                2,
                2,
                0, 0, 0, 0, 0, 0, 0, 0,
                false,
                new ObjectMapper().createObjectNode(),
                null,
                Instant.EPOCH,
                null,
                "test",
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static ShadowItem item(long id, String symbol) {
        return new ShadowItem(
                id,
                7,
                (int) id,
                symbol,
                SelectionSource.EXPLICIT,
                "explicit:symbol=" + symbol,
                null,
                false,
                false,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
