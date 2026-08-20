package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.ComparisonResult;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.agent.research.AgentResearchModels;
import com.stockquant.server.agent.research.AgentResearchModels.AgentFinding;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.researchselection.ResearchSelectionModels.Candidate;
import com.stockquant.server.researchselection.ResearchSelectionModels.EligibilityCheck;
import com.stockquant.server.researchselection.ResearchSelectionModels.FirstExcludedComparison;
import com.stockquant.server.researchselection.ResearchSelectionModels.GateCheck;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalComponentScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalResearch;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalStability;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionExplanation;
import com.stockquant.server.researchselection.ResearchSelectionRankingService.RankingResult;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.ExclusionReason;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.MemberEvaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Projects the existing selection path without changing any rank or gate. */
public final class ResearchSelectionExplanationService {

    public List<SelectionExplanation> explain(
            RankingResult ranking,
            HistoricalResearch historical,
            List<QuantitativeScore> strategyPool,
            List<QuantitativeScore> agentPool,
            List<Candidate> candidates,
            List<String> agentRankedSecurities,
            ComparisonResult comparison,
            ResearchReport report,
            List<MemberEvaluation> evaluations,
            int finalLimit
    ) {
        Objects.requireNonNull(ranking, "ranking");
        Map<Security, QuantitativeScore> scores = bySecurity(
                ranking.scores());
        Map<Security, HistoricalStability> histories = historical.securities()
                .stream().collect(java.util.stream.Collectors
                        .toUnmodifiableMap(HistoricalStability::security,
                                value -> value));
        Map<Security, MemberEvaluation> members = evaluations.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.member().security(), value -> value));
        Map<Security, Integer> historicalRanks = ranks(
                historical.securities().stream().map(
                        HistoricalStability::security).toList());
        Map<Security, Integer> strategyRanks = ranks(strategyPool.stream()
                .map(QuantitativeScore::security).toList());
        Map<Security, Integer> agentRanks = canonicalRanks(
                agentRankedSecurities, scores);
        FirstExcludedComparison firstExcluded = firstExcluded(agentPool,
                candidates, agentRanks, histories, historicalRanks,
                strategyRanks, report, finalLimit);
        List<String> evidenceIds = report.finalDecision()
                .supportingEvidenceIds();
        List<String> corrections = criticCorrections(report);
        List<String> critic = report.criticReview().issues().stream()
                .map(Enum::name).toList();
        List<SelectionExplanation> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            QuantitativeScore score = scores.get(candidate.security());
            HistoricalStability history = histories.get(candidate.security());
            MemberEvaluation member = members.get(candidate.security());
            if (score == null || history == null || member == null) {
                throw new IllegalStateException(
                        "SELECTION_EXPLANATION_SOURCE_MISSING");
            }
            var scoreExplanation = ranking.explanations().get(
                    candidate.security());
            List<String> supporting = supportingFindings(candidate, report,
                    evidenceIds);
            List<String> opposing = opposingFindings(candidate, report);
            result.add(new SelectionExplanation(
                    ResearchSelectionModels.SELECTION_EXPLANATION_VERSION,
                    candidate.security(), member.exclusionReasons().isEmpty(),
                    eligibilityChecks(member), score.rank(),
                    ranking.scores().size(), score.score(),
                    scoreExplanation.contributions(),
                    scoreExplanation.metricPercentiles(),
                    historicalRanks.get(candidate.security()),
                    historical.securities().size(), history.score(),
                    history.grade(), historicalComponents(history),
                    strategyRanks.get(candidate.security()),
                    strategyPool.size(), agentRanks.get(candidate.security()),
                    agentPool.size(), candidate.rank(), finalLimit,
                    comparison.strategies(), supporting, opposing, critic,
                    corrections, finalGates(score, report, candidate.rank(),
                    finalLimit), firstExcluded, evidenceIds,
                    candidate.opposingReasons()));
        }
        return List.copyOf(result);
    }

    private static List<EligibilityCheck> eligibilityChecks(
            MemberEvaluation value
    ) {
        Set<ExclusionReason> reasons = Set.copyOf(value.exclusionReasons());
        return List.of(
                check("CURRENTLY_LISTED_MAINBOARD",
                        "L".equals(value.member().listStatus())
                                && "主板".equals(value.member().market())),
                check("NOT_ST", !reasons.contains(
                        ExclusionReason.ST_SECURITY)),
                check("DAILY_COMPLETE", value.missingDaily() == 0),
                check("ADJUSTMENT_FACTOR_COMPLETE",
                        value.missingAdjustmentFactors() == 0),
                check("MINIMUM_TWENTY_SESSIONS",
                        value.availableSessions() >= ResearchUniverseMainboard
                                .BASIC_MINIMUM_SESSIONS),
                check("TRADABLE_AT_ANCHOR", !reasons.contains(
                        ExclusionReason.SUSPENDED_OR_NO_TRADE)),
                check("LIQUIDITY_GATE", !reasons.contains(
                        ExclusionReason.EXTREMELY_LOW_LIQUIDITY)),
                check("PRICE_AND_VOLUME_QUALITY", !reasons.contains(
                        ExclusionReason.PRICE_OR_VOLUME_ANOMALY)),
                check("NO_FUTURE_DATA", !reasons.contains(
                        ExclusionReason.FUTURE_DATA_GUARD_FAILED)));
    }

    private static EligibilityCheck check(String code, boolean passed) {
        return new EligibilityCheck(code, passed,
                passed ? "PASSED" : "FAILED");
    }

    private static List<HistoricalComponentScore> historicalComponents(
            HistoricalStability value
    ) {
        return List.of(
                historical("DATA_COMPLETENESS",
                        value.dataCompletenessComponent(), "0.20"),
                historical("MULTI_WINDOW_AND_STRATEGY_CONSISTENCY",
                        value.multiWindowConsistencyComponent(), "0.20"),
                historical("OUT_OF_SAMPLE",
                        value.outOfSampleComponent(), "0.25"),
                historical("DRAWDOWN_AND_VOLATILITY",
                        value.riskComponent(), "0.20"),
                historical("COST_ADJUSTED_AND_SAMPLE_SIZE",
                        value.costAndSampleComponent(), "0.15"));
    }

    private static HistoricalComponentScore historical(
            String component,
            BigDecimal score,
            String weight
    ) {
        BigDecimal decimalWeight = new BigDecimal(weight);
        return new HistoricalComponentScore(component, score, decimalWeight,
                score.multiply(decimalWeight).setScale(4,
                        RoundingMode.HALF_EVEN));
    }

    private static List<GateCheck> finalGates(
            QuantitativeScore score,
            ResearchReport report,
            int finalRank,
            int limit
    ) {
        return List.of(
                gate("RESEARCH_PREFERENCE", report.finalDecision().code()
                        == AgentResearchModels.DecisionCode
                        .RESEARCH_PREFERENCE),
                gate("DATA_QUALITY", report.dataset().dataQualityPassed()),
                gate("NO_FUTURE_DATA",
                        report.dataset().noFutureDataLeakage()),
                gate("ACCOUNTING_INVARIANT",
                        report.risk().accountingPassed()),
                gate("LOOK_AHEAD_GUARD", report.risk().lookAheadPassed()),
                gate("CURRENT_SCORE_AT_LEAST_55",
                        score.score().compareTo(
                                new BigDecimal("55.0000")) >= 0),
                gate("FINAL_CANDIDATE_LIMIT", finalRank <= limit));
    }

    private static GateCheck gate(String code, boolean passed) {
        return new GateCheck(code, passed, passed ? "PASSED" : "FAILED");
    }

    private static FirstExcludedComparison firstExcluded(
            List<QuantitativeScore> agentPool,
            List<Candidate> candidates,
            Map<Security, Integer> agentRanks,
            Map<Security, HistoricalStability> histories,
            Map<Security, Integer> historicalRanks,
            Map<Security, Integer> strategyRanks,
            ResearchReport report,
            int finalLimit
    ) {
        QuantitativeScore excluded = firstExcludedScore(agentPool,
                candidates, agentRanks);
        if (excluded == null) return null;
        List<String> failed = new ArrayList<>();
        if (excluded.score().compareTo(new BigDecimal("55.0000")) < 0) {
            failed.add("CURRENT_SCORE_BELOW_55");
        }
        Integer agentRank = agentRanks.get(excluded.security());
        if (agentRank == null) failed.add("NOT_SELECTED_BY_AGENT_PORTFOLIO");
        else if (agentRank > finalLimit) {
            failed.add("FINAL_CANDIDATE_LIMIT_REACHED");
        }
        finalGates(excluded, report, finalLimit + 1, finalLimit).stream()
                .filter(value -> !value.passed()).map(GateCheck::code)
                .forEach(failed::add);
        if (failed.isEmpty()) failed.add("LOWER_FINAL_RESEARCH_PRIORITY");
        HistoricalStability history = histories.get(excluded.security());
        return new FirstExcludedComparison(excluded.security(),
                excluded.name(), excluded.rank(),
                historicalRanks.get(excluded.security()),
                strategyRanks.get(excluded.security()), agentRank,
                excluded.score(), history == null ? null : history.score(),
                List.copyOf(new LinkedHashSet<>(failed)));
    }

    static QuantitativeScore firstExcludedScore(
            List<QuantitativeScore> agentPool,
            List<Candidate> candidates
    ) {
        Set<Security> selected = candidates.stream().map(Candidate::security)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return agentPool.stream().filter(value ->
                !selected.contains(value.security())).findFirst()
                .orElse(null);
    }

    private static QuantitativeScore firstExcludedScore(
            List<QuantitativeScore> agentPool,
            List<Candidate> candidates,
            Map<Security, Integer> agentRanks
    ) {
        Set<Security> selected = candidates.stream().map(Candidate::security)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return agentPool.stream().filter(value ->
                        !selected.contains(value.security()))
                .min(Comparator.comparingInt((QuantitativeScore value) ->
                                agentRanks.getOrDefault(value.security(),
                                        Integer.MAX_VALUE))
                        .thenComparingInt(QuantitativeScore::rank))
                .orElse(null);
    }

    private static List<String> supportingFindings(
            Candidate candidate,
            ResearchReport report,
            List<String> evidenceIds
    ) {
        Set<String> accepted = Set.copyOf(evidenceIds);
        LinkedHashSet<String> result = new LinkedHashSet<>(
                candidate.supportingReasons());
        report.agentRuns().stream()
                .flatMap(value -> value.findings().stream())
                .filter(value -> value.claimType()
                        != AgentResearchModels.ClaimType.UNKNOWN)
                .filter(value -> value.evidenceIds().stream()
                        .anyMatch(accepted::contains))
                .filter(value -> mentions(value.statement(),
                        candidate.security()))
                .sorted(Comparator.comparing(AgentFinding::findingId))
                .map(AgentFinding::statement).forEach(result::add);
        return List.copyOf(result);
    }

    private static List<String> opposingFindings(
            Candidate candidate,
            ResearchReport report
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>(
                candidate.opposingReasons());
        report.agentRuns().stream()
                .flatMap(value -> value.findings().stream())
                .filter(value -> value.claimType()
                        == AgentResearchModels.ClaimType.UNKNOWN)
                .filter(value -> mentions(value.statement(),
                        candidate.security()))
                .map(AgentFinding::statement).forEach(result::add);
        return List.copyOf(result);
    }

    private static boolean mentions(String statement, Security security) {
        return statement.contains(security.symbol())
                || statement.contains(security.canonicalCode())
                || statement.contains(security.symbol()
                + ("SSE".equals(security.exchange()) ? ".SH" : ".SZ"));
    }

    private static List<String> criticCorrections(ResearchReport report) {
        if (!report.criticReview().correctionApplied()) return List.of();
        List<String> result = new ArrayList<>();
        result.add("CRITIC_CORRECTION_APPLIED");
        report.criticReview().challengedFindingIds().forEach(value ->
                result.add("CHALLENGED:" + value));
        report.agentRuns().stream().filter(value -> value.revised())
                .flatMap(value -> value.findings().stream())
                .map(value -> "REVISED:" + value.statement())
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static Map<Security, QuantitativeScore> bySecurity(
            List<QuantitativeScore> values
    ) {
        return values.stream().collect(java.util.stream.Collectors
                .toUnmodifiableMap(QuantitativeScore::security,
                        value -> value));
    }

    private static Map<Security, Integer> ranks(List<Security> values) {
        Map<Security, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            result.put(values.get(index), index + 1);
        }
        return Map.copyOf(result);
    }

    private static Map<Security, Integer> canonicalRanks(
            List<String> values,
            Map<Security, QuantitativeScore> scores
    ) {
        Map<String, Security> byCode = new LinkedHashMap<>();
        scores.keySet().forEach(value -> byCode.put(value.canonicalCode(),
                value));
        Map<Security, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            Security security = byCode.get(values.get(index));
            if (security != null) result.putIfAbsent(security, index + 1);
        }
        return Map.copyOf(result);
    }
}
