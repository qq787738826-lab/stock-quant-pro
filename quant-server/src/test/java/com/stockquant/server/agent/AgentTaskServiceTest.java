package com.stockquant.server.agent;

import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.model.AgentModels.CacheKey;
import com.stockquant.server.agent.model.AgentModels.ContextSnapshot;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import com.stockquant.server.agent.repository.AgentEvidenceRepository;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.repository.AgentVetoRepository;
import com.stockquant.server.agent.service.AgentCacheService;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import com.stockquant.server.agent.service.AgentTaskCreationTransaction;
import com.stockquant.server.agent.service.AgentTaskCreatedEvent;
import com.stockquant.server.agent.service.AgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskServiceTest {

    @Mock AgentTaskRepository taskRepository;
    @Mock AgentRunRepository runRepository;
    @Mock AgentEvidenceRepository evidenceRepository;
    @Mock AgentDecisionRepository decisionRepository;
    @Mock AgentVetoRepository vetoRepository;
    @Mock AgentCacheService cacheService;
    @Mock AgentContextSnapshotService contextService;
    @Mock AgentTaskCreationTransaction creationTransaction;

    private AgentTaskService service;
    private CreateAgentTaskRequest request;
    private ContextSnapshot context;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(
                taskRepository, runRepository, evidenceRepository, decisionRepository, vetoRepository,
                cacheService, contextService, creationTransaction
        );
        request = new CreateAgentTaskRequest(
                "600000", AgentTestFixtures.TRADE_DATE, ExecutionMode.LOCAL_RULES,
                "rules-1", false, TriggerType.MANUAL
        );
        context = new ContextSnapshot("1.0", AgentTestFixtures.context(), AgentTestFixtures.NOW, AgentTestFixtures.HASH);
        lenient().when(contextService.create(request.symbol(), request.tradeDate())).thenReturn(context);
    }

    @Test
    void createsNewTaskWhenNoCacheMatches() {
        var task = AgentTestFixtures.task(1, TaskStatus.QUEUED);
        when(cacheService.active(any())).thenReturn(Optional.empty());
        when(cacheService.completed(any())).thenReturn(Optional.empty());
        when(creationTransaction.create(request, context, "API")).thenReturn(new CreatedTask(task, true));

        CreatedTask result = service.create(request, "API");

        assertTrue(result.newlyCreated());
        verify(creationTransaction).create(request, context, "API");
    }

    @Test
    void reusesActiveTask() {
        var active = AgentTestFixtures.task(7, TaskStatus.RUNNING);
        when(cacheService.active(any())).thenReturn(Optional.of(active));

        CreatedTask result = service.create(request, "API");

        assertEquals(7, result.task().id());
        assertFalse(result.newlyCreated());
        verify(cacheService, never()).completed(any());
        verify(creationTransaction, never()).create(any(), any(), any());
    }

    @Test
    void reusesCompletedTaskWhenForceRefreshIsFalse() {
        var completed = AgentTestFixtures.task(8, TaskStatus.COMPLETED);
        when(cacheService.active(any())).thenReturn(Optional.empty());
        when(cacheService.completed(any())).thenReturn(Optional.of(completed));

        assertEquals(8, service.create(request, "API").task().id());
        verify(creationTransaction, never()).create(any(), any(), any());
    }

    @Test
    void forceRefreshSkipsCompletedCache() {
        CreateAgentTaskRequest forced = new CreateAgentTaskRequest(
                request.symbol(), request.tradeDate(), request.executionMode(), request.ruleVersion(),
                true, request.triggerType()
        );
        var task = AgentTestFixtures.task(9, TaskStatus.QUEUED);
        when(cacheService.active(any())).thenReturn(Optional.empty());
        when(creationTransaction.create(eq(forced), any(), eq("API"))).thenReturn(new CreatedTask(task, true));

        assertEquals(9, service.create(forced, "API").task().id());
        verify(cacheService, never()).completed(any());
    }

    @Test
    void forceRefreshStillReusesActiveTask() {
        CreateAgentTaskRequest forced = new CreateAgentTaskRequest(
                request.symbol(), request.tradeDate(), request.executionMode(), request.ruleVersion(),
                true, request.triggerType()
        );
        var active = AgentTestFixtures.task(10, TaskStatus.QUEUED);
        when(cacheService.active(any())).thenReturn(Optional.of(active));

        assertFalse(service.create(forced, "API").newlyCreated());
        verify(creationTransaction, never()).create(any(), any(), any());
    }

    @Test
    void duplicateKeyRaceRequeriesAndReturnsActiveTask() {
        var winner = AgentTestFixtures.task(11, TaskStatus.RUNNING);
        when(cacheService.active(any())).thenReturn(Optional.empty(), Optional.of(winner));
        when(cacheService.completed(any())).thenReturn(Optional.empty());
        when(creationTransaction.create(request, context, "API"))
                .thenThrow(new DuplicateKeyException("active cache key"));

        CreatedTask result = service.create(request, "API");

        assertEquals(11, result.task().id());
        assertFalse(result.newlyCreated());
    }

    @Test
    void professionalAgentSetContainsExactlySixAndNoChiefRun() {
        assertEquals(6, AgentCode.PROFESSIONAL_AGENTS.size());
        assertFalse(AgentCode.PROFESSIONAL_AGENTS.stream().anyMatch(code -> code.name().equals("CHIEF_DECISION")));
    }

    @Test
    void creationTransactionPrecreatesRunsAndPublishesEvent() {
        AgentTaskRepository tasks = org.mockito.Mockito.mock(AgentTaskRepository.class);
        AgentRunRepository runs = org.mockito.Mockito.mock(AgentRunRepository.class);
        ApplicationEventPublisher publisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        AgentTaskCreationTransaction transaction = new AgentTaskCreationTransaction(tasks, runs, publisher);
        var task = AgentTestFixtures.task(12, TaskStatus.QUEUED);
        when(tasks.insert(any(), any(), any(), any(), any(), any(), any(), eq(false))).thenReturn(task);
        when(runs.createProfessionalRuns(12)).thenReturn(AgentTestFixtures.runs(12));

        transaction.create(request, context, "API");

        verify(runs).createProfessionalRuns(12);
        verify(publisher).publishEvent(new AgentTaskCreatedEvent(12));
    }

    @Test
    void sixtyFourCharacterRuleVersionPassesAndUsesOneNormalizedValue() {
        String version = "v".repeat(64);
        CreateAgentTaskRequest valid = new CreateAgentTaskRequest(
                request.symbol(), request.tradeDate(), request.executionMode(), "  " + version + "  ",
                false, request.triggerType());
        var task = AgentTestFixtures.task(13, TaskStatus.QUEUED);
        when(cacheService.active(any())).thenReturn(Optional.empty());
        when(cacheService.completed(any())).thenReturn(Optional.empty());
        when(creationTransaction.create(valid, context, "API")).thenReturn(new CreatedTask(task, true));

        assertEquals(13, service.create(valid, "API").task().id());
        org.mockito.ArgumentCaptor<CacheKey> key = org.mockito.ArgumentCaptor.forClass(CacheKey.class);
        verify(cacheService).active(key.capture());
        assertEquals(version, key.getValue().ruleVersion());
        assertEquals(version, valid.ruleVersion());
        verify(creationTransaction).create(valid, context, "API");
    }

    @Test
    void sixtyFiveCharacterRuleVersionIsRejectedByService() {
        CreateAgentTaskRequest invalid = new CreateAgentTaskRequest(
                request.symbol(), request.tradeDate(), request.executionMode(), "v".repeat(65),
                false, request.triggerType());
        assertThrows(IllegalArgumentException.class, () -> service.create(invalid, "API"));
    }

    @Test
    void whitespaceOnlyRuleVersionIsRejectedByService() {
        CreateAgentTaskRequest invalid = new CreateAgentTaskRequest(
                request.symbol(), request.tradeDate(), request.executionMode(), "   ",
                false, request.triggerType());
        assertThrows(IllegalArgumentException.class, () -> service.create(invalid, "API"));
    }
}
