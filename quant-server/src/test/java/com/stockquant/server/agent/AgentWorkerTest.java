package com.stockquant.server.agent;

import com.stockquant.server.agent.client.AgentTeamClient;
import com.stockquant.server.agent.client.HttpAgentTeamClient;
import com.stockquant.server.agent.config.AgentTeamProperties;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.exception.AgentTeamClientException;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.service.AgentFailureService;
import com.stockquant.server.agent.service.AgentResultPersistenceService;
import com.stockquant.server.agent.service.AgentSafeErrorMapper;
import com.stockquant.server.agent.service.AgentTaskCreatedEvent;
import com.stockquant.server.agent.service.AgentTaskEventListener;
import com.stockquant.server.agent.service.AgentTaskStartService;
import com.stockquant.server.agent.service.AgentTaskWorker;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkerTest {

    @Test
    void taskListenerIsBoundToAfterCommit() throws Exception {
        Method method = AgentTaskEventListener.class.getMethod("onTaskCreated", AgentTaskCreatedEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }

    @Test
    void committedEventUsesDedicatedExecutorToInvokeWorker() {
        AgentTaskWorker worker = mock(AgentTaskWorker.class);
        AgentFailureService failure = mock(AgentFailureService.class);
        AgentTaskEventListener listener = new AgentTaskEventListener(new SyncTaskExecutor(), worker, failure);

        listener.onTaskCreated(new AgentTaskCreatedEvent(1));

        verify(worker).execute(1);
    }

    @Test
    void clientFailureMarksTaskAndRunsFailedWithoutPersistingDecision() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentTaskStartService start = mock(AgentTaskStartService.class);
        AgentTeamClient client = mock(AgentTeamClient.class);
        AgentResponseValidator validator = mock(AgentResponseValidator.class);
        AgentResultPersistenceService persistence = mock(AgentResultPersistenceService.class);
        AgentFailureService failure = mock(AgentFailureService.class);
        when(tasks.findById(1)).thenReturn(Optional.of(AgentTestFixtures.task(1, com.stockquant.server.agent.model.AgentTypes.TaskStatus.QUEUED)));
        when(runs.findByTaskId(1)).thenReturn(AgentTestFixtures.runs(1));
        when(start.claim(1)).thenReturn(true);
        when(client.analyze(any())).thenThrow(new AgentTeamClientException(
                AgentTeamClientException.Kind.DISABLED, "agent team client disabled"));
        AgentTaskWorker worker = new AgentTaskWorker(tasks, runs, start, client, validator, persistence,
                failure, new AgentSafeErrorMapper());

        worker.execute(1);

        verify(start).claim(1);
        verify(failure).markFailed(eq(1L), eq("智能体团队功能未启用"), any(Long.class));
        verify(persistence, never()).persist(any(), any());
    }

    @Test
    void failureServiceUpdatesOnlyTaskAndUnfinishedRuns() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentFailureService failure = new AgentFailureService(tasks, runs);

        failure.markFailed(2, "python unavailable", 25);

        verify(runs).markUnfinishedFailed(2, "python unavailable", 25);
        verify(tasks).markFailed(2, "python unavailable");
    }

    @Test
    void successfulWorkerCallsPythonExactlyOnceAndPersistsAfterValidation() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentTaskStartService start = mock(AgentTaskStartService.class);
        AgentTeamClient client = mock(AgentTeamClient.class);
        AgentResponseValidator validator = mock(AgentResponseValidator.class);
        AgentResultPersistenceService persistence = mock(AgentResultPersistenceService.class);
        AgentFailureService failure = mock(AgentFailureService.class);
        when(tasks.findById(1)).thenReturn(Optional.of(AgentTestFixtures.task(1, com.stockquant.server.agent.model.AgentTypes.TaskStatus.QUEUED)));
        when(runs.findByTaskId(1)).thenReturn(AgentTestFixtures.runs(1));
        when(start.claim(1)).thenReturn(true);
        when(client.analyze(any())).thenReturn(AgentTestFixtures.validResponse());
        AgentTaskWorker worker = new AgentTaskWorker(tasks, runs, start, client, validator, persistence,
                failure, new AgentSafeErrorMapper());

        worker.execute(1);

        verify(client).analyze(any());
        verify(validator).validate(any(), eq(AgentTestFixtures.validResponse()));
        verify(persistence).persist(eq(AgentTestFixtures.validResponse()), any(Duration.class));
        verify(failure, never()).markFailed(any(Long.class), any(), any(Long.class));
    }

    @Test
    void disabledHttpClientFailsBeforeAnyNetworkCall() {
        RestClient restClient = mock(RestClient.class);
        AgentTeamProperties properties = new AgentTeamProperties();
        properties.setEnabled(false);
        HttpAgentTeamClient client = new HttpAgentTeamClient(restClient, properties);

        assertThrows(AgentTeamException.class, () -> client.analyze(AgentTestFixtures.request()));
        verify(restClient, never()).post();
    }

    @Test
    void resultPersistenceIsTransactional() throws Exception {
        Method method = AgentResultPersistenceService.class.getMethod(
                "persist",
                com.stockquant.server.agent.model.AgentModels.AgentTeamResponse.class,
                Duration.class
        );
        org.springframework.transaction.annotation.Transactional annotation =
                method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRED, annotation.propagation());
    }

    @Test
    void duplicateDispatchThatCannotClaimDoesNothing() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentTaskStartService start = mock(AgentTaskStartService.class);
        AgentTeamClient client = mock(AgentTeamClient.class);
        AgentResponseValidator validator = mock(AgentResponseValidator.class);
        AgentResultPersistenceService persistence = mock(AgentResultPersistenceService.class);
        AgentFailureService failure = mock(AgentFailureService.class);
        when(start.claim(1)).thenReturn(true, false);
        when(tasks.findById(1)).thenReturn(Optional.of(AgentTestFixtures.task(1,
                com.stockquant.server.agent.model.AgentTypes.TaskStatus.RUNNING)));
        when(runs.findByTaskId(1)).thenReturn(AgentTestFixtures.runs(1));
        when(client.analyze(any())).thenReturn(AgentTestFixtures.validResponse());
        AgentTaskWorker worker = new AgentTaskWorker(
                tasks, runs, start, client, validator, persistence, failure, new AgentSafeErrorMapper()
        );

        worker.execute(1);
        worker.execute(1);

        verify(start, times(2)).claim(1);
        verify(client, times(1)).analyze(any());
        verify(persistence, times(1)).persist(any(), any());
        verify(failure, never()).markFailed(any(Long.class), any(), any(Long.class));
    }

    @Test
    void rejectedExecutorMarksFailedWithoutRunningWorker() {
        TaskExecutor executor = mock(TaskExecutor.class);
        AgentTaskWorker worker = mock(AgentTaskWorker.class);
        AgentFailureService failure = mock(AgentFailureService.class);
        org.mockito.Mockito.doThrow(new TaskRejectedException("queue full"))
                .when(executor).execute(any());
        AgentTaskEventListener listener = new AgentTaskEventListener(executor, worker, failure);

        listener.onTaskCreated(new AgentTaskCreatedEvent(9));

        verify(failure).markFailed(9, "agent team executor queue is full", 0);
        verify(worker, never()).execute(any(Long.class));
    }

    @Test
    void sensitiveUnderlyingFailureIsReplacedByControlledPublicMessage() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentTaskStartService start = mock(AgentTaskStartService.class);
        AgentTeamClient client = mock(AgentTeamClient.class);
        AgentResponseValidator validator = mock(AgentResponseValidator.class);
        AgentResultPersistenceService persistence = mock(AgentResultPersistenceService.class);
        AgentFailureService failure = mock(AgentFailureService.class);
        when(start.claim(1)).thenReturn(true);
        when(tasks.findById(1)).thenReturn(Optional.of(AgentTestFixtures.task(1,
                com.stockquant.server.agent.model.AgentTypes.TaskStatus.RUNNING)));
        when(runs.findByTaskId(1)).thenReturn(AgentTestFixtures.runs(1));
        when(client.analyze(any())).thenThrow(new RuntimeException(
                "jdbc:postgresql://db-host:5432/secret SELECT * FROM users "
                        + "http://python.internal:8001 C:\\private\\config.yml"));
        AgentTaskWorker worker = new AgentTaskWorker(tasks, runs, start, client, validator, persistence,
                failure, new AgentSafeErrorMapper());

        worker.execute(1);

        verify(failure).markFailed(eq(1L), eq("智能体团队执行失败"), any(Long.class));
        verify(persistence, never()).persist(any(), any());
    }

    @Test
    void safeErrorMapperClassifiesAllPublicFailureMessagesByExceptionType() {
        AgentSafeErrorMapper mapper = new AgentSafeErrorMapper();

        assertEquals("智能体团队功能未启用", mapper.publicMessage(new AgentTeamClientException(
                AgentTeamClientException.Kind.DISABLED, "secret disabled detail")));
        assertEquals("智能体分析服务暂时不可用", mapper.publicMessage(new AgentTeamClientException(
                AgentTeamClientException.Kind.SERVICE_UNAVAILABLE, "http://private:8001")));
        assertEquals("智能体响应校验失败", mapper.publicMessage(
                new AgentResponseValidationException("invalid payload: secret")));
        assertEquals("智能体团队执行失败", mapper.publicMessage(
                new RuntimeException("jdbc:postgresql://private")));
    }

    @Test
    void failureServiceUsesRequiresNewTransaction() throws Exception {
        Method method = AgentFailureService.class.getMethod("markFailed", long.class, String.class, long.class);
        org.springframework.transaction.annotation.Transactional annotation =
                method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, annotation.propagation());
    }
}
