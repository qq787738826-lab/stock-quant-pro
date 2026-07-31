package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareDedicatedResearchCaptureContractTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 30);
    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    @Test
    void bindsOrderedCommandSharedSessionAndExactResponses() {
        Fixture fixture = fixture(securities());

        var contract =
                TushareDedicatedResearchCaptureContract.validated(
                        fixture.command(),
                        fixture.session(),
                        fixture.responses());

        assertEquals(DATE, contract.tradeDate());
        assertEquals(fixture.command().securities(),
                contract.orderedSecurities());
        assertEquals(6, contract.expectedProviderCallCount());
        assertEquals(0, contract.expectedRetryCount());
        assertEquals(FACT_TYPES, contract.expectedFactTypes());
        assertEquals(
                TushareManualBoundedSession.SessionProfile
                        .F1E_DEDICATED_LOCAL_MANUAL,
                contract.expectedSessionProfile());
    }

    @Test
    void rejectsResponseOrderAndDateMismatch() {
        Fixture fixture = fixture(securities());
        List<MarketFactResponse> reversed =
                new ArrayList<>(fixture.responses());
        java.util.Collections.reverse(reversed);
        assertThrows(
                IllegalArgumentException.class,
                () -> TushareDedicatedResearchCaptureContract
                        .validated(
                                fixture.command(),
                                fixture.session(),
                                reversed));

        List<MarketFactResponse> wrongDate =
                new ArrayList<>(fixture.responses());
        wrongDate.set(1, copy(
                wrongDate.get(1),
                DATE.minusDays(1),
                DATE.minusDays(1),
                metadata(wrongDate.get(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> TushareDedicatedResearchCaptureContract
                        .validated(
                                fixture.command(),
                                fixture.session(),
                                wrongDate));
    }

    @Test
    void rejectsWrongCallRetrySessionAndAutomaticRetryMetadata() {
        assertMetadataRejected(
                metadata -> metadata.put("providerCallCount", 2));
        assertMetadataRejected(
                metadata -> metadata.put("rateLimitRetryCount", 1));
        assertMetadataRejected(
                metadata -> metadata.put(
                        "sessionProfile",
                        "F1C_ISOLATED_MANUAL"));
        assertMetadataRejected(
                metadata -> metadata.put(
                        "automaticRetryAllowed", true));
    }

    @Test
    void rejectsSessionThatIsNotTheConsumedF1eSharedSession() {
        List<SecuritySelection> values = List.of(
                new SecuritySelection("600000", "SSE"));
        TushareDedicatedResearchBatchCommand command =
                command(values);
        TushareManualBoundedSession unconsumed =
                TushareManualBoundedSession.f1eDedicatedLocalManual(
                        values, DATE);

        assertThrows(
                IllegalArgumentException.class,
                () -> TushareDedicatedResearchCaptureContract
                        .validated(
                                command,
                                unconsumed,
                                List.of()));
    }

    @Test
    void rejectsClosedCalendarBeforeContractCreation() {
        Fixture fixture = fixture(List.of(
                new SecuritySelection("600000", "SSE")));
        MarketFactResponse original = fixture.responses().get(0);
        TradingCalendar calendar =
                original.tradingCalendar().get(0);
        TradingCalendar closed = new TradingCalendar(
                calendar.sourceIdentity(),
                calendar.exchange(),
                calendar.calendarDate(),
                false,
                "CLOSED",
                calendar.version(),
                calendar.rawFields());

        assertThrows(
                IllegalArgumentException.class,
                () -> TushareDedicatedResearchCaptureContract
                        .validated(
                                fixture.command(),
                                fixture.session(),
                                List.of(copyWithCalendar(
                                        original,
                                        List.of(closed)))));
    }

    private static void assertMetadataRejected(
            java.util.function.Consumer<ObjectNode> mutation
    ) {
        Fixture fixture = fixture(List.of(
                new SecuritySelection("600000", "SSE")));
        MarketFactResponse original = fixture.responses().get(0);
        ObjectNode metadata = metadata(original);
        mutation.accept(metadata);
        MarketFactResponse changed = copy(
                original,
                original.requestedStart(),
                original.requestedEnd(),
                metadata);

        assertThrows(
                IllegalArgumentException.class,
                () -> TushareDedicatedResearchCaptureContract
                        .validated(
                                fixture.command(),
                                fixture.session(),
                                List.of(changed)));
    }

    private static Fixture fixture(
            List<SecuritySelection> securities
    ) {
        TushareDedicatedResearchBatchCommand command =
                command(securities);
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1eDedicatedLocalManual(
                        securities, DATE);
        TushareMarketFactProvider provider = provider();
        List<MarketFactResponse> responses = new ArrayList<>();
        for (SecuritySelection security : securities) {
            responses.add(provider.fetchForDedicatedReducedResearch(
                    request(security), session));
        }
        return new Fixture(command, session, List.copyOf(responses));
    }

    private static TushareDedicatedResearchBatchCommand command(
            List<SecuritySelection> securities
    ) {
        return new TushareDedicatedResearchBatchCommand(
                DATE, securities, Duration.ofSeconds(5));
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
        properties.setToken("synthetic-f1e-contract-test-token");
        return new TushareMarketFactProvider(
                new ObjectMapper(),
                properties,
                new F1eSyntheticTushareGateway());
    }

    private static MarketFactResponse copy(
            MarketFactResponse value,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            ObjectNode metadata
    ) {
        return new MarketFactResponse(
                value.providerContractVersion(),
                value.providerCode(),
                value.adapterVersion(),
                value.runNamespace(),
                value.sourceCode(),
                value.sourceInstrumentId(),
                requestedStart,
                requestedEnd,
                value.complete(),
                value.capability(),
                value.rawDailyBars(),
                value.adjustmentFactors(),
                value.tradingCalendar(),
                value.corporateActions(),
                value.errors(),
                metadata);
    }

    private static MarketFactResponse copyWithCalendar(
            MarketFactResponse value,
            List<TradingCalendar> calendars
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
                value.capability(),
                value.rawDailyBars(),
                value.adjustmentFactors(),
                calendars,
                value.corporateActions(),
                value.errors(),
                value.providerMetadata());
    }

    private static ObjectNode metadata(MarketFactResponse value) {
        return (ObjectNode) value.providerMetadata().deepCopy();
    }

    private static List<SecuritySelection> securities() {
        return List.of(
                new SecuritySelection("600000", "SSE"),
                new SecuritySelection("000001", "SZSE"));
    }

    private record Fixture(
            TushareDedicatedResearchBatchCommand command,
            TushareManualBoundedSession session,
            List<MarketFactResponse> responses
    ) {
    }
}
