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
    public static final int M1_TOKEN_VERIFICATION_MAX_PROVIDER_REQUESTS = 1;
    public static final int M4_CALENDAR_ADMISSION_MAX_PROVIDER_REQUESTS = 2;
    public static final int RESEARCH_UNIVERSE_MAX_SYMBOLS = 25;
    /**
     * One daily and one adj_factor window per fixed security, plus one
     * trade_cal window per represented exchange. Tushare does not expose a
     * bounded multi-security window parameter and an unfiltered market-wide
     * window can be truncated by the provider row limit.
     */
    public static final int RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS = 52;
    public static final int RESEARCH_UNIVERSE_MAX_MARKET_FACT_NATURAL_DAYS =
            400;
    public static final int RESEARCH_UNIVERSE_MAX_NATURAL_DAYS = 431;
    public static final int RESEARCH_UNIVERSE_CALENDAR_FORWARD_DAYS = 31;
    public static final int MAINBOARD_UNIVERSE_MAX_PROVIDER_REQUESTS = 503;
    public static final int MAINBOARD_MAX_NETWORK_RECOVERIES = 4;
    public static final int MAINBOARD_UNIVERSE_MAX_NATURAL_DAYS = 500;
    private static final String MAINBOARD_MARKET_SCOPE =
            "MAINBOARD_MARKET_WIDE";
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
    private final int expectedBusinessRequests;
    private final int maximumNetworkRecoveries;
    private final Set<String> allowedSymbols;
    private final Set<String> allowedExchanges;
    private final LocalDate allowedStart;
    private final LocalDate allowedEnd;
    private final Set<String> allowedEndpoints;
    private final boolean automaticRetryAllowed;
    private final SessionProfile sessionProfile;
    private final int maximumNaturalDays;
    private final Set<LocalDate> allowedTradeDates;
    private int consumedBusinessRequests;
    private int consumedNetworkRecoveries;

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
        this(maximumBusinessRequests, allowedSymbols, allowedExchanges,
                allowedStart, allowedEnd, allowedEndpoints,
                automaticRetryAllowed, initiallyConsumedBusinessRequests,
                sessionProfile, Set.of());
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
            SessionProfile sessionProfile,
            Set<LocalDate> allowedTradeDates
    ) {
        this(maximumBusinessRequests, allowedSymbols, allowedExchanges,
                allowedStart, allowedEnd, allowedEndpoints,
                automaticRetryAllowed, initiallyConsumedBusinessRequests,
                sessionProfile, allowedTradeDates, 0);
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
            SessionProfile sessionProfile,
            Set<LocalDate> allowedTradeDates,
            int maximumNetworkRecoveries
    ) {
        int profileMaximumRequests = sessionProfile
                == SessionProfile.RESEARCH_UNIVERSE_V1
                ? RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS
                : sessionProfile == SessionProfile.MAINBOARD_UNIVERSE_V1
                ? MAINBOARD_UNIVERSE_MAX_PROVIDER_REQUESTS
                : MAX_PROVIDER_BUSINESS_REQUESTS;
        if (maximumBusinessRequests <= 0
                || maximumBusinessRequests > profileMaximumRequests) {
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
        this.allowedTradeDates = Set.copyOf(Objects.requireNonNull(
                allowedTradeDates, "allowedTradeDates"));
        this.maximumNaturalDays = maximumNaturalDays(sessionProfile);
        int profileMaximumSymbols = (sessionProfile
                == SessionProfile.RESEARCH_UNIVERSE_V1
                || sessionProfile
                == SessionProfile.RESEARCH_UNIVERSE_DAILY_INCREMENT)
                ? RESEARCH_UNIVERSE_MAX_SYMBOLS : MAX_SESSION_SYMBOLS;
        if (this.allowedSymbols.isEmpty()
                || this.allowedSymbols.size() > profileMaximumSymbols
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
                > maximumBusinessRequests
                || maximumNetworkRecoveries < 0
                || maximumNetworkRecoveries
                > MAINBOARD_MAX_NETWORK_RECOVERIES
                || maximumBusinessRequests <= maximumNetworkRecoveries) {
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
                sessionProfile,
                this.allowedTradeDates,
                maximumNetworkRecoveries);
        this.maximumBusinessRequests = maximumBusinessRequests;
        this.maximumNetworkRecoveries = maximumNetworkRecoveries;
        this.expectedBusinessRequests = maximumBusinessRequests
                - maximumNetworkRecoveries;
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

    /** One fixed daily request used only to verify an updated M1 credential. */
    public static TushareManualBoundedSession m1TokenVerification(
            String symbol,
            String exchange,
            LocalDate tradeDate
    ) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        String tsCode = f1cTsCode(symbol, exchange);
        return new TushareManualBoundedSession(
                M1_TOKEN_VERIFICATION_MAX_PROVIDER_REQUESTS,
                Set.of(tsCode), Set.of(exchange), tradeDate, tradeDate,
                Set.of("daily"), false, 0,
                SessionProfile.M1_TOKEN_VERIFICATION);
    }

    /** Two exchange-scoped trade_cal calls for a bounded M4 horizon. */
    public static TushareManualBoundedSession m4CalendarAdmission(
            java.util.List<TushareDedicatedResearchBatchCommand
                    .SecuritySelection> securities,
            LocalDate rangeStart,
            LocalDate rangeEnd
    ) {
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(rangeStart, "rangeStart");
        Objects.requireNonNull(rangeEnd, "rangeEnd");
        if (securities.size() != 2 || rangeEnd.isBefore(rangeStart)
                || ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1
                > M1_MAX_NATURAL_DAYS) {
            throw new IllegalArgumentException(
                    "M4_CALENDAR_ADMISSION_SESSION_INVALID");
        }
        Set<String> symbols = new java.util.LinkedHashSet<>();
        Set<String> exchanges = new java.util.LinkedHashSet<>();
        securities.forEach(security -> {
            symbols.add(security.providerInstrumentId());
            exchanges.add(security.exchange());
        });
        if (!exchanges.equals(Set.of("SSE", "SZSE"))
                || symbols.size() != 2) {
            throw new IllegalArgumentException(
                    "M4_CALENDAR_ADMISSION_SESSION_INVALID");
        }
        return new TushareManualBoundedSession(
                M4_CALENDAR_ADMISSION_MAX_PROVIDER_REQUESTS,
                Set.copyOf(symbols), Set.copyOf(exchanges), rangeStart,
                rangeEnd, Set.of("trade_cal"), false, 0,
                SessionProfile.M4_CALENDAR_ADMISSION);
    }

    /** One bounded V1.0.1 fixed-universe history session; never retries. */
    public static TushareManualBoundedSession researchUniverse(
            java.util.List<TushareDedicatedResearchBatchCommand
                    .SecuritySelection> securities,
            LocalDate rangeStart,
            LocalDate priceRangeEnd,
            LocalDate calendarRangeEnd
    ) {
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(rangeStart, "rangeStart");
        Objects.requireNonNull(priceRangeEnd, "priceRangeEnd");
        Objects.requireNonNull(calendarRangeEnd, "calendarRangeEnd");
        Set<String> symbols = new java.util.LinkedHashSet<>();
        Set<String> exchanges = new java.util.LinkedHashSet<>();
        securities.forEach(security -> {
            Objects.requireNonNull(security, "security");
            symbols.add(security.providerInstrumentId());
            exchanges.add(security.exchange());
        });
        int requests = securities.size() * 2 + exchanges.size();
        if (securities.size() != RESEARCH_UNIVERSE_MAX_SYMBOLS
                || symbols.size() != securities.size()
                || !exchanges.equals(Set.of("SSE", "SZSE"))
                || priceRangeEnd.isBefore(rangeStart)
                || calendarRangeEnd.isBefore(priceRangeEnd)
                || ChronoUnit.DAYS.between(rangeStart, calendarRangeEnd) + 1
                > RESEARCH_UNIVERSE_MAX_NATURAL_DAYS
                || requests != RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_SESSION_INVALID");
        }
        return new TushareManualBoundedSession(requests, Set.copyOf(symbols),
                Set.copyOf(exchanges), rangeStart, calendarRangeEnd,
                F1E_ALLOWED_ENDPOINTS, false, 0,
                SessionProfile.RESEARCH_UNIVERSE_V1);
    }

    /** Two market-wide trade_date requests for one incremental open day. */
    public static TushareManualBoundedSession researchUniverseDailyIncrement(
            java.util.List<TushareDedicatedResearchBatchCommand
                    .SecuritySelection> securities,
            LocalDate tradeDate
    ) {
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(tradeDate, "tradeDate");
        Set<String> symbols = securities.stream().map(
                        TushareDedicatedResearchBatchCommand.SecuritySelection
                                ::providerInstrumentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> exchanges = securities.stream().map(
                        TushareDedicatedResearchBatchCommand.SecuritySelection
                                ::exchange)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (securities.size() != RESEARCH_UNIVERSE_MAX_SYMBOLS
                || symbols.size() != securities.size()
                || !exchanges.equals(Set.of("SSE", "SZSE"))) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_BULK_SESSION_INVALID");
        }
        return new TushareManualBoundedSession(2, symbols, exchanges,
                tradeDate, tradeDate, Set.of("daily", "adj_factor"),
                false, 0, SessionProfile.RESEARCH_UNIVERSE_DAILY_INCREMENT);
    }

    /** Exact stock_basic/date-bulk contract for V1.0.9 full main-board data. */
    public static TushareManualBoundedSession mainboardUniverse(
            Set<LocalDate> tradeDates,
            LocalDate calendarStart,
            LocalDate calendarEnd,
            boolean includeStockBasic,
            boolean includeCalendar
    ) {
        return mainboardUniverse(tradeDates, calendarStart, calendarEnd,
                includeStockBasic, includeCalendar, 0);
    }

    /**
     * Exact main-board request contract with a separately bounded recovery
     * allowance. Recovery permits never broaden endpoint/date scope and are
     * consumed only after an eligible no-response network failure.
     */
    public static TushareManualBoundedSession mainboardUniverse(
            Set<LocalDate> tradeDates,
            LocalDate calendarStart,
            LocalDate calendarEnd,
            boolean includeStockBasic,
            boolean includeCalendar,
            int maximumNetworkRecoveries
    ) {
        Objects.requireNonNull(tradeDates, "tradeDates");
        Objects.requireNonNull(calendarStart, "calendarStart");
        Objects.requireNonNull(calendarEnd, "calendarEnd");
        if (calendarEnd.isBefore(calendarStart)
                || tradeDates.stream().anyMatch(date ->
                date.isBefore(calendarStart) || date.isAfter(calendarEnd))) {
            throw new IllegalArgumentException(
                    "MAINBOARD_UNIVERSE_SESSION_INVALID");
        }
        int expectedRequests = tradeDates.size() * 2
                + (includeStockBasic ? 1 : 0)
                + (includeCalendar ? 2 : 0);
        int requests = expectedRequests + maximumNetworkRecoveries;
        if (expectedRequests < 1
                || maximumNetworkRecoveries < 0
                || maximumNetworkRecoveries
                > MAINBOARD_MAX_NETWORK_RECOVERIES
                || requests > MAINBOARD_UNIVERSE_MAX_PROVIDER_REQUESTS) {
            throw new IllegalArgumentException(
                    "MAINBOARD_UNIVERSE_SESSION_INVALID");
        }
        Set<String> endpoints = new java.util.LinkedHashSet<>();
        if (includeStockBasic) endpoints.add("stock_basic");
        if (!tradeDates.isEmpty()) {
            endpoints.add("daily");
            endpoints.add("adj_factor");
        }
        if (includeCalendar) endpoints.add("trade_cal");
        return new TushareManualBoundedSession(requests,
                Set.of(MAINBOARD_MARKET_SCOPE), Set.of("SSE", "SZSE"),
                calendarStart, calendarEnd, Set.copyOf(endpoints), false, 0,
                SessionProfile.MAINBOARD_UNIVERSE_V1,
                Set.copyOf(tradeDates), maximumNetworkRecoveries);
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

    /** Reports whether a global main-board no-response recovery is available. */
    synchronized boolean networkRecoveryAvailable() {
        return sessionProfile == SessionProfile.MAINBOARD_UNIVERSE_V1
                && consumedNetworkRecoveries < maximumNetworkRecoveries
                && consumedBusinessRequests < maximumBusinessRequests;
    }

    /** Reserves one of the global main-board no-response recovery permits. */
    synchronized boolean reserveNetworkRecovery() {
        if (!networkRecoveryAvailable()) {
            return false;
        }
        consumedNetworkRecoveries++;
        return true;
    }

    private void validateScope(
            String endpoint,
            ObjectNode parameters
    ) {
        if (sessionProfile == SessionProfile.MAINBOARD_UNIVERSE_V1
                && "stock_basic".equals(endpoint)) {
            if (parameters.size() != 2
                    || !"主板".equals(requiredText(parameters, "market"))
                    || !"L".equals(requiredText(parameters,
                    "list_status"))) {
                throw new IllegalArgumentException(
                        "MAINBOARD_STOCK_BASIC_PARAMETERS_INVALID");
            }
            return;
        }
        if ("trade_cal".equals(endpoint)) {
            String exchange = requiredText(parameters, "exchange");
            if (!allowedExchanges.contains(exchange)) {
                throw new IllegalArgumentException(
                        "Tushare exchange is outside MANUAL_BOUNDED session");
            }
            validateDates(parameters);
            return;
        }

        if ((sessionProfile
                == SessionProfile.RESEARCH_UNIVERSE_DAILY_INCREMENT
                || sessionProfile == SessionProfile.MAINBOARD_UNIVERSE_V1)
                && ("daily".equals(endpoint)
                || "adj_factor".equals(endpoint))
                && parameters.has("trade_date")) {
            if (parameters.size() != 1) {
                throw new IllegalArgumentException(
                        "RESEARCH_UNIVERSE_BULK_PARAMETERS_INVALID");
            }
            LocalDate date = providerDate(requiredText(parameters,
                    "trade_date"));
            if (date.isBefore(allowedStart)
                    || date.isAfter(allowedEnd)
                    || sessionProfile == SessionProfile.MAINBOARD_UNIVERSE_V1
                    && !allowedTradeDates.contains(date)) {
                throw new IllegalArgumentException(
                        "Tushare dates are outside MANUAL_BOUNDED session");
            }
            return;
        }

        if (sessionProfile == SessionProfile.RESEARCH_UNIVERSE_V1
                && ("daily".equals(endpoint)
                || "adj_factor".equals(endpoint))) {
            LocalDate end = providerDate(requiredText(parameters,
                    "end_date"));
            if (end.isAfter(allowedEnd.minusDays(
                    RESEARCH_UNIVERSE_CALENDAR_FORWARD_DAYS))) {
                throw new IllegalArgumentException(
                        "RESEARCH_UNIVERSE_FUTURE_MARKET_FACT_FORBIDDEN");
            }
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

    public int expectedBusinessRequests() {
        return expectedBusinessRequests;
    }

    public int maximumNetworkRecoveries() {
        return maximumNetworkRecoveries;
    }

    public synchronized int consumedNetworkRecoveries() {
        return consumedNetworkRecoveries;
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
            SessionProfile sessionProfile,
            Set<LocalDate> allowedTradeDates,
            int maximumNetworkRecoveries
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
        if (sessionProfile == SessionProfile.M1_TOKEN_VERIFICATION) {
            if (maximumBusinessRequests
                    != M1_TOKEN_VERIFICATION_MAX_PROVIDER_REQUESTS
                    || allowedSymbols.size() != 1
                    || allowedExchanges.size() != 1
                    || !allowedEndpoints.equals(Set.of("daily"))
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_TOKEN_VERIFICATION_SESSION_INVALID");
            }
            validateSymbolIdentities(allowedSymbols, allowedExchanges,
                    "TUSHARE_M1_TOKEN_VERIFICATION_IDENTITY_INVALID");
            return;
        }
        if (sessionProfile == SessionProfile.M4_CALENDAR_ADMISSION) {
            if (maximumBusinessRequests
                    != M4_CALENDAR_ADMISSION_MAX_PROVIDER_REQUESTS
                    || allowedSymbols.size() != 2
                    || !allowedExchanges.equals(Set.of("SSE", "SZSE"))
                    || !allowedEndpoints.equals(Set.of("trade_cal"))
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "M4_CALENDAR_ADMISSION_SESSION_INVALID");
            }
            validateSymbolIdentities(allowedSymbols, allowedExchanges,
                    "M4_CALENDAR_ADMISSION_IDENTITY_INVALID");
            return;
        }

        if (sessionProfile == SessionProfile.RESEARCH_UNIVERSE_V1) {
            if (maximumBusinessRequests
                    != RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS
                    || allowedSymbols.size() != RESEARCH_UNIVERSE_MAX_SYMBOLS
                    || !allowedExchanges.equals(Set.of("SSE", "SZSE"))
                    || !allowedEndpoints.equals(F1E_ALLOWED_ENDPOINTS)
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "RESEARCH_UNIVERSE_SESSION_INVALID");
            }
            validateSymbolIdentities(allowedSymbols, allowedExchanges,
                    "RESEARCH_UNIVERSE_SECURITY_IDENTITY_INVALID");
            return;
        }
        if (sessionProfile
                == SessionProfile.RESEARCH_UNIVERSE_DAILY_INCREMENT) {
            if (maximumBusinessRequests != 2
                    || allowedSymbols.size()
                    != RESEARCH_UNIVERSE_MAX_SYMBOLS
                    || !allowedExchanges.equals(Set.of("SSE", "SZSE"))
                    || !allowedEndpoints.equals(
                    Set.of("daily", "adj_factor"))
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "RESEARCH_UNIVERSE_BULK_SESSION_INVALID");
            }
            validateSymbolIdentities(allowedSymbols, allowedExchanges,
                    "RESEARCH_UNIVERSE_SECURITY_IDENTITY_INVALID");
            return;
        }
        if (sessionProfile == SessionProfile.MAINBOARD_UNIVERSE_V1) {
            int expected = allowedTradeDates.size() * 2
                    + (allowedEndpoints.contains("stock_basic") ? 1 : 0)
                    + (allowedEndpoints.contains("trade_cal") ? 2 : 0);
            if (maximumBusinessRequests
                    != expected + maximumNetworkRecoveries
                    || maximumNetworkRecoveries < 0
                    || maximumNetworkRecoveries
                    > MAINBOARD_MAX_NETWORK_RECOVERIES
                    || !allowedSymbols.equals(Set.of(MAINBOARD_MARKET_SCOPE))
                    || !allowedExchanges.equals(Set.of("SSE", "SZSE"))
                    || !Set.of("stock_basic", "daily", "adj_factor",
                    "trade_cal").containsAll(allowedEndpoints)
                    || automaticRetryAllowed
                    || initiallyConsumedBusinessRequests != 0) {
                throw new IllegalArgumentException(
                        "MAINBOARD_UNIVERSE_SESSION_INVALID");
            }
            return;
        }
        if (maximumNetworkRecoveries != 0) {
            throw new IllegalArgumentException(
                    "invalid Tushare network recovery profile");
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
            case M1_TOKEN_VERIFICATION -> 1;
            case M4_CALENDAR_ADMISSION -> M1_MAX_NATURAL_DAYS;
            case RESEARCH_UNIVERSE_V1 ->
                    RESEARCH_UNIVERSE_MAX_NATURAL_DAYS;
            case RESEARCH_UNIVERSE_DAILY_INCREMENT -> 1;
            case MAINBOARD_UNIVERSE_V1 ->
                    MAINBOARD_UNIVERSE_MAX_NATURAL_DAYS;
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
        M1_RESEARCH_DATA_MANUAL,
        M1_TOKEN_VERIFICATION,
        M4_CALENDAR_ADMISSION,
        RESEARCH_UNIVERSE_V1,
        RESEARCH_UNIVERSE_DAILY_INCREMENT,
        MAINBOARD_UNIVERSE_V1
    }
}
