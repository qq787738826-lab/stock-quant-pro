package com.stockquant.server.agent.shadow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AgentShadowBatchEventListener {

    private static final Logger log = LoggerFactory.getLogger(
            AgentShadowBatchEventListener.class);

    private final TaskExecutor executor;
    private final AgentShadowRunner runner;
    private final AgentShadowBatchService batchService;

    public AgentShadowBatchEventListener(
            @Qualifier("agentShadowExecutor") TaskExecutor executor,
            AgentShadowRunner runner,
            AgentShadowBatchService batchService
    ) {
        this.executor = executor;
        this.runner = runner;
        this.batchService = batchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBatchCreated(AgentShadowBatchCreatedEvent event) {
        try {
            executor.execute(() -> runner.run(event.batchId()));
        } catch (RuntimeException rejected) {
            log.error(
                    "shadow runner scheduling failed, batchId={}",
                    event.batchId(),
                    rejected);
            batchService.failQueued(
                    event.batchId(),
                    "SHADOW_RUNNER_SCHEDULING_FAILED");
        }
    }
}
