package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.ComparisonResult;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.ResearchResult;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyDefinition;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TemporalSplit;
import com.stockquant.core.research.StrategyResearchModels.TrainTestResult;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardPlan;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardResult;

import java.time.LocalDate;
import java.util.List;

/** Public Java contract intended for the future M3 research agent. */
public interface StrategyResearchApi {
    List<StrategyDefinition> catalog();

    ResearchResult backtest(BacktestRequest request, Security benchmark);

    ComparisonResult compare(
            ResearchDataset dataset,
            List<StrategySpec> strategies,
            BacktestConfig config,
            LocalDate executionStart,
            LocalDate executionEnd,
            Security benchmark
    );

    TrainTestResult trainTest(
            ResearchDataset dataset,
            StrategySpec strategy,
            BacktestConfig config,
            TemporalSplit split,
            Security benchmark
    );

    WalkForwardResult walkForward(
            ResearchDataset dataset,
            StrategySpec strategy,
            BacktestConfig config,
            WalkForwardPlan plan,
            Security benchmark
    );
}
