package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.research.AgentResearchDatasetSource.LoadedDataset;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.shadowresearch.InMemoryShadowResearchDatasetSource;
import com.stockquant.server.agent.shadowresearch.ShadowPaperPortfolioService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRequest;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.TriggerMode;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRuntime;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalResearch;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reuses the accepted M3/M4 runtime for a deterministic Top10 subset. */
public final class ResearchSelectionDeepResearchService
        implements ResearchSelectionEngine.DeepResearch {
    private final ShadowResearchRepository repository;
    private final ShadowPaperPortfolioService paper;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final NextPaperExecutionResolver nextExecution;

    public ResearchSelectionDeepResearchService(
            ShadowResearchRepository repository,
            ShadowPaperPortfolioService paper,
            TransactionTemplate transaction,
            Clock clock,
            NextPaperExecutionResolver nextExecution
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.paper = Objects.requireNonNull(paper, "paper");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nextExecution = Objects.requireNonNull(nextExecution,
                "nextExecution");
    }

    @Override
    public ShadowExecutionResult run(
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
    ) {
        int bars = topDataset.bars().size();
        LoadedDataset accepted = new LoadedDataset(topDataset,
                "M1_RESEARCH_DATASET_V1", bars, bars,
                topDataset.sessions().size()
                        * topDataset.securities().size(), bars,
                true, true, true, true, true, false);
        var source = new InMemoryShadowResearchDatasetSource(accepted);
        var runtime = new ShadowResearchRuntime(repository, source, paper,
                transaction, clock, recommendation -> constrainRecommendation(
                        recommendation, shortlist,
                        selectionRequest.finalLimit()));
        boolean scheduled = selectionRequest.triggerMode()
                == ResearchSelectionModels.TriggerMode.SCHEDULED_SHADOW;
        TriggerMode trigger = scheduled ? TriggerMode.SCHEDULED
                : TriggerMode.ON_DEMAND_SELECTION;
        String slot = scheduled ? ShadowResearchModels.RESEARCH_SLOT
                : "ON_DEMAND_" + publicRunId.substring(
                publicRunId.length() - 12);
        Instant paperExecution = selectionRequest.paperEnabled()
                ? nextExecution.resolve(anchor, asOf) : null;
        paperExecution = validatePaperExecution(paperExecution, asOf);
        var request = new ShadowRequest(trigger,
                anchor, topDataset.firstSessionDate(), asOf,
                topDataset.securities(), shortlist.get(0).security(),
                strategies, paperExecution, providerCalls,
                objective(historical, shortlist),
                slot,
                ShadowResearchModels.SELECTION_STRATEGY_VERSION);
        return runtime.run(request, model);
    }

    static String objective(
            HistoricalResearch historical,
            List<QuantitativeScore> shortlist
    ) {
        Objects.requireNonNull(historical, "historical");
        Map<String, ResearchSelectionModels.HistoricalStability> bySecurity =
                historical.securities().stream().collect(
                Collectors.toUnmodifiableMap(value ->
                        value.security().canonicalCode(), value -> value));
        StringBuilder value = new StringBuilder("基于 ")
                .append(ResearchUniverseMainboard.VERSION)
                .append(" 开展当前时点研究；历史稳定性=")
                .append(historical.version()).append('/')
                .append(historical.researchLabel()).append('/')
                .append(historical.pitQualification()).append("，可用")
                .append(historical.availableSessions()).append("日；Top10[");
        for (QuantitativeScore score : shortlist) {
            var stability = bySecurity.get(score.security().canonicalCode());
            if (stability == null) continue;
            String item = score.security().canonicalCode() + '='
                    + stability.score() + '/' + stability.grade() + ';';
            if (value.length() + item.length() > 440) break;
            value.append(item);
        }
        value.append("]。仅用于研究和模拟，不进行真实交易；历史结果不得冒充Live Shadow。");
        if (value.length() > 500) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_HISTORY_OBJECTIVE_TOO_LONG");
        }
        return value.toString();
    }

    static Instant validatePaperExecution(
            Instant paperExecution,
            Instant researchAsOf
    ) {
        Objects.requireNonNull(researchAsOf, "researchAsOf");
        if (paperExecution != null && !paperExecution.isAfter(researchAsOf)) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_PAPER_EXECUTION_NOT_AFTER_AS_OF");
        }
        return paperExecution;
    }

    static ShadowRecommendation constrainRecommendation(
            ShadowRecommendation recommendation,
            List<QuantitativeScore> shortlist,
            int finalLimit
    ) {
        Map<String, QuantitativeScore> scores = shortlist.stream().collect(
                Collectors.toUnmodifiableMap(value ->
                        value.security().canonicalCode(), value -> value));
        Set<String> eligible = shortlist.stream().filter(value ->
                        value.dataQualityPassed()
                                && value.score().compareTo(
                                new java.math.BigDecimal("55.0000")) >= 0)
                .map(value -> value.security().canonicalCode())
                .collect(Collectors.toUnmodifiableSet());
        List<String> ranked = recommendation.rankedSecurities().stream()
                .filter(scores::containsKey).filter(eligible::contains)
                .distinct().limit(finalLimit).toList();
        List<String> limitations = new java.util.ArrayList<>(
                recommendation.limitations());
        if (ranked.isEmpty() && !limitations.contains(
                "NO_SECURITY_PASSED_SELECTION_THRESHOLD")) {
            limitations.add("NO_SECURITY_PASSED_SELECTION_THRESHOLD");
        }
        return new ShadowRecommendation(ranked.isEmpty()
                ? "INSUFFICIENT_EVIDENCE" : recommendation.decisionCode(),
                recommendation.rankedStrategies(), ranked,
                ranked.isEmpty() ? "NONE" : recommendation.preferredStrategy(),
                recommendation.riskLevel(), ranked.isEmpty()
                ? java.math.BigDecimal.ZERO : recommendation.confidence(),
                ranked.isEmpty() ? java.math.BigDecimal.ZERO
                        : recommendation.suggestedGrossExposure(),
                ranked.isEmpty() ? List.of()
                        : recommendation.supportingEvidenceIds(),
                List.copyOf(limitations), true, true);
    }

    @FunctionalInterface
    public interface NextPaperExecutionResolver {
        Instant resolve(LocalDate signalDate, Instant knowledgeCutoff);
    }
}
