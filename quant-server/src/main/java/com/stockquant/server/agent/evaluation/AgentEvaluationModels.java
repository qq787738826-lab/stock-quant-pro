package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchEval.EvalReport;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Stable, deliberately small contracts for AGENT_EVALUATION_SYSTEM_V1. */
public final class AgentEvaluationModels {
    public static final String SYSTEM_VERSION = "AGENT_EVALUATION_SYSTEM_V1";
    public static final String SCORECARD_VERSION = "AGENT_SCORECARD_V1";
    public static final String VERSION_REGISTRY_VERSION =
            "AGENT_VERSION_REGISTRY_V1";
    public static final String OUTCOME_EVAL_VERSION =
            "SHADOW_OUTCOME_EVAL_V1";
    public static final String CHAMPION_CHALLENGER_VERSION =
            "CHAMPION_CHALLENGER_V1";
    public static final String PERFORMANCE_REPORT_VERSION =
            "RESEARCH_PERFORMANCE_REPORT_V1";
    public static final int MINIMUM_SHADOW_SAMPLE = 20;

    private AgentEvaluationModels() {
    }

    public enum VersionKind { CHAMPION, CHALLENGER }

    public enum LifecycleDecision { RETAIN, WATCH, DEMOTE, REPLACE }

    public enum EvaluationStatus { PASS, FAIL, INSUFFICIENT_SAMPLE }

    public enum ComparisonDecision {
        RETAIN_CHAMPION, WATCH_CHALLENGER, PROMOTE_CHALLENGER,
        REJECT_CHALLENGER
    }

    /**
     * Explicitly binds safety/replay claims to one immutable AGENT_EVAL
     * report.  A caller may report zero historical samples; that is valid
     * evidence for WATCH, but can never satisfy the promotion gate.
     */
    public record EvaluationProof(
            String evalFingerprint,
            int historicalReplaySamples,
            boolean deterministicReplayPassed,
            boolean lookAheadGuardPassed,
            boolean riskGatePassed,
            String fingerprint
    ) {
        public EvaluationProof {
            requireHash(evalFingerprint,
                    "M5_EVALUATION_PROOF_EVAL_HASH_INVALID");
            requireHash(fingerprint,
                    "M5_EVALUATION_PROOF_HASH_INVALID");
            if (historicalReplaySamples < 0) {
                throw invalid("M5_EVALUATION_PROOF_SAMPLE_INVALID");
            }
        }

        public static EvaluationProof from(
                EvalReport report,
                int historicalReplaySamples
        ) {
            Objects.requireNonNull(report, "report");
            if (historicalReplaySamples < 0) {
                throw invalid("M5_EVALUATION_PROOF_SAMPLE_INVALID");
            }
            Map<String, Boolean> cases = new TreeMap<>();
            report.cases().forEach(value -> {
                if (cases.put(value.caseId(), value.passed()) != null) {
                    throw invalid("M5_EVALUATION_PROOF_CASE_DUPLICATE");
                }
            });
            boolean deterministic = passed(cases,
                    "DETERMINISTIC_REPLAY");
            boolean lookAhead = passed(cases, "FUTURE_DATA_REJECTED")
                    && passed(cases, "PROMPT_INJECTION_CONTAINED")
                    && passed(cases, "FINAL_REPORT_CONSISTENCY");
            boolean risk = passed(cases, "RISK_IDENTIFICATION")
                    && passed(cases, "OVERFITTING_IDENTIFIED")
                    && passed(cases,
                    "HIGH_RETURN_HIGH_DRAWDOWN_IDENTIFIED")
                    && passed(cases, "CRITIC_CORRECTION_APPLIED");
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("evalFingerprint", report.fingerprint());
            canonical.put("historicalReplaySamples",
                    historicalReplaySamples);
            canonical.put("deterministicReplayPassed", deterministic);
            canonical.put("lookAheadGuardPassed", lookAhead);
            canonical.put("riskGatePassed", risk);
            return new EvaluationProof(report.fingerprint(),
                    historicalReplaySamples, deterministic, lookAhead, risk,
                    AgentEvaluationCanonical.hash(canonical));
        }

        private static boolean passed(
                Map<String, Boolean> cases,
                String caseId
        ) {
            return Boolean.TRUE.equals(cases.get(caseId));
        }
    }

