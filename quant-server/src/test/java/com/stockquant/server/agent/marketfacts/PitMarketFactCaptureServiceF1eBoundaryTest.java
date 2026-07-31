package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PitMarketFactCaptureServiceF1eBoundaryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 30);
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-31T04:00:00Z");
    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    @Test
    void secondResponseDuplicateNaturalIdentityFailsBeforeFirstWrite() {
        Fixture fixture = fixture();
        MarketFactResponse first = fixture.responses().get(0);
        MarketFactResponse second = fixture.responses().get(1);
        RawDailyBar secondRaw = second.rawDailyBars().get(0);
        AdjustmentFactor secondFactor =
                second.adjustmentFactors().get(0);
        RawDailyBar duplicateRaw = new RawDailyBar(
                first.sourceInstrumentId(),
                first.rawDailyBars().get(0).symbol(),
                secondRaw.exchange(),
                secondRaw.tradeDate(),
                secondRaw.open(),
                secondRaw.high(),
                secondRaw.low(),
                secondRaw.close(),
                secondRaw.volume(),
                secondRaw.amount(),
                secondRaw.turnoverRate(),
                secondRaw.version(),
                secondRaw.rawFields());
        AdjustmentFactor duplicateFactor = new AdjustmentFactor(
                first.sourceInstrumentId(),
                first.adjustmentFactors().get(0).symbol(),
                secondFactor.factorEffectiveTradeDate(),
                secondFactor.factorType(),
                secondFactor.coverageMode(),
                secondFactor.factor(),
                secondFactor.version(),
                secondFactor.rawFields());
        List<MarketFactResponse> responses =
                new ArrayList<>(fixture.responses());
        responses.set(1, copy(
                second,
                second.capability(),
                List.of(duplicateRaw),
                List.of(duplicateFactor)));
        TushareDedicatedResearchCaptureContract contract =
                TushareDedicatedResearchCaptureContract.validated(
                        fixture.command(),
                        fixture.session(),
                        responses);
        PitMarketFactRepository repository =
                mock(PitMarketFactRepository.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> captureService(repository)
                        .captureAuthorizedDedicatedResearchBatch(
                                contract,
                                OBSERVED_AT,
                                TushareDedicatedResearchBatchAuthorization
                                        .manualPersonalResearch(),
                                verification(false)));

        verifyNoInteractions(repository);
    }

    @Test
    void secondResponseCapabilityMismatchFailsBeforeFirstWrite() {
        Fixture fixture = fixture();
        MarketFactResponse second = fixture.responses().get(1);
        ProviderCapability capability = second.capability();
        ProviderCapability mismatched = new ProviderCapability(
                capability.providerContractVersion(),
                capability.providerCode(),
                "TUSHARE_FORGED_ADAPTER",
                capability.supportedFactTypes(),
                capability.revisionIdAvailable(),
                capability.snapshotIdAvailable(),
                capability.providerPublishedAtAvailable(),
                capability.providerUpdatedAtAvailable(),
                capability.historicalVersionsQueryable(),
                capability.localPersistenceAllowed(),
                capability.historicalReplayAllowed(),
                capability.backtestAllowed(),
                capability.agentUseAllowed(),
                capability.maximumSymbolsPerRequest(),
                capability.maximumNaturalDaysPerRequest(),
                capability.minimumRequestInterval(),
                capability.fieldUnits(),
                capability.decimalScales(),
                capability.coverage(),
                capability.licensing(),
                capability.rateLimit());
        List<MarketFactResponse> responses =
                new ArrayList<>(fixture.responses());
        responses.set(1, copy(
                second,
                mismatched,
                second.rawDailyBars(),
                second.adjustmentFactors()));
        TushareDedicatedResearchCaptureContract contract =
                TushareDedicatedResearchCaptureContract.validated(
                        fixture.command(),
                        fixture.session(),
                        responses);
        PitMarketFactRepository repository =
                mock(PitMarketFactRepository.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> captureService(repository)
                        .captureAuthorizedDedicatedResearchBatch(
                                contract,
                                OBSERVED_AT,
                                TushareDedicatedResearchBatchAuthorization
                                        .manualPersonalResearch(),
                                verification(false)));

        verifyNoInteractions(repository);
    }

    private static PitMarketFactCaptureService captureService(
            PitMarketFactRepository repository
    ) {
        ObjectMapper mapper = new ObjectMapper();
        return new PitMarketFactCaptureService(
                mapper,
                new PitMarketFactsCanonicalService(
                        mapper,
                        new BacktestCanonicalHashService(mapper)),
                repository,
                mock(TemporalMarketFoundationService.class),
                mock(TushareReducedResearchPersistenceGuard.class),
                mock(TushareDedicatedResearchPersistenceGuard.class),
                Clock.fixed(OBSERVED_AT, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
    }

    private static Fixture fixture() {
        List<SecuritySelection> securities = List.of(
                new SecuritySelection("600000", "SSE"),
                new SecuritySelection("600001", "SSE"));
        TushareDedicatedResearchBatchCommand command =
                new TushareDedicatedResearchBatchCommand(
                        DATE, securities, Duration.ofSeconds(5));
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1eDedicatedLocalManual(
                        securities, DATE);
        TushareMarketFactProvider provider = provider();
        List<MarketFactResponse> responses = new ArrayList<>();
        securities.forEach(security ->
                responses.add(provider.fetchForDedicatedReducedResearch(
                        request(security), session)));
        return new Fixture(command, session, List.copyOf(responses));
    }

    private static MarketFactRequest request(
            SecuritySelection security
    ) {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        security.symbol(), security.exchange()),
                security.symbol(),
                security.exchange(),
                DATE,
                DATE,
                FACT_TYPES,
                Duration.ofSeconds(5));
    }

    private static TushareMarketFactProvider provider() {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken("synthetic-f1e-capture-boundary-token");
        return new TushareMarketFactProvider(
                new ObjectMapper(),
                properties,
                new F1eSyntheticTushareGateway());
    }

    private static MarketFactResponse copy(
            MarketFactResponse value,
            ProviderCapability capability,
            List<RawDailyBar> raw,
            List<AdjustmentFactor> factors
    ) {
        return new MarketFactResponse(
                value.providerContractVersion(),
                value.providerCode(),
                value.adapterVersion(),
                value.runNamespace(),
                value.sourceCode(),
                value.sourceInstrumentId(),
                value.requestedStart(),
                value.requestedEnd(),
                value.complete(),
                capability,
                raw,
                factors,
                value.tradingCalendar(),
                value.corporateActions(),
                value.errors(),
                value.providerMetadata());
    }

    private static TushareDedicatedResearchPersistenceGuard.Verification
    verification(boolean transactionBound) {
        return new TushareDedicatedResearchPersistenceGuard.Verification(
                "stock_quant_research",
                "stock_quant_research",
                "jdbc:postgresql://127.0.0.1:55433/"
                        + "stock_quant_research"
                        + "?currentSchema=tushare_research",
                TushareDedicatedResearchPersistenceGuard
                        .DATABASE_PURPOSE,
                "tushare_research",
                "tushare_research",
                TushareDedicatedResearchPersistenceGuard
                        .REQUIRED_MIGRATIONS,
                10_001,
                transactionBound,
                TushareDedicatedResearchPersistenceGuard
                        .DatabaseIdentityQualification.VERIFIED,
                TushareDedicatedResearchPersistenceGuard
                        .SchemaQualification.VERIFIED);
    }

    private record Fixture(
            TushareDedicatedResearchBatchCommand command,
            TushareManualBoundedSession session,
            List<MarketFactResponse> responses
    ) {
    }
}
