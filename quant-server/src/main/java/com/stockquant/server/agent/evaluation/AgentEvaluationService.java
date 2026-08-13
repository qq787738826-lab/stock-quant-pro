package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.AgentScorecard;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.AgentVersion;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ChampionChallengerComparison;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ConfidenceCalibration;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.EvaluationStatus;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.EvaluationProof;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ResearchPerformanceReport;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ShadowOutcomeEvaluation;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.VersionEvaluation;
import com.stockquant.server.agent.research.AgentResearchEval.EvalReport;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.FrozenSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** M5 read/evaluate/persist facade. It never mutates an M4 decision. */
@Service
public class AgentEvaluationService {
    private final AgentEvaluationRepository evaluations;
    private final ShadowResearchRepository shadows;
    private final Clock clock;
    private final AgentScorecardService scorecards =
            new AgentScorecardService();
    private final ConfidenceCalibrationService calibration =
            new ConfidenceCalibrationService();
    private final ShadowOutcomeEvaluationService outcomes =
            new ShadowOutcomeEvaluationService();
    private final ChampionChallengerService comparisons =
            new ChampionChallengerService();

    public AgentEvaluationService(
            AgentEvaluationRepository evaluations,
            ShadowResearchRepository shadows,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations");
        this.shadows = Objects.requireNonNull(shadows, "shadows");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public ResearchPerformanceReport evaluateAndFreeze(
            AgentVersion champion,
            AgentVersion challenger,
            EvalReport championEval,
            EvalReport challengerEval,
            List<ResearchReport> challengerReports,
            EvaluationProof championProof,
            EvaluationProof challengerProof
    ) {
        Objects.requireNonNull(champion, "champion");
        Objects.requireNonNull(challenger, "challenger");
        Objects.requireNonNull(championEval, "championEval");
        Objects.requireNonNull(challengerEval, "challengerEval");
        Objects.requireNonNull(championProof, "championProof");
        Objects.requireNonNull(challengerProof, "challengerProof");
        if (!championProof.equals(EvaluationProof.from(championEval,
                championProof.historicalReplaySamples()))
                || !challengerProof.equals(EvaluationProof.from(
                challengerEval,
                challengerProof.historicalReplaySamples()))) {
            throw AgentEvaluationModels.invalid(
                    "M5_EVALUATION_PROOF_BINDING_INVALID");
        }
        if (champion.kind()
                != AgentEvaluationModels.VersionKind.CHAMPION
                || challenger.kind()
                != AgentEvaluationModels.VersionKind.CHALLENGER
                || !challenger.parentVersionKey().equals(
                champion.versionKey())) {
            throw AgentEvaluationModels.invalid(
                    "M5_VERSION_RELATIONSHIP_INVALID");
        }
        if (sameLineage(champion, challenger)) {
            throw AgentEvaluationModels.invalid(
                    "M5_CHALLENGER_LINEAGE_NOT_DISTINCT");
        }
        List<FrozenSnapshot> snapshots = shadows.frozenSnapshots(250);
        if (snapshots.isEmpty()) {
            throw AgentEvaluationModels.invalid("M5_SHADOW_SAMPLE_EMPTY");
        }
        Map<Long, ShadowRun> runs = new LinkedHashMap<>();
        shadows.runs(250).forEach(run -> runs.put(run.id(), run));
        for (FrozenSnapshot snapshot : snapshots) {
            if (!runs.containsKey(snapshot.runId())) {
                throw AgentEvaluationModels.invalid("M5_SHADOW_RUN_MISSING");
            }
        }
        AgentVersion registeredChampion = evaluations.currentChampion()
                .orElse(null);
        if (registeredChampion != null
                && !registeredChampion.fingerprint().equals(
                champion.fingerprint())) {
            throw AgentEvaluationModels.invalid(
                    "M5_CHAMPION_REGISTRY_MISMATCH");
        }
        VersionEvidence championEvidence = versionEvidence(champion,
                snapshots, runs);
        if (championEvidence.reports().isEmpty()) {
            throw AgentEvaluationModels.invalid(
                    "M5_CHAMPION_SHADOW_LINEAGE_MISSING");
        }
        VersionEvidence challengerShadowEvidence = versionEvidence(challenger,
                snapshots, runs);
        List<ResearchReport> submittedChallengerReports = List.copyOf(
                Objects.requireNonNull(challengerReports,
                        "challengerReports"));
        Set<String> frozenResearch = snapshots.stream()
                .map(FrozenSnapshot::report)
                .map(ResearchReport::researchFingerprint)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (submittedChallengerReports.stream().map(
                ResearchReport::researchFingerprint).anyMatch(
                frozenResearch::contains)) {
            throw AgentEvaluationModels.invalid(
                "M5_FROZEN_CHAMPION_EVIDENCE_REUSE_FORBIDDEN");
        }
        Set<String> championScopes = championEvidence.reports().stream()
                .map(AgentEvaluationService::evaluationScope)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (submittedChallengerReports.stream()
                .map(AgentEvaluationService::evaluationScope)
                .anyMatch(scope -> !championScopes.contains(scope))) {
            throw AgentEvaluationModels.invalid(
                    "M5_CHALLENGER_EVALUATION_SCOPE_MISMATCH");
        }
        List<ResearchReport> boundChallengerReports = mergeReports(
                challengerShadowEvidence.reports(),
                submittedChallengerReports);
        validateLineage(champion, championEvidence.reports());
        validateLineage(challenger, boundChallengerReports);
        evaluations.register(champion);
        evaluations.register(challenger);
        VersionEvaluation championEvaluation = versionEvaluation(champion,
                championEvidence.reports(), championEvidence.outcomes(),
                championEval,
                championProof);
        VersionEvaluation challengerEvaluation = versionEvaluation(challenger,
                boundChallengerReports, challengerShadowEvidence.outcomes(),
                challengerEval,
                challengerProof);
        ChampionChallengerComparison comparison = comparisons.compare(
                championEvaluation, challengerEvaluation);
        List<PortfolioSnapshot> portfolio = shadows.portfolioSnapshots(250);
        BigDecimal totalReturn = portfolio.isEmpty() ? BigDecimal.ZERO
                : portfolio.get(0).totalReturn();
        BigDecimal maxDrawdown = maximumDrawdown(portfolio);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("champion", championEvaluation);
        canonical.put("challenger", challengerEvaluation);
        canonical.put("comparison", comparison);
        canonical.put("shadowFingerprints", snapshots.stream()
                .map(FrozenSnapshot::snapshotFingerprint).toList());
        canonical.put("championOutcomes", championEvidence.outcomes());
        canonical.put("challengerOutcomes",
                challengerShadowEvidence.outcomes());
        canonical.put("paperReturn", totalReturn);
        canonical.put("paperMaximumDrawdown", maxDrawdown);
        // Promotion eligibility is evidence, not authorization. A separate
        // explicit human action is required before changing the Champion.
        String currentChampion = champion.versionKey();
        VersionEvaluation selected = championEvaluation;
        ResearchPerformanceReport report = new ResearchPerformanceReport(
                AgentEvaluationModels.PERFORMANCE_REPORT_VERSION,
                currentChampion,
                List.of(championEvaluation, challengerEvaluation), comparison,
                snapshots.size(), selected.calibration().eligibleSampleCount(),
                totalReturn, maxDrawdown, selected.calibration().status(),
                clock.instant(),
                AgentEvaluationCanonical.hash(canonical), true, false, false);
        return evaluations.save(report);
    }

    private VersionEvidence versionEvidence(
            AgentVersion version,
            List<FrozenSnapshot> snapshots,
            Map<Long, ShadowRun> runs
    ) {
        List<ResearchReport> reports = new ArrayList<>();
        List<ShadowOutcomeEvaluation> observed = new ArrayList<>();
        for (FrozenSnapshot snapshot : snapshots) {
            ShadowRun run = runs.get(snapshot.runId());
            if (!lineageMatches(version, run, snapshot.report())) {
                continue;
            }
            reports.add(snapshot.report());
            List<PortfolioSnapshot> portfolio = shadows
                    .portfolioSnapshotsForRun(run.id());
            observed.addAll(outcomes.evaluate(run, snapshot,
                    shadows.outcomes(run.id()), portfolio));
        }
        return new VersionEvidence(mergeReports(List.of(), reports),
                List.copyOf(observed));
    }

    private static boolean lineageMatches(
            AgentVersion version,
            ShadowRun run,
            ResearchReport report
    ) {
        return version.strategyVersion().equals(run.strategyVersion())
                && version.runtimeVersion().equals(run.agentRuntimeVersion())
                && version.runtimeVersion().equals(report.runtimeVersion())
                && version.toolVersion().equals(report.toolGatewayVersion())
                && version.modelProvider().equals(run.modelProvider())
                && version.model().equals(run.model())
                && report.agentRuns().stream().allMatch(agentRun ->
                version.modelProvider().equals(agentRun.modelProvider())
                        && version.model().equals(agentRun.model())
                        && version.promptVersions().get(agentRun.agentRole())
                        .equals(agentRun.promptVersion()))
                && report.agentRuns().stream().map(value ->
                value.agentRole()).collect(java.util.stream.Collectors.toSet())
                .equals(version.promptVersions().keySet());
    }

    private static boolean sameLineage(
            AgentVersion champion,
            AgentVersion challenger
    ) {
        return champion.runtimeVersion().equals(challenger.runtimeVersion())
                && champion.toolVersion().equals(challenger.toolVersion())
                && champion.strategyVersion().equals(
                challenger.strategyVersion())
                && champion.modelProvider().equals(
                challenger.modelProvider())
                && champion.model().equals(challenger.model())
                && champion.promptVersions().equals(
                challenger.promptVersions());
    }

    private static List<ResearchReport> mergeReports(
            List<ResearchReport> first,
            List<ResearchReport> second
    ) {
        LinkedHashMap<String, ResearchReport> values = new LinkedHashMap<>();
        java.util.stream.Stream.concat(first.stream(), second.stream())
                .forEach(report -> values.putIfAbsent(
                        report.researchFingerprint(), report));
        return List.copyOf(values.values());
    }

    private static String evaluationScope(ResearchReport report) {
        var task = report.task();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("datasetFingerprint",
                report.dataset().datasetFingerprint());
        canonical.put("securities", task.securities());
        canonical.put("rangeStart", task.rangeStart());
        canonical.put("rangeEnd", task.rangeEnd());
        canonical.put("anchorTradeDate", task.anchorTradeDate());
        canonical.put("knowledgeCutoff", task.knowledgeCutoff());
        canonical.put("benchmark", task.benchmark());
        canonical.put("strategies", task.strategies());
        canonical.put("limits", task.limits());
        return AgentEvaluationCanonical.hash(canonical);
    }

    private static void validateLineage(
            AgentVersion version,
            List<ResearchReport> reports
    ) {
        if (reports.isEmpty()) {
            throw AgentEvaluationModels.invalid("M5_VERSION_SAMPLE_EMPTY");
        }
        for (ResearchReport report : reports) {
            if (!version.runtimeVersion().equals(report.runtimeVersion())
                    || !version.toolVersion().equals(
                    report.toolGatewayVersion())
                    || report.agentRuns().isEmpty()
                    || report.agentRuns().stream().anyMatch(run ->
                    !version.modelProvider().equals(run.modelProvider())
                            || !version.model().equals(run.model())
                            || !version.promptVersions().get(run.agentRole())
                            .equals(run.promptVersion()))
                    || report.agentRuns().stream()
                    .map(run -> run.agentRole()).distinct().count()
                    != version.promptVersions().size()) {
                throw AgentEvaluationModels.invalid(
                        "M5_VERSION_REPORT_LINEAGE_MISMATCH");
            }
        }
    }

    public Overview overview() {
        ResearchPerformanceReport latest = evaluations.latest().orElse(null);
        return new Overview(AgentEvaluationModels.SYSTEM_VERSION, latest,
                latest == null ? 0 : latest.frozenShadowRunCount(),
                latest == null ? EvaluationStatus.INSUFFICIENT_SAMPLE
                        : latest.realShadowStatus(),
                evaluations.versions(), true, false, false);
    }

    private VersionEvaluation versionEvaluation(
            AgentVersion version,
            List<ResearchReport> reports,
            List<ShadowOutcomeEvaluation> shadowOutcomes,
            EvalReport eval,
            EvaluationProof proof
    ) {
        List<AgentScorecard> cards = scorecards.scoreAll(version.versionKey(),
                reports);
        ConfidenceCalibration calibrated = calibration.evaluate(shadowOutcomes);
        BigDecimal overall = cards.stream().map(AgentScorecard::weightedScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(cards.size()), 4,
                        RoundingMode.HALF_UP);
        int modelCalls = reports.stream().mapToInt(
                ResearchReport::modelCallCount).sum();
        int totalTokens = reports.stream().mapToInt(report ->
                report.totalModelUsage().totalTokens()).sum();
        BigDecimal cost = reports.stream().map(report ->
                        report.totalModelUsage().estimatedCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<String> currencies = reports.stream().map(report ->
                report.totalModelUsage().costCurrency()).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        if (currencies.size() != 1) {
            throw AgentEvaluationModels.invalid(
                    "M5_VERSION_USAGE_CURRENCY_MISMATCH");
        }
        Duration elapsed = reports.stream().map(report ->
                        Duration.between(report.startedAt(), report.completedAt()))
                .reduce(Duration.ZERO, Duration::plus);
        boolean pass = "PASS".equals(eval.status());
        EvaluationStatus status = pass ? calibrated.status()
                : EvaluationStatus.FAIL;
        List<String> failures = new ArrayList<>();
        if (!pass) failures.add("AGENT_EVAL_FAILED");
        if (calibrated.status() == EvaluationStatus.INSUFFICIENT_SAMPLE) {
            failures.add("INSUFFICIENT_REAL_SHADOW_SAMPLE");
        } else if (calibrated.status() == EvaluationStatus.FAIL) {
            failures.add("CONFIDENCE_CALIBRATION_FAILED");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("version", version.fingerprint());
        canonical.put("scorecards", cards);
        canonical.put("calibration", calibrated);
        canonical.put("outcomes", shadowOutcomes);
        canonical.put("eval", eval.fingerprint());
        canonical.put("evaluationProof", proof.fingerprint());
        canonical.put("usage", List.of(modelCalls, totalTokens, cost, elapsed));
        return new VersionEvaluation(version.versionKey(), cards, calibrated,
                shadowOutcomes, eval.passed(), eval.total(),
                proof.historicalReplaySamples(),
                proof.deterministicReplayPassed(),
                proof.lookAheadGuardPassed(), proof.riskGatePassed(),
                modelCalls, totalTokens, cost,
                currencies.iterator().next(), elapsed,
                overall, status, failures,
                AgentEvaluationCanonical.hash(canonical));
    }

    private static BigDecimal maximumDrawdown(List<PortfolioSnapshot> values) {
        List<PortfolioSnapshot> chronological = values.stream()
                .sorted(java.util.Comparator.comparing(
                        PortfolioSnapshot::snapshotTime)).toList();
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maximum = BigDecimal.ZERO;
        for (PortfolioSnapshot value : chronological) {
            peak = peak.max(value.totalEquity());
            if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(value.totalEquity())
                        .divide(peak, 12, RoundingMode.HALF_UP);
                maximum = maximum.max(drawdown);
            }
        }
        return maximum;
    }

    public record Overview(
            String systemVersion,
            ResearchPerformanceReport latestReport,
            int frozenShadowSamples,
            EvaluationStatus realShadowStatus,
            List<AgentVersion> registeredVersions,
            boolean researchOnly,
            boolean brokerConnected,
            boolean realTradingEnabled
    ) {
        public Overview {
            registeredVersions = List.copyOf(registeredVersions);
        }
    }

    private record VersionEvidence(
            List<ResearchReport> reports,
            List<ShadowOutcomeEvaluation> outcomes
    ) {
    }
}
