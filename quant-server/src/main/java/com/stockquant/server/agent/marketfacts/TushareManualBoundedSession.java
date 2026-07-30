package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, in-memory request contract for the only network-enabled F1A path.
 *
 * <p>The counter is shared by every endpoint used by one acceptance session.
 * It is intentionally conservative: a reserved attempt remains consumed even
 * when a later transport or quota check stops before HTTP.</p>
 */
public final class TushareManualBoundedSession {

    public static final int MAX_PROVIDER_BUSINESS_REQUESTS = 10;
    public static final int MAX_SYMBOLS = 2;
    /**
     * Natural-day bound for daily, adj_factor and trade_cal only.
     * Reference endpoints have no date parameters and remain bounded by
     * symbol, endpoint, row limit and the shared session request budget.
     */
    public static final int MAX_TIME_SERIES_NATURAL_DAYS = 2;
    public static final Set<String> F1A_ALLOWED_ENDPOINTS = Set.of(
            "stock_basic", "trade_cal", "daily", "adj_factor", "dividend");
    public static final Set<String> F1A_ALLOWED_SYMBOLS = Set.of(
            "600000.SH", "000001.SZ");
    public static final Set<String> F1A_ALLOWED_EXCHANGES = Set.of(
            "SSE", "SZSE");
    public static final LocalDate F1A_ALLOWED_START =
            LocalDate.of(2025, 1, 6);
    public static final LocalDate F1A_ALLOWED_END =
            LocalDate.of(2025, 1, 7);

    private static final DateTimeFormatter PROVIDER_DATE =
            DateTimeFormatter.BASIC_ISO_DATE;

    private final int maximumBusinessRequests;
    private final Set<String> allowedSymbols;
    private final Set<String> allowedExchanges;
    private final LocalDate allowedStart;
    private final LocalDate allowedEnd;
    private final Set<String> allowedEndpoints;
    private final boolean automaticRetryAllowed;
    private int consumedBusinessRequests;

    public TushareManualBoundedSession(
            int maximumBusinessRequests,
            Set<String> allowedSymbols,
            Set<String> allowedExchanges,
            LocalDate allowedStart,
            LocalDate allowedEnd,
            Set<String> allowedEndpoints,
            boolean automaticRetryAllowed,
            int initiallyConsumedBusinessRequests
    ) {
        if (maximumBusinessRequests <= 0
                || maximumBusinessRequests
                > MAX_PROVIDER_BUSINESS_REQUESTS) {
            throw new IllegalArgumentException(
                    "invalid Tushare maximumBusinessRequests");
        }
        this.allowedSymbols = Set.copyOf(Objects.requireNonNull(
                allowedSymbols, "allowedSymbols"));
        this.allowedExchanges = Set.copyOf(Objects.requireNonNull(
                allowedExchanges, "allowedExchanges"));
        this.allowedEndpoints = Set.copyOf(Objects.requireNonNull(
                allowedEndpoints, "allowedEndpoints"));
        this.allowedStart = Objects.requireNonNull(
                allowedStart, "allowedStart");
        this.allowedEnd = Objects.requireNonNull(
                allowedEnd, "allowedEnd");
        if (this.allowedSymbols.isEmpty()
                || this.allowedSymbols.size() > MAX_SYMBOLS
                || !F1A_ALLOWED_SYMBOLS.containsAll(this.allowedSymbols)
                || this.allowedExchanges.isEmpty()
                || !F1A_ALLOWED_EXCHANGES.containsAll(
                this.allowedExchanges)
                || this.allowedEndpoints.isEmpty()
                || !F1A_ALLOWED_ENDPOINTS.containsAll(
                this.allowedEndpoints)
                || allowedEnd.isBefore(allowedStart)
                || ChronoUnit.DAYS.between(
                allowedStart, allowedEnd) + 1
                > MAX_TIME_SERIES_NATURAL_DAYS
                || initiallyConsumedBusinessRequests < 0
                || initiallyConsumedBusinessRequests
                > maximumBusinessRequests) {
            throw new IllegalArgumentException(
                    "invalid Tushare MANUAL_BOUNDED contract");
        }
        this.maximumBusinessRequests = maximumBusinessRequests;
        this.automaticRetryAllowed = automaticRetryAllowed;
        this.consumedBusinessRequests =
                initiallyConsumedBusinessRequests;
    }

