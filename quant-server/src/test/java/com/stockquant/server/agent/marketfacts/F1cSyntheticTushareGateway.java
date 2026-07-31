package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, non-network F1C gateway used by the random-schema test.
 */
public final class F1cSyntheticTushareGateway
        implements TushareApiGateway, F1cRateLimitedGateway {

    private final ArrayList<String> endpoints = new ArrayList<>();
    private final TushareTokenRateLimiter rateLimiter;
    private final String tsCode;
    private final String exchange;
    private int calls;

    public F1cSyntheticTushareGateway() {
        this("600000.SH", "SSE");
    }

    public F1cSyntheticTushareGateway(
            String tsCode,
            String exchange
    ) {
        TushareEndpointRateLimitPolicy policy =
                TushareEndpointRateLimitPolicy.frozenF1cPolicy();
        this.rateLimiter = new TushareTokenRateLimiter(policy);
        this.tsCode = tsCode;
        this.exchange = exchange;
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
        rateLimiter.acquire(endpoint);
        calls++;
        endpoints.add(endpoint);
        List<List<JsonNode>> rows = switch (endpoint) {
            case "daily" -> List.of(
                    List.of(
                            text(tsCode),
                            text("20260727"),
                            decimal("10"),
                            decimal("12"),
                            decimal("9"),
                            decimal("11"),
                            decimal("100"),
                            decimal("10")),
                    List.of(
                            text(tsCode),
                            text("20260728"),
                            decimal("20"),
                            decimal("22"),
                            decimal("19"),
                            decimal("21"),
                            decimal("200"),
                            decimal("20")));
            case "adj_factor" -> List.of(
                    List.of(
                            text(tsCode),
                            text("20260727"),
                            decimal("1")),
                    List.of(
                            text(tsCode),
                            text("20260728"),
                            decimal("2")));
            case "trade_cal" -> List.of(
                    List.of(
                            text(exchange),
                            text("20260727"),
                            DecimalNode.valueOf(BigDecimal.ONE),
                            text("20260724")),
                    List.of(
                            text(exchange),
                            text("20260728"),
                            DecimalNode.valueOf(BigDecimal.ONE),
                            text("20260727")));
            default -> throw new IllegalArgumentException(endpoint);
        };
        return new QueryResult(new Table(fields, rows), 1, 0);
    }

    public int calls() {
        return calls;
    }

    public List<String> endpoints() {
        return List.copyOf(endpoints);
    }

    @Override
    public F1cRateLimitedGatewayContract f1cRateLimitContract() {
        return F1cRateLimitedGatewayContract.from(
                rateLimiter.policy(), rateLimiter);
    }

    private static JsonNode text(String value) {
        return TextNode.valueOf(value);
    }

    private static JsonNode decimal(String value) {
        return DecimalNode.valueOf(new BigDecimal(value));
    }
}
