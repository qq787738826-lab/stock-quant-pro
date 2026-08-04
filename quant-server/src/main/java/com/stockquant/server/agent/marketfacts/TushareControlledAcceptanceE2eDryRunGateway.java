package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Network-free gateway used only by an explicitly marked packaged E2E dry run. */
final class TushareControlledAcceptanceE2eDryRunGateway
        implements TushareApiGateway, F1cRateLimitedGateway {
    private final TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(
            TushareEndpointRateLimitPolicy.frozenF1cPolicy());
    private final AtomicInteger calls = new AtomicInteger();
    private final int failAtCall;

    TushareControlledAcceptanceE2eDryRunGateway() {
        this(-1);
    }

    TushareControlledAcceptanceE2eDryRunGateway(int failAtCall) {
        this.failAtCall = failAtCall;
    }

    @Override
    public QueryResult query(
            String endpoint,
            ObjectNode parameters,
            List<String> fields,
            Duration timeout,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        if (mode != QueryMode.CONTROLLED_NO_RETRY) {
            throw new IllegalArgumentException(
                    "TUSHARE_E2E_DRY_RUN_RETRY_MODE_INVALID");
        }
        session.authorizeAndReserve(endpoint, parameters);
        limiter.acquire(endpoint);
        int current = calls.incrementAndGet();
        if (current == failAtCall) {
            throw new GatewayException(
                    ErrorKind.NETWORK_ERROR,
                    "TUSHARE_E2E_DRY_RUN_PROVIDER_FAILURE",
                    "TUSHARE_E2E_DRY_RUN_PROVIDER_FAILURE",
                    1, 0, null);
        }
        String date = parameters.path("start_date").asText("20250103");
        String tsCode = parameters.path("ts_code").asText("600000.SH");
        String exchange = parameters.path("exchange").asText("SSE");
        List<List<JsonNode>> rows = switch (endpoint) {
            case "daily" -> List.of(List.of(
                    text(tsCode), text(date), decimal("10.00"),
                    decimal("10.80"), decimal("9.80"), decimal("10.50"),
                    decimal("1000"), decimal("10500")));
            case "adj_factor" -> List.of(List.of(
                    text(tsCode), text(date), decimal("2.0000")));
            case "trade_cal" -> List.of(List.of(
                    text(exchange), text(date), decimal("1"),
                    text(previousDate(date))));
            default -> throw new IllegalArgumentException(
                    "TUSHARE_ENDPOINT_NOT_ALLOWED");
        };
        return new QueryResult(new Table(fields, rows), 1, 0);
    }

    @Override
    public F1cRateLimitedGatewayContract f1cRateLimitContract() {
        return F1cRateLimitedGatewayContract.from(limiter.policy(), limiter);
    }

    int calls() {
        return calls.get();
    }

    private static String previousDate(String value) {
        return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                .minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static JsonNode text(String value) {
        return TextNode.valueOf(value);
    }

    private static JsonNode decimal(String value) {
        return DecimalNode.valueOf(new BigDecimal(value));
    }
}
