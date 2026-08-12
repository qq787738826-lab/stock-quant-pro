package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.server.agent.research.AgentResearchDatasetSource.LoadedDataset;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Replay-only as-of projection over a previously accepted immutable dataset.
 * It never expands the supplied data or changes fact knowledge timestamps.
 */
public final class InMemoryShadowResearchDatasetSource
        implements ShadowResearchDatasetSource {
    private final LoadedDataset accepted;
    private volatile LoadedDataset lastLoaded;

    public InMemoryShadowResearchDatasetSource(LoadedDataset accepted) {
        this.accepted = Objects.requireNonNull(accepted, "accepted");
    }

    @Override
    public LoadedDataset load(ResearchTask task) {
        var sessions = accepted.dataset().sessions().stream()
                .filter(value -> !value.tradeDate().isBefore(task.rangeStart())
                        && !value.tradeDate().isAfter(task.rangeEnd()))
                .toList();
        var bars = accepted.dataset().bars().stream()
                .filter(value -> !value.tradeDate().isBefore(task.rangeStart())
                        && !value.tradeDate().isAfter(task.rangeEnd())
                        && !value.sourceKnownAt().isAfter(
                        task.knowledgeCutoff()))
                .toList();
        if (sessions.isEmpty() || bars.isEmpty()) {
            throw new IllegalStateException("M4_REPLAY_WINDOW_EMPTY");
        }
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT,
                "M4_REPLAY_" + ShadowResearchCanonical.hash(java.util.Map.of(
                        "source", accepted.dataset().datasetVersion(),
                        "start", task.rangeStart(), "end", task.rangeEnd(),
                        "cutoff", task.knowledgeCutoff())),
                accepted.dataset().knowledgeMode(), task.knowledgeCutoff(),
                sessions, bars);
        int securityCount = dataset.securities().size();
        int calendarCount = Math.multiplyExact(sessions.size(),
                securityCount);
        lastLoaded = new LoadedDataset(dataset,
                accepted.sourceContractVersion(), bars.size(), bars.size(),
                calendarCount, bars.size(), accepted.typedFactReadback(),
                accepted.systemKnowledgeReadback(), accepted.dataQuality(),
                accepted.noFutureDataLeakage(), accepted.formulaOnlyQfq(),
                accepted.providerPitVerified());
        return lastLoaded;
    }

    @Override
    public LoadedDataset requireLastLoaded() {
        LoadedDataset value = lastLoaded;
        if (value == null) {
            throw new IllegalStateException("M4_REPLAY_DATASET_NOT_LOADED");
        }
        return value;
    }
}
