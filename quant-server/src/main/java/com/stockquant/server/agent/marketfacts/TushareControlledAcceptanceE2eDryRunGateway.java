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
import java.util.ArrayList;
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
                    "TUSHARE_SYNTHETIC_PROVIDER_FAILURE",
                    "TUSHARE_SYNTHETIC_PROVIDER_FAILURE",
                    1, 0, null);
        }
        String date = parameters.path("start_date").asText("20250103");
        String endDate = parameters.path("end_date").asText(date);
        String tsCode = parameters.path("ts_code").asText("600000.SH");
        String exchange = parameters.path("exchange").asText("SSE");
        List<List<JsonNode>> rows = switch (endpoint) {
            case "daily" -> dailyWindow(tsCode, date, endDate);
            case "adj_factor" -> factorWindow(tsCode, date, endDate);
            case "trade_cal" -> calendarWindow(exchange, date, endDate);
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

    private static List<JsonNode> daily(String tsCode, String date) {
        return List.of(
                text(tsCode), text(date), decimal("10.00"),
                decimal("10.80"), decimal("9.80"), decimal("10.50"),
                decimal("1000"), decimal("10500"));
    }

    private static List<JsonNode> factor(String tsCode, String date) {
        return List.of(text(tsCode), text(date), decimal("2.0000"));
    }

    private static List<JsonNode> calendar(String exchange, String date) {
        boolean open = isOpen(date);
        return List.of(
                text(exchange), text(date), decimal(open ? "1" : "0"),
                text(previousDate(date)));
    }

    private static List<List<JsonNode>> dailyWindow(
            String tsCode,
            String start,
            String end
    ) {
        List<List<JsonNode>> result = new ArrayList<>();
        result.add(daily("999999.SZ", start));
        result.add(daily(tsCode, previousDate(start)));
        dates(start, end).stream().filter(
                TushareControlledAcceptanceE2eDryRunGateway::isOpen)
                .forEach(value -> result.add(daily(tsCode, value)));
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> factorWindow(
            String tsCode,
            String start,
            String end
    ) {
        List<List<JsonNode>> result = new ArrayList<>();
        result.add(factor("999999.SZ", start));
        result.add(factor(tsCode, previousDate(start)));
        dates(start, end).stream().filter(
                TushareControlledAcceptanceE2eDryRunGateway::isOpen)
                .forEach(value -> result.add(factor(tsCode, value)));
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> calendarWindow(
            String exchange,
            String start,
            String end
    ) {
        List<List<JsonNode>> result = new ArrayList<>();
        result.add(calendar(otherExchange(exchange), start));
        result.add(calendar(exchange, previousDate(start)));
        dates(start, end).forEach(value ->
                result.add(calendar(exchange, value)));
        return List.copyOf(result);
    }

    private static List<String> dates(String start, String end) {
        LocalDate from = LocalDate.parse(start, DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate to = LocalDate.parse(end, DateTimeFormatter.BASIC_ISO_DATE);
        List<String> result = new ArrayList<>();
        for (LocalDate value = from; !value.isAfter(to);
                value = value.plusDays(1)) {
            result.add(value.format(DateTimeFormatter.BASIC_ISO_DATE));
        }
        return List.copyOf(result);
    }

    private static boolean isOpen(String value) {
        return switch (LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                .getDayOfWeek()) {
            case SATURDAY, SUNDAY -> false;
            default -> true;
        };
    }

    private static String otherExchange(String exchange) {
        return "SSE".equals(exchange) ? "SZSE" : "SSE";
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
