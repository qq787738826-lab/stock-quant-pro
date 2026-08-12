package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.research.AgentResearchModels;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable M4 contracts for research-only shadow observation. */
public final class ShadowResearchModels {
    public static final String RUNTIME_VERSION =
            "SHADOW_RESEARCH_RUNTIME_V1";
    public static final String SCHEDULER_VERSION = "SHADOW_SCHEDULER_V1";
    public static final String SNAPSHOT_VERSION = "SHADOW_SNAPSHOT_V1";
    public static final String PORTFOLIO_VERSION = "PAPER_PORTFOLIO_V1";
    public static final String REPLAY_VERSION = "SHADOW_REPLAY_V1";
    public static final String OUTCOME_VERSION = "SHADOW_OUTCOME_V1";
    public static final String UI_VERSION = "SHADOW_UI_V1";
    public static final String STRATEGY_VERSION = "M4_SHADOW_STRATEGY_V1";
    public static final String RESEARCH_SLOT = "AFTER_CLOSE";
    public static final String PAPER_PORTFOLIO = "M4_SHADOW_PAPER";

    private ShadowResearchModels() {
    }

    public enum RunStatus {
        QUEUED, RUNNING, FROZEN, FAILED, INTERRUPTED;

        public boolean terminal() {
            return this == FROZEN || this == FAILED || this == INTERRUPTED;
        }
    }

    public enum TriggerMode {
        SCHEDULED, MANUAL, HISTORICAL_REPLAY
    }

    public enum PaperOrderStatus {
        PENDING, FILLED, REJECTED
    }

    public enum Side {
        BUY, SELL
    }

    public record ShadowRun(
            long id,
            String runKey,
            int attempt,
            RunStatus status,
            TriggerMode triggerMode,
            LocalDate tradeDate,
            String researchSlot,
            Instant researchAsOf,
            Instant signalTime,
            Instant paperExecutionTime,
            String strategyVersion,
            String modelProvider,
            String model,
            String promptVersion,
            String agentRuntimeVersion,
            String datasetFingerprint,
            String strategyFingerprint,
            String researchFingerprint,
            String requestFingerprint,
            String errorCode,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt
    ) {
    }

    public record ShadowRecommendation(
            String decisionCode,
            List<String> rankedStrategies,
            List<String> rankedSecurities,
            String preferredStrategy,
            String riskLevel,
            BigDecimal confidence,
            BigDecimal suggestedGrossExposure,
            List<String> supportingEvidenceIds,
            List<String> limitations,
            boolean emptyPortfolioAllowed,
            boolean researchOnly
    ) {
        public ShadowRecommendation {
            decisionCode = required(decisionCode);
            rankedStrategies = List.copyOf(rankedStrategies);
            rankedSecurities = List.copyOf(rankedSecurities);
            preferredStrategy = required(preferredStrategy);
            riskLevel = required(riskLevel);
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(suggestedGrossExposure,
                    "suggestedGrossExposure");
            supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
            limitations = List.copyOf(limitations);
            if (!researchOnly || !emptyPortfolioAllowed
                    || confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0
                    || suggestedGrossExposure.signum() < 0
                    || suggestedGrossExposure.compareTo(BigDecimal.ONE) > 0) {
                throw invalid("M4_RECOMMENDATION_INVALID");
            }
        }

        public static ShadowRecommendation from(ResearchReport report) {
            boolean researchPreference = report.finalDecision().code().name()
                    .equals("RESEARCH_PREFERENCE");
            return new ShadowRecommendation(
                    report.finalDecision().code().name(),
                    report.portfolio().rankedStrategies(),
                    researchPreference
                            ? report.technical().stream().sorted(
                                            java.util.Comparator.comparing(
                                                            AgentResearchModels
                                                                    .TechnicalSnapshot::shortMomentum)
                                                    .reversed()
                                                    .thenComparing(value ->
                                                            value.security()
                                                                    .canonicalCode()))
                                    .map(value -> value.security()
                                            .canonicalCode())
                                    .toList()
                            : List.of(),
                    report.finalDecision().preferredStrategy(),
                    report.finalDecision().riskLevel().name(),
                    report.finalDecision().confidence(),
                    researchPreference
                            ? report.portfolio().suggestedGrossExposure()
                            : BigDecimal.ZERO,
                    report.finalDecision().supportingEvidenceIds(),
                    report.portfolio().limitations(), true, true);
        }

        public ShadowRecommendation withoutPaperExecution(String reason) {
            java.util.ArrayList<String> updated = new java.util.ArrayList<>(
                    limitations);
            if (!updated.contains(reason)) {
                updated.add(reason);
            }
            return new ShadowRecommendation(decisionCode, rankedStrategies,
                    rankedSecurities, preferredStrategy, riskLevel,
                    confidence, BigDecimal.ZERO, supportingEvidenceIds,
                    updated, true, true);
        }
    }

