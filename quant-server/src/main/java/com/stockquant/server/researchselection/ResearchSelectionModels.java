package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyComparison;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Stable user-facing contracts for current-as-of stock selection. */
public final class ResearchSelectionModels {
    public static final String VERSION = "RESEARCH_SELECTION_V1";
    public static final String RANKING_VERSION =
            "RESEARCH_SELECTION_RANKING_V1";
    public static final String HISTORICAL_STABILITY_VERSION =
            "HISTORICAL_STABILITY_SCORE_V1";
    public static final String SELECTION_EXPLANATION_VERSION =
            "SELECTION_EXPLANATION_V1";
    public static final String RESEARCH_TRADE_PLAN_VERSION =
            "RESEARCH_TRADE_PLAN_V1";
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

    public enum HistoricalAvailability {
        AVAILABLE, INSUFFICIENT_HISTORY
    }

    public enum HistoricalGrade {
        A, B, C
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
                    || auxiliaryWindow < Math.min(primaryWindow,
                    DEFAULT_AUXILIARY_WINDOW)
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

    public record HistoricalWindowCoverage(
            int requestedSessions,
            HistoricalAvailability status,
            int availableSessions,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            int missingSessions,
            String reason
    ) {
    }

    public record HistoricalWindowMetrics(
            String windowCode,
            int sessionCount,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            BigDecimal totalReturn,
            BigDecimal costAdjustedReturn,
            BigDecimal maxDrawdown,
            BigDecimal annualizedVolatility,
            BigDecimal sharpe,
            BigDecimal turnover,
            BigDecimal winRate,
            int tradeCount,
            String bestStrategy,
            BigDecimal bestStrategyReturn,
            String worstStrategy,
            BigDecimal worstStrategyReturn,
            int positiveStrategyCount,
            int strategyCount
    ) {
    }

    public record WalkForwardSummary(
            boolean available,
            String reason,
            int trainSessions,
            int testSessions,
            int stepSessions,
            int foldCount,
            int strategyFoldCount,
            BigDecimal averageOutOfSampleReturn,
            BigDecimal worstOutOfSampleReturn,
            BigDecimal positiveFoldRatio,
            BigDecimal maximumOutOfSampleDrawdown,
            int tradeCount,
            boolean strictlyIsolated,
            boolean noFutureDataLeakage
    ) {
    }

    public record HistoricalStability(
            Security security,
            int availableSessions,
            BigDecimal score,
            HistoricalGrade grade,
            BigDecimal dataCompletenessComponent,
            BigDecimal multiWindowConsistencyComponent,
            BigDecimal outOfSampleComponent,
            BigDecimal riskComponent,
            BigDecimal costAndSampleComponent,
            BigDecimal multiWindowConsistency,
            BigDecimal multiStrategyConsistency,
            String bestWindow,
            BigDecimal bestWindowReturn,
            String worstWindow,
            BigDecimal worstWindowReturn,
            WalkForwardSummary walkForward,
            List<HistoricalWindowMetrics> windows,
            int liveShadowSamples,
            List<String> supportingEvidence,
            List<String> limitations,
            boolean noFutureDataLeakage
    ) {
        public HistoricalStability {
            windows = List.copyOf(windows);
            supportingEvidence = List.copyOf(supportingEvidence);
            limitations = List.copyOf(limitations);
        }
    }

