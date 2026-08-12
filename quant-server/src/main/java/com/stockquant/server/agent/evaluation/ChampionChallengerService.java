package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ChampionChallengerComparison;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ComparisonDecision;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.EvaluationStatus;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.VersionEvaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed promotion gate: offline superiority cannot replace real evidence. */
public final class ChampionChallengerService {
    private static final BigDecimal REQUIRED_DELTA = new BigDecimal("2.0");
    private static final BigDecimal MAX_RATIO = new BigDecimal("1.25");

    public ChampionChallengerComparison compare(
            VersionEvaluation champion,
            VersionEvaluation challenger
    ) {
        BigDecimal delta = challenger.overallScore().subtract(
                champion.overallScore());
        BigDecimal costRatio = ratio(challenger.accountedCost(),
                champion.accountedCost());
        BigDecimal latencyRatio = ratio(BigDecimal.valueOf(
                        challenger.elapsed().toMillis()),
                BigDecimal.valueOf(champion.elapsed().toMillis()));
        boolean evidence = challenger.shadowOutcomes().size()
                >= AgentEvaluationModels.MINIMUM_SHADOW_SAMPLE
                && challenger.offlineEvalPassed()
                == challenger.offlineEvalTotal()
                && challenger.historicalReplaySamples() >= 60;
        boolean safe = challenger.deterministicReplayPassed()
                && challenger.lookAheadGuardPassed()
                && challenger.riskGatePassed()
                && challenger.status() != EvaluationStatus.FAIL;
        boolean efficient = costRatio.compareTo(MAX_RATIO) <= 0
                && latencyRatio.compareTo(MAX_RATIO) <= 0;
        boolean overfitGuard = challenger.historicalReplaySamples() >= 60
                && safe;
        boolean promote = evidence && safe && efficient && overfitGuard
                && delta.compareTo(REQUIRED_DELTA) >= 0;
        ComparisonDecision decision;
        List<String> reasons = new ArrayList<>();
        if (promote) {
            decision = ComparisonDecision.PROMOTE_CHALLENGER;
            reasons.add("CHALLENGER_SUPERIOR_WITH_MINIMUM_EVIDENCE");
        } else if (!safe) {
            decision = ComparisonDecision.REJECT_CHALLENGER;
            reasons.add("SAFETY_OR_REGRESSION_GATE_FAILED");
        } else if (!evidence) {
            decision = ComparisonDecision.WATCH_CHALLENGER;
            if (challenger.shadowOutcomes().size()
                    < AgentEvaluationModels.MINIMUM_SHADOW_SAMPLE) {
                reasons.add("INSUFFICIENT_SHADOW_SAMPLE");
            }
            if (challenger.offlineEvalPassed()
                    != challenger.offlineEvalTotal()) {
                reasons.add("OFFLINE_AGENT_EVAL_INCOMPLETE");
            }
            if (challenger.historicalReplaySamples() < 60) {
                reasons.add("INSUFFICIENT_BOUND_REPLAY_EVIDENCE");
            }
        } else {
            decision = ComparisonDecision.RETAIN_CHAMPION;
            reasons.add(delta.compareTo(REQUIRED_DELTA) < 0
                    ? "NO_MATERIAL_SCORE_IMPROVEMENT"
                    : "COST_OR_LATENCY_REGRESSION");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("champion", champion.fingerprint());
        canonical.put("challenger", challenger.fingerprint());
        canonical.put("decision", decision);
        canonical.put("scoreDelta", delta);
        canonical.put("costRatio", costRatio);
        canonical.put("latencyRatio", latencyRatio);
        canonical.put("evidence", evidence);
        canonical.put("overfitGuard", overfitGuard);
        canonical.put("reasons", reasons);
        return new ChampionChallengerComparison(
                AgentEvaluationModels.CHAMPION_CHALLENGER_VERSION,
                champion.versionKey(), challenger.versionKey(), decision,
                delta, costRatio, latencyRatio, evidence, overfitGuard,
                promote, reasons, AgentEvaluationCanonical.hash(canonical));
    }

    private static BigDecimal ratio(BigDecimal value, BigDecimal base) {
        if (base.signum() == 0) {
            return value.signum() == 0 ? BigDecimal.ONE
                    : new BigDecimal("999");
        }
        return value.divide(base, 6, RoundingMode.HALF_UP);
    }
}