    public record AgentVersion(
            String versionKey,
            VersionKind kind,
            String parentVersionKey,
            String runtimeVersion,
            String toolVersion,
            String strategyVersion,
            String modelProvider,
            String model,
            Map<AgentRole, String> promptVersions,
            String evaluationRuleVersion,
            Instant registeredAt,
            String fingerprint
    ) {
        public AgentVersion {
            versionKey = required(versionKey, "versionKey");
            Objects.requireNonNull(kind, "kind");
            parentVersionKey = parentVersionKey == null ? "NONE"
                    : required(parentVersionKey, "parentVersionKey");
            runtimeVersion = required(runtimeVersion, "runtimeVersion");
            toolVersion = required(toolVersion, "toolVersion");
            strategyVersion = required(strategyVersion, "strategyVersion");
            modelProvider = required(modelProvider, "modelProvider");
            model = required(model, "model");
            evaluationRuleVersion = required(evaluationRuleVersion,
                    "evaluationRuleVersion");
            Objects.requireNonNull(registeredAt, "registeredAt");
            promptVersions = Collections.unmodifiableMap(new TreeMap<>(
                    Objects.requireNonNull(promptVersions, "promptVersions")));
            requireHash(fingerprint, "M5_VERSION_FINGERPRINT_INVALID");
            if (!versionKey.matches("M5V_[A-Z0-9_]{6,80}")
                    || !promptVersions.keySet().equals(Set.of(
                    AgentRole.values()))
                    || promptVersions.values().stream().anyMatch(value ->
                    value == null || value.isBlank())) {
                throw invalid("M5_AGENT_VERSION_INVALID");
            }
            if (kind == VersionKind.CHALLENGER
                    && "NONE".equals(parentVersionKey)) {
                throw invalid("M5_CHALLENGER_PARENT_REQUIRED");
            }
        }

        public static AgentVersion create(
                String key,
                VersionKind kind,
                String parent,
                String runtime,
                String tool,
                String strategy,
                String provider,
                String model,
                Map<AgentRole, String> prompts,
                String evaluationRule,
                Instant at
        ) {
            Map<String, Object> identity = Map.ofEntries(
                    Map.entry("key", key), Map.entry("kind", kind),
                    Map.entry("parent", parent == null ? "NONE" : parent),
                    Map.entry("runtime", runtime), Map.entry("tool", tool),
                    Map.entry("strategy", strategy),
                    Map.entry("provider", provider),
                    Map.entry("model", model), Map.entry("prompts", prompts),
                    Map.entry("evaluationRule", evaluationRule));
            return new AgentVersion(key, kind, parent, runtime, tool,
                    strategy, provider, model, prompts, evaluationRule, at,
                    AgentEvaluationCanonical.hash(identity));
        }
    }

    public record MetricScore(
            String metric,
            BigDecimal score,
            BigDecimal weight,
            int numerator,
            int denominator,
            String rationale
    ) {
        public MetricScore {
            metric = required(metric, "metric");
            Objects.requireNonNull(score, "score");
            Objects.requireNonNull(weight, "weight");
            rationale = required(rationale, "rationale");
            if (score.signum() < 0
                    || score.compareTo(BigDecimal.valueOf(100)) > 0
                    || weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0
                    || numerator < 0 || denominator < 0
                    || numerator > denominator) {
                throw invalid("M5_METRIC_SCORE_INVALID");
            }
        }
    }

