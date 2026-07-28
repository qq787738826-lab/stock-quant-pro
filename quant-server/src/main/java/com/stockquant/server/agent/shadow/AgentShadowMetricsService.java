package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.marketfacts.PitMarketFactsContracts;
import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.shadow.AgentShadowModels.MetricsFilter;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentShadowMetricsService {

    private final AgentShadowRepository repository;

    public AgentShadowMetricsService(AgentShadowRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ShadowMetrics metrics(MetricsFilter filter) {
        validate(filter);
        var batches = repository.findMetricBatches(filter);
        var items = repository.findMetricItems(filter);
        var reviews = repository.findMetricReviews(filter);

        Map<String, Long> outcomes = zeroed(
                java.util.Arrays.stream(OutcomeClass.values())
                        .map(Enum::name).toList());
        Map<String, Long> decisions = zeroed(
                java.util.Arrays.stream(FinalDecisionCode.values())
                        .map(Enum::name).toList());
        Map<String, Long> reasons = new LinkedHashMap<>();
        Map<String, Map<String, Long>> runStatuses =
                new LinkedHashMap<>();
        Map<String, Map<String, Long>> runErrors =
                new LinkedHashMap<>();
        for (AgentCode code : AgentShadowContracts.AGENT_ORDER) {
            runStatuses.put(code.name(), new LinkedHashMap<>());
            runErrors.put(code.name(), new LinkedHashMap<>());
        }

        long dqBlocked = 0;
        long vetoes = 0;
        long cacheHits = 0;
        List<Long> durations = new ArrayList<>();
        int comparable = 0;
        int contextChanged = 0;
        int decisionChanged = 0;
        long absoluteScoreDelta = 0;
        int scoreDeltaCount = 0;
        long absoluteConfidenceDelta = 0;
        int confidenceDeltaCount = 0;

        for (ShadowItem item : items) {
            increment(outcomes, enumName(item.outcomeClass()));
            increment(decisions, enumName(item.finalDecision()));
            if (item.finalDecision()
                    == FinalDecisionCode.BLOCKED_BY_DATA_QUALITY) {
                dqBlocked++;
            }
            if (Boolean.TRUE.equals(item.vetoed())) {
                vetoes++;
            }
            if (item.cacheHit()) {
                cacheHits++;
            }
            if (item.primaryReasonCode() != null) {
                increment(reasons, item.primaryReasonCode());
            }
            if (item.durationMs() != null) {
                durations.add(item.durationMs());
            }
            if (item.previousItemId() != null) {
                comparable++;
                if (Boolean.TRUE.equals(item.contextChanged())) {
                    contextChanged++;
                }
                if (Boolean.TRUE.equals(item.decisionChanged())) {
                    decisionChanged++;
                }
                if (item.scoreDelta() != null) {
                    absoluteScoreDelta += Math.abs(item.scoreDelta());
                    scoreDeltaCount++;
                }
                if (item.confidenceDelta() != null) {
                    absoluteConfidenceDelta +=
                            Math.abs(item.confidenceDelta());
                    confidenceDeltaCount++;
                }
            }
            accumulateRuns(
                    item.runSnapshot(), runStatuses, runErrors);
        }

        Map<String, Long> reviewLabels = new LinkedHashMap<>();
        Set<Long> reviewedItems = new HashSet<>();
        reviews.forEach(review -> {
            increment(reviewLabels, review.label().name());
            reviewedItems.add(review.itemId());
        });
        long unreviewed = items.stream()
                .filter(ShadowItem::terminal)
                .filter(item -> !reviewedItems.contains(item.id()))
                .count();
        durations.sort(Long::compareTo);

        return new ShadowMetrics(
                AgentShadowContracts.METRICS_VERSION,
                batches.size(),
                items.size(),
                Map.copyOf(outcomes),
                Map.copyOf(decisions),
                dqBlocked,
                vetoes,
                cacheHits,
                ratio(cacheHits, items.size()),
                Map.copyOf(reasons),
                deepCopy(runStatuses),
                deepCopy(runErrors),
                percentile(durations, 0.50),
                percentile(durations, 0.95),
                ratio(contextChanged, comparable),
                ratio(decisionChanged, comparable),
                average(absoluteScoreDelta, scoreDeltaCount),
                average(
                        absoluteConfidenceDelta,
                        confidenceDeltaCount),
                Map.copyOf(reviewLabels),
                unreviewed);
    }

    @Transactional(readOnly = true)
    public List<ShadowItem> drift(MetricsFilter filter) {
        validate(filter);
        return repository.findMetricItems(filter).stream()
                .filter(item -> item.previousItemId() != null)
                .toList();
    }

    private static void accumulateRuns(
            JsonNode snapshot,
            Map<String, Map<String, Long>> statuses,
            Map<String, Map<String, Long>> errors
    ) {
        if (snapshot == null
                || !snapshot.path("runs").isArray()) {
            return;
        }
        for (JsonNode run : snapshot.path("runs")) {
            String code = run.path("agentCode").asText("");
            if (!statuses.containsKey(code)) {
                continue;
            }
            String status = run.path("status").asText("");
            if (!status.isBlank()) {
                increment(statuses.get(code), status);
            }
            JsonNode runErrors = run.path("errors");
            if (runErrors.isArray()) {
                for (JsonNode error : runErrors) {
                    String errorCode =
                            error.path("code").asText("").trim();
                    if (!errorCode.isEmpty()) {
                        increment(errors.get(code), errorCode);
                    }
                }
            }
        }
    }

    private static Map<String, Long> zeroed(List<String> keys) {
        Map<String, Long> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, 0L));
        return result;
    }

    private static void increment(
            Map<String, Long> values,
            String key
    ) {
        if (key != null) {
            values.merge(key, 1L, Long::sum);
        }
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static double ratio(long value, long total) {
        return total == 0
                ? 0.0
                : Math.round(
                value * 10_000.0 / total) / 10_000.0;
    }

    private static Double average(long total, int count) {
        return count == 0
                ? null
                : Math.round(total * 10_000.0 / count) / 10_000.0;
    }

    private static Long percentile(
            List<Long> sorted,
            double percentile
    ) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static Map<String, Map<String, Long>> deepCopy(
            Map<String, Map<String, Long>> source
    ) {
        Map<String, Map<String, Long>> result =
                new LinkedHashMap<>();
        source.forEach((key, value) ->
                result.put(key, Map.copyOf(value)));
        return Map.copyOf(result);
    }

    private static void validate(MetricsFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException(
                    "metrics filter is required");
        }
        if (filter.fromDate() != null
                && filter.toDate() != null
                && filter.fromDate().isAfter(filter.toDate())) {
            throw new IllegalArgumentException(
                    "fromDate must not follow toDate");
        }
        if (filter.ruleVersion() != null
                && !filter.ruleVersion().isBlank()
                && !AgentShadowContracts.RULE_VERSION.equals(
                filter.ruleVersion())
                && !PitMarketFactsContracts.RULE_VERSION.equals(
                filter.ruleVersion())) {
            throw new IllegalArgumentException(
                    "unsupported shadow ruleVersion");
        }
        if (filter.batchId() != null && filter.batchId() <= 0) {
            throw new IllegalArgumentException(
                    "batchId must be positive");
        }
        if (filter.symbol() != null
                && !filter.symbol().isBlank()
                && !filter.symbol().matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException(
                    "symbol must contain six digits");
        }
    }
}
