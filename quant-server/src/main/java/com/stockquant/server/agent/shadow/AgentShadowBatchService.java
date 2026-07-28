package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.config.AgentShadowProperties;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionResult;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowFeatureStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.TriggerMode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class AgentShadowBatchService {

    private final AgentShadowRepository repository;
    private final AgentShadowSelectionService selectionService;
    private final AgentShadowProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AgentShadowBatchService(
            AgentShadowRepository repository,
            AgentShadowSelectionService selectionService,
            AgentShadowProperties properties,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.repository = repository;
        this.selectionService = selectionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ShadowBatch createManual(
            LocalDate tradeDate,
            SelectionMode selectionMode,
            List<String> explicitSymbols,
            Integer requestedMaxSymbols,
            String createdBy
    ) {
        requireEnabled();
        return create(
                TriggerMode.MANUAL,
                tradeDate,
                selectionMode,
                explicitSymbols,
                requestedMaxSymbols,
                createdBy);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ShadowBatch createScheduled() {
        requireEnabled();
        LocalDate tradeDate = clock.instant()
                .atZone(AgentShadowContracts.MARKET_ZONE)
                .toLocalDate();
        return create(
                TriggerMode.SCHEDULED,
                tradeDate,
                SelectionMode.AUTO,
                List.of(),
                properties.getMaxSymbols(),
                "scheduler");
    }

    @Transactional
    public ShadowBatch recordScheduledSkip(String reason) {
        LocalDate tradeDate = clock.instant()
                .atZone(AgentShadowContracts.MARKET_ZONE)
                .toLocalDate();
        SelectionResult selection = selectionService.empty(
                SelectionMode.AUTO,
                tradeDate,
                properties.getMaxSymbols());
        Instant now = clock.instant();
        return repository.insertBatch(
                BatchStatus.FAILED,
                TriggerMode.SCHEDULED,
                tradeDate,
                properties.getRuleVersion(),
                SelectionMode.AUTO,
                selection.selectionHash(),
                properties.getMaxSymbols(),
                0,
                configuration(
                        TriggerMode.SCHEDULED,
                        tradeDate,
                        SelectionMode.AUTO,
                        properties.getMaxSymbols(),
                        selection,
                        reason),
                reason,
                now,
                now,
                "scheduler");
    }

    public ShadowBatch batch(long batchId) {
        return repository.findBatch(batchId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "shadow batch does not exist: " + batchId));
    }

    public List<ShadowBatch> batches(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be within [1,200]");
        }
        return repository.findBatches(limit);
    }

    public List<ShadowItem> items(long batchId) {
        batch(batchId);
        return repository.findItems(batchId);
    }

    @Transactional
    public ShadowBatch cancel(long batchId) {
        ShadowBatch current = batch(batchId);
        if (current.status().terminal()) {
            throw new IllegalArgumentException(
                    "terminal shadow batch cannot be cancelled");
        }
        repository.requestCancellation(batchId);
        return batch(batchId);
    }

    public ShadowFeatureStatus featureStatus() {
        return new ShadowFeatureStatus(
                properties.isEnabled(),
                properties.isSchedulerEnabled(),
                properties.getRuleVersion(),
                properties.getZone(),
                properties.getSafeWindowStart().toString(),
                properties.getSafeWindowEnd().toString(),
                properties.getMaxSymbols(),
                properties.getMaxConcurrency(),
                properties.getItemTimeout().toString(),
                properties.getPollInterval().toString(),
                repository.hasActiveBatch());
    }

    @Transactional
    public void failQueued(long batchId, String message) {
        repository.failQueuedBatch(
                batchId,
                safeMessage(message),
                clock.instant());
    }

    private ShadowBatch create(
            TriggerMode triggerMode,
            LocalDate tradeDate,
            SelectionMode selectionMode,
            List<String> explicitSymbols,
            Integer requestedMaxSymbols,
            String createdBy
    ) {
        properties.validateFrozenContract();
        validateIdentity(
                tradeDate,
                createdBy,
                clock.instant()
                        .atZone(AgentShadowContracts.MARKET_ZONE)
                        .toLocalDate());
        int maxSymbols = requestedMaxSymbols == null
                ? properties.getMaxSymbols()
                : requestedMaxSymbols;
        if (selectionMode == SelectionMode.AUTO
                && !AgentShadowJob.weekday(tradeDate)) {
            return skipped(
                    triggerMode,
                    tradeDate,
                    selectionMode,
                    maxSymbols,
                    createdBy,
                    "SHADOW_AUTO_NON_WORKDAY");
        }
        if (selectionMode == SelectionMode.AUTO) {
            var calendarOpen = repository.reliableCalendarOpen(
                    tradeDate, clock.instant());
            if (calendarOpen.isPresent()
                    && !calendarOpen.orElseThrow()) {
                return skipped(
                        triggerMode,
                        tradeDate,
                        selectionMode,
                        maxSymbols,
                        createdBy,
                        "SHADOW_RELIABLE_CALENDAR_CLOSED");
            }
        }
        if (repository.hasActiveBatch()) {
            return skipped(
                    triggerMode,
                    tradeDate,
                    selectionMode,
                    maxSymbols,
                    createdBy,
                    "SHADOW_BATCH_OVERLAP");
        }
        if (repository.hasRunningMarketWork()) {
            return skipped(
                    triggerMode,
                    tradeDate,
                    selectionMode,
                    maxSymbols,
                    createdBy,
                    "SHADOW_CONFLICTING_MARKET_WORK");
        }
        SelectionResult selection = selectionService.select(
                selectionMode,
                explicitSymbols,
                maxSymbols,
                tradeDate);
        if (selection.entries().isEmpty()) {
            return skipped(
                    triggerMode,
                    tradeDate,
                    selectionMode,
                    maxSymbols,
                    createdBy,
                    "SHADOW_SELECTION_EMPTY");
        }
        ShadowBatch batch = repository.insertBatch(
                BatchStatus.QUEUED,
                triggerMode,
                tradeDate,
                properties.getRuleVersion(),
                selectionMode,
                selection.selectionHash(),
                maxSymbols,
                selection.entries().size(),
                configuration(
                        triggerMode,
                        tradeDate,
                        selectionMode,
                        maxSymbols,
                        selection,
                        null),
                null,
                null,
                null,
                createdBy.trim());
        repository.insertItems(batch.id(), selection.entries());
        eventPublisher.publishEvent(
                new AgentShadowBatchCreatedEvent(batch.id()));
        return batch;
    }

    private ShadowBatch skipped(
            TriggerMode triggerMode,
            LocalDate tradeDate,
            SelectionMode selectionMode,
            int maxSymbols,
            String createdBy,
            String reason
    ) {
        SelectionResult empty = selectionService.empty(
                selectionMode, tradeDate, maxSymbols);
        Instant now = clock.instant();
        return repository.insertBatch(
                BatchStatus.FAILED,
                triggerMode,
                tradeDate,
                properties.getRuleVersion(),
                selectionMode,
                empty.selectionHash(),
                maxSymbols,
                0,
                configuration(
                        triggerMode,
                        tradeDate,
                        selectionMode,
                        maxSymbols,
                        empty,
                        reason),
                reason,
                now,
                now,
                createdBy.trim());
    }

    private ObjectNode configuration(
            TriggerMode triggerMode,
            LocalDate tradeDate,
            SelectionMode selectionMode,
            int maxSymbols,
            SelectionResult selection,
            String skipReason
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("contractVersion",
                AgentShadowContracts.RUN_CONTROL_VERSION);
        node.put("selectionContractVersion",
                AgentShadowContracts.SELECTION_VERSION);
        node.put("outcomeSnapshotContractVersion",
                AgentShadowContracts.OUTCOME_SNAPSHOT_VERSION);
        node.put("ruleVersion", properties.getRuleVersion());
        node.put("triggerMode", triggerMode.name());
        node.put("tradeDate", tradeDate.toString());
        node.put("selectionMode", selectionMode.name());
        node.put("selectionHash", selection.selectionHash());
        node.put("configuredMaxSymbols", maxSymbols);
        node.put("maxConcurrency", properties.getMaxConcurrency());
        node.put("itemTimeout", properties.getItemTimeout().toString());
        node.put("pollInterval", properties.getPollInterval().toString());
        node.put("schedulerEnabled", properties.isSchedulerEnabled());
        node.put("safeWindowStart",
                properties.getSafeWindowStart().toString());
        node.put("safeWindowEnd",
                properties.getSafeWindowEnd().toString());
        node.put("marketTimezone", properties.getZone());
        node.put("businessTablesReadOnly", true);
        node.put("externalCaptureTriggered", false);
        node.put("marketRefreshTriggered", false);
        node.put("fullMarketScanTriggered", false);
        if (skipReason == null) {
            node.putNull("skipReason");
        } else {
            node.put("skipReason", skipReason);
        }
        ArrayNode limitations = node.putArray("limitations");
        limitations.add("2F_REQUIRES_REQUEST_DATE_DAY_END");
        limitations.add("2H_REQUIRES_CURRENT_SHANGHAI_DATE");
        limitations.add("SOURCE_REVISION_MAY_BE_UNVERIFIABLE");
        limitations.add("ANNOUNCEMENT_CAPTURE_MAY_BE_UNAVAILABLE_OR_STALE");
        limitations.add("READINESS_OBSERVATION_NOT_STRATEGY_PERFORMANCE");
        return node;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "shadow execution is disabled");
        }
    }

    private static void validateIdentity(
            LocalDate tradeDate,
            String createdBy,
            LocalDate today
    ) {
        if (tradeDate == null) {
            throw new IllegalArgumentException(
                    "tradeDate is required");
        }
        if (tradeDate.isAfter(today)) {
            throw new IllegalArgumentException(
                    "future shadow tradeDate is not allowed");
        }
        if (createdBy == null || createdBy.isBlank()
                || createdBy.trim().length() > 128) {
            throw new IllegalArgumentException(
                    "createdBy must contain 1 to 128 characters");
        }
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "SHADOW_RUNNER_START_FAILED";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500
                ? normalized
                : normalized.substring(0, 500);
    }
}
