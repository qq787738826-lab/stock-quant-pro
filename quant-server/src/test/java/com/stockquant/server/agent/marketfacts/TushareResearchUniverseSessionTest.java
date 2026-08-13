package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.researchselection.ResearchUniverseV1;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareResearchUniverseSessionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 12);

    @Test
    void fullUniverseAllowsExactlyFiftyTwoScopedRequestsAndNoRetry() {
        List<SecuritySelection> securities = selections();
        var session = TushareManualBoundedSession.researchUniverse(
                securities, START, END, END.plusDays(31));

        for (SecuritySelection security : securities) {
            session.authorizeAndReserve("daily", securityParameters(security));
            session.authorizeAndReserve("adj_factor",
                    securityParameters(security));
        }
        session.authorizeAndReserve("trade_cal",
                calendarParameters("SSE").put("end_date",
                        date(END.plusDays(31))));
        session.authorizeAndReserve("trade_cal",
                calendarParameters("SZSE").put("end_date",
                        date(END.plusDays(31))));

        assertEquals(52, session.maximumBusinessRequests());
        assertEquals(52, session.consumedBusinessRequests());
        assertEquals(25, session.allowedSymbols().size());
        assertEquals(TushareManualBoundedSession.SessionProfile
                .RESEARCH_UNIVERSE_V1, session.sessionProfile());
        assertFalse(session.automaticRetryAllowed());
        assertThrows(TushareApiGateway.GatewayException.class, () ->
                session.authorizeAndReserve("daily",
                        securityParameters(securities.get(0))));
    }

    @Test
    void fullUniverseRejectsDynamicSymbolAndScopeWithoutConsumingBudget() {
        var session = TushareManualBoundedSession.researchUniverse(
                selections(), START, END, END.plusDays(31));
        ObjectNode dynamic = MAPPER.createObjectNode()
                .put("ts_code", "600001.SH")
                .put("start_date", date(START))
                .put("end_date", date(END));
        ObjectNode tooWide = securityParameters(selections().get(0))
                .put("end_date", date(END.plusDays(1)));
        ObjectNode allowedCalendar = calendarParameters("SSE")
                .put("end_date", date(END.plusDays(31)));

        assertThrows(IllegalArgumentException.class, () ->
                session.authorizeAndReserve("daily", dynamic));
        assertThrows(IllegalArgumentException.class, () ->
                session.authorizeAndReserve("daily", tooWide));
        session.authorizeAndReserve("trade_cal", allowedCalendar);
        assertThrows(IllegalArgumentException.class, () ->
                session.authorizeAndReserve("stock_basic", dynamic));
        assertEquals(1, session.consumedBusinessRequests());
    }

    @Test
    void dailyIncrementAllowsOnlyTwoMarketWideTradeDateRequests() {
        LocalDate tradeDate = LocalDate.of(2026, 8, 13);
        var session = TushareManualBoundedSession
                .researchUniverseDailyIncrement(selections(), tradeDate);
        ObjectNode exact = MAPPER.createObjectNode().put("trade_date",
                date(tradeDate));

        session.authorizeAndReserve("daily", exact.deepCopy());
        session.authorizeAndReserve("adj_factor", exact.deepCopy());

        assertEquals(2, session.consumedBusinessRequests());
        assertEquals(TushareManualBoundedSession.SessionProfile
                .RESEARCH_UNIVERSE_DAILY_INCREMENT,
                session.sessionProfile());
        ObjectNode injected = exact.deepCopy().put("ts_code", "600000.SH");
        assertThrows(IllegalArgumentException.class, () ->
                TushareManualBoundedSession
                        .researchUniverseDailyIncrement(selections(), tradeDate)
                        .authorizeAndReserve("daily", injected));
    }

    private static List<SecuritySelection> selections() {
        return ResearchUniverseV1.securities().stream().map(value ->
                new SecuritySelection(value.symbol(), value.exchange()))
                .toList();
    }

    private static ObjectNode securityParameters(SecuritySelection security) {
        return MAPPER.createObjectNode()
                .put("ts_code", security.providerInstrumentId())
                .put("start_date", date(START))
                .put("end_date", date(END));
    }

    private static ObjectNode calendarParameters(String exchange) {
        return MAPPER.createObjectNode().put("exchange", exchange)
                .put("start_date", date(START))
                .put("end_date", date(END));
    }

    private static String date(LocalDate value) {
        return value.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
