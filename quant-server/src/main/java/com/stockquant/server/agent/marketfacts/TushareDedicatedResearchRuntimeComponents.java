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
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
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
    private final TushareM1ResearchDataService m1ResearchDataService;
    private final TushareM4TradingCalendarAdmissionService
            m4CalendarAdmissionService;
    private final TushareM1ResearchDatasetService m1ResearchDatasetService;
    private final TushareResearchUniverseCaptureService
            researchUniverseCaptureService;
    private final TushareMainboardUniverseCaptureService
            mainboardUniverseCaptureService;
    private final TushareControlledAcceptanceReadbackService readbackService;

    private TushareDedicatedResearchRuntimeComponents(
            ObjectMapper mapper,
            TushareMarketFactProperties properties,
            TushareDedicatedResearchBatchService batchService,
            TushareM1ResearchDataService m1ResearchDataService,
            TushareM4TradingCalendarAdmissionService
                    m4CalendarAdmissionService,
            TushareM1ResearchDatasetService m1ResearchDatasetService,
            TushareResearchUniverseCaptureService
                    researchUniverseCaptureService,
            TushareMainboardUniverseCaptureService
                    mainboardUniverseCaptureService,
            TushareControlledAcceptanceReadbackService readbackService
    ) {
        this.mapper = mapper;
        this.properties = properties;
        this.batchService = batchService;
        this.m1ResearchDataService = m1ResearchDataService;
        this.m4CalendarAdmissionService = m4CalendarAdmissionService;
        this.m1ResearchDatasetService = m1ResearchDatasetService;
        this.researchUniverseCaptureService = researchUniverseCaptureService;
        this.mainboardUniverseCaptureService = mainboardUniverseCaptureService;
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
        TushareM1ResearchDatasetService m1Dataset =
                new TushareM1ResearchDatasetService(facts, jdbc);
        TushareM1ResearchDataService m1 = new TushareM1ResearchDataService(
                provider, dedicatedGuard, capture, m1Dataset, clock);
        TushareM4TradingCalendarAdmissionService m4Calendar =
                new TushareM4TradingCalendarAdmissionService(provider,
                        dedicatedGuard, capture,
                        new org.springframework.transaction.support
                                .TransactionTemplate(transactions), clock);
        TushareControlledAcceptanceReadbackService readback =
                new TushareControlledAcceptanceReadbackService(jdbc, dedicatedGuard);
        TushareResearchUniverseCaptureService universe =
                new TushareResearchUniverseCaptureService(provider,
                        dedicatedGuard, capture, clock);
        TushareMainboardUniverseCaptureService mainboard =
                new TushareMainboardUniverseCaptureService(provider,
                        dedicatedGuard, capture,
                        new ResearchUniverseMainboardRepository(jdbc), clock);
        return new TushareDedicatedResearchRuntimeComponents(
                mapper, properties, batch, m1, m4Calendar, m1Dataset,
                universe, mainboard,
                readback);
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

    TushareM1ResearchDataService m1ResearchDataService() {
        return m1ResearchDataService;
    }

    TushareM4TradingCalendarAdmissionService m4CalendarAdmissionService() {
        return m4CalendarAdmissionService;
    }

    TushareM1ResearchDatasetService m1ResearchDatasetService() {
        return m1ResearchDatasetService;
    }

    TushareResearchUniverseCaptureService researchUniverseCaptureService() {
        return researchUniverseCaptureService;
    }

    TushareMainboardUniverseCaptureService mainboardUniverseCaptureService() {
        return mainboardUniverseCaptureService;
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
