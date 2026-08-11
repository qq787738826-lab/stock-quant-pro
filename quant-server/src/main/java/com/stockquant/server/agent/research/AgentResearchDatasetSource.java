package com.stockquant.server.agent.research;

import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;

import java.util.Objects;

/** Read-only M1 dataset boundary consumed by the M3 tool gateway. */
public interface AgentResearchDatasetSource {
    LoadedDataset load(ResearchTask task);

    record LoadedDataset(
            ResearchDataset dataset,
            String sourceContractVersion,
            int rawDailyCount,
            int adjustmentFactorCount,
            int calendarCount,
            int qfqBarCount,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean dataQuality,
            boolean noFutureDataLeakage,
            boolean formulaOnlyQfq,
            boolean providerPitVerified
    ) {
        public LoadedDataset {
            Objects.requireNonNull(dataset, "dataset");
            sourceContractVersion = AgentResearchModels.required(
                    sourceContractVersion, "sourceContractVersion");
            if (rawDailyCount <= 0
                    || adjustmentFactorCount != rawDailyCount
                    || qfqBarCount != rawDailyCount
                    || calendarCount < dataset.sessions().stream()
                    .filter(value -> value.anyOpen()).count()
                    || !typedFactReadback || !systemKnowledgeReadback
                    || !dataQuality || !noFutureDataLeakage
                    || !formulaOnlyQfq) {
                throw AgentResearchModels.invalid(
                        "M3_LOADED_DATASET_INVALID");
            }
        }
    }
}
