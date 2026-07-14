package com.stockquant.server.agent.service;

import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.ContextSnapshot;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskCreationTransaction {

    private final AgentTaskRepository taskRepository;
    private final AgentRunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AgentTaskCreationTransaction(
            AgentTaskRepository taskRepository,
            AgentRunRepository runRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CreatedTask create(CreateAgentTaskRequest request, ContextSnapshot context, String requestedBy) {
        AgentTask task = taskRepository.insert(
                request.symbol(),
                request.tradeDate(),
                context,
                request.ruleVersion(),
                request.executionMode(),
                request.triggerType(),
                requestedBy,
                request.forceRefresh()
        );
        runRepository.createProfessionalRuns(task.id());
        eventPublisher.publishEvent(new AgentTaskCreatedEvent(task.id()));
        return new CreatedTask(task, true);
    }
}
