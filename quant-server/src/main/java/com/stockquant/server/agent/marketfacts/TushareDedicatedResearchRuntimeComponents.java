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

/**
 * Shared manual-only assembly for the accepted dedicated research runtime.
 *
 * <p>The component graph is constructed explicitly. This class does not start
 * Spring, component scanning, a web server, Flyway, scheduling, an Agent,
 * backtesting, Shadow or trading.</p>
 */
final class TushareDedicatedResearchRuntimeComponents implements AutoCloseable {
    private final ObjectMapper mapper;
    private final TushareMarketFactProperties properties;
    private final TushareDedicatedResearchBatchService batchService;
    private final TushareControlledAcceptanceReadbackService readbackService;

    private TushareDedicatedResearchRuntimeComponents(
            ObjectMapper mapper,
            TushareMarketFactProperties properties,
            TushareDedicatedResearchBatchService batchService,
            TushareControlledAcceptanceReadbackService readbackService
    ) {
        this.mapper = mapper;
        this.properties = properties;
        this.batchService = batchService;
        this.readbackService = readbackService;
    }

    static TushareDedicatedResearchRuntimeComponents create(
            DataSource dataSource,
            char[] token,
            Clock clock
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(clock, "clock");
        ObjectMapper mapper = mapper();
        TushareMarketFactProperties properties;
        try {
            properties = properties(token);
        } finally {
            Arrays.fill(token, '\0');
        }
        TushareEndpointRateLimitPolicy policy =
                new TushareEndpointRateLimitPolicy(properties);
        TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(policy);
        TushareHttpApiGateway gateway = new TushareHttpApiGateway(
                mapper, properties, limiter);
        try {
            return assemble(dataSource, clock, mapper, properties, gateway);
        } catch (RuntimeException | Error failure) {
            properties.clearToken();
            throw failure;
        }
    }

    static TushareDedicatedResearchRuntimeComponents createE2eDryRun(
            DataSource dataSource,
            Clock clock
    ) {
        return createE2eDryRun(
                dataSource, clock,
                new TushareControlledAcceptanceE2eDryRunGateway());
    }

    static TushareDedicatedResearchRuntimeComponents createE2eDryRun(
            DataSource dataSource,
            Clock clock,
            TushareControlledAcceptanceE2eDryRunGateway gateway
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(gateway, "gateway");
        ObjectMapper mapper = mapper();
        char[] syntheticToken = "E2E_DRY_RUN_FAKE_TOKEN".toCharArray();
        TushareMarketFactProperties properties;
        try {
            properties = properties(syntheticToken);
        } finally {
            Arrays.fill(syntheticToken, '\0');
        }
        try {
            return assemble(dataSource, clock, mapper, properties, gateway);
        } catch (RuntimeException | Error failure) {
            properties.clearToken();
            throw failure;
        }
    }

    private static TushareDedicatedResearchRuntimeComponents assemble(
            DataSource dataSource,
            Clock clock,
            ObjectMapper mapper,
            TushareMarketFactProperties properties,
            TushareApiGateway gateway
    ) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
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
        TushareControlledAcceptanceReadbackService readback =
                new TushareControlledAcceptanceReadbackService(jdbc, dedicatedGuard);
        return new TushareDedicatedResearchRuntimeComponents(
                mapper, properties, batch, readback);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static TushareMarketFactProperties properties(char[] token) {
        TushareMarketFactProperties properties = new TushareMarketFactProperties();
        properties.setMode(TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setMaximumRateLimitRetries(0);
        properties.setToken(token);
        properties.validateFrozenContract();
        return properties;
    }

    ObjectMapper objectMapper() {
        return mapper;
    }

    TushareDedicatedResearchBatchService batchService() {
        return batchService;
    }

    TushareControlledAcceptanceReadbackService readbackService() {
        return readbackService;
    }

    long totalProviderAttemptCount() {
        return batchService.totalProviderAttemptCount();
    }

    @Override
    public void close() {
        properties.clearToken();
    }

    @Override
    public String toString() {
        return "TushareDedicatedResearchRuntimeComponents[MANUAL_WHITELISTED]";
    }
}