    public record ShadowRequest(
            TriggerMode triggerMode,
            LocalDate tradeDate,
            LocalDate rangeStart,
            Instant researchAsOf,
            List<Security> securities,
            Security benchmark,
            List<StrategySpec> strategies,
            Instant nextPaperExecutionTime,
            int tushareProviderRequests,
            String objective
    ) {
        public ShadowRequest {
            Objects.requireNonNull(triggerMode, "triggerMode");
            Objects.requireNonNull(tradeDate, "tradeDate");
            Objects.requireNonNull(rangeStart, "rangeStart");
            Objects.requireNonNull(researchAsOf, "researchAsOf");
            Objects.requireNonNull(benchmark, "benchmark");
            securities = List.copyOf(securities);
            strategies = List.copyOf(strategies);
            objective = required(objective);
            if (rangeStart.isAfter(tradeDate) || securities.size() < 1
                    || securities.size() > 20
                    || !securities.contains(benchmark)
                    || strategies.size() < 2 || strategies.size() > 8
                    || tushareProviderRequests < 0
                    || tushareProviderRequests > 20
                    || researchAsOf.isBefore(com.stockquant.core.research
                    .StrategyResearchModels.closeInstant(tradeDate))
                    || nextPaperExecutionTime != null
                    && !nextPaperExecutionTime.isAfter(researchAsOf)) {
                throw invalid("M4_SHADOW_REQUEST_INVALID");
            }
        }
    }

    public record PaperOrder(
            long id,
            long runId,
            long portfolioId,
            String orderKey,
            Side side,
            Security security,
            Instant signalTime,
            Instant earliestExecutionTime,
            BigDecimal targetWeight,
            PaperOrderStatus status,
            String rejectionReason
    ) {
    }

    public record PaperFill(
            long id,
            long orderId,
            long runId,
            LocalDate executionDate,
            Instant executionTime,
            Security security,
            Side side,
            BigDecimal referencePrice,
            BigDecimal executionPrice,
            int quantity,
            BigDecimal grossAmount,
            BigDecimal commission,
            BigDecimal stampDuty,
            BigDecimal slippageCost,
            BigDecimal realizedPnl,
            BigDecimal cashAfter,
            int positionAfter,
            String fingerprint
    ) {
    }

    public record PaperPosition(
            Security security,
            int quantity,
            int availableQuantity,
            BigDecimal averageCost,
            BigDecimal lastPrice,
            LocalDate lastBuyDate
    ) {
    }

    public record PaperPortfolio(
            long id,
            String portfolioCode,
            BigDecimal initialCash,
            BigDecimal cash,
            BigDecimal realizedPnl,
            BigDecimal totalFees,
            long stateVersion,
            List<PaperPosition> positions
    ) {
        public PaperPortfolio {
            positions = List.copyOf(positions);
        }
    }

    public record PortfolioSnapshot(
            long id,
            long portfolioId,
            Long runId,
            LocalDate snapshotDate,
            Instant snapshotTime,
            BigDecimal cash,
            BigDecimal marketValue,
            BigDecimal totalEquity,
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalFees,
            BigDecimal totalReturn,
            int positionCount,
            String fingerprint
    ) {
    }

    public record FrozenSnapshot(
            long id,
            long runId,
            String snapshotFingerprint,
            Instant frozenAt,
            ResearchReport report,
            ShadowRecommendation recommendation
    ) {
    }

    /** Append-only future observation; never changes the frozen decision. */
    public record ShadowOutcome(
            long id,
            long runId,
            String horizonCode,
            LocalDate evaluationDate,
            OutcomeObservation observation,
            String fingerprint,
            Instant evaluatedAt
    ) {
    }

    public record OutcomeObservation(
            String version,
            String decisionCode,
            List<String> rankedSecurities,
            Map<String, BigDecimal> securityReturns,
            BigDecimal equalWeightReturn,
            boolean emptyRecommendation,
            boolean noFutureDataLeakage,
            boolean researchOnly
    ) {
        public OutcomeObservation {
            version = required(version);
            decisionCode = required(decisionCode);
            rankedSecurities = List.copyOf(rankedSecurities);
            securityReturns = Map.copyOf(securityReturns);
            Objects.requireNonNull(equalWeightReturn, "equalWeightReturn");
            if (!OUTCOME_VERSION.equals(version) || !noFutureDataLeakage
                    || !researchOnly
                    || emptyRecommendation != rankedSecurities.isEmpty()
                    || !securityReturns.keySet().equals(
                    java.util.Set.copyOf(rankedSecurities))) {
                throw invalid("M4_OUTCOME_OBSERVATION_INVALID");
            }
        }
    }

    public record ShadowExecutionResult(
            ShadowRun run,
            FrozenSnapshot snapshot,
            List<PaperOrder> orders,
            List<PaperFill> fills,
            PaperPortfolio portfolio,
            PortfolioSnapshot portfolioSnapshot,
            int modelCalls,
            int modelProviderRequests,
            int tushareProviderRequests,
            int inputTokens,
            int outputTokens,
            int reasoningTokens,
            int totalTokens,
            BigDecimal conservativeCostCny,
            boolean outputAuditClean,
            boolean noFutureDataLeakage,
            boolean idempotent
    ) {
        public ShadowExecutionResult {
            orders = List.copyOf(orders);
            fills = List.copyOf(fills);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("M4_REQUIRED_TEXT_INVALID");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