    public static TushareManualBoundedSession f1aAcceptance(
            int initiallyConsumedBusinessRequests
    ) {
        return new TushareManualBoundedSession(
                MAX_PROVIDER_BUSINESS_REQUESTS,
                F1A_ALLOWED_SYMBOLS,
                F1A_ALLOWED_EXCHANGES,
                F1A_ALLOWED_START,
                F1A_ALLOWED_END,
                F1A_ALLOWED_ENDPOINTS,
                false,
                initiallyConsumedBusinessRequests);
    }

    /**
     * Validates and atomically reserves one provider business request.
     * The budget failure happens before the HTTP strategy is invoked.
     */
    synchronized void authorizeAndReserve(
            String endpoint,
            ObjectNode parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        if (!allowedEndpoints.contains(endpoint)) {
            throw new IllegalArgumentException(
                    "Tushare endpoint is outside MANUAL_BOUNDED session");
        }
        validateScope(endpoint, parameters);
        if (consumedBusinessRequests >= maximumBusinessRequests) {
            throw new GatewayException(
                    ErrorKind.API_ERROR,
                    "TUSHARE_REQUEST_BUDGET_EXHAUSTED",
                    "Tushare manual request budget is exhausted",
                    0,
                    0,
                    null);
        }
        consumedBusinessRequests++;
    }

    private void validateScope(
            String endpoint,
            ObjectNode parameters
    ) {
        if ("trade_cal".equals(endpoint)) {
            String exchange = requiredText(parameters, "exchange");
            if (!allowedExchanges.contains(exchange)) {
                throw new IllegalArgumentException(
                        "Tushare exchange is outside MANUAL_BOUNDED session");
            }
            validateDates(parameters);
            return;
        }

        String tsCode = requiredText(parameters, "ts_code");
        if (!allowedSymbols.contains(tsCode)) {
            throw new IllegalArgumentException(
                    "Tushare symbol is outside MANUAL_BOUNDED session");
        }
        if ("daily".equals(endpoint) || "adj_factor".equals(endpoint)) {
            validateDates(parameters);
        }
    }

    private void validateDates(ObjectNode parameters) {
        LocalDate start = providerDate(
                requiredText(parameters, "start_date"));
        LocalDate end = providerDate(
                requiredText(parameters, "end_date"));
        if (end.isBefore(start)
                || start.isBefore(allowedStart)
                || end.isAfter(allowedEnd)
                || ChronoUnit.DAYS.between(start, end) + 1
                > MAX_TIME_SERIES_NATURAL_DAYS) {
            throw new IllegalArgumentException(
                    "Tushare dates are outside MANUAL_BOUNDED session");
        }
    }

    private static String requiredText(
            ObjectNode parameters,
            String field
    ) {
        JsonNode value = parameters.get(field);
        if (value == null || !value.isTextual()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "missing Tushare bounded parameter " + field);
        }
        return value.asText();
    }

    private static LocalDate providerDate(String value) {
        try {
            return LocalDate.parse(value, PROVIDER_DATE);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    "invalid Tushare bounded date", error);
        }
    }

    public synchronized int consumedBusinessRequests() {
        return consumedBusinessRequests;
    }

    public int maximumBusinessRequests() {
        return maximumBusinessRequests;
    }

    public Set<String> allowedSymbols() {
        return allowedSymbols;
    }

    public Set<String> allowedExchanges() {
        return allowedExchanges;
    }

    public LocalDate allowedStart() {
        return allowedStart;
    }

    public LocalDate allowedEnd() {
        return allowedEnd;
    }

    public Set<String> allowedEndpoints() {
        return allowedEndpoints;
    }

    public boolean automaticRetryAllowed() {
        return automaticRetryAllowed;
    }
}
