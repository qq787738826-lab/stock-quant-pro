package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.DefaultStrategyResearchApi;
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
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalResearch;
import com.stockquant.server.researchselection.ResearchSelectionModels.Lineage;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.RecommendationStatus;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionResult;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionExplanation;
import com.stockquant.server.researchselection.ResearchSelectionModels.ResearchTradePlan;
import com.stockquant.server.researchselection.ResearchSelectionModels.Status;
import com.stockquant.server.researchselection.ResearchSelectionModels.Timings;
import com.stockquant.server.researchselection.ResearchSelectionModels.Usage;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.EligibilityStatus;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.MemberEvaluation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader.LoadedMainboard;

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
import java.util.Set;
import java.util.LinkedHashMap;

/** M1 -> deterministic scan -> M2 -> Top10 seven-agent V1.0.1 pipeline. */
public final class ResearchSelectionEngine {
    private static final String STRATEGY_VERSION =
            "RESEARCH_SELECTION_STRATEGY_V1";
    private static final String PROMPT_VERSION = "M3_PROMPT_CATALOG_V3";

    private final TushareResearchUniverseDatasetLoader datasetLoader;
    private final ResearchUniverseMainboardDatasetLoader mainboardLoader;
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
        this.mainboardLoader = null;
        this.ranking = new ResearchSelectionRankingService();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ResearchSelectionEngine(
            ResearchUniverseMainboardDatasetLoader mainboardLoader,
            Clock clock,
            ObjectMapper mapper
    ) {
        this.datasetLoader = null;
        this.mainboardLoader = Objects.requireNonNull(mainboardLoader,
                "mainboardLoader");
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
            Map<String, Integer> liveShadowSamples,
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
        var historicalDataset = new ResearchSelectionHistoricalDatasetLoader()
                .expand(datasetLoader, loaded, anchor, asOf);
        long dataMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.QUANTITATIVE_SCAN);
        List<QuantitativeScore> fullRanking = ranking.rank(loaded.dataset());
        List<QuantitativeScore> shortlist = fullRanking.stream()
                .limit(request.shortlistSize()).toList();
        long rankingMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.STRATEGY_ANALYSIS);
        HistoricalResearch historical =
                new ResearchSelectionHistoricalStabilityService().analyze(
                        historicalDataset, fullRanking, liveShadowSamples);
        ResearchDataset topDataset = subset(loaded.dataset(), shortlist);
        List<StrategySpec> strategies = ResearchSelectionStrategies.fixed();
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
                providerCalls, historical, model);
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
                report.researchFingerprint(), "historical", historical,
                "asOf", asOf));
        Lineage lineage = new Lineage(ResearchUniverseV1.VERSION,
                ResearchUniverseV1.securities(), request.primaryWindow(),
                request.auxiliaryWindow(),
                ResearchSelectionModels.RANKING_VERSION,
                AgentResearchModels.RUNTIME_VERSION, PROMPT_VERSION,
                descriptor.provider(), descriptor.model(), STRATEGY_VERSION,
                ResearchSelectionModels.HISTORICAL_STABILITY_VERSION,
                gitCommit, datasetFingerprint,
                historical.datasetFingerprint(), resultFingerprint, null,
                ResearchUniverseV1.securities().size(), null);
        SelectionResult result = new SelectionResult(
                ResearchSelectionModels.VERSION, runId, publicRunId,
                Status.COMPLETED, request.triggerMode(), asOf, anchor,
                preparedCoverage == null ? loaded.coverage()
                        : preparedCoverage, null, historical, fullRanking,
                shortlist, candidates, List.of(), List.of(),
                candidates.isEmpty(), candidates.isEmpty()
                ? AgentResearchModels.DecisionCode.INSUFFICIENT_EVIDENCE.name()
                : report.finalDecision().code().name(),
                report, shadow.run().id(), request.paperEnabled(), false,
                false, timings,
                usage, lineage, null, null, startedAt, clock.instant());
        return new EngineResult(result, comparison, List.of());
    }

    public EngineResult runMainboard(
            long runId,
            String publicRunId,
            SelectionRequest request,
            SnapshotBundle snapshot,
            LocalDate anchor,
            Instant asOf,
            String gitCommit,
            int providerCalls,
            int retryCount,
            Map<String, Integer> liveShadowSamples,
            ModelAdapter model,
            DeepResearch deepResearch,
            StageListener stages,
            long startedNanos,
            Instant startedAt
    ) {
        if (mainboardLoader == null) {
            throw new IllegalStateException(
                    "MAINBOARD_RESEARCH_ENGINE_NOT_CONFIGURED");
        }
        long phase = System.nanoTime();
        stages.stage(Status.PREPARING_DATA);
        LoadedMainboard loaded = mainboardLoader.load(snapshot, anchor, asOf);
        long dataMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.QUANTITATIVE_SCAN);
        Map<Security, Member> metadata = snapshot.members().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        Member::security, value -> value));
        ResearchSelectionRankingService.RankingResult explainedRanking =
                ranking.rankExplained(loaded.dataset(), metadata);
        List<QuantitativeScore> fullRanking = explainedRanking.scores();
        List<QuantitativeScore> historicalPool = fullRanking.stream()
                .filter(score -> available(loaded.evaluations(),
                        score.security()) >= ResearchUniverseMainboard
                        .STABILITY_MINIMUM_SESSIONS)
                .limit(ResearchUniverseMainboard.HISTORICAL_LIMIT).toList();
        long rankingMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.STRATEGY_ANALYSIS);
        HistoricalResearch fullHistorical = historical(historicalPool, loaded,
                liveShadowSamples);
        Map<Security, QuantitativeScore> scoresBySecurity = fullRanking.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        QuantitativeScore::security, value -> value));
        List<QuantitativeScore> strategyPool = fullHistorical.securities()
                .stream().map(value -> scoresBySecurity.get(value.security()))
                .filter(java.util.Objects::nonNull)
                .limit(ResearchUniverseMainboard.STRATEGY_LIMIT).toList();
        if (strategyPool.size() < 3) {
            throw new IllegalStateException(
                    "MAINBOARD_STRATEGY_POOL_INSUFFICIENT");
        }
        ResearchDataset strategyDataset = subsetTrailing(loaded.dataset(),
                strategyPool, ResearchUniverseMainboard
                        .STABILITY_MINIMUM_SESSIONS, "TOP30");
        List<StrategySpec> strategies = ResearchSelectionStrategies.fixed();
        ComparisonResult comparison = new DefaultStrategyResearchApi().compare(
                strategyDataset, strategies, BacktestConfig.standard(),
                strategyDataset.firstSessionDate(),
                strategyDataset.lastSessionDate(),
                benchmark(strategyPool));
        List<QuantitativeScore> shortlist = strategyPool.stream()
                .limit(request.shortlistSize()).toList();
        HistoricalResearch historical = compactHistorical(fullHistorical,
                strategyPool);
        long strategyMillis = elapsed(phase);

        phase = System.nanoTime();
        stages.stage(Status.AI_RESEARCH);
        ResearchDataset topDataset = subsetTrailing(loaded.dataset(),
                shortlist, ResearchUniverseMainboard
                        .STABILITY_MINIMUM_SESSIONS, "TOP10");
        var descriptor = model.descriptor();
        ShadowExecutionResult shadow = deepResearch.run(topDataset,
                shortlist, strategies, request, publicRunId, anchor, asOf,
                providerCalls, historical, model);
        ResearchReport report = shadow.snapshot().report();
        long agentMillis = elapsed(phase);

        stages.stage(Status.CRITIC_REVIEW);
        List<Candidate> candidates = candidates(shortlist, comparison, report,
                shadow.snapshot().recommendation().rankedSecurities(),
                request.finalLimit());
        List<SelectionExplanation> explanations =
                new ResearchSelectionExplanationService().explain(
                        explainedRanking, fullHistorical, strategyPool,
                        shortlist, candidates,
                        shadow.snapshot().recommendation().rankedSecurities(),
                        comparison, report, loaded.evaluations(),
                        request.finalLimit());
        List<ResearchTradePlan> tradePlans =
                new ResearchTradePlanService().create(candidates, historical,
                        loaded.tradePlanPrices(), loaded.dataset(), shadow);
        List<MemberEvaluation> evaluations = enrich(loaded.evaluations(),
                fullRanking, fullHistorical, strategyPool, shortlist,
                candidates);
        ResearchUniverseMainboard.Funnel funnel = funnel(snapshot,
                evaluations, fullHistorical, strategyPool, shortlist,
                candidates);
        var modelUsage = report.totalModelUsage();
        Usage usage = new Usage(providerCalls, retryCount,
                report.modelCallCount(), shadow.modelProviderRequests(),
                modelUsage.inputTokens(), modelUsage.outputTokens(),
                modelUsage.reasoningTokens(), modelUsage.totalTokens(),
                modelUsage.estimatedCost());
        Timings timings = new Timings(dataMillis, rankingMillis,
                strategyMillis, agentMillis, Math.max(0,
                (System.nanoTime() - startedNanos) / 1_000_000L));
        List<QuantitativeScore> persistedRanking = fullRanking.stream()
                .limit(ResearchUniverseMainboard.HISTORICAL_LIMIT).toList();
        String datasetFingerprint = report.dataset().datasetFingerprint();
        String resultFingerprint = hash(Map.of(
                "ranking", persistedRanking, "shortlist", shortlist,
                "candidates", candidates, "funnel", funnel,
                "selectionExplanations", explanations,
                "researchTradePlans", tradePlans,
                "research", report.researchFingerprint(),
                "historical", historical, "asOf", asOf));
        Lineage lineage = new Lineage(ResearchUniverseMainboard.VERSION,
                List.of(), request.primaryWindow(), request.auxiliaryWindow(),
                ResearchSelectionModels.RANKING_VERSION,
                AgentResearchModels.RUNTIME_VERSION, PROMPT_VERSION,
                descriptor.provider(), descriptor.model(), STRATEGY_VERSION,
                ResearchSelectionModels.HISTORICAL_STABILITY_VERSION,
                gitCommit, datasetFingerprint,
                historical.datasetFingerprint(), resultFingerprint,
                snapshot.snapshot().snapshotId(),
                snapshot.snapshot().memberCount(),
                snapshot.snapshot().memberFingerprint());
        SelectionResult result = new SelectionResult(
                ResearchSelectionModels.VERSION, runId, publicRunId,
                Status.COMPLETED, request.triggerMode(), asOf, anchor,
                loaded.coverage(), funnel, historical, persistedRanking,
                shortlist, candidates, explanations, tradePlans,
                candidates.isEmpty(),
                candidates.isEmpty()
                        ? AgentResearchModels.DecisionCode
                        .INSUFFICIENT_EVIDENCE.name()
                        : report.finalDecision().code().name(),
                report, shadow.run().id(), request.paperEnabled(), false,
                false, timings, usage, lineage, null, null, startedAt,
                clock.instant());
        return new EngineResult(result, comparison, evaluations);
    }

    private static HistoricalResearch compactHistorical(
            HistoricalResearch source,
            List<QuantitativeScore> selected
    ) {
        Set<Security> allowed = selected.stream().map(
                QuantitativeScore::security).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        return new HistoricalResearch(source.version(),
                source.researchLabel(), source.pitQualification(),
                source.availableSessions(), source.rangeStart(),
                source.rangeEnd(), source.windowCoverage(),
                source.missingTradeDates(), source.securities().stream()
                .filter(value -> allowed.contains(value.security())).toList(),
                source.gradeDistribution(),
                source.calendarCompleteThroughAnchor(),
                source.knownAtQualified(), source.dataQualityPassed(),
                source.noFutureDataLeakage(), source.datasetFingerprint());
    }

    private HistoricalResearch historical(
            List<QuantitativeScore> pool,
            LoadedMainboard loaded,
            Map<String, Integer> liveShadowSamples
    ) {
        if (pool.size() < 3) {
            throw new IllegalStateException(
                    "MAINBOARD_HISTORICAL_POOL_INSUFFICIENT");
        }
        int sessions = pool.stream().mapToInt(value -> available(
                loaded.evaluations(), value.security())).min()
                .orElseThrow();
        sessions = Math.min(250, sessions);
        ResearchDataset dataset = subsetTrailing(loaded.dataset(), pool,
                sessions, "TOP200_HISTORY");
        DataCoverage coverage = new DataCoverage(dataset.firstSessionDate(),
                dataset.lastSessionDate(), sessions, sessions, pool.size(),
                pool.size(), 0, 0, true, true, true, true, true);
        var loadedUniverse = new TushareResearchUniverseDatasetLoader
                .LoadedUniverse(dataset, coverage);
        var historicalDataset = new ResearchSelectionHistoricalDatasetLoader
                .HistoricalDataset(loadedUniverse,
                ResearchSelectionHistoricalDatasetLoader.coverage(
                        dataset.sessions().stream().map(
                                value -> value.tradeDate()).toList()));
        return new ResearchSelectionHistoricalStabilityService().analyze(
                historicalDataset, pool, liveShadowSamples);
    }

    private static ResearchDataset subsetTrailing(
            ResearchDataset source,
            List<QuantitativeScore> selected,
            int sessions,
            String label
    ) {
        int bounded = Math.min(sessions, source.sessions().size());
        List<com.stockquant.core.research.StrategyResearchModels
                .TradingSession> selectedSessions = source.sessions().subList(
                source.sessions().size() - bounded, source.sessions().size());
        Set<LocalDate> dates = selectedSessions.stream().map(value ->
                value.tradeDate()).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        Set<Security> securities = selected.stream().map(
                QuantitativeScore::security).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        List<com.stockquant.core.research.StrategyResearchModels.DailyBar>
                bars = source.bars().stream().filter(value ->
                securities.contains(value.security())
                        && dates.contains(value.tradeDate())).toList();
        if (bars.size() != securities.size() * bounded) {
            throw new IllegalStateException(
                    "MAINBOARD_SUBSET_HISTORY_INCOMPLETE");
        }
        return new ResearchDataset(source.contractVersion(),
                subsetDatasetVersion(source.datasetVersion(), securities,
                        dates, label),
                source.knowledgeMode(), source.knowledgeCutoff(),
                selectedSessions, bars);
    }

    static String subsetDatasetVersion(
            String sourceDatasetVersion,
            java.util.Collection<Security> securities,
            java.util.Collection<LocalDate> dates,
            String label
    ) {
        String securityCodes = securities.stream().map(
                        Security::canonicalCode).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String tradeDates = dates.stream().distinct().sorted()
                .map(LocalDate::toString).collect(
                        java.util.stream.Collectors.joining(","));
        return "MAINBOARD_" + label + '_'
                + BacktestCanonicalHashService.sha256(
                sourceDatasetVersion + "|securities=" + securityCodes
                        + "|dates=" + tradeDates);
    }

    private static int available(
            List<MemberEvaluation> evaluations,
            Security security
    ) {
        return evaluations.stream().filter(value ->
                value.member().security().equals(security)).findFirst()
                .map(MemberEvaluation::availableSessions).orElse(0);
    }

    private static List<MemberEvaluation> enrich(
            List<MemberEvaluation> source,
            List<QuantitativeScore> ranking,
            HistoricalResearch historical,
            List<QuantitativeScore> strategyPool,
            List<QuantitativeScore> shortlist,
            List<Candidate> candidates
    ) {
        Map<Security, QuantitativeScore> scores = ranking.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        QuantitativeScore::security, value -> value));
        Map<Security, ResearchSelectionModels.HistoricalStability> stability =
                historical.securities().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        ResearchSelectionModels.HistoricalStability::security,
                        value -> value));
        Map<Security, Integer> historicalRanks = new LinkedHashMap<>();
        for (int index = 0; index < historical.securities().size(); index++) {
            historicalRanks.put(historical.securities().get(index).security(),
                    index + 1);
        }
        Map<Security, Integer> strategyRanks = ranks(strategyPool);
        Set<Security> agent = shortlist.stream().map(
                QuantitativeScore::security).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        Set<Security> candidate = candidates.stream().map(
                Candidate::security).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        return source.stream().map(value -> {
            Security security = value.member().security();
            QuantitativeScore score = scores.get(security);
            var history = stability.get(security);
            return new MemberEvaluation(value.member(), value.status(),
                    value.exclusionReasons(), value.availableSessions(),
                    value.missingDaily(), value.missingAdjustmentFactors(),
                    value.averageTradedAmount(),
                    score == null ? null : score.rank(),
                    score == null ? null : score.score(),
                    historicalRanks.get(security),
                    history == null ? null : history.score(),
                    history == null ? null : history.grade().name(),
                    strategyRanks.get(security), agent.contains(security),
                    candidate.contains(security));
        }).toList();
    }

    private static Map<Security, Integer> ranks(
            List<QuantitativeScore> values
    ) {
        Map<Security, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            result.put(values.get(index).security(), index + 1);
        }
        return Map.copyOf(result);
    }

    private static ResearchUniverseMainboard.Funnel funnel(
            SnapshotBundle snapshot,
            List<MemberEvaluation> evaluations,
            HistoricalResearch historical,
            List<QuantitativeScore> strategyPool,
            List<QuantitativeScore> shortlist,
            List<Candidate> candidates
    ) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        evaluations.forEach(value -> value.exclusionReasons().forEach(reason ->
                reasons.merge(reason.name(), 1, Integer::sum)));
        int eligible = Math.toIntExact(evaluations.stream().filter(value ->
                value.status() == EligibilityStatus.ELIGIBLE).count());
        int suspended = reasons.getOrDefault(
                ResearchUniverseMainboard.ExclusionReason
                        .SUSPENDED_OR_NO_TRADE.name(), 0);
        int insufficient = reasons.getOrDefault(
                ResearchUniverseMainboard.ExclusionReason
                        .TWENTY_SESSION_HISTORY_INSUFFICIENT.name(), 0);
        return new ResearchUniverseMainboard.Funnel(
                ResearchUniverseMainboard.VERSION,
                snapshot.snapshot().snapshotId(),
                snapshot.snapshot().memberCount(),
                snapshot.snapshot().sseCount(),
                snapshot.snapshot().szseCount(),
                snapshot.snapshot().stCount(), eligible,
                snapshot.snapshot().memberCount() - eligible, suspended,
                insufficient, eligible, historical.securities().size(),
                strategyPool.size(), shortlist.size(), candidates.size(),
                reasons);
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

    private String hash(Object value) {
        return new BacktestCanonicalHashService(mapper)
                .hash(mapper.valueToTree(value));
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public record EngineResult(
            SelectionResult selection,
            ComparisonResult strategyComparison,
            List<MemberEvaluation> memberEvaluations
    ) {
        public EngineResult {
            memberEvaluations = List.copyOf(memberEvaluations);
        }
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
                HistoricalResearch historical,
                ModelAdapter model
        );
    }
}
