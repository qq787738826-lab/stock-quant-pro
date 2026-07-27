package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.TerminalOutcome;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class AgentShadowLifecycleService {

    private final AgentShadowRepository repository;
    private final AgentShadowOutcomeService outcomeService;
    private final Clock clock;

    public AgentShadowLifecycleService(
            AgentShadowRepository repository,
            AgentShadowOutcomeService outcomeService,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.repository = repository;
        this.outcomeService = outcomeService;
        this.clock = clock;
    }

    @Transactional
    public ShadowBatch start(long batchId) {
        repository.markBatchRunning(batchId, clock.instant());
        return repository.findBatch(batchId).orElseThrow();
    }

    @Transactional
    public ShadowItem attachTask(
            long itemId,
            long taskId,
            boolean newlyCreated
    ) {
        repository.attachTask(
                itemId, taskId, newlyCreated, clock.instant());
        return repository.findItem(itemId).orElseThrow();
    }

    @Transactional
    public void finishTask(
            ShadowItem item,
            AgentTask task
    ) {
        Instant now = clock.instant();
        TerminalOutcome outcome = outcomeService.terminalOutcome(
                item, task, now);
        repository.finishItem(
                item.id(),
                outcome,
                outcomeService.drift(item, outcome),
                now);
    }

    @Transactional
    public void failLaunch(ShadowItem item, String message) {
        Instant now = clock.instant();
        TerminalOutcome outcome = outcomeService.launchFailure(
                item, message, now);
        repository.finishItem(
                item.id(),
                outcome,
                outcomeService.drift(item, outcome),
                now);
    }

    @Transactional
    public void timeout(ShadowItem item, AgentTask task) {
        Instant now = clock.instant();
        TerminalOutcome outcome = outcomeService.timeout(
                item, task, now);
        repository.finishItem(
                item.id(),
                outcome,
                outcomeService.drift(item, outcome),
                now);
    }

    @Transactional
    public void cancelUnstarted(long batchId) {
        repository.cancelUnstartedItems(batchId, clock.instant());
    }

    @Transactional
    public ShadowBatch finish(
            long batchId,
            String circuitReason
    ) {
        ShadowBatch batch = repository.findBatch(batchId).orElseThrow();
        var counts = repository.counts(batchId);
        BatchStatus status;
        String errorMessage = circuitReason;
        if (batch.cancellationRequested()) {
            status = counts.launchedCount() == 0
                    ? BatchStatus.CANCELLED
                    : BatchStatus.PARTIAL;
            errorMessage = "SHADOW_CANCELLATION_REQUESTED";
        } else if (circuitReason != null) {
            status = counts.terminalCount() == 0
                    ? BatchStatus.FAILED
                    : BatchStatus.PARTIAL;
        } else if (counts.failedCount() > 0) {
            status = counts.failedCount() == batch.selectedCount()
                    ? BatchStatus.FAILED
                    : BatchStatus.PARTIAL;
            errorMessage = "SHADOW_ITEMS_FAILED";
        } else {
            status = BatchStatus.COMPLETED;
        }
        repository.finishBatch(
                batchId,
                status,
                counts,
                errorMessage,
                clock.instant());
        return repository.findBatch(batchId).orElseThrow();
    }
}
