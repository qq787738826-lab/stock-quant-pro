package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.temporal.MarketDataDatasetVersionRepository;
import com.stockquant.server.agent.temporal.SecurityStatusEventRepository;
import com.stockquant.server.agent.temporal.SecurityStatusHistoryRepository;
import com.stockquant.server.agent.temporal.SecurityStatusStateHasher;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import com.stockquant.server.agent.temporal.TradingCalendarRevisionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

/** Explicit component whitelist for the non-Spring F1F-B2 process. */
final class TushareControlledAcceptanceComponents implements AutoCloseable {
    private final TushareMarketFactProperties properties;
    private final TushareControlledAcceptanceExecutor executor;

    private TushareControlledAcceptanceComponents(
            TushareMarketFactProperties properties,
            TushareControlledAcceptanceExecutor executor
    ) {
        this.properties = properties;
        this.executor = executor;
    }

    static TushareControlledAcceptanceComponents create(
            DataSource dataSource,
            char[] token,
            Clock clock
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(clock, "clock");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);

        TushareMarketFactProperties properties = new TushareMarketFactProperties();
        properties.setMode(TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setMaximumRateLimitRetries(0);
        properties.setToken(token);
        Arrays.fill(token, '\0');
        properties.validateFrozenContract();

        TushareEndpointRateLimitPolicy policy =
                new TushareEndpointRateLimitPolicy(properties);
        TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(policy);
        TushareHttpApiGateway gateway = new TushareHttpApiGateway(
                mapper, properties, limiter);
        TushareMarketFactProvider provider = new TushareMarketFactProvider(
                mapper, properties, gateway);

        TushareDedicatedResearchPersistenceGuard dedicatedGuard =
                new TushareDedicatedResearchPersistenceGuard(
                        jdbc, TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE);
        TushareReducedResearchPersistenceGuard reducedGuard =
                new TushareReducedResearchPersistenceGuard(
                        jdbc, TushareReducedResearchPersistenceGuard.DATABASE_PURPOSE);
        BacktestCanonicalHashService canonicalHash =
                new BacktestCanonicalHashService(mapper);
        PitMarketFactsCanonicalService canonical =
                new PitMarketFactsCanonicalService(mapper, canonicalHash);
        PitMarketFactRepository facts = new PitMarketFactRepository(jdbc, mapper);
        TemporalMarketFoundationService temporal = new TemporalMarketFoundationService(
                new MarketDataDatasetVersionRepository(jdbc, mapper),
                new SecurityStatusEventRepository(jdbc, mapper),
                new SecurityStatusHistoryRepository(jdbc),
                new TradingCalendarRevisionRepository(jdbc),
                new SecurityStatusStateHasher(),
                clock);
        PitMarketFactCaptureService capture = new PitMarketFactCaptureService(
                mapper, canonical, facts, temporal, reducedGuard, dedicatedGuard,
                clock, transactions);
        TushareDedicatedResearchBatchService batch =
                new TushareDedicatedResearchBatchService(
                        provider, dedicatedGuard, capture, clock);
        TushareControlledAcceptanceDatabaseGuard controlledGuard =
                new TushareControlledAcceptanceDatabaseGuard(jdbc, dedicatedGuard);
        TushareControlledAcceptanceExecutionRepository executions =
                new TushareControlledAcceptanceExecutionRepository(
                        jdbc, mapper, transactions, clock);
        TushareControlledAcceptanceReadbackService readback =
                new TushareControlledAcceptanceReadbackService(jdbc, dedicatedGuard);
        TushareControlledAcceptanceEvaluator evaluator =
                new TushareControlledAcceptanceEvaluator(mapper);
        return new TushareControlledAcceptanceComponents(
                properties,
                new TushareControlledAcceptanceExecutor(
                        executions, controlledGuard, batch, readback, evaluator, clock));
    }

    TushareControlledAcceptanceExecutor executor() {
        return executor;
    }

    @Override
    public void close() {
        properties.clearToken();
    }

    @Override
    public String toString() {
        return "TushareControlledAcceptanceComponents[WHITELISTED]";
    }
}