    public record HistoricalResearch(
            String version,
            String researchLabel,
            String pitQualification,
            int availableSessions,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            List<HistoricalWindowCoverage> windowCoverage,
            List<LocalDate> missingTradeDates,
            List<HistoricalStability> securities,
            Map<String, Integer> gradeDistribution,
            boolean calendarCompleteThroughAnchor,
            boolean knownAtQualified,
            boolean dataQualityPassed,
            boolean noFutureDataLeakage,
            String datasetFingerprint
    ) {
        public HistoricalResearch {
            windowCoverage = List.copyOf(windowCoverage);
            missingTradeDates = List.copyOf(missingTradeDates);
            securities = List.copyOf(securities);
            gradeDistribution = Map.copyOf(gradeDistribution);
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

    public record EligibilityCheck(
            String code,
            boolean passed,
            String detail
    ) {
    }

    public record ScoreContribution(
            String metric,
            BigDecimal rawValue,
            BigDecimal percentileScore,
            BigDecimal weight,
            BigDecimal weightedContribution
    ) {
    }

    public record HistoricalComponentScore(
            String component,
            BigDecimal componentScore,
            BigDecimal weight,
            BigDecimal weightedContribution
    ) {
    }

    public record GateCheck(
            String code,
            boolean passed,
            String detail
    ) {
    }

    public record FirstExcludedComparison(
            Security security,
            String name,
            Integer basicRank,
            Integer historicalRank,
            Integer strategyRank,
            Integer agentRank,
            BigDecimal currentScore,
            BigDecimal historicalScore,
            List<String> failedChecks
    ) {
        public FirstExcludedComparison {
            failedChecks = failedChecks == null
                    ? List.of() : List.copyOf(failedChecks);
        }
    }

    /** Deterministic provenance for every final selection decision. */
    public record SelectionExplanation(
            String version,
            Security security,
            boolean eligibilityPassed,
            List<EligibilityCheck> eligibilityChecks,
            int basicRank,
            int basicUniverseSize,
            BigDecimal currentScore,
            List<ScoreContribution> currentScoreContributions,
            Map<String, BigDecimal> metricPercentiles,
            Integer historicalRank,
            int historicalPoolSize,
            BigDecimal historicalScore,
            HistoricalGrade historicalGrade,
            List<HistoricalComponentScore> historicalComponentScores,
            Integer strategyRank,
            int strategyPoolSize,
            Integer agentRank,
            int agentPoolSize,
            int finalCandidateRank,
            int finalCandidateLimit,
            List<StrategyComparison> strategyComparison,
            List<String> supportingFindings,
            List<String> opposingFindings,
            List<String> criticIssues,
            List<String> criticCorrections,
            List<GateCheck> finalGateChecks,
            FirstExcludedComparison firstExcludedComparison,
            List<String> evidenceIds,
            List<String> limitations
    ) {
        public SelectionExplanation {
            if (!SELECTION_EXPLANATION_VERSION.equals(version)
                    || security == null || basicRank < 1
                    || basicUniverseSize < basicRank
                    || finalCandidateRank < 1
                    || finalCandidateLimit < finalCandidateRank) {
                throw new IllegalArgumentException(
                        "SELECTION_EXPLANATION_INVALID");
            }
            eligibilityChecks = immutable(eligibilityChecks);
            currentScoreContributions = immutable(
                    currentScoreContributions);
            metricPercentiles = metricPercentiles == null
                    ? Map.of() : Map.copyOf(metricPercentiles);
            historicalComponentScores = immutable(
                    historicalComponentScores);
            strategyComparison = immutable(strategyComparison);
            supportingFindings = immutable(supportingFindings);
            opposingFindings = immutable(opposingFindings);
            criticIssues = immutable(criticIssues);
            criticCorrections = immutable(criticCorrections);
            finalGateChecks = immutable(finalGateChecks);
            evidenceIds = immutable(evidenceIds);
            limitations = immutable(limitations);
        }
    }

    public enum ResearchTradePlanStatus {
        PLANNED, OBSERVATION_ONLY, SKIPPED, ENTERED, EXIT_INTENT,
        CLOSED, INVALIDATED
    }

    /** Research-only price plan. It is never an order or brokerage command. */
    public record ResearchTradePlan(
            String version,
            Security security,
            LocalDate anchorTradeDate,
            BigDecimal rawReferenceClose,
            BigDecimal qfqReferenceClose,
            BigDecimal atr14,
            BigDecimal entryBandPercent,
            BigDecimal plannedEntryLower,
            BigDecimal plannedEntryUpper,
            BigDecimal maximumAcceptableEntryPrice,
            LocalDate plannedExecutionDate,
            LocalTime plannedExecutionTime,
            BigDecimal stopLossPrice,
            BigDecimal targetExitPrice,
            BigDecimal riskAmountPerShare,
            BigDecimal riskRewardRatio,
            String preferredStrategy,
            Integer expectedHoldingMinSessions,
            Integer expectedHoldingMaxSessions,
            Integer maximumHoldingSessions,
            String strategyInvalidationRule,
            List<String> exitConditions,
            ResearchTradePlanStatus planStatus,
            String statusReason,
            BigDecimal actualPaperEntryPrice,
            BigDecimal actualPaperExitPrice,
            Integer actualHoldingSessions,
            BigDecimal actualFees,
            BigDecimal actualPnl,
            String calculationVersion,
            String sourceFingerprint
    ) {
        public ResearchTradePlan {
            if (!RESEARCH_TRADE_PLAN_VERSION.equals(version)
                    || security == null || anchorTradeDate == null
                    || planStatus == null || calculationVersion == null
                    || calculationVersion.isBlank()
                    || sourceFingerprint == null
                    || !sourceFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "RESEARCH_TRADE_PLAN_INVALID");
            }
            exitConditions = immutable(exitConditions);
            if (planStatus == ResearchTradePlanStatus.PLANNED
                    && (rawReferenceClose == null || qfqReferenceClose == null
                    || atr14 == null || entryBandPercent == null
                    || plannedEntryLower == null || plannedEntryUpper == null
                    || maximumAcceptableEntryPrice == null
                    || plannedExecutionDate == null
                    || plannedExecutionTime == null
                    || stopLossPrice == null || targetExitPrice == null
                    || riskAmountPerShare == null
                    || riskRewardRatio == null
                    || preferredStrategy == null
                    || expectedHoldingMinSessions == null
                    || expectedHoldingMaxSessions == null
                    || maximumHoldingSessions == null)) {
                throw new IllegalArgumentException(
                        "RESEARCH_TRADE_PLAN_EXECUTABLE_FIELDS_INVALID");
            }
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
            String historicalStabilityVersion,
            String gitCommit,
            String datasetFingerprint,
            String historicalDatasetFingerprint,
            String resultFingerprint,
            String universeSnapshotId,
            int universeMemberCount,
            String universeMemberFingerprint
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
            ResearchUniverseMainboard.Funnel universeFunnel,
            HistoricalResearch historicalResearch,
            List<QuantitativeScore> ranking,
            List<QuantitativeScore> shortlist,
            List<Candidate> candidates,
            List<SelectionExplanation> selectionExplanations,
            List<ResearchTradePlan> researchTradePlans,
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
            selectionExplanations = selectionExplanations == null
                    ? List.of() : List.copyOf(selectionExplanations);
            researchTradePlans = researchTradePlans == null
                    ? List.of() : List.copyOf(researchTradePlans);
            if (!VERSION.equals(contractVersion) || publicRunId == null
                    || publicRunId.isBlank() || status == null
                    || triggerMode == null || realTradingEnabled
                    || historicalLiveShadow) {
                throw new IllegalArgumentException(
                        "RESEARCH_SELECTION_RESULT_INVALID");
            }
        }

        public SelectionResult withResearchTradePlans(
                List<ResearchTradePlan> updatedPlans
        ) {
            return new SelectionResult(contractVersion, runId, publicRunId,
                    status, triggerMode, researchAsOf, anchorTradeDate,
                    dataCoverage, universeFunnel, historicalResearch, ranking,
                    shortlist, candidates, selectionExplanations,
                    updatedPlans, emptyResult, decisionCode, agentReport,
                    shadowRunId, paperEnabled, realTradingEnabled,
                    historicalLiveShadow, timings, usage, lineage,
                    failureCategory, failureReason, startedAt, completedAt);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
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
