package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.config.AgentShadowProperties;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.service.AgentTaskService;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AgentShadowRunner {

    private static final Logger log = LoggerFactory.getLogger(
            AgentShadowRunner.class);
    private static final int CONSECUTIVE_FAILURE_LIMIT = 2;

    private final AgentShadowRepository shadowRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentTaskService taskService;
    private final AgentShadowLifecycleService lifecycleService;
    private final AgentShadowProperties properties;
    private final Clock clock;

    public AgentShadowRunner(
            AgentShadowRepository shadowRepository,
            AgentTaskRepository taskRepository,
            AgentTaskService taskService,
            AgentShadowLifecycleService lifecycleService,
            AgentShadowProperties properties,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.shadowRepository = shadowRepository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
        this.clock = clock;
    }

    public void run(long batchId) {
        String circuitReason = null;
        Map<Long, ShadowItem> inFlight = new LinkedHashMap<>();
        int consecutiveCreateFailures = 0;
        int consecutivePythonFailures = 0;
        int startFailures = 0;
        try {
            ShadowBatch batch = lifecycleService.start(batchId);
            while (true) {
                batch = requireBatch(batchId);
                if (batch.cancellationRequested()
                        || circuitReason != null) {
                    lifecycleService.cancelUnstarted(batchId);
                }

                PollResult poll = poll(inFlight);
                consecutivePythonFailures =
                        poll.pythonUnavailableFailures() > 0
                                ? consecutivePythonFailures
                                + poll.pythonUnavailableFailures()
                                : poll.completedTasks() > 0
                                ? 0 : consecutivePythonFailures;
                if (consecutivePythonFailures
                        >= CONSECUTIVE_FAILURE_LIMIT) {
                    circuitReason =
                            "SHADOW_PYTHON_CONSECUTIVELY_UNAVAILABLE";
                }

                if (!batch.cancellationRequested()
                        && circuitReason == null) {
                    List<ShadowItem> unstarted =
                            shadowRepository.findUnstartedItems(batchId);
                    int capacity = properties.getMaxConcurrency()
                            - inFlight.size();
                    for (ShadowItem item : unstarted) {
                        if (capacity <= 0) {
                            break;
                        }
                        try {
                            CreatedTask created = taskService.create(
                                    request(batch, item.symbol()),
                                    "shadow:" + batchId);
                            ShadowItem attached =
                                    lifecycleService.attachTask(
                                            item.id(),
                                            created.task().id(),
                                            created.newlyCreated());
                            inFlight.put(attached.id(), attached);
                            consecutiveCreateFailures = 0;
                        } catch (RuntimeException error) {
                            lifecycleService.failLaunch(
                                    item,
                                    safeMessage(error));
                            startFailures++;
                            consecutiveCreateFailures++;
                            if (consecutiveCreateFailures
                                    >= CONSECUTIVE_FAILURE_LIMIT) {
                                circuitReason =
                                        "SHADOW_ITEM_CREATION_CONSECUTIVE_FAILURE";
                                break;
                            }
                        }
                        capacity--;
                    }
                    if (startFailures * 2 > batch.selectedCount()) {
                        circuitReason =
                                "SHADOW_MORE_THAN_HALF_ITEMS_FAILED_TO_START";
                    }
                }

                boolean hasUnstarted = !shadowRepository
                        .findUnstartedItems(batchId).isEmpty();
                if (inFlight.isEmpty() && !hasUnstarted) {
                    break;
                }
                pause();
            }
            lifecycleService.finish(batchId, circuitReason);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            safelyFinishAfterCircuit(
                    batchId,
                    "SHADOW_RUNNER_INTERRUPTED");
        } catch (RuntimeException error) {
            log.error(
                    "shadow batch failed, batchId={}",
                    batchId,
                    error);
            safelyFinishAfterCircuit(
                    batchId,
                    "SHADOW_RUNNER_FAILURE");
        }
    }

    private PollResult poll(Map<Long, ShadowItem> inFlight) {
        int completed = 0;
        int pythonUnavailable = 0;
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ShadowItem> entry = iterator.next();
            ShadowItem item = shadowRepository.findItem(
                    entry.getKey()).orElseThrow();
            AgentTask task = taskRepository.findById(
                    item.agentTaskId()).orElseThrow();
            if (terminal(task.status())) {
                lifecycleService.finishTask(item, task);
                completed++;
                if (task.status() == TaskStatus.FAILED
                        && pythonUnavailable(task.errorMessage())) {
                    pythonUnavailable++;
                }
                iterator.remove();
                continue;
            }
            if (item.startedAt() != null
                    && Duration.between(
                            item.startedAt(), clock.instant())
                    .compareTo(properties.getItemTimeout()) > 0) {
                lifecycleService.timeout(item, task);
                completed++;
                iterator.remove();
            }
        }
        return new PollResult(completed, pythonUnavailable);
    }

    private CreateAgentTaskRequest request(
            ShadowBatch batch,
            String symbol
    ) {
        return new CreateAgentTaskRequest(
                symbol,
                batch.tradeDate(),
                ExecutionMode.LOCAL_RULES,
                batch.ruleVersion(),
                false,
                TriggerType.SHADOW);
    }

    private ShadowBatch requireBatch(long batchId) {
        return shadowRepository.findBatch(batchId)
                .orElseThrow(() -> new IllegalStateException(
                        "shadow batch disappeared: " + batchId));
    }

    private void safelyFinishAfterCircuit(
            long batchId,
            String reason
    ) {
        try {
            ShadowBatch batch = requireBatch(batchId);
            if (batch.status().terminal()) {
                return;
            }
            lifecycleService.cancelUnstarted(batchId);
            lifecycleService.finish(batchId, reason);
        } catch (RuntimeException finalizationError) {
            log.error(
                    "shadow batch finalization failed, batchId={}",
                    batchId,
                    finalizationError);
        }
    }

    private void pause() throws InterruptedException {
        Thread.sleep(properties.getPollInterval().toMillis());
    }

    private static boolean terminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.PARTIAL
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    private static boolean pythonUnavailable(String message) {
        if (message == null) {
            return false;
        }
        String value = message.toLowerCase(Locale.ROOT);
        return value.contains("python")
                || value.contains("connection")
                || value.contains("connect")
                || value.contains("服务不可用")
                || value.contains("连接");
    }

    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            return "SHADOW_ITEM_CREATION_FAILED";
        }
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() <= 500
                ? value
                : value.substring(0, 500);
    }

    private record PollResult(
            int completedTasks,
            int pythonUnavailableFailures
    ) {
    }
}
