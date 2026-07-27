package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class AgentShadowModels {

    private AgentShadowModels() {
    }

    public enum BatchStatus {
        QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == PARTIAL
                    || this == FAILED || this == CANCELLED;
        }
    }

    public enum TriggerMode { MANUAL, SCHEDULED }

    public enum SelectionMode { EXPLICIT, AUTO }

    public enum SelectionSource {
        EXPLICIT, CURRENT_POSITION, LATEST_SCAN_CANDIDATE
    }

    public enum OutcomeClass { DETERMINED, INSUFFICIENT, FAILED, CANCELLED }

    public enum ReviewLabel {
        EXPECTED,
        UNEXPECTED,
        DATA_ISSUE,
        RULE_ISSUE,
        FALSE_POSITIVE,
        FALSE_NEGATIVE,
        NEEDS_FOLLOW_UP
    }

    public record SelectionEntry(
            int selectionOrder,
            String symbol,
            SelectionSource selectionSource,
            String selectionSourceRef
    ) {
    }

    public record SelectionResult(
            String selectionHash,
            List<SelectionEntry> entries
    ) {
    }

    public record ShadowBatch(
            long id,
            String contractVersion,
            BatchStatus status,
            TriggerMode triggerMode,
            LocalDate tradeDate,
            String ruleVersion,
            SelectionMode selectionMode,
            String selectionHash,
            int configuredMaxSymbols,
            int selectedCount,
            int launchedCount,
            int terminalCount,
            int determinedCount,
            int insufficientCount,
            int failedCount,
            int vetoCount,
            int dataQualityBlockedCount,
            int cacheHitCount,
            boolean cancellationRequested,
            JsonNode configuration,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ShadowItem(
            long id,
            long batchId,
            int selectionOrder,
            String symbol,
            SelectionSource selectionSource,
            String selectionSourceRef,
            Long agentTaskId,
            boolean taskNewlyCreated,
            boolean cacheHit,
            TaskStatus taskStatus,
            FinalDecisionCode finalDecision,
            GateStatus gateStatus,
            Integer score,
            Integer confidence,
            Boolean vetoed,
            OutcomeClass outcomeClass,
            String primaryReasonCode,
            JsonNode reasonCodes,
            JsonNode runSnapshot,
            String contextHash,
            Long durationMs,
            Long previousItemId,
            Boolean contextChanged,
            Boolean decisionChanged,
            Integer scoreDelta,
            Integer confidenceDelta,
            JsonNode changedAgents,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public boolean terminal() {
            return outcomeClass != null;
        }
    }

    public record ShadowReview(
            long id,
            long batchId,
            long itemId,
            String reviewContractVersion,
            ReviewLabel label,
            String note,
            String reviewer,
            Long supersedesReviewId,
            Instant createdAt
    ) {
    }

    public record ShadowFeatureStatus(
            boolean enabled,
            boolean schedulerEnabled,
            String ruleVersion,
            String zone,
            String safeWindowStart,
            String safeWindowEnd,
            int maxSymbols,
            int maxConcurrency,
            String itemTimeout,
            String pollInterval,
            boolean activeBatch
    ) {
    }

    public record ShadowMetrics(
            String contractVersion,
            long batchCount,
            long itemCount,
            Map<String, Long> outcomeDistribution,
            Map<String, Long> finalDecisionDistribution,
            long dataQualityBlockedCount,
            long vetoCount,
            long cacheHitCount,
            double cacheHitRate,
            Map<String, Long> primaryReasonCodeDistribution,
            Map<String, Map<String, Long>> agentRunStatusDistribution,
            Map<String, Map<String, Long>> agentErrorDistribution,
            Long p50DurationMs,
            Long p95DurationMs,
            double contextChangeRate,
            double decisionChangeRate,
            Double averageAbsoluteScoreChange,
            Double averageAbsoluteConfidenceChange,
            Map<String, Long> reviewLabelDistribution,
            long unreviewedItemCount
    ) {
    }

    public record MetricsFilter(
            LocalDate fromDate,
            LocalDate toDate,
            String ruleVersion,
            Long batchId,
            String symbol
    ) {
    }

    public record TerminalOutcome(
            TaskStatus taskStatus,
            FinalDecisionCode finalDecision,
            GateStatus gateStatus,
            Integer score,
            Integer confidence,
            boolean vetoed,
            OutcomeClass outcomeClass,
            List<String> reasonCodes,
            JsonNode runSnapshot,
            String contextHash,
            long durationMs,
            String errorMessage
    ) {
    }

    public record DriftResult(
            Long previousItemId,
            Boolean contextChanged,
            Boolean decisionChanged,
            Integer scoreDelta,
            Integer confidenceDelta,
            JsonNode changedAgents
    ) {
    }
}
