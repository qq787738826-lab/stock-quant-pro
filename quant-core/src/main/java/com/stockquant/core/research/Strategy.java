package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.StrategyContext;
import com.stockquant.core.research.StrategyResearchModels.StrategyDefinition;
import com.stockquant.core.research.StrategyResearchModels.TargetPortfolio;

/** A deterministic close-signal strategy. Execution is owned by the engine. */
public interface Strategy {
    StrategyDefinition definition();

    TargetPortfolio generateTargets(StrategyContext context);
}
