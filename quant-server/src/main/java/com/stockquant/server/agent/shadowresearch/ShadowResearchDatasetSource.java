package com.stockquant.server.agent.shadowresearch;

import com.stockquant.server.agent.research.AgentResearchDatasetSource;

/** Dataset source that exposes the exact immutable dataset used by M4. */
public interface ShadowResearchDatasetSource
        extends AgentResearchDatasetSource {
    LoadedDataset requireLastLoaded();
}
