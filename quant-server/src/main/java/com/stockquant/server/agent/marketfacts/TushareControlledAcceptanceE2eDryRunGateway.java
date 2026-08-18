package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
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
    static final int MAINBOARD_FAKE_MEMBER_COUNT = 3_000;
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
        QueryMode expectedMode = session.sessionProfile()
                == TushareManualBoundedSession.SessionProfile
                .MAINBOARD_UNIVERSE_V1
                ? QueryMode.CONTROLLED_NETWORK_RECOVERY
                : QueryMode.CONTROLLED_NO_RETRY;
        if (mode != expectedMode) {
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
        String date = parameters.has("trade_date")
                ? parameters.path("trade_date").asText()
                : parameters.path("start_date").asText("20250103");
        String endDate = parameters.has("trade_date") ? date
                : parameters.path("end_date").asText(date);
        String tsCode = parameters.path("ts_code").asText("600000.SH");
        String exchange = parameters.path("exchange").asText("SSE");
        List<List<JsonNode>> rows = switch (endpoint) {
            case "stock_basic" -> mainboardStockBasic();
            case "daily" -> parameters.has("trade_date")
                    ? session.sessionProfile() == TushareManualBoundedSession
                    .SessionProfile.MAINBOARD_UNIVERSE_V1
                    ? mainboardDailyMarket(date) : dailyMarket(date)
                    : parameters.has("ts_code")
                    ? dailyWindow(tsCode, date, endDate)
                    : dailyMarketWindow(date, endDate);
            case "adj_factor" -> parameters.has("trade_date")
                    ? session.sessionProfile() == TushareManualBoundedSession
                    .SessionProfile.MAINBOARD_UNIVERSE_V1
                    ? mainboardFactorMarket(date) : factorMarket(date)
                    : parameters.has("ts_code")
                    ? factorWindow(tsCode, date, endDate)
                    : factorMarketWindow(date, endDate);
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

    private static List<List<JsonNode>> dailyMarket(String date) {
        List<List<JsonNode>> result = new ArrayList<>();
        com.stockquant.server.researchselection.ResearchUniverseV1
                .securities().forEach(value -> result.add(daily(
                tsCode(value.symbol(), value.exchange()), date)));
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> factorMarket(String date) {
        List<List<JsonNode>> result = new ArrayList<>();
        com.stockquant.server.researchselection.ResearchUniverseV1
                .securities().forEach(value -> result.add(factor(
                tsCode(value.symbol(), value.exchange()), date)));
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> mainboardStockBasic() {
        List<List<JsonNode>> result = new ArrayList<>(
                MAINBOARD_FAKE_MEMBER_COUNT);
        for (int index = 0; index < MAINBOARD_FAKE_MEMBER_COUNT; index++) {
            String exchange = index < MAINBOARD_FAKE_MEMBER_COUNT / 2
                    ? "SSE" : "SZSE";
            int local = index < MAINBOARD_FAKE_MEMBER_COUNT / 2
                    ? 600000 + index
                    : 1 + index - MAINBOARD_FAKE_MEMBER_COUNT / 2;
            String symbol = String.format("%06d", local);
            String tsCode = tsCode(symbol, exchange);
            String name = index == 7 ? "ST离线样本"
                    : "主板离线样本" + symbol;
            String industry = "行业" + (index % 20 + 1);
            result.add(List.of(text(tsCode), text(symbol), text(name),
                    text(industry), text("主板"), text(exchange), text("L"),
                    text(index == 11 ? "20260801" : "20000101"),
                    NullNode.instance));
        }
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> mainboardDailyMarket(String date) {
        List<List<JsonNode>> result = new ArrayList<>(
                MAINBOARD_FAKE_MEMBER_COUNT);
        LocalDate tradeDate = LocalDate.parse(date,
                DateTimeFormatter.BASIC_ISO_DATE);
        for (int index = 0; index < MAINBOARD_FAKE_MEMBER_COUNT; index++) {
            if (index == 11 && tradeDate.isBefore(
                    LocalDate.of(2026, 8, 1))) continue;
            String exchange = index < MAINBOARD_FAKE_MEMBER_COUNT / 2
                    ? "SSE" : "SZSE";
            int local = index < MAINBOARD_FAKE_MEMBER_COUNT / 2
                    ? 600000 + index
                    : 1 + index - MAINBOARD_FAKE_MEMBER_COUNT / 2;
            result.add(mainboardDaily(tsCode(String.format("%06d", local),
                    exchange), date, index));
        }
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> mainboardFactorMarket(String date) {
        List<List<JsonNode>> result = new ArrayList<>(
                MAINBOARD_FAKE_MEMBER_COUNT);
        LocalDate tradeDate = LocalDate.parse(date,
                DateTimeFormatter.BASIC_ISO_DATE);
        for (int index = 0; index < MAINBOARD_FAKE_MEMBER_COUNT; index++) {
            if (index == 11 && tradeDate.isBefore(
                    LocalDate.of(2026, 8, 1))) continue;
            String exchange = index < MAINBOARD_FAKE_MEMBER_COUNT / 2
                    ? "SSE" : "SZSE";
            int local = index < MAINBOARD_FAKE_MEMBER_COUNT / 2
                    ? 600000 + index
                    : 1 + index - MAINBOARD_FAKE_MEMBER_COUNT / 2;
            result.add(factor(tsCode(String.format("%06d", local), exchange),
                    date));
        }
        return List.copyOf(result);
    }

    private static List<JsonNode> mainboardDaily(
            String tsCode,
            String date,
            int index
    ) {
        long day = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
                .toEpochDay();
        BigDecimal base = BigDecimal.valueOf(500 + index % 300, 2);
        BigDecimal drift = BigDecimal.valueOf(
                ((day + index * 7L) % 41L) - 20L, 3);
        BigDecimal open = base.add(drift).max(new BigDecimal("1.00"));
        BigDecimal close = open.add(BigDecimal.valueOf(
                ((day * 3L + index) % 11L) - 5L, 2));
        if (close.signum() <= 0) close = new BigDecimal("1.00");
        BigDecimal high = open.max(close).add(new BigDecimal("0.15"));
        BigDecimal low = open.min(close).subtract(new BigDecimal("0.12"))
                .max(new BigDecimal("0.01"));
        BigDecimal volume = BigDecimal.valueOf(150_000L
                + (index % 700) * 1_000L + Math.floorMod(day, 10) * 500L);
        BigDecimal amount = volume.multiply(close)
                .divide(new BigDecimal("10"), 3,
                        java.math.RoundingMode.HALF_EVEN);
        return List.of(text(tsCode), text(date), decimal(open), decimal(high),
                decimal(low), decimal(close), decimal(volume),
                decimal(amount));
    }

    private static String tsCode(String symbol, String exchange) {
        return symbol + ("SSE".equals(exchange) ? ".SH" : ".SZ");
    }

    private static List<List<JsonNode>> dailyMarketWindow(
            String start,
            String end
    ) {
        List<List<JsonNode>> result = new ArrayList<>();
        dates(start, end).stream().filter(
                TushareControlledAcceptanceE2eDryRunGateway::isOpen)
                .forEach(date -> result.addAll(dailyMarket(date)));
        return List.copyOf(result);
    }

    private static List<List<JsonNode>> factorMarketWindow(
            String start,
            String end
    ) {
        List<List<JsonNode>> result = new ArrayList<>();
        dates(start, end).stream().filter(
                TushareControlledAcceptanceE2eDryRunGateway::isOpen)
                .forEach(date -> result.addAll(factorMarket(date)));
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

    private static JsonNode decimal(BigDecimal value) {
        return DecimalNode.valueOf(value);
    }
}
