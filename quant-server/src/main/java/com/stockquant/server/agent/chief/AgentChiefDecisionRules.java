package com.stockquant.server.agent.chief;

import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentChiefDecisionRules {

    public record Evaluation(
            FinalDecisionCode decision,
            GateStatus gateStatus,
            boolean vetoed,
            int score,
            int confidence,
            String summary,
            List<String> vetoIds,
            Integer weightedScoreSum,
            Integer weightedConfidenceSum,
            Severity highestRiskSeverity
    ) {
    }

    public Evaluation evaluate(
            List<AgentOutput> runs,
            List<FormalVeto> vetoes
    ) {
        Map<AgentCode, AgentOutput> byCode = orderedRuns(runs);
        AgentOutput dataQuality = byCode.get(AgentCode.DATA_QUALITY);
        AgentOutput positionRisk = byCode.get(AgentCode.POSITION_RISK);

        if (!vetoes.isEmpty()) {
            return result(
                    FinalDecisionCode.REJECTED_BY_VETO,
                    GateStatus.BLOCKED,
                    true,
                    0,
                    positionRisk.confidence(),
                    vetoes.stream().map(FormalVeto::vetoId).toList(),
                    null,
                    null,
                    null);
        }
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            return result(
                    FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                    GateStatus.BLOCKED,
                    false,
                    0,
                    dataQuality.confidence(),
                    List.of(),
                    null,
                    null,
                    null);
        }
        if (!composable(byCode)) {
            return result(
                    FinalDecisionCode.INSUFFICIENT_DATA,
                    GateStatus.NOT_APPLICABLE,
                    false,
                    0,
                    0,
                    List.of(),
                    null,
                    null,
                    null);
        }

        int weightedScoreSum = weightedSum(byCode, true);
        int weightedConfidenceSum = weightedSum(byCode, false);
        int score = halfUpNonNegative(weightedScoreSum, 100);
        int confidence = halfUpNonNegative(weightedConfidenceSum, 100);
        if (dataQuality.gateStatus() == GateStatus.WARN) {
            confidence = Math.min(confidence, 50);
        }
        if (positionRisk.status() == RunStatus.PARTIAL) {
            confidence = Math.min(confidence, 50);
        }

        Severity risk = highestRiskSeverity(
                byCode.get(AgentCode.ANNOUNCEMENT_RISK),
                positionRisk);
        boolean forcedResearch =
                dataQuality.gateStatus() == GateStatus.WARN
                        || positionRisk.status() == RunStatus.PARTIAL
                        || risk == Severity.HIGH
                        || risk == Severity.CRITICAL
                        || score < 50
                        || confidence < 40;
        FinalDecisionCode decision;
        GateStatus gateStatus;
        if (forcedResearch) {
            decision = FinalDecisionCode.RESEARCH_ONLY;
            gateStatus = GateStatus.WARN;
        } else if (manualReviewEligible(
                byCode, score, confidence, risk)) {
            decision = FinalDecisionCode.PASS_TO_MANUAL_REVIEW;
            gateStatus = GateStatus.PASS;
        } else {
            decision = FinalDecisionCode.WATCH;
            gateStatus = GateStatus.WARN;
        }
        return result(
                decision,
                gateStatus,
                false,
                score,
                confidence,
                List.of(),
                weightedScoreSum,
                weightedConfidenceSum,
                risk);
    }

    public static Severity highestRiskSeverity(
            AgentOutput announcementRisk,
            AgentOutput positionRisk
    ) {
        Severity highest = Severity.INFO;
        for (Finding finding : announcementRisk.findings()) {
            if (ChiefDecisionContracts.ANNOUNCEMENT_RISK_FINDINGS
                    .contains(finding.code())) {
                highest = maximum(highest, finding.severity());
            }
        }
        for (Finding finding : positionRisk.findings()) {
            if (ChiefDecisionContracts.POSITION_RISK_FINDINGS
                    .contains(finding.code())) {
                highest = maximum(highest, finding.severity());
            }
        }
        return highest;
    }

    public static String summary(
            FinalDecisionCode decision,
            int score,
            int confidence
    ) {
        return "%s decision=%s; compositeScore=%d; compositeConfidence=%d; "
                .formatted(
                        ChiefDecisionContracts.CONTRACT_VERSION,
                        decision.name(),
                        score,
                        confidence)
                + "MARKET_REGIME V1 is informational with score/confidence "
                + "weight 0; research or manual review only; "
                + "no executable action.";
    }

    public static int halfUpNonNegative(int numerator, int denominator) {
        if (numerator < 0 || denominator <= 0) {
            throw new IllegalArgumentException(
                    "HALF_UP input must be non-negative");
        }
        return Math.toIntExact(
                (2L * numerator + denominator) / (2L * denominator));
    }

    private static Evaluation result(
            FinalDecisionCode decision,
            GateStatus gateStatus,
            boolean vetoed,
            int score,
            int confidence,
            List<String> vetoIds,
            Integer weightedScoreSum,
            Integer weightedConfidenceSum,
            Severity highestRiskSeverity
    ) {
        return new Evaluation(
                decision,
                gateStatus,
                vetoed,
                score,
                confidence,
                summary(decision, score, confidence),
                List.copyOf(vetoIds),
                weightedScoreSum,
                weightedConfidenceSum,
                highestRiskSeverity);
    }

    private static Map<AgentCode, AgentOutput> orderedRuns(
            List<AgentOutput> runs
    ) {
        if (runs == null
                || runs.size() != AgentCode.PROFESSIONAL_AGENTS.size()) {
            throw new IllegalArgumentException(
                    "2I requires exactly six professional runs");
        }
        EnumMap<AgentCode, AgentOutput> values =
                new EnumMap<>(AgentCode.class);
        for (int index = 0; index < runs.size(); index++) {
            AgentOutput run = Objects.requireNonNull(runs.get(index));
            AgentCode expected = AgentCode.PROFESSIONAL_AGENTS.get(index);
            if (run.agentCode() != expected
                    || values.put(run.agentCode(), run) != null) {
                throw new IllegalArgumentException(
                        "2I professional runs must use the fixed order");
            }
        }
        return Map.copyOf(values);
    }

    private static boolean composable(
            Map<AgentCode, AgentOutput> byCode
    ) {
        AgentOutput dq = byCode.get(AgentCode.DATA_QUALITY);
        AgentOutput market = byCode.get(AgentCode.MARKET_REGIME);
        AgentOutput technical = byCode.get(AgentCode.TECHNICAL_ANALYSIS);
        AgentOutput backtest = byCode.get(AgentCode.STRATEGY_BACKTEST);
        AgentOutput announcement = byCode.get(AgentCode.ANNOUNCEMENT_RISK);
        AgentOutput position = byCode.get(AgentCode.POSITION_RISK);

        if (!(dq.status() == RunStatus.COMPLETED
                && passOrWarn(dq.gateStatus())
                && passOrWarn(dq.decision())
                && !dq.veto()
                && dq.confidence() == 100
                && dq.errors().isEmpty()
                && !dq.evidence().isEmpty())) {
            return false;
        }
        if (!(market.status() == RunStatus.COMPLETED
                && market.confidence() == 0
                && !market.veto()
                && market.errors().isEmpty()
                && !market.findings().isEmpty()
                && !market.evidence().isEmpty())) {
            return false;
        }
        if (!(technical.status() == RunStatus.COMPLETED
                && passOrWarn(technical.gateStatus())
                && technical.decision() == RunDecision.WARN
                && !technical.veto()
                && technical.confidence() > 0
                && technical.errors().isEmpty()
                && !technical.findings().isEmpty()
                && !technical.evidence().isEmpty())) {
            return false;
        }
        if (!(backtest.status() == RunStatus.COMPLETED
                && passOrWarn(backtest.gateStatus())
                && backtest.decision() == RunDecision.WARN
                && !backtest.veto()
                && backtest.confidence() > 0
                && backtest.errors().isEmpty()
                && !backtest.findings().isEmpty()
                && !backtest.evidence().isEmpty())) {
            return false;
        }
        if (!(announcement.status() == RunStatus.COMPLETED
                && passOrWarn(announcement.gateStatus())
                && passOrWarn(announcement.decision())
                && !announcement.veto()
                && announcement.confidence() == 40
                && announcement.errors().isEmpty()
                && !announcement.findings().isEmpty()
                && !announcement.evidence().isEmpty())) {
            return false;
        }
        return (position.status() == RunStatus.COMPLETED
                || position.status() == RunStatus.PARTIAL)
                && passOrWarn(position.gateStatus())
                && passOrWarn(position.decision())
                && !position.veto()
                && position.confidence() > 0
                && position.errors().isEmpty()
                && !position.findings().isEmpty()
                && !position.evidence().isEmpty();
    }

    private static int weightedSum(
            Map<AgentCode, AgentOutput> byCode,
            boolean score
    ) {
        return ChiefDecisionContracts.CONTRIBUTOR_ORDER.stream()
                .mapToInt(code -> {
                    AgentOutput run = byCode.get(code);
                    int value = score ? run.score() : run.confidence();
                    return value * ChiefDecisionContracts.WEIGHTS.get(code);
                })
                .sum();
    }

    private static boolean manualReviewEligible(
            Map<AgentCode, AgentOutput> byCode,
            int score,
            int confidence,
            Severity risk
    ) {
        AgentOutput dq = byCode.get(AgentCode.DATA_QUALITY);
        AgentOutput technical = byCode.get(AgentCode.TECHNICAL_ANALYSIS);
        AgentOutput backtest = byCode.get(AgentCode.STRATEGY_BACKTEST);
        AgentOutput announcement = byCode.get(AgentCode.ANNOUNCEMENT_RISK);
        AgentOutput position = byCode.get(AgentCode.POSITION_RISK);
        return dq.gateStatus() == GateStatus.PASS
                && technical.score() >= 60
                && backtest.score() >= 60
                && announcement.gateStatus() == GateStatus.PASS
                && position.status() == RunStatus.COMPLETED
                && position.gateStatus() == GateStatus.PASS
                && score >= 70
                && confidence >= 60
                && risk == Severity.INFO;
    }

    private static boolean passOrWarn(GateStatus value) {
        return value == GateStatus.PASS || value == GateStatus.WARN;
    }

    private static boolean passOrWarn(RunDecision value) {
        return value == RunDecision.PASS || value == RunDecision.WARN;
    }

    private static Severity maximum(Severity left, Severity right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
