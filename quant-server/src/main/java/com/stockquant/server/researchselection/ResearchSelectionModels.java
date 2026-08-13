package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyComparison;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Stable user-facing contracts for current-as-of stock selection. */
public final class ResearchSelectionModels {
    public static final String VERSION = "RESEARCH_SELECTION_V1";
    public static final String RANKING_VERSION =
            "RESEARCH_SELECTION_RANKING_V1";
    public static final int DEFAULT_PRIMARY_WINDOW = 20;
    public static final int DEFAULT_AUXILIARY_WINDOW = 60;
    public static final int DEFAULT_SHORTLIST_SIZE = 10;
    public static final int DEFAULT_FINAL_LIMIT = 5;

    private ResearchSelectionModels() {
    }

    public enum Status {
        QUEUED, PREPARING_DATA, QUANTITATIVE_SCAN, STRATEGY_ANALYSIS,
        AI_RESEARCH, CRITIC_REVIEW, COMPLETED, FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    public enum TriggerMode {
        ON_DEMAND, SCHEDULED_SHADOW
    }

    public enum RecommendationStatus {
        WATCH, INSUFFICIENT_EVIDENCE
    }

    public record SelectionRequest(
            TriggerMode triggerMode,
            int primaryWindow,
            int auxiliaryWindow,
            int shortlistSize,
            int finalLimit,
            boolean paperEnabled
    ) {
        public SelectionRequest {
            if (triggerMode == null
                    || !List.of(20, 60, 120, 250).contains(primaryWindow)
                    || auxiliaryWindow < primaryWindow
                    || auxiliaryWindow > 250
                    || shortlistSize < 1 || shortlistSize > 10
                    || finalLimit < 1 || finalLimit > 5
                    || finalLimit > shortlistSize) {
                throw new IllegalArgumentException(
                        "RESEARCH_SELECTION_REQUEST_INVALID");
            }
        }

        public static SelectionRequest immediate() {
            return new SelectionRequest(TriggerMode.ON_DEMAND,
                    DEFAULT_PRIMARY_WINDOW, DEFAULT_AUXILIARY_WINDOW,
                    DEFAULT_SHORTLIST_SIZE, DEFAULT_FINAL_LIMIT, true);
        }
    }

    public record DataCoverage(
            LocalDate rangeStart,
            LocalDate rangeEnd,
            int requiredOpenSessions,
            int actualOpenSessions,
            int securityCount,
            int completeSecurityCount,
            int missingDailyFacts,
            int missingAdjustmentFactors,
            boolean calendarComplete,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean formulaOnlyQfq,
            boolean noFutureDataLeakage
    ) {
    }

    public record QuantitativeScore(
            int rank,
            Security security,
            String name,
            String industry,
            BigDecimal score,
            BigDecimal fiveDayReturn,
            BigDecimal twentyDayReturn,
            BigDecimal sixtyDayReturn,
            BigDecimal annualizedVolatility,
            BigDecimal maxDrawdown,
            BigDecimal sharpe,
            BigDecimal meanReversionZ,
            String trend,
            int observationCount,
            List<String> explanations,
            boolean dataQualityPassed
    ) {
        public QuantitativeScore {
            explanations = List.copyOf(explanations);
        }
    }

    public record Candidate(
            int rank,
            Security security,
            String name,
            String industry,
            BigDecimal quantitativeScore,
            RecommendationStatus recommendation,
            String riskLevel,
            BigDecimal confidence,
            List<String> supportingReasons,
            List<String> opposingReasons,
            String preferredStrategy,
            List<StrategyComparison> strategyComparison,
            BigDecimal maxDrawdown,
            String trend,
            List<String> criticIssues
    ) {
        public Candidate {
            supportingReasons = List.copyOf(supportingReasons);
            opposingReasons = List.copyOf(opposingReasons);
            strategyComparison = List.copyOf(strategyComparison);
            criticIssues = List.copyOf(criticIssues);
        }
    }

    public record Timings(
            long dataPreparationMillis,
            long quantitativeScanMillis,
            long strategyAnalysisMillis,
            long agentResearchMillis,
            long totalMillis
    ) {
    }

    public record Usage(
            int tushareProviderRequests,
            int retryCount,
            int modelCalls,
            int modelProviderRequests,
            int inputTokens,
            int outputTokens,
            int reasoningTokens,
            int totalTokens,
            BigDecimal conservativeCostCny
    ) {
    }

    public record Lineage(
            String researchUniverseVersion,
            List<Security> universeSecurities,
            int primaryWindow,
            int auxiliaryWindow,
            String rankingVersion,
            String agentRuntimeVersion,
            String promptVersion,
            String modelProvider,
            String model,
            String strategyVersion,
            String gitCommit,
            String datasetFingerprint,
            String resultFingerprint
    ) {
        public Lineage {
            universeSecurities = List.copyOf(universeSecurities);
        }
    }

    public record SelectionResult(
            String contractVersion,
            long runId,
            String publicRunId,
            Status status,
            TriggerMode triggerMode,
            Instant researchAsOf,
            LocalDate anchorTradeDate,
            DataCoverage dataCoverage,
            List<QuantitativeScore> ranking,
            List<QuantitativeScore> shortlist,
            List<Candidate> candidates,
            boolean emptyResult,
            String decisionCode,
            ResearchReport agentReport,
            Long shadowRunId,
            boolean paperEnabled,
            boolean realTradingEnabled,
            boolean historicalLiveShadow,
            Timings timings,
            Usage usage,
            Lineage lineage,
            String failureCategory,
            String failureReason,
            Instant startedAt,
            Instant completedAt
    ) {
        public SelectionResult {
            ranking = List.copyOf(ranking);
            shortlist = List.copyOf(shortlist);
            candidates = List.copyOf(candidates);
            if (!VERSION.equals(contractVersion) || publicRunId == null
                    || publicRunId.isBlank() || status == null
                    || triggerMode == null || realTradingEnabled
                    || historicalLiveShadow) {
                throw new IllegalArgumentException(
                        "RESEARCH_SELECTION_RESULT_INVALID");
            }
        }
    }

    public record RunSummary(
            long runId,
            String publicRunId,
            Status status,
            TriggerMode triggerMode,
            Instant researchAsOf,
            LocalDate anchorTradeDate,
            int universeSize,
            int shortlistSize,
            int candidateCount,
            String decisionCode,
            String failureCategory,
            String failureReason,
            Instant createdAt,
            Instant completedAt
    ) {
    }
}
