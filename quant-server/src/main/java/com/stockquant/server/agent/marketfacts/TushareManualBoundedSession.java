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
    private static final int MAX_SESSION_SYMBOLS = 3;
    public static final int F1C_MAX_PROVIDER_BUSINESS_REQUESTS = 3;
    public static final int F1C_MAX_SYMBOLS = 1;
    public static final int F1E_MAX_PROVIDER_BUSINESS_REQUESTS = 9;
    public static final int F1E_MAX_SYMBOLS = 3;
    public static final int F1E_MAX_NATURAL_DAYS = 1;
    public static final int M1_MAX_PROVIDER_BUSINESS_REQUESTS = 9;
    public static final int M1_MAX_SYMBOLS = 3;
    public static final int M1_MAX_NATURAL_DAYS = 31;
    /**
     * Natural-day bound for daily, adj_factor and trade_cal only.
     * Reference endpoints have no date parameters and remain bounded by
     * symbol, endpoint, row limit and the shared session request budget.
     */
    public static final int MAX_TIME_SERIES_NATURAL_DAYS = 2;
    public static final Set<String> F1A_ALLOWED_ENDPOINTS = Set.of(
            "stock_basic", "trade_cal", "daily", "adj_factor", "dividend");
    public static final Set<String> F1C_ALLOWED_ENDPOINTS = Set.of(
            "trade_cal", "daily", "adj_factor");
    public static final Set<String> F1E_ALLOWED_ENDPOINTS =
            F1C_ALLOWED_ENDPOINTS;
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
    private final SessionProfile sessionProfile;
    private final int maximumNaturalDays;
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
        this(
                maximumBusinessRequests,
                allowedSymbols,
                allowedExchanges,
                allowedStart,
                allowedEnd,
                allowedEndpoints,
                automaticRetryAllowed,
                initiallyConsumedBusinessRequests,
                SessionProfile.F1A_ACCEPTANCE);
    }

    private TushareManualBoundedSession(
            int maximumBusinessRequests,
            Set<String> allowedSymbols,
            Set<String> allowedExchanges,
            LocalDate allowedStart,
            LocalDate allowedEnd,
            Set<String> allowedEndpoints,
            boolean automaticRetryAllowed,
            int initiallyConsumedBusinessRequests,
            SessionProfile sessionProfile
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
        this.sessionProfile = Objects.requireNonNull(
                sessionProfile, "sessionProfile");
        this.maximumNaturalDays = maximumNaturalDays(sessionProfile);
        if (this.allowedSymbols.isEmpty()
                || this.allowedSymbols.size() > MAX_SESSION_SYMBOLS
                || this.allowedExchanges.isEmpty()
                || !F1A_ALLOWED_EXCHANGES.containsAll(
                this.allowedExchanges)
                || this.allowedEndpoints.isEmpty()
                || allowedEnd.isBefore(allowedStart)
                || ChronoUnit.DAYS.between(
                allowedStart, allowedEnd) + 1
                > this.maximumNaturalDays
                || initiallyConsumedBusinessRequests < 0
                || initiallyConsumedBusinessRequests
                > maximumBusinessRequests) {
            throw new IllegalArgumentException(
                    "invalid Tushare MANUAL_BOUNDED contract");
        }
        validateProfile(
                maximumBusinessRequests,
                this.allowedSymbols,
                this.allowedExchanges,
                this.allowedEndpoints,
                automaticRetryAllowed,
                initiallyConsumedBusinessRequests,
                sessionProfile);
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

    public static TushareManualBoundedSession f1cIsolatedManual(
            String symbol,
            String exchange,
            LocalDate allowedStart,
            LocalDate allowedEnd
    ) {
        String tsCode = f1cTsCode(symbol, exchange);
        return new TushareManualBoundedSession(
                F1C_MAX_PROVIDER_BUSINESS_REQUESTS,
                Set.of(tsCode),
                Set.of(exchange),
                allowedStart,
                allowedEnd,
                F1C_ALLOWED_ENDPOINTS,
                false,
                0,
                SessionProfile.F1C_ISOLATED_MANUAL);
    }

    public static TushareManualBoundedSession
    f1eDedicatedLocalManual(
            java.util.List<TushareDedicatedResearchBatchCommand
                    .SecuritySelection> securities,
            LocalDate tradeDate
    ) {
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (securities.isEmpty()
                || securities.size() > F1E_MAX_SYMBOLS) {
            throw new IllegalArgumentException(
                    "invalid Tushare F1E session profile");
        }
        Set<String> symbols = new java.util.LinkedHashSet<>();
        Set<String> exchanges = new java.util.LinkedHashSet<>();
        securities.forEach(security -> {
            Objects.requireNonNull(security, "security");
            symbols.add(security.providerInstrumentId());
            exchanges.add(security.exchange());
        });
        if (symbols.size() != securities.size()) {
            throw new IllegalArgumentException(
                    "invalid Tushare F1E session profile");
        }
        return new TushareManualBoundedSession(
                securities.size() * 3,
                Set.copyOf(symbols),
                Set.copyOf(exchanges),
                tradeDate,
                tradeDate,
                F1E_ALLOWED_ENDPOINTS,
                false,
                0,
                SessionProfile.F1E_DEDICATED_LOCAL_MANUAL);
    }

    public static TushareManualBoundedSession m1ResearchDataManual(
            java.util.List<TushareDedicatedResearchBatchCommand
                    .SecuritySelection> securities,
            LocalDate rangeStart,
            LocalDate rangeEnd
    ) {
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(rangeStart, "rangeStart");
        Objects.requireNonNull(rangeEnd, "rangeEnd");
        if (securities.isEmpty() || securities.size() > M1_MAX_SYMBOLS
                || rangeEnd.isBefore(rangeStart)
                || ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1
                > M1_MAX_NATURAL_DAYS) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_SESSION_PROFILE_INVALID");
        }
        Set<String> symbols = new java.util.LinkedHashSet<>();
        Set<String> exchanges = new java.util.LinkedHashSet<>();
        securities.forEach(security -> {
            Objects.requireNonNull(security, "security");
            symbols.add(security.providerInstrumentId());
            exchanges.add(security.exchange());
        });
        if (symbols.size() != securities.size()) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_SESSION_PROFILE_INVALID");
        }
        return new TushareManualBoundedSession(
                securities.size() * 3,
                Set.copyOf(symbols),
                Set.copyOf(exchanges),
                rangeStart,
                rangeEnd,
                F1E_ALLOWED_ENDPOINTS,
                false,
                0,
                SessionProfile.M1_RESEARCH_DATA_MANUAL);
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
                > maximumNaturalDays) {
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

    public SessionProfile sessionProfile() {
        return sessionProfile;
    }

    private static void validateProfile(
            int maximumBusinessRequests,
            Set<String> allowedSymbols,
            Set<String> allowedExchanges,
            Set<String> allowedEndpoints,
            boolean automaticRetryAllowed,
            int initiallyConsumedBusinessRequests,
            SessionProfile sessionProfile
    ) {
        if (sessionProfile == SessionProfile.F1A_ACCEPTANCE) {
            if (!F1A_ALLOWED_SYMBOLS.containsAll(allowedSymbols)
                    || !F1A_ALLOWED_ENDPOINTS.containsAll(
                    allowedEndpoints)) {
                throw new IllegalArgumentException(
                        "invalid Tushare F1A session profile");
            }
            return;
        }
        if (sessionProfile == SessionProfile.F1C_ISOLATED_MANUAL) {
            if (maximumBusinessRequests
                    != F1C_MAX_PROVIDER_BUSINESS_REQUESTS
                    || allowedSymbols.size() != F1C_MAX_SYMBOLS
                    || allowedExchanges.size() != 1
                    || !allowedEndpoints.equals(F1C_ALLOWED_ENDPOINTS)
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "invalid Tushare F1C session profile");
            }
            String tsCode = allowedSymbols.iterator().next();
            String exchange = allowedExchanges.iterator().next();
            if (!tsCode.equals(f1cTsCode(
                    tsCode.substring(0, 6), exchange))) {
                throw new IllegalArgumentException(
                        "invalid Tushare F1C symbol identity");
            }
            return;
        }
        if (sessionProfile == SessionProfile.M1_RESEARCH_DATA_MANUAL) {
            if (maximumBusinessRequests != allowedSymbols.size() * 3
                    || maximumBusinessRequests
                    > M1_MAX_PROVIDER_BUSINESS_REQUESTS
                    || allowedSymbols.isEmpty()
                    || allowedSymbols.size() > M1_MAX_SYMBOLS
                    || allowedExchanges.isEmpty()
                    || !F1A_ALLOWED_EXCHANGES.containsAll(allowedExchanges)
                    || !allowedEndpoints.equals(F1E_ALLOWED_ENDPOINTS)
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_SESSION_PROFILE_INVALID");
            }
            validateSymbolIdentities(allowedSymbols, allowedExchanges,
                    "TUSHARE_M1_SECURITY_IDENTITY_INVALID");
            return;
        }
        if (sessionProfile != SessionProfile.F1E_DEDICATED_LOCAL_MANUAL
                || maximumBusinessRequests != allowedSymbols.size() * 3
                || maximumBusinessRequests
                > F1E_MAX_PROVIDER_BUSINESS_REQUESTS
                || allowedSymbols.isEmpty()
                || allowedSymbols.size() > F1E_MAX_SYMBOLS
                || allowedExchanges.isEmpty()
                || !F1A_ALLOWED_EXCHANGES.containsAll(allowedExchanges)
                || !allowedEndpoints.equals(F1E_ALLOWED_ENDPOINTS)
                || automaticRetryAllowed
                || initiallyConsumedBusinessRequests != 0) {
            throw new IllegalArgumentException(
                    "invalid Tushare F1E session profile");
        }
        validateSymbolIdentities(allowedSymbols, allowedExchanges,
                "invalid Tushare F1E symbol identity");
    }

    private static void validateSymbolIdentities(
            Set<String> allowedSymbols,
            Set<String> allowedExchanges,
            String failureCode
    ) {
        allowedSymbols.forEach(tsCode -> {
            String suffix = tsCode.substring(6);
            String exchange = switch (suffix) {
                case ".SH" -> "SSE";
                case ".SZ" -> "SZSE";
                default -> "";
            };
            if (!allowedExchanges.contains(exchange)
                    || !tsCode.equals(f1cTsCode(
                    tsCode.substring(0, 6), exchange))) {
                throw new IllegalArgumentException(failureCode);
            }
        });
    }

    private static int maximumNaturalDays(SessionProfile profile) {
        return switch (profile) {
            case F1A_ACCEPTANCE, F1C_ISOLATED_MANUAL ->
                    MAX_TIME_SERIES_NATURAL_DAYS;
            case F1E_DEDICATED_LOCAL_MANUAL -> F1E_MAX_NATURAL_DAYS;
            case M1_RESEARCH_DATA_MANUAL -> M1_MAX_NATURAL_DAYS;
        };
    }

    private static String f1cTsCode(String symbol, String exchange) {
        if (symbol == null || !symbol.matches("[0-9]{6}")) {
            throw new IllegalArgumentException(
                    "invalid Tushare F1C symbol");
        }
        boolean mainBoard = switch (exchange) {
            case "SSE" -> symbol.matches("60[0135][0-9]{3}");
            case "SZSE" -> symbol.matches("00[0123][0-9]{3}");
            default -> false;
        };
        if (!mainBoard) {
            throw new IllegalArgumentException(
                    "Tushare F1C requires a main-board symbol");
        }
        return symbol + ("SSE".equals(exchange) ? ".SH" : ".SZ");
    }

    public enum SessionProfile {
        F1A_ACCEPTANCE,
        F1C_ISOLATED_MANUAL,
        F1E_DEDICATED_LOCAL_MANUAL,
        M1_RESEARCH_DATA_MANUAL
    }
}
