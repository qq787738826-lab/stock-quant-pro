package com.stockquant.server.agent.service;

import com.stockquant.server.agent.client.AgentTeamClient;
import com.stockquant.server.agent.exception.AgentTaskNotFoundException;
import com.stockquant.server.agent.model.AgentModels.AgentRun;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.RunIds;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AgentTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskWorker.class);

    private final AgentTaskRepository taskRepository;
    private final AgentRunRepository runRepository;
    private final AgentTaskStartService startService;
    private final AgentTeamClient client;
    private final AgentResponseValidator validator;
    private final AgentResultPersistenceService persistenceService;
    private final AgentFailureService failureService;
    private final AgentSafeErrorMapper safeErrorMapper;
    private final Clock clock;

    public AgentTaskWorker(
            AgentTaskRepository taskRepository,
            AgentRunRepository runRepository,
            AgentTaskStartService startService,
            AgentTeamClient client,
            AgentResponseValidator validator,
            AgentResultPersistenceService persistenceService,
            AgentFailureService failureService,
            AgentSafeErrorMapper safeErrorMapper
    ) {
        this(taskRepository, runRepository, startService, client, validator, persistenceService,
                failureService, safeErrorMapper, Clock.systemUTC());
    }

    AgentTaskWorker(
            AgentTaskRepository taskRepository,
            AgentRunRepository runRepository,
            AgentTaskStartService startService,
            AgentTeamClient client,
            AgentResponseValidator validator,
            AgentResultPersistenceService persistenceService,
            AgentFailureService failureService,
            AgentSafeErrorMapper safeErrorMapper,
            Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.startService = startService;
        this.client = client;
        this.validator = validator;
        this.persistenceService = persistenceService;
        this.failureService = failureService;
        this.safeErrorMapper = safeErrorMapper;
        this.clock = clock;
    }

    public void execute(long taskId) {
        Instant startedAt = clock.instant();
        try {
            if (!startService.claim(taskId)) {
                return;
            }
            AgentTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new AgentTaskNotFoundException(taskId));
            List<AgentRun> runs = runRepository.findByTaskId(taskId);
            RunIds runIds = RunIds.from(runs);

            AgentTeamRequest request = new AgentTeamRequest(
                    "1.0",
                    task.id(),
                    runIds,
                    task.symbol(),
                    task.tradeDate(),
                    task.contextHash(),
                    task.contextSchemaVersion(),
                    task.ruleVersion(),
                    task.executionMode(),
                    task.contextSnapshot(),
                    clock.instant()
            );
            AgentTeamResponse response = client.analyze(request);
            validator.validate(request, response);
            persistenceService.persist(response, Duration.between(startedAt, clock.instant()));
        } catch (Exception error) {
            long durationMs = Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
            log.error("智能体团队任务失败 taskId={}", taskId, error);
            failureService.markFailed(taskId, safeErrorMapper.publicMessage(error), durationMs);
        }
    }
}
