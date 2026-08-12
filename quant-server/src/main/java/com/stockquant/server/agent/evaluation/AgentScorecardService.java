package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.AgentScorecard;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.LifecycleDecision;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.MetricScore;
import com.stockquant.server.agent.research.AgentResearchModels.AgentFinding;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRun;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.CriticIssueCode;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explainable role-specific scoring over frozen M3 evidence. */
public final class AgentScorecardService {
    private static final List<String> METRICS = List.of(
            "EVIDENCE_CORRECTNESS", "TOOL_CALL_CORRECTNESS",
            "UNSUPPORTED_CLAIM_CONTROL", "UNKNOWN_DISCIPLINE",
            "FUTURE_DATA_RECOGNITION", "RISK_RECOGNITION",
            "CONFLICT_DISCOVERY", "CRITIC_CORRECTION_CONTRIBUTION",
            "FINAL_REPORT_CONTRIBUTION", "STABILITY_CONSISTENCY",
            "TOKEN_COST_EFFICIENCY", "LATENCY_EFFICIENCY");
    private static final Map<AgentRole, Set<ToolCode>> ALLOWED_TOOLS = Map.of(
            AgentRole.RESEARCH_COORDINATOR, Set.of(ToolCode.values()),
            AgentRole.DATA_ANALYST, Set.of(ToolCode.RESEARCH_DATASET),
            AgentRole.MARKET_TECHNICAL, Set.of(ToolCode.MARKET_TECHNICAL),
            AgentRole.STRATEGY_RESEARCH, Set.of(ToolCode.STRATEGY_COMPARE),
            AgentRole.RISK, Set.of(ToolCode.RISK_METRICS),
            AgentRole.PORTFOLIO, Set.of(), AgentRole.CRITIC_REVIEW, Set.of());
    private static final Map<AgentRole, Map<String, BigDecimal>> WEIGHTS =
            weights();

