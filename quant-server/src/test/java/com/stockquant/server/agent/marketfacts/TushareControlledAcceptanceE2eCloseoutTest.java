package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareControlledAcceptanceE2eCloseoutTest {
    private static final LocalDate TRADE_DATE = LocalDate.of(2025, 1, 3);

    @Test
    void fakeGatewayUsesTheExactThreeEndpointNoRetryContract() {
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        var session = TushareManualBoundedSession.f1eDedicatedLocalManual(
                List.of(new SecuritySelection("600000", "SSE")), TRADE_DATE);

        query(gateway, session, "daily", List.of(
                "ts_code", "trade_date", "open", "high", "low", "close",
                "vol", "amount"));
        query(gateway, session, "adj_factor", List.of(
                "ts_code", "trade_date", "adj_factor"));
        query(gateway, session, "trade_cal", List.of(
                "exchange", "cal_date", "is_open", "pretrade_date"));

        assertEquals(3, gateway.calls());
        assertEquals(3, session.consumedBusinessRequests());
    }

    @Test
    void fakeGatewayFailureConsumesOneAttemptAndNeverRetries() {
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway(1);
        var session = TushareManualBoundedSession.f1eDedicatedLocalManual(
                List.of(new SecuritySelection("600000", "SSE")), TRADE_DATE);

        assertThrows(TushareApiGateway.GatewayException.class,
                () -> query(gateway, session, "daily", List.of(
                        "ts_code", "trade_date", "open", "high", "low", "close",
                        "vol", "amount")));
        assertEquals(1, gateway.calls());
        assertEquals(1, session.consumedBusinessRequests());
    }

    @Test
    void failureAfterFirstEndpointStopsAtTheSecondAttempt() {
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway(2);
        var session = TushareManualBoundedSession.f1eDedicatedLocalManual(
                List.of(new SecuritySelection("600000", "SSE")), TRADE_DATE);
        query(gateway, session, "daily", List.of(
                "ts_code", "trade_date", "open", "high", "low", "close",
                "vol", "amount"));

        assertThrows(TushareApiGateway.GatewayException.class,
                () -> query(gateway, session, "adj_factor", List.of(
                        "ts_code", "trade_date", "adj_factor")));
        assertEquals(2, gateway.calls());
        assertEquals(2, session.consumedBusinessRequests());
    }

    private static void query(
            TushareControlledAcceptanceE2eDryRunGateway gateway,
            TushareManualBoundedSession session,
            String endpoint,
            List<String> fields
    ) {
        var parameters = new ObjectMapper().createObjectNode();
        if ("trade_cal".equals(endpoint)) {
            parameters.put("exchange", "SSE");
        } else {
            parameters.put("ts_code", "600000.SH");
        }
        parameters.put("start_date", "20250103");
        parameters.put("end_date", "20250103");
        gateway.query(endpoint, parameters, fields, Duration.ofSeconds(5),
                QueryMode.CONTROLLED_NO_RETRY, session);
    }
}
