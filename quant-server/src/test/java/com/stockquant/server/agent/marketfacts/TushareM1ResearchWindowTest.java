package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM1ResearchWindowTest {
    private static final LocalDate START = LocalDate.of(2025, 1, 2);
    private static final LocalDate END = LocalDate.of(2025, 1, 6);

    @Test
    void multiSecurityWindowFiltersProviderSupersetAndKeepsClosedDates() {
        var securities = List.of(
                new SecuritySelection("600000", "SSE"),
                new SecuritySelection("000001", "SZSE"));
        var command = new TushareM1ResearchWindowCommand(
                securities, START, END, END,
                TushareM1ResearchWindowCommand.Mode.CAPTURE,
                Duration.ofSeconds(5));
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        var provider = provider(gateway);
        var session = TushareManualBoundedSession.m1ResearchDataManual(
                securities, START, END);
        var responses = new ArrayList<MarketFactProviderModels.MarketFactResponse>();

        for (SecuritySelection security : securities) {
            var response = provider.fetchForM1ResearchData(
                    request(security), session);
            var validated = TushareM1ResearchWindowValidator.validate(
                    response, security, command);
            assertEquals(3, response.rawDailyBars().size());
            assertEquals(3, response.adjustmentFactors().size());
            assertEquals(5, response.tradingCalendar().size());
            assertEquals(3, validated.openDateCount());
            assertEquals(2, validated.closedDateCount());
            assertTrue(response.rawDailyBars().stream().allMatch(value ->
                    value.symbol().equals(security.symbol())
                            && value.exchange().equals(security.exchange())));
            responses.add(response);
        }

        var contract = TushareM1ResearchCaptureContract.validated(
                command, session, responses);
        assertEquals(6, gateway.calls());
        assertEquals(6, session.consumedBusinessRequests());
        assertEquals(2, contract.validatedWindows().size());
        assertEquals(22, contract.validatedWindows().stream().mapToInt(
                TushareM1ResearchWindowValidator.ValidatedWindow
                        ::expectedRecordCount).sum());
    }

    @Test
    void rejectsWrongAnchorDuplicateSecurityAndOversizeRange() {
        var security = new SecuritySelection("600000", "SSE");
        var command = new TushareM1ResearchWindowCommand(
                List.of(security), START, END, START,
                TushareM1ResearchWindowCommand.Mode.CAPTURE,
                Duration.ofSeconds(5));
        var session = TushareManualBoundedSession.m1ResearchDataManual(
                List.of(security), START, END);
        var response = provider(
                new TushareControlledAcceptanceE2eDryRunGateway())
                .fetchForM1ResearchData(request(security), session);

        assertThrows(IllegalArgumentException.class, () ->
                TushareM1ResearchWindowValidator.validate(
                        response, security, command));
        assertThrows(IllegalArgumentException.class, () ->
                new TushareM1ResearchWindowCommand(
                        List.of(security, security), START, END, END,
                        TushareM1ResearchWindowCommand.Mode.CAPTURE,
                        Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () ->
                TushareManualBoundedSession.m1ResearchDataManual(
                        List.of(security), START, START.plusDays(31)));
    }

    private static TushareMarketFactProvider provider(
            TushareApiGateway gateway
    ) {
        var properties = new TushareMarketFactProperties();
        properties.setMode(TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setMaximumRateLimitRetries(0);
        properties.setToken("M1_SYNTHETIC_UNIT_TOKEN");
        return new TushareMarketFactProvider(
                new ObjectMapper(), properties, gateway);
    }

    private static MarketFactRequest request(SecuritySelection security) {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        security.symbol(), security.exchange()),
                security.symbol(), security.exchange(), START, END,
                Set.of(FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR,
                        FactType.TRADING_CALENDAR),
                Duration.ofSeconds(5));
    }
}