    public AgentScorecard score(
            String versionKey,
            AgentRole role,
            List<ResearchReport> reports
    ) {
        if (reports == null || reports.isEmpty()) {
            throw AgentEvaluationModels.invalid("M5_SCORECARD_SAMPLE_EMPTY");
        }
        List<AgentRun> runs = reports.stream().flatMap(report ->
                        report.agentRuns().stream())
                .filter(run -> run.agentRole() == role).toList();
        if (runs.isEmpty()) {
            throw AgentEvaluationModels.invalid("M5_SCORECARD_ROLE_MISSING");
        }
        List<AgentFinding> findings = runs.stream().flatMap(run ->
                run.findings().stream()).toList();
        Map<String, Fraction> fractions = new LinkedHashMap<>();
        fractions.put("EVIDENCE_CORRECTNESS", evidence(role, reports));
        fractions.put("TOOL_CALL_CORRECTNESS", tools(role, runs));
        fractions.put("UNSUPPORTED_CLAIM_CONTROL", supported(findings));
        fractions.put("UNKNOWN_DISCIPLINE", unknowns(findings));
        fractions.put("FUTURE_DATA_RECOGNITION", future(role, reports));
        fractions.put("RISK_RECOGNITION", risk(role, reports));
        fractions.put("CONFLICT_DISCOVERY", conflict(role, reports));
        fractions.put("CRITIC_CORRECTION_CONTRIBUTION",
                correction(role, reports));
        fractions.put("FINAL_REPORT_CONTRIBUTION",
                contribution(role, reports, findings));
        fractions.put("STABILITY_CONSISTENCY", stability(reports, role));
        fractions.put("TOKEN_COST_EFFICIENCY", tokenEfficiency(runs));
        fractions.put("LATENCY_EFFICIENCY", latency(reports));

        Map<String, BigDecimal> weights = WEIGHTS.get(role);
        List<MetricScore> metrics = METRICS.stream().map(name -> {
            Fraction fraction = fractions.get(name);
            return new MetricScore(name, fraction.score(), weights.get(name),
                    fraction.passed(), fraction.total(), fraction.rationale());
        }).toList();
        BigDecimal weighted = metrics.stream().map(metric -> metric.score()
                        .multiply(metric.weight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
        List<String> failures = metrics.stream()
                .filter(metric -> metric.score().compareTo(
                        new BigDecimal("80")) < 0)
                .map(MetricScore::metric).toList();
        LifecycleDecision decision = reports.size() < 3
                ? LifecycleDecision.WATCH
                : weighted.compareTo(new BigDecimal("90")) >= 0
                ? LifecycleDecision.RETAIN
                : weighted.compareTo(new BigDecimal("75")) >= 0
                ? LifecycleDecision.WATCH
                : weighted.compareTo(new BigDecimal("60")) >= 0
                ? LifecycleDecision.DEMOTE : LifecycleDecision.REPLACE;
        List<String> rationale = new ArrayList<>(List.of(
                "ROLE_SPECIFIC_WEIGHTS_APPLIED",
                "ABSTENTION_IS_NOT_A_FAILURE",
                "RETURN_IS_NOT_AN_AGENT_SCORE_INPUT"));
        if (reports.size() < 3) {
            rationale.add("MINIMUM_REPORT_SAMPLE_NOT_MET");
        }
        return new AgentScorecard(AgentEvaluationModels.SCORECARD_VERSION,
                versionKey, role, weighted, decision, reports.size(),
                findings.size(), metrics, failures, rationale);
    }

    public List<AgentScorecard> scoreAll(
            String versionKey,
            List<ResearchReport> reports
    ) {
        return java.util.Arrays.stream(AgentRole.values())
                .map(role -> score(versionKey, role, reports)).toList();
    }

    private static Fraction evidence(
            AgentRole role,
            List<ResearchReport> reports
    ) {
        int passed = 0;
        int total = 0;
        for (ResearchReport report : reports) {
            Set<String> valid = report.evidence().stream()
                    .map(value -> value.evidenceId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (AgentFinding finding : report.agentRuns().stream()
                    .filter(run -> run.agentRole() == role)
                    .flatMap(run -> run.findings().stream())
                    .filter(finding -> finding.claimType()
                            != ClaimType.UNKNOWN).toList()) {
                total++;
                if (!finding.evidenceIds().isEmpty()
                        && valid.containsAll(finding.evidenceIds())) {
                    passed++;
                }
            }
        }
        return fraction(passed, total,
                "claims cite evidence from the same frozen report");
    }

    private static Fraction tools(AgentRole role, List<AgentRun> runs) {
        int passed = (int) runs.stream().filter(run -> ALLOWED_TOOLS.get(role)
                .containsAll(run.requestedTools())).count();
        return fraction(passed, runs.size(), "requested tools stay in role allowlist");
    }

    private static Fraction supported(List<AgentFinding> findings) {
        int passed = (int) findings.stream().filter(finding ->
                finding.claimType() == ClaimType.UNKNOWN
                        || !finding.evidenceIds().isEmpty()).count();
        return fraction(passed, findings.size(), "no unsupported factual claim");
    }

    private static Fraction unknowns(List<AgentFinding> findings) {
        List<AgentFinding> unknowns = findings.stream().filter(finding ->
                finding.claimType() == ClaimType.UNKNOWN).toList();
        int passed = (int) unknowns.stream().filter(finding ->
                finding.confidence().compareTo(new BigDecimal("0.5")) <= 0)
                .count();
        return fraction(passed, unknowns.size(),
                "UNKNOWN is calibrated and never penalized for abstention");
    }

    private static Fraction future(AgentRole role, List<ResearchReport> reports) {
        if (role != AgentRole.DATA_ANALYST && role != AgentRole.RISK
                && role != AgentRole.CRITIC_REVIEW
                && role != AgentRole.RESEARCH_COORDINATOR) {
            return fraction(1, 1, "future-data detection is not this role's primary duty");
        }
        int passed = (int) reports.stream().filter(report ->
                report.dataset().noFutureDataLeakage()
                        && report.strategyExperiments().experiments().stream()
                        .allMatch(value -> value.lookAheadGuard())).count();
        return fraction(passed, reports.size(), "PIT and execution guards retained");
    }

    private static Fraction risk(AgentRole role, List<ResearchReport> reports) {
        if (role != AgentRole.RISK && role != AgentRole.PORTFOLIO
                && role != AgentRole.CRITIC_REVIEW
                && role != AgentRole.RESEARCH_COORDINATOR) {
            return fraction(1, 1, "risk synthesis is not this role's primary duty");
        }
        int passed = (int) reports.stream().filter(report ->
                report.risk().accountingPassed()
                        && report.risk().lookAheadPassed()
                        && report.finalDecision().riskLevel()
                        == report.risk().overallLevel()).count();
        return fraction(passed, reports.size(), "quantified risk survives synthesis");
    }

    private static Fraction conflict(AgentRole role, List<ResearchReport> reports) {
        if (role != AgentRole.CRITIC_REVIEW
                && role != AgentRole.RESEARCH_COORDINATOR) {
            return fraction(1, 1, "conflict adjudication is not this role's primary duty");
        }
        int passed = (int) reports.stream().filter(report ->
                !report.criticReview().issues().contains(
                        CriticIssueCode.AGENT_CONFLICT)
                        || !report.criticReview().challengedFindingIds()
                        .isEmpty())
                .count();
        return fraction(passed, reports.size(),
                "identified conflicts name at least one challenged finding");
    }

    private static Fraction correction(AgentRole role,
            List<ResearchReport> reports) {
        if (role != AgentRole.CRITIC_REVIEW && role != AgentRole.PORTFOLIO
                && role != AgentRole.RESEARCH_COORDINATOR) {
            return fraction(1, 1, "critic correction is not this role's primary duty");
        }
        int passed = (int) reports.stream().filter(report ->
                !report.criticReview().reworkRequested()
                        || report.criticReview().correctionApplied()).count();
        return fraction(passed, reports.size(), "requested correction was applied");
    }

    private static Fraction contribution(AgentRole role,
            List<ResearchReport> reports, List<AgentFinding> findings) {
        int reportContribution = (int) reports.stream().filter(report ->
                report.agentRuns().stream().anyMatch(run ->
                        run.agentRole() == role)).count();
        if (findings.isEmpty() && role != AgentRole.RESEARCH_COORDINATOR) {
            return fraction(reportContribution, reports.size(),
                    "tool-selection-only runs remain traceable");
        }
        return fraction(reportContribution, reports.size(),
                "role output is present in the frozen report lineage");
    }

    private static Fraction stability(List<ResearchReport> reports,
            AgentRole role) {
        Set<List<String>> signatures = new LinkedHashSet<>();
        reports.forEach(report -> signatures.add(report.agentRuns().stream()
                .filter(run -> run.agentRole() == role)
                .map(run -> run.phase() + ":" + run.status() + ":"
                        + run.requestedTools().stream().sorted().toList() + ":"
                        + run.findings().stream()
                        .map(AgentFinding::claimType).sorted().toList())
                .toList()));
        return fraction(signatures.size() <= 1 ? reports.size() : 0,
                reports.size(),
                "role protocol and claim-shape stay stable across inputs");
    }

    private static Fraction tokenEfficiency(List<AgentRun> runs) {
        int efficient = (int) runs.stream().filter(run ->
                run.usage().totalTokens() <= 8_000).count();
        return fraction(efficient, runs.size(), "per-call token bound respected");
    }

    private static Fraction latency(List<ResearchReport> reports) {
        int passed = (int) reports.stream().filter(report ->
                !report.completedAt().isBefore(report.startedAt())
                        && java.time.Duration.between(report.startedAt(),
                        report.completedAt()).compareTo(
                        report.task().limits().timeout()) <= 0).count();
        return fraction(passed, reports.size(), "research finished inside runtime timeout");
    }

    private static Fraction fraction(int passed, int total, String rationale) {
        if (total == 0) {
            return new Fraction(1, 1, rationale + "; no applicable failure");
        }
        return new Fraction(passed, total, rationale);
    }

    private static Map<AgentRole, Map<String, BigDecimal>> weights() {
        EnumMap<AgentRole, Map<String, BigDecimal>> result = new EnumMap<>(
                AgentRole.class);
        result.put(AgentRole.RESEARCH_COORDINATOR, map(18, 8, 12, 5, 8, 8,
                8, 8, 12, 5, 4, 4));
        result.put(AgentRole.DATA_ANALYST, map(28, 18, 12, 8, 14, 3, 2, 2,
                4, 4, 3, 2));
        result.put(AgentRole.MARKET_TECHNICAL, map(24, 18, 14, 8, 8, 4, 2,
                2, 8, 5, 4, 3));
        result.put(AgentRole.STRATEGY_RESEARCH, map(22, 20, 14, 6, 10, 6,
                2, 2, 7, 4, 4, 3));
        result.put(AgentRole.RISK, map(18, 14, 12, 7, 14, 18, 3, 5, 3, 2,
                2, 2));
        result.put(AgentRole.PORTFOLIO, map(16, 8, 14, 8, 8, 16, 5, 8, 8,
                4, 3, 2));
        result.put(AgentRole.CRITIC_REVIEW, map(18, 6, 14, 8, 12, 10, 10,
                14, 3, 2, 2, 1));
        return Map.copyOf(result);
    }

    private static Map<String, BigDecimal> map(int... percentages) {
        if (percentages.length != METRICS.size()
                || java.util.Arrays.stream(percentages).sum() != 100) {
            throw new IllegalStateException("M5_ROLE_WEIGHTS_INVALID");
        }
        LinkedHashMap<String, BigDecimal> result = new LinkedHashMap<>();
        for (int index = 0; index < percentages.length; index++) {
            result.put(METRICS.get(index), BigDecimal.valueOf(
                    percentages[index], 2));
        }
        return Map.copyOf(result);
    }

    private record Fraction(int passed, int total, String rationale) {
        private BigDecimal score() {
            return BigDecimal.valueOf(passed)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 4,
                            RoundingMode.HALF_UP);
        }
    }
}
