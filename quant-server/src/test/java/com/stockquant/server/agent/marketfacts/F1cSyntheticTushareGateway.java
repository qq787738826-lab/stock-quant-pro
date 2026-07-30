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
        implements TushareApiGateway {

    private final ArrayList<String> endpoints = new ArrayList<>();
    private int calls;

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
        calls++;
        endpoints.add(endpoint);
        List<List<JsonNode>> rows = switch (endpoint) {
            case "daily" -> List.of(
                    List.of(
                            text("600000.SH"),
                            text("20260727"),
                            decimal("10"),
                            decimal("12"),
                            decimal("9"),
                            decimal("11"),
                            decimal("100"),
                            decimal("10")),
                    List.of(
                            text("600000.SH"),
                            text("20260728"),
                            decimal("20"),
                            decimal("22"),
                            decimal("19"),
                            decimal("21"),
                            decimal("200"),
                            decimal("20")));
            case "adj_factor" -> List.of(
                    List.of(
                            text("600000.SH"),
                            text("20260727"),
                            decimal("1")),
                    List.of(
                            text("600000.SH"),
                            text("20260728"),
                            decimal("2")));
            case "trade_cal" -> List.of(
                    List.of(
                            text("SSE"),
                            text("20260727"),
                            DecimalNode.valueOf(BigDecimal.ONE),
                            text("20260724")),
                    List.of(
                            text("SSE"),
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

    private static JsonNode text(String value) {
        return TextNode.valueOf(value);
    }

    private static JsonNode decimal(String value) {
        return DecimalNode.valueOf(new BigDecimal(value));
    }
}
