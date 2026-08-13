package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.ComparisonResult;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyComparison;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader;
import com.stockquant.server.agent.research.AgentResearchModels;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.researchselection.ResearchSelectionModels.Candidate;
import com.stockquant.server.researchselection.ResearchSelectionModels.DataCoverage;
import com.stockquant.server.researchselection.ResearchSelectionModels.Lineage;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.RecommendationStatus;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionResult;
import com.stockquant.server.researchselection.ResearchSelectionModels.Status;
import com.stockquant.server.researchselection.ResearchSelectionModels.Timings;
import com.stockquant.server.researchselection.ResearchSelectionModels.Usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** M1 -> deterministic scan -> M2 -> Top10 seven-agent V1.0.1 pipeline. */
public final class ResearchSelectionEngine {
    private static final String STRATEGY_VERSION =
            "RESEARCH_SELECTION_STRATEGY_V1";
    private static final String PROMPT_VERSION = "M3_PROMPT_CATALOG_V2";

    private final TushareResearchUniverseDatasetLoader datasetLoader;
    private final ResearchSelectionRankingService ranking;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ResearchSelectionEngine(
            TushareResearchUniverseDatasetLoader datasetLoader,
            Clock clock,
            ObjectMapper mapper
    ) {
        this.datasetLoader = Objects.requireNonNull(datasetLoader,
                "datasetLoader");
        this.ranking = new ResearchSelectionRankingService();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public EngineResult run(
            long runId,
            String publicRunId,
            SelectionRequest request,
            LocalDate anchor,
            Instant asOf,
            String gitCommit,
            int providerCalls,
            int retryCount,
            DataCoverage preparedCoverage,
            ModelAdapter model,
            DeepResearch deepResearch,
            StageListener stages,
            long startedNanos,
            Instant startedAt
    ) {
        long phase = System.nanoTime();
        stages.stage(Status.PREPARING_DATA);
        var loaded = datasetLoader.load(ResearchUniverseV1.securities(),
                request.auxiliaryWindow(), anchor, asOf);
        long dataMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.QUANTITATIVE_SCAN);
        List<QuantitativeScore> fullRanking = ranking.rank(loaded.dataset());
        List<QuantitativeScore> shortlist = fullRanking.stream()
                .limit(request.shortlistSize()).toList();
        long rankingMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.STRATEGY_ANALYSIS);
        ResearchDataset topDataset = subset(loaded.dataset(), shortlist);
        List<StrategySpec> strategies = strategies();
        ComparisonResult comparison = new DefaultStrategyResearchApi().compare(
                topDataset, strategies, BacktestConfig.standard(),
                topDataset.firstSessionDate(), topDataset.lastSessionDate(),
                benchmark(shortlist));
        long strategyMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.AI_RESEARCH);
        var descriptor = model.descriptor();
        ShadowExecutionResult shadow = deepResearch.run(topDataset,
                shortlist, strategies, request, publicRunId, anchor, asOf,
                providerCalls, model);
        ResearchReport report = shadow.snapshot().report();
        long agentMillis = elapsed(phase);

        stages.stage(Status.CRITIC_REVIEW);
        List<Candidate> candidates = candidates(shortlist, comparison, report,
                shadow.snapshot().recommendation().rankedSecurities(),
                request.finalLimit());
        var modelUsage = report.totalModelUsage();
        Usage usage = new Usage(providerCalls, retryCount,
                report.modelCallCount(), shadow.modelProviderRequests(),
                modelUsage.inputTokens(),
                modelUsage.outputTokens(), modelUsage.reasoningTokens(),
                modelUsage.totalTokens(), modelUsage.estimatedCost());
        long totalMillis = Math.max(0, (System.nanoTime() - startedNanos)
                / 1_000_000L);
        Timings timings = new Timings(dataMillis, rankingMillis,
                strategyMillis, agentMillis, totalMillis);
        String datasetFingerprint = report.dataset().datasetFingerprint();
        String resultFingerprint = hash(Map.of(
                "ranking", fullRanking, "shortlist", shortlist,
                "candidates", candidates, "research",
                report.researchFingerprint(), "asOf", asOf));
        Lineage lineage = new Lineage(ResearchUniverseV1.VERSION,
                ResearchUniverseV1.securities(), request.primaryWindow(),
                request.auxiliaryWindow(),
                ResearchSelectionModels.RANKING_VERSION,
                AgentResearchModels.RUNTIME_VERSION, PROMPT_VERSION,
                descriptor.provider(), descriptor.model(), STRATEGY_VERSION,
                gitCommit, datasetFingerprint, resultFingerprint);
        SelectionResult result = new SelectionResult(
                ResearchSelectionModels.VERSION, runId, publicRunId,
                Status.COMPLETED, request.triggerMode(), asOf, anchor,
                preparedCoverage == null ? loaded.coverage()
                        : preparedCoverage, fullRanking, shortlist, candidates,
                candidates.isEmpty(), candidates.isEmpty()
                ? AgentResearchModels.DecisionCode.INSUFFICIENT_EVIDENCE.name()
                : report.finalDecision().code().name(),
                report, shadow.run().id(), request.paperEnabled(), false,
                false, timings,
                usage, lineage, null, null, startedAt, clock.instant());
        return new EngineResult(result, comparison);
    }

    private static ResearchDataset subset(
            ResearchDataset source,
            List<QuantitativeScore> shortlist
    ) {
        var allowed = shortlist.stream().map(QuantitativeScore::security)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ResearchDataset(source.contractVersion(),
                "SELECTION_TOP10_" + BacktestCanonicalHashService.sha256(
                        source.datasetVersion() + allowed),
                source.knowledgeMode(), source.knowledgeCutoff(),
                source.sessions(), source.bars().stream()
                .filter(value -> allowed.contains(value.security())).toList());
    }

    private static List<Candidate> candidates(
            List<QuantitativeScore> shortlist,
            ComparisonResult comparison,
            ResearchReport report,
            List<String> agentRankedSecurities,
            int limit
    ) {
        if (report.finalDecision().code()
                != AgentResearchModels.DecisionCode.RESEARCH_PREFERENCE
                || !report.dataset().dataQualityPassed()
                || !report.dataset().noFutureDataLeakage()
                || !report.risk().accountingPassed()
                || !report.risk().lookAheadPassed()) {
            return List.of();
        }
        List<String> critic = report.criticReview().issues().stream()
                .map(Enum::name).toList();
        List<String> opposing = new ArrayList<>(
                report.portfolio().limitations());
        opposing.addAll(critic);
        Map<String, QuantitativeScore> bySecurity = shortlist.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.security().canonicalCode(),
                        value -> value));
        List<QuantitativeScore> ranked = agentRankedSecurities.stream()
                .map(bySecurity::get).filter(Objects::nonNull)
                .filter(value -> value.dataQualityPassed()
                        && value.score().compareTo(
                        new BigDecimal("55.0000")) >= 0)
                .distinct().limit(limit).toList();
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            QuantitativeScore value = ranked.get(index);
            candidates.add(new Candidate(index + 1,
                        value.security(), value.name(), value.industry(),
                        value.score(), RecommendationStatus.WATCH,
                        report.risk().overallLevel().name(),
                        report.finalDecision().confidence(),
                        value.explanations(), opposing,
                        report.finalDecision().preferredStrategy(),
                        comparison.strategies(), preferredDrawdown(comparison,
                                report.finalDecision().preferredStrategy()),
                        value.trend(), critic));
        }
        return List.copyOf(candidates);
    }

    private static BigDecimal preferredDrawdown(
            ComparisonResult comparison,
            String preferredStrategy
    ) {
        return comparison.strategies().stream().filter(value ->
                        value.strategyCode().equals(preferredStrategy))
                .findFirst()
                .map(StrategyComparison::maxDrawdown).orElse(BigDecimal.ZERO)
                .setScale(8, RoundingMode.HALF_EVEN);
    }

    private static Security benchmark(List<QuantitativeScore> shortlist) {
        Security configured = ResearchUniverseV1.benchmark();
        return shortlist.stream().map(QuantitativeScore::security)
                .filter(configured::equals).findFirst()
                .orElse(shortlist.get(0).security());
    }

    private static List<StrategySpec> strategies() {
        return List.of(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("symbol", "ALL", "targetWeight", "0.80")),
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "5", "longWindow", "20",
                                "targetWeight", "0.20")),
                new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                        Map.of("lookback", "10", "entryDeviation", "0.02",
                                "exitDeviation", "0.00",
                                "targetWeight", "0.20")),
                new StrategySpec(StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                        Map.of("lookback", "20", "topN", "3",
                                "rebalanceEvery", "5",
                                "targetGrossExposure", "0.60")));
    }

    private String hash(Object value) {
        return new BacktestCanonicalHashService(mapper)
                .hash(mapper.valueToTree(value));
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public record EngineResult(
            SelectionResult selection,
            ComparisonResult strategyComparison
    ) {
    }

    @FunctionalInterface
    public interface StageListener {
        void stage(Status status);
    }

    @FunctionalInterface
    public interface DeepResearch {
        ShadowExecutionResult run(
                ResearchDataset topDataset,
                List<QuantitativeScore> shortlist,
                List<StrategySpec> strategies,
                SelectionRequest selectionRequest,
                String publicRunId,
                LocalDate anchor,
                Instant asOf,
                int providerCalls,
                ModelAdapter model
        );
    }
}
