package com.stockquant.server.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.agent.marketfacts.TushareM1AsOfDatasetLoader;
import com.stockquant.server.agent.research.AgentPromptCatalog;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import com.stockquant.server.agent.research.AgentResearchRuntime;
import com.stockquant.server.agent.research.AgentResearchToolGateway;
import com.stockquant.server.agent.research.DeterministicFakeModelAdapter;
import com.stockquant.server.agent.shadowresearch.M4AsOfAgentResearchDatasetSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;

/** Zero-network M1 -> M2 -> M3 probe used by the fixed M5 challenger. */
@FunctionalInterface
interface AgentEvaluationResearchProbe {
    ResearchReport run(ResearchTask task, AgentPromptCatalog prompts);
}

@Component
final class M1M2AgentEvaluationResearchProbe
        implements AgentEvaluationResearchProbe {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    M1M2AgentEvaluationResearchProbe(
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public ResearchReport run(
            ResearchTask task,
            AgentPromptCatalog prompts
    ) {
        Clock clock = Clock.fixed(task.knowledgeCutoff(), ZoneOffset.UTC);
        var facts = new PitMarketFactRepository(jdbc, mapper);
        var source = new M4AsOfAgentResearchDatasetSource(
                new TushareM1AsOfDatasetLoader(facts));
        var gateway = new AgentResearchToolGateway(source,
                new DefaultStrategyResearchApi(), BacktestConfig.standard(),
                clock);
        try (var runtime = new AgentResearchRuntime(gateway,
                new DeterministicFakeModelAdapter(), prompts, clock)) {
            return runtime.run(task);
        }
    }
}
