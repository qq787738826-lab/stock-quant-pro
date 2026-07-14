package com.stockquant.server.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AgentTaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskEventListener.class);

    private final TaskExecutor executor;
    private final AgentTaskWorker worker;
    private final AgentFailureService failureService;

    public AgentTaskEventListener(
            @Qualifier("agentTeamExecutor") TaskExecutor executor,
            AgentTaskWorker worker,
            AgentFailureService failureService
    ) {
        this.executor = executor;
        this.worker = worker;
        this.failureService = failureService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(AgentTaskCreatedEvent event) {
        try {
            executor.execute(() -> worker.execute(event.taskId()));
        } catch (RuntimeException rejected) {
            log.error("智能体任务调度失败 taskId={}", event.taskId(), rejected);
            failureService.markFailed(event.taskId(), "agent team executor queue is full", 0);
        }
    }
}
