package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Deterministic, non-network F1E gateway for dedicated database tests. */
public final class F1eSyntheticTushareGateway
        implements TushareApiGateway, F1cRateLimitedGateway {

    private final TushareTokenRateLimiter limiter =
            new TushareTokenRateLimiter(
                    TushareEndpointRateLimitPolicy.frozenF1cPolicy());
    private final List<String> endpoints = new ArrayList<>();
    private int calls;
    private int failAtCall = -1;
    private int closeDelta = 1;

    public F1eSyntheticTushareGateway failAtCall(int value) {
        this.failAtCall = value;
        return this;
    }

    public F1eSyntheticTushareGateway changedCloseDelta(int value) {
        this.closeDelta = value;
        return this;
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
        session.authorizeAndReserve(endpoint, parameters);
        limiter.acquire(endpoint);
        calls++;
        endpoints.add(endpoint);
        if (calls == failAtCall) {
            throw new GatewayException(
                    ErrorKind.NETWORK_ERROR,
                    "F1E_SYNTHETIC_PROVIDER_FAILURE",
                    "synthetic F1E provider failure",
                    1,
                    0,
                    null);
        }
        String date = parameters.path("start_date")
                .asText("20260730");
        String tsCode = parameters.path("ts_code")
                .asText("CALENDAR");
        String exchange = parameters.path("exchange")
                .asText("SSE");
        BigDecimal base = new BigDecimal(
                Integer.toString(10
                        + Math.floorMod(tsCode.hashCode(), 7)));
        List<List<JsonNode>> rows = switch (endpoint) {
            case "daily" -> List.of(List.of(
                    text(tsCode),
                    text(date),
                    decimal(base),
                    decimal(base.add(BigDecimal.valueOf(2))),
                    decimal(base.subtract(BigDecimal.ONE)),
                    decimal(base.add(BigDecimal.valueOf(closeDelta))),
                    decimal(BigDecimal.valueOf(100)),
                    decimal(BigDecimal.TEN)));
            case "adj_factor" -> List.of(List.of(
                    text(tsCode),
                    text(date),
                    decimal(BigDecimal.valueOf(2))));
            case "trade_cal" -> List.of(List.of(
                    text(exchange),
                    text(date),
                    decimal(BigDecimal.ONE),
                    text(previousDate(date))));
            default -> throw new IllegalArgumentException(endpoint);
        };
        return new QueryResult(new Table(fields, rows), 1, 0);
    }

    @Override
    public F1cRateLimitedGatewayContract f1cRateLimitContract() {
        return F1cRateLimitedGatewayContract.from(
                limiter.policy(), limiter);
    }

    public int calls() {
        return calls;
    }

    public List<String> endpoints() {
        return List.copyOf(endpoints);
    }

    private static String previousDate(String value) {
        return LocalDate.parse(
                        value, DateTimeFormatter.BASIC_ISO_DATE)
                .minusDays(1)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static JsonNode text(String value) {
        return TextNode.valueOf(value);
    }

    private static JsonNode decimal(String value) {
        return DecimalNode.valueOf(new BigDecimal(value));
    }

    private static JsonNode decimal(BigDecimal value) {
        return DecimalNode.valueOf(value);
    }
}