    public record AgentScorecard(
            String scorecardVersion,
            String versionKey,
            AgentRole role,
            BigDecimal weightedScore,
            LifecycleDecision lifecycleDecision,
            int reportSampleCount,
            int findingSampleCount,
            List<MetricScore> metrics,
            List<String> failureModes,
            List<String> rationale
    ) {
        public AgentScorecard {
            scorecardVersion = required(scorecardVersion,
                    "scorecardVersion");
            versionKey = required(versionKey, "versionKey");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(weightedScore, "weightedScore");
            Objects.requireNonNull(lifecycleDecision, "lifecycleDecision");
            metrics = List.copyOf(metrics);
            failureModes = List.copyOf(failureModes);
            rationale = List.copyOf(rationale);
            BigDecimal weights = metrics.stream().map(MetricScore::weight)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!SCORECARD_VERSION.equals(scorecardVersion)
                    || reportSampleCount < 1 || findingSampleCount < 0
                    || metrics.isEmpty()
                    || weights.subtract(BigDecimal.ONE).abs()
                    .compareTo(new BigDecimal("0.000001")) > 0
                    || weightedScore.signum() < 0
                    || weightedScore.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalid("M5_AGENT_SCORECARD_INVALID");
            }
        }
    }

    public record CalibrationBin(
            BigDecimal lowerInclusive,
            BigDecimal upperInclusive,
            int sampleCount,
            BigDecimal meanConfidence,
            BigDecimal observedHitRate
    ) {
        public CalibrationBin {
            Objects.requireNonNull(lowerInclusive, "lowerInclusive");
            Objects.requireNonNull(upperInclusive, "upperInclusive");
            Objects.requireNonNull(meanConfidence, "meanConfidence");
            Objects.requireNonNull(observedHitRate, "observedHitRate");
            if (lowerInclusive.signum() < 0
                    || upperInclusive.compareTo(BigDecimal.ONE) > 0
                    || lowerInclusive.compareTo(upperInclusive) > 0
                    || sampleCount < 0 || meanConfidence.signum() < 0
                    || meanConfidence.compareTo(BigDecimal.ONE) > 0
                    || observedHitRate.signum() < 0
                    || observedHitRate.compareTo(BigDecimal.ONE) > 0) {
                throw invalid("M5_CALIBRATION_BIN_INVALID");
            }
        }
    }

    public record ConfidenceCalibration(
            EvaluationStatus status,
            int eligibleSampleCount,
            int abstentionCount,
            BigDecimal brierScore,
            BigDecimal expectedCalibrationError,
            List<CalibrationBin> bins
    ) {
        public ConfidenceCalibration {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(brierScore, "brierScore");
            Objects.requireNonNull(expectedCalibrationError,
                    "expectedCalibrationError");
            bins = List.copyOf(bins);
            if (eligibleSampleCount < 0 || abstentionCount < 0
                    || brierScore.signum() < 0
                    || brierScore.compareTo(BigDecimal.ONE) > 0
                    || expectedCalibrationError.signum() < 0
                    || expectedCalibrationError.compareTo(BigDecimal.ONE) > 0
                    || (status == EvaluationStatus.INSUFFICIENT_SAMPLE)
                    != (eligibleSampleCount < MINIMUM_SHADOW_SAMPLE)) {
                throw invalid("M5_CONFIDENCE_CALIBRATION_INVALID");
            }
        }
    }

    public record ShadowOutcomeEvaluation(
            long runId,
            String horizonCode,
            Instant predictionTime,
            Instant outcomeKnownAt,
            LocalDate outcomeDate,
            String decisionCode,
            BigDecimal confidence,
            BigDecimal rankedReturn,
            BigDecimal rankingAccuracy,
            boolean directionHit,
            boolean directionScorable,
            boolean abstention,
            String riskLevel,
            boolean riskUnderestimated,
            BigDecimal paperReturn,
            List<String> errorTypes,
            boolean noFutureDataLeakage
    ) {
        public ShadowOutcomeEvaluation {
            horizonCode = required(horizonCode, "horizonCode");
            Objects.requireNonNull(predictionTime, "predictionTime");
            Objects.requireNonNull(outcomeKnownAt, "outcomeKnownAt");
            Objects.requireNonNull(outcomeDate, "outcomeDate");
            decisionCode = required(decisionCode, "decisionCode");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(rankedReturn, "rankedReturn");
            Objects.requireNonNull(rankingAccuracy, "rankingAccuracy");
            riskLevel = required(riskLevel, "riskLevel");
            Objects.requireNonNull(paperReturn, "paperReturn");
            errorTypes = List.copyOf(errorTypes);
            if (runId < 1 || !Set.of("D1", "D5", "D20")
                    .contains(horizonCode)
                    || !outcomeKnownAt.isAfter(predictionTime)
                    || confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0
                    || (directionHit && !directionScorable)
                    || rankingAccuracy.signum() < 0
                    || rankingAccuracy.compareTo(BigDecimal.ONE) > 0
                    || errorTypes.stream().anyMatch(value -> value == null
                    || !value.matches("[A-Z][A-Z0-9_]{3,63}"))
                    || !noFutureDataLeakage) {
                throw invalid("M5_SHADOW_OUTCOME_EVALUATION_INVALID");
            }
        }
    }

    public record VersionEvaluation(
            String versionKey,
            List<AgentScorecard> scorecards,
            ConfidenceCalibration calibration,
            List<ShadowOutcomeEvaluation> shadowOutcomes,
            int offlineEvalPassed,
            int offlineEvalTotal,
            int historicalReplaySamples,
            boolean deterministicReplayPassed,
            boolean lookAheadGuardPassed,
            boolean riskGatePassed,
            int modelCalls,
            int totalTokens,
            BigDecimal accountedCost,
            String costCurrency,
            Duration elapsed,
            BigDecimal overallScore,
            EvaluationStatus status,
            List<String> failureModes,
            String fingerprint
    ) {
        public VersionEvaluation {
            versionKey = required(versionKey, "versionKey");
            scorecards = List.copyOf(scorecards);
            Objects.requireNonNull(calibration, "calibration");
            shadowOutcomes = List.copyOf(shadowOutcomes);
            Objects.requireNonNull(accountedCost, "accountedCost");
            costCurrency = required(costCurrency, "costCurrency");
            Objects.requireNonNull(elapsed, "elapsed");
            Objects.requireNonNull(overallScore, "overallScore");
            Objects.requireNonNull(status, "status");
            failureModes = List.copyOf(failureModes);
            requireHash(fingerprint, "M5_VERSION_EVALUATION_HASH_INVALID");
            if (scorecards.size() != AgentRole.values().length
                    || scorecards.stream().map(AgentScorecard::role).distinct()
                    .count() != AgentRole.values().length
                    || offlineEvalPassed < 0 || offlineEvalTotal < 1
                    || offlineEvalPassed > offlineEvalTotal
                    || historicalReplaySamples < 0 || modelCalls < 0
                    || totalTokens < 0 || accountedCost.signum() < 0
                    || elapsed.isNegative() || overallScore.signum() < 0
                    || overallScore.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalid("M5_VERSION_EVALUATION_INVALID");
            }
        }
    }

    public record ChampionChallengerComparison(
            String comparisonVersion,
            String championVersionKey,
            String challengerVersionKey,
            ComparisonDecision decision,
            BigDecimal scoreDelta,
            BigDecimal costRatio,
            BigDecimal latencyRatio,
            boolean minimumEvidencePassed,
            boolean overfitGuardPassed,
            boolean promotionAllowed,
            List<String> reasons,
            String fingerprint
    ) {
        public ChampionChallengerComparison {
            comparisonVersion = required(comparisonVersion,
                    "comparisonVersion");
            championVersionKey = required(championVersionKey,
                    "championVersionKey");
            challengerVersionKey = required(challengerVersionKey,
                    "challengerVersionKey");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(scoreDelta, "scoreDelta");
            Objects.requireNonNull(costRatio, "costRatio");
            Objects.requireNonNull(latencyRatio, "latencyRatio");
            reasons = List.copyOf(reasons);
            requireHash(fingerprint, "M5_COMPARISON_HASH_INVALID");
            if (!CHAMPION_CHALLENGER_VERSION.equals(comparisonVersion)
                    || championVersionKey.equals(challengerVersionKey)
                    || costRatio.signum() < 0 || latencyRatio.signum() < 0
                    || promotionAllowed
                    != (decision == ComparisonDecision.PROMOTE_CHALLENGER)) {
                throw invalid("M5_CHAMPION_CHALLENGER_INVALID");
            }
        }
    }

    public record ResearchPerformanceReport(
            String reportVersion,
            String currentChampionVersionKey,
            List<VersionEvaluation> versionEvaluations,
            ChampionChallengerComparison comparison,
            int frozenShadowRunCount,
            int eligibleOutcomeCount,
            BigDecimal paperTotalReturn,
            BigDecimal paperMaximumDrawdown,
            EvaluationStatus realShadowStatus,
            Instant generatedAt,
            String fingerprint,
            boolean researchOnly,
            boolean brokerConnected,
            boolean realTradingEnabled
    ) {
        public ResearchPerformanceReport {
            reportVersion = required(reportVersion, "reportVersion");
            currentChampionVersionKey = required(currentChampionVersionKey,
                    "currentChampionVersionKey");
            versionEvaluations = List.copyOf(versionEvaluations);
            Objects.requireNonNull(comparison, "comparison");
            Objects.requireNonNull(paperTotalReturn, "paperTotalReturn");
            Objects.requireNonNull(paperMaximumDrawdown,
                    "paperMaximumDrawdown");
            Objects.requireNonNull(realShadowStatus, "realShadowStatus");
            Objects.requireNonNull(generatedAt, "generatedAt");
            requireHash(fingerprint, "M5_PERFORMANCE_REPORT_HASH_INVALID");
            if (!PERFORMANCE_REPORT_VERSION.equals(reportVersion)
                    || versionEvaluations.size() < 2
                    || frozenShadowRunCount < 0 || eligibleOutcomeCount < 0
                    || !currentChampionVersionKey.equals(
                    comparison.championVersionKey())
                    || !researchOnly || brokerConnected || realTradingEnabled) {
                throw invalid("M5_RESEARCH_PERFORMANCE_REPORT_INVALID");
            }
        }
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw invalid("M5_REQUIRED_TEXT_INVALID_" + name.toUpperCase());
        }
        return value;
    }

    static void requireHash(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid(code);
        }
    }

    static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
