package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldSemantic;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldUnit;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderError;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderErrorType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.QualifiedMarketField;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.DividendEvidence;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.InstrumentIdentity;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.ReferenceDataResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Limited personal-research adapter for the verified Tushare 2000-point
 * endpoints. It emits SYSTEM_KNOWLEDGE_ONLY facts and does not claim provider
 * revisions, permanent instrument identity, or a complete corporate-action
 * lineage.
 */
@Component
public final class TushareMarketFactProvider implements MarketFactProvider {

    public static final String PROVIDER_CODE = "TUSHARE_PRO";
    public static final String ADAPTER_VERSION =
            "TUSHARE_MARKET_FACT_PROVIDER_V1";
    public static final String IMPLEMENTATION_SCOPE =
            "LIMITED_PERSONAL_RESEARCH_USE";
    public static final int STOCK_BASIC_MAX_ROWS = 1;
    public static final int DIVIDEND_EVIDENCE_MAX_ROWS = 1_000;
    private static final int MAXIMUM_NATURAL_DAYS =
            TushareManualBoundedSession.MAX_TIME_SERIES_NATURAL_DAYS;
    private static final DateTimeFormatter PROVIDER_DATE =
            DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<FactType> SUPPORTED_FACT_TYPES =
            Set.copyOf(EnumSet.of(
                    FactType.RAW_DAILY_BAR,
                    FactType.ADJUSTMENT_FACTOR,
                    FactType.TRADING_CALENDAR));
    private static final List<String> DAILY_FIELDS = List.of(
            "ts_code", "trade_date", "open", "high", "low", "close",
            "vol", "amount");
    private static final List<String> FACTOR_FIELDS = List.of(
            "ts_code", "trade_date", "adj_factor");
    private static final List<String> CALENDAR_FIELDS = List.of(
            "exchange", "cal_date", "is_open", "pretrade_date");
    private static final List<String> STOCK_BASIC_FIELDS = List.of(
            "ts_code", "symbol", "name", "market", "exchange",
            "list_status", "list_date", "delist_date");
    private static final List<String> DIVIDEND_FIELDS = List.of(
            "ts_code", "end_date", "ann_date", "div_proc", "stk_div",
            "stk_bo_rate", "stk_co_rate", "cash_div", "cash_div_tax",
            "record_date", "ex_date", "pay_date", "div_listdate",
            "imp_ann_date");
    private static final ProviderVersion SYSTEM_KNOWLEDGE_VERSION =
            new ProviderVersion(
                    null, null, null, null, null,
                    RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);

    private final ObjectMapper objectMapper;
    private final TushareMarketFactProperties properties;
    private final TushareApiGateway gateway;

    public TushareMarketFactProvider(
            ObjectMapper objectMapper,
            TushareMarketFactProperties properties,
            TushareApiGateway gateway
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.gateway = gateway;
    }

    @Override
    public ProviderCapability capability() {
        ObjectNode coverage = objectMapper.createObjectNode();
        coverage.put("implementationScope", IMPLEMENTATION_SCOPE);
        coverage.put("rawDaily", "MINIMUM_SAMPLE_VERIFIED");
        coverage.put("adjustmentFactor", "MINIMUM_DAILY_EXACT_VERIFIED");
        coverage.put("tradingCalendar", "SSE_SZSE_MINIMUM_SAMPLE_VERIFIED");
        coverage.put("stockBasicIdentity", "PARTIAL");
        coverage.put("dividendEvidence", "PARTIAL_NOT_V13_ELIGIBLE");
        coverage.put("corporateAction", "INCOMPLETE_TECHNICAL_BLOCKER");
        coverage.put("stableSecurityIdentity", "PARTIAL");
        coverage.put("v13Lineage", "PARTIAL");
        coverage.put("pitQualification", "PIT_PARTIAL");

        ObjectNode licensing = objectMapper.createObjectNode();
        licensing.put("usageQualification", "RESEARCH_ONLY");
        licensing.put("formalEligible", false);
        licensing.put("personalUseOnly", true);
        licensing.put(
                "writtenQuantDataSourceUsePermission", "VERIFIED");
        licensing.put(
                "writtenPersonalLocalStoragePermission", "UNVERIFIED");
        licensing.put(
                "writtenPersonalBacktestPermission", "UNVERIFIED");
        licensing.put(
                "writtenPersonalAgentAnalysisPermission", "UNVERIFIED");
        licensing.put(
                "userPersonalUseImplementationAuthorization",
                "CONFIRMED");
        licensing.put(
                "limitedPersonalUseImplementation",
                "APPROVED_BY_USER");
        licensing.put("fullF1EntryReady", false);
        licensing.put(
                "authorizationBasis",
                "USER_APPROVED_LIMITED_PERSONAL_USE");
        licensing.put("providerWrittenPermissionComplete", false);
        licensing.put(
                "postExpiryDataRetentionPermission", "UNVERIFIED");
        licensing.put(
                "rawDataRedistributionPermission", "NOT_GRANTED");

        ObjectNode rateLimit = objectMapper.createObjectNode();
        rateLimit.put(
                "officialPerMinute",
                properties.getOfficialRateLimitPerMinute());
        rateLimit.put(
                "applicationSafePerMinute",
                properties.getApplicationSafeLimitPerMinute());
        rateLimit.put(
                "officialDailyPerApi",
                properties.getOfficialDailyLimitPerApi());
        rateLimit.put(
                "applicationDailySafePerApi",
                properties.getApplicationDailySafeLimitPerApi());
        rateLimit.put("processWide", true);
        rateLimit.put("sharedAcrossEndpoints", true);
        rateLimit.put("sharedAcrossCallersInProcess", true);
        rateLimit.put("tokenLevelGlobalAcrossProcesses", false);
        rateLimit.put("distributedRateLimitCoordinated", false);
        rateLimit.put("dailyQuotaProcessWideOnly", true);
        rateLimit.put("distributedDailyQuotaCoordinated", false);
        rateLimit.put(
                "normalMaximumRateLimitRetries",
                properties.getMaximumRateLimitRetries());
        rateLimit.put("controlledProbeMaximumRetries", 0);
        rateLimit.put(
                "manualBoundedMaximumBusinessRequests",
                TushareManualBoundedSession.MAX_PROVIDER_BUSINESS_REQUESTS);

        return new ProviderCapability(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                PROVIDER_CODE,
                ADAPTER_VERSION,
                SUPPORTED_FACT_TYPES,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                1,
                MAXIMUM_NATURAL_DAYS,
                Duration.ofMillis(334),
                Map.of(
                        "price", "CNY_PER_SHARE",
                        "volume", "SHARES_CONVERTED_FROM_HANDS",
                        "amount", "CNY_CONVERTED_FROM_THOUSAND_CNY",
                        "turnoverRate", "NOT_EXPOSED",
                        "factor", "DIMENSIONLESS"
                ),
                Map.of(
                        "price", 8,
                        "volume", 8,
                        "amount", 8,
                        "turnoverRate", 12,
                        "factor", 18),
                coverage,
                licensing,
                rateLimit);
    }

    @Override
    public MarketFactResponse fetch(MarketFactRequest request) {
        throw new IllegalStateException(
                "TUSHARE_MANUAL_BOUNDED_SESSION_REQUIRED");
    }

    /** Explicit acceptance path: exactly one attempt per requested endpoint. */
    public MarketFactResponse fetchForControlledAcceptance(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        return fetch(
                request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    private MarketFactResponse fetch(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        validateRequest(request);
        properties.requireManualBoundedToken();
        if (session == null) {
            throw new IllegalArgumentException(
                    "Tushare MANUAL_BOUNDED session is required");
        }
        List<RawDailyBar> rawDailyBars = new ArrayList<>();
        List<AdjustmentFactor> adjustmentFactors = new ArrayList<>();
        List<TradingCalendar> tradingCalendar = new ArrayList<>();
        List<ProviderError> errors = new ArrayList<>();
        int providerCalls = 0;
        int retryCount = 0;

        for (FactType type : request.factTypes().stream().sorted().toList()) {
            try {
                QueryResult result = switch (type) {
                    case RAW_DAILY_BAR ->
                            queryDaily(request, mode, session);
                    case ADJUSTMENT_FACTOR ->
                            queryFactor(request, mode, session);
                    case TRADING_CALENDAR ->
                            queryCalendar(request, mode, session);
                    case CORPORATE_ACTION -> throw new IllegalArgumentException(
                            "Tushare corporate actions remain outside F1A");
                };
                providerCalls += result.providerCallCount();
                retryCount += result.rateLimitRetryCount();
                switch (type) {
                    case RAW_DAILY_BAR ->
                            rawDailyBars.addAll(mapDaily(request, result.table()));
                    case ADJUSTMENT_FACTOR ->
                            adjustmentFactors.addAll(
                                    mapFactors(request, result.table()));
                    case TRADING_CALENDAR ->
                            tradingCalendar.addAll(
                                    mapCalendar(request, result.table()));
                    case CORPORATE_ACTION -> {
                        // Rejected before any network call.
                    }
                }
            } catch (GatewayException error) {
                providerCalls += error.providerCallCount();
                retryCount += error.rateLimitRetryCount();
                errors.add(providerError(error));
                break;
            } catch (RuntimeException error) {
                errors.add(new ProviderError(
                        ProviderErrorType.STRUCTURE_CHANGED,
                        "TUSHARE_MAPPING_REJECTED",
                        "Tushare response could not be mapped safely",
                        false,
                        null));
                break;
            }
        }
        rawDailyBars.sort(Comparator.comparing(RawDailyBar::tradeDate));
        adjustmentFactors.sort(Comparator.comparing(
                AdjustmentFactor::factorEffectiveTradeDate));
        tradingCalendar.sort(Comparator.comparing(
                TradingCalendar::calendarDate));
        return response(
                request,
                errors.isEmpty(),
                rawDailyBars,
                adjustmentFactors,
                tradingCalendar,
                errors,
                providerCalls,
                retryCount,
                mode,
                session);
    }

    private QueryResult queryDaily(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode parameters = baseSecurityParameters(request);
        return gateway.query(
                "daily", parameters, DAILY_FIELDS, request.timeout(), mode,
                session);
    }

    private QueryResult queryFactor(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode parameters = baseSecurityParameters(request);
        return gateway.query(
                "adj_factor", parameters, FACTOR_FIELDS,
                request.timeout(), mode, session);
    }

    private QueryResult queryCalendar(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("exchange", request.exchange());
        parameters.put("start_date", providerDate(request.rangeStart()));
        parameters.put("end_date", providerDate(request.rangeEnd()));
        return gateway.query(
                "trade_cal", parameters, CALENDAR_FIELDS,
                request.timeout(), mode, session);
    }

    /**
     * Reads ordinary provider identity fields. The result is explicitly
     * PARTIAL and does not assert a permanent security identity.
     */
    public ReferenceDataResponse<InstrumentIdentity>
    fetchInstrumentIdentityForControlledAcceptance(
            String symbol,
            String exchange,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        validateReferenceRequest(symbol, exchange, timeout, session);
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("ts_code", tsCode(symbol, exchange));
        QueryResult result = gateway.query(
                "stock_basic",
                parameters,
                STOCK_BASIC_FIELDS,
                timeout,
                QueryMode.CONTROLLED_NO_RETRY,
                session);
        validateReferenceRowLimit(
                "stock_basic", result, STOCK_BASIC_MAX_ROWS);
        List<InstrumentIdentity> identities =
                mapInstrumentIdentities(symbol, exchange, result.table());
        return new ReferenceDataResponse<>(
                "stock_basic",
                result.table().fields(),
                identities,
                result.providerCallCount(),
                result.rateLimitRetryCount(),
                false);
    }

    /**
     * Reads partial dividend evidence without creating a V13 corporate action.
     */
    public ReferenceDataResponse<DividendEvidence>
    fetchDividendEvidenceForControlledAcceptance(
            String symbol,
            String exchange,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        validateReferenceRequest(symbol, exchange, timeout, session);
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("ts_code", tsCode(symbol, exchange));
        QueryResult result = gateway.query(
                "dividend",
                parameters,
                DIVIDEND_FIELDS,
                timeout,
                QueryMode.CONTROLLED_NO_RETRY,
                session);
        validateReferenceRowLimit(
                "dividend", result, DIVIDEND_EVIDENCE_MAX_ROWS);
        List<DividendEvidence> evidence =
                mapDividendEvidence(symbol, exchange, result.table());
        return new ReferenceDataResponse<>(
                "dividend",
                result.table().fields(),
                evidence,
                result.providerCallCount(),
                result.rateLimitRetryCount(),
                false);
    }

    private static void validateReferenceRowLimit(
            String endpoint,
            QueryResult result,
            int maximumRows
    ) {
        if (result.table().rows().size() <= maximumRows) {
            return;
        }
        throw new GatewayException(
                ErrorKind.STRUCTURE_CHANGED,
                "TUSHARE_REFERENCE_ROW_LIMIT_EXCEEDED",
                "Tushare " + endpoint
                        + " response exceeds the bounded row limit",
                result.providerCallCount(),
                result.rateLimitRetryCount(),
                null);
    }

    private List<InstrumentIdentity> mapInstrumentIdentities(
            String symbol,
            String exchange,
            Table table
    ) {
        List<RowView> rows = rows(table, STOCK_BASIC_FIELDS);
        if (rows.size() > 1) {
            throw new IllegalArgumentException(
                    "duplicate Tushare stock_basic identity");
        }
        List<InstrumentIdentity> values = new ArrayList<>();
        for (RowView row : rows) {
            String expectedTsCode = tsCode(symbol, exchange);
            if (!expectedTsCode.equals(row.text("ts_code"))
                    || !symbol.equals(row.text("symbol"))
                    || !exchange.equals(row.text("exchange"))) {
                throw new IllegalArgumentException(
                        "Tushare stock_basic identity mismatch");
            }
            values.add(new InstrumentIdentity(
                    expectedTsCode,
                    symbol,
                    exchange,
                    row.text("name"),
                    row.text("market"),
                    row.text("list_status"),
                    row.nullableDate("list_date"),
                    row.nullableDate("delist_date")));
        }
        return List.copyOf(values);
    }

    private List<DividendEvidence> mapDividendEvidence(
            String symbol,
            String exchange,
            Table table
    ) {
        List<RowView> rows = rows(table, DIVIDEND_FIELDS);
        List<DividendEvidence> values = new ArrayList<>();
        String expectedTsCode = tsCode(symbol, exchange);
        for (RowView row : rows) {
            if (!expectedTsCode.equals(row.text("ts_code"))) {
                throw new IllegalArgumentException(
                        "Tushare dividend identity mismatch");
            }
            values.add(new DividendEvidence(
                    expectedTsCode,
                    row.nullableDate("end_date"),
                    row.nullableDate("ann_date"),
                    row.nullableDate("imp_ann_date"),
                    row.nullableText("div_proc"),
                    row.nullableDecimal("stk_div"),
                    row.nullableDecimal("stk_bo_rate"),
                    row.nullableDecimal("stk_co_rate"),
                    row.nullableDecimal("cash_div"),
                    row.nullableDecimal("cash_div_tax"),
                    row.nullableDate("record_date"),
                    row.nullableDate("ex_date"),
                    row.nullableDate("pay_date"),
                    row.nullableDate("div_listdate")));
        }
        return List.copyOf(values);
    }

    private void validateReferenceRequest(
            String symbol,
            String exchange,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        properties.requireManualBoundedToken();
        if (!isMainBoard(symbol, exchange)) {
            throw new IllegalArgumentException(
                    "Tushare F1A is restricted to main-board securities");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "invalid Tushare reference timeout");
        }
        if (session == null) {
            throw new IllegalArgumentException(
                    "Tushare MANUAL_BOUNDED session is required");
        }
    }

    private ObjectNode baseSecurityParameters(MarketFactRequest request) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("ts_code", tsCode(
                request.symbol(), request.exchange()));
        parameters.put("start_date", providerDate(request.rangeStart()));
        parameters.put("end_date", providerDate(request.rangeEnd()));
        return parameters;
    }

    private List<RawDailyBar> mapDaily(
            MarketFactRequest request,
            Table table
    ) {
        List<RowView> rows = rows(table, DAILY_FIELDS);
        List<RawDailyBar> values = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (RowView row : rows) {
            requireTsCode(request, row.text("ts_code"));
            LocalDate tradeDate = row.date("trade_date");
            requireDate(request, tradeDate);
            if (!dates.add(tradeDate)) {
                throw new IllegalArgumentException(
                        "duplicate Tushare daily row");
            }
            BigDecimal providerVolume = row.nullableDecimal("vol");
            BigDecimal providerAmount = row.nullableDecimal("amount");
            values.add(new RawDailyBar(
                    rawSourceIdentity(request.symbol(), request.exchange()),
                    request.symbol(),
                    request.exchange(),
                    tradeDate,
                    row.decimal("open"),
                    row.decimal("high"),
                    row.decimal("low"),
                    row.decimal("close"),
                    qualified(
                            providerVolume == null ? null
                                    : providerVolume.movePointRight(2),
                            MarketFieldUnit.SHARES,
                            MarketFieldSemantic.TRADED_VOLUME),
                    qualified(
                            providerAmount == null ? null
                                    : providerAmount.movePointRight(3),
                            MarketFieldUnit.CNY,
                            MarketFieldSemantic.TRADED_AMOUNT),
                    qualified(
                            null,
                            MarketFieldUnit.RATIO,
                            MarketFieldSemantic.TURNOVER_RATE),
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload(
                            "daily", row, Map.of(
                                    "vol", "HANDS_TO_SHARES_X100",
                                    "amount",
                                    "THOUSAND_CNY_TO_CNY_X1000"))));
        }
        return List.copyOf(values);
    }

    private List<AdjustmentFactor> mapFactors(
            MarketFactRequest request,
            Table table
    ) {
        List<RowView> rows = rows(table, FACTOR_FIELDS);
        List<AdjustmentFactor> values = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (RowView row : rows) {
            requireTsCode(request, row.text("ts_code"));
            LocalDate tradeDate = row.date("trade_date");
            requireDate(request, tradeDate);
            if (!dates.add(tradeDate)) {
                throw new IllegalArgumentException(
                        "duplicate Tushare factor row");
            }
            values.add(new AdjustmentFactor(
                    factorSourceIdentity(
                            request.symbol(), request.exchange()),
                    request.symbol(),
                    tradeDate,
                    PitMarketFactsContracts.FACTOR_TYPE,
                    PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                    row.decimal("adj_factor"),
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload("adj_factor", row, Map.of())));
        }
        return List.copyOf(values);
    }

    private List<TradingCalendar> mapCalendar(
            MarketFactRequest request,
            Table table
    ) {
        List<RowView> rows = rows(table, CALENDAR_FIELDS);
        List<TradingCalendar> values = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (RowView row : rows) {
            if (!request.exchange().equals(row.text("exchange"))) {
                throw new IllegalArgumentException(
                        "Tushare calendar exchange mismatch");
            }
            LocalDate calendarDate = row.date("cal_date");
            requireDate(request, calendarDate);
            if (!dates.add(calendarDate)) {
                throw new IllegalArgumentException(
                        "duplicate Tushare calendar row");
            }
            int openValue = row.integer("is_open");
            if (openValue != 0 && openValue != 1) {
                throw new IllegalArgumentException(
                        "invalid Tushare calendar open state");
            }
            boolean open = openValue == 1;
            values.add(new TradingCalendar(
                    calendarSourceIdentity(request.exchange()),
                    request.exchange(),
                    calendarDate,
                    open,
                    open ? "REGULAR" : "CLOSED",
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload("trade_cal", row, Map.of())));
        }
        return List.copyOf(values);
    }

    private List<RowView> rows(
            Table table,
            List<String> requiredFields
    ) {
        if (!table.fields().containsAll(requiredFields)) {
            throw new IllegalArgumentException(
                    "Tushare response fields are incomplete");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < table.fields().size(); index++) {
            indexes.put(table.fields().get(index), index);
        }
        return table.rows().stream()
                .map(row -> new RowView(indexes, row))
                .toList();
    }

    private ObjectNode rawPayload(
            String endpoint,
            RowView row,
            Map<String, String> conversions
    ) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("provider", PROVIDER_CODE);
        result.put("endpoint", endpoint);
        result.put("providerVersionQualification",
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY.name());
        ObjectNode values = result.putObject("providerRow");
        row.indexes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.set(
                        entry.getKey(),
                        row.values.get(entry.getValue()).deepCopy()));
        ObjectNode units = result.putObject("unitConversions");
        conversions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> units.put(
                        entry.getKey(), entry.getValue()));
        return result;
    }

    private MarketFactResponse response(
            MarketFactRequest request,
            boolean complete,
            List<RawDailyBar> bars,
            List<AdjustmentFactor> factors,
            List<TradingCalendar> calendar,
            List<ProviderError> errors,
            int providerCalls,
            int retryCount,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("implementationScope", IMPLEMENTATION_SCOPE);
        metadata.put("providerCallCount", providerCalls);
        metadata.put("rateLimitRetryCount", retryCount);
        metadata.put("queryMode", mode.name());
        metadata.put("tushareMode",
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED.name());
        metadata.put("sessionMaximumBusinessRequests",
                session.maximumBusinessRequests());
        metadata.put("sessionConsumedBusinessRequests",
                session.consumedBusinessRequests());
        metadata.put("automaticRetryAllowed",
                session.automaticRetryAllowed());
        metadata.put("systemKnowledgeOnly", true);
        metadata.put("formalEligible", false);
        metadata.put("corporateActionLineageComplete", false);
        ArrayNode requested = metadata.putArray("requestedFactTypes");
        request.factTypes().stream().sorted()
                .forEach(type -> requested.add(type.name()));
        return new MarketFactResponse(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                PROVIDER_CODE,
                ADAPTER_VERSION,
                request.runNamespace(),
                request.sourceCode(),
                request.sourceInstrumentId(),
                request.rangeStart(),
                request.rangeEnd(),
                complete,
                capability(),
                bars,
                factors,
                calendar,
                List.of(),
                errors,
                metadata);
    }

    private static ProviderError providerError(GatewayException error) {
        ProviderErrorType type = switch (error.kind()) {
            case PERMISSION_DENIED -> ProviderErrorType.PERMISSION_DENIED;
            case RATE_LIMITED -> ProviderErrorType.RATE_LIMITED;
            case TIMEOUT -> ProviderErrorType.TIMEOUT;
            case STRUCTURE_CHANGED ->
                    ProviderErrorType.STRUCTURE_CHANGED;
            case NETWORK_ERROR, API_ERROR ->
                    ProviderErrorType.UNAVAILABLE;
        };
        return new ProviderError(
                type,
                error.safeCode(),
                error.getMessage(),
                false,
                null);
    }

    private void validateRequest(MarketFactRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Tushare request is required");
        }
        if (request.runNamespace() != RunNamespace.FORMAL) {
            throw new IllegalArgumentException(
                    "Tushare requires FORMAL namespace");
        }
        if (!PROVIDER_CODE.equals(request.sourceCode())) {
            throw new IllegalArgumentException(
                    "Tushare sourceCode mismatch");
        }
        if (!SUPPORTED_FACT_TYPES.containsAll(request.factTypes())) {
            throw new IllegalArgumentException(
                    "Tushare requested fact type is outside F1A");
        }
        if (!sourceInstrumentId(
                request.symbol(), request.exchange())
                .equals(request.sourceInstrumentId())) {
            throw new IllegalArgumentException(
                    "Tushare source instrument identity mismatch");
        }
        if (!isMainBoard(request.symbol(), request.exchange())) {
            throw new IllegalArgumentException(
                    "Tushare F1A is restricted to Shanghai/Shenzhen main board");
        }
        long naturalDays = ChronoUnit.DAYS.between(
                request.rangeStart(), request.rangeEnd()) + 1;
        if (naturalDays > MAXIMUM_NATURAL_DAYS) {
            throw new IllegalArgumentException(
                    "Tushare request range exceeds F1A limit");
        }
    }

    private static void requireTsCode(
            MarketFactRequest request,
            String actual
    ) {
        if (!tsCode(request.symbol(), request.exchange()).equals(actual)) {
            throw new IllegalArgumentException(
                    "Tushare response instrument mismatch");
        }
    }

    private static void requireDate(
            MarketFactRequest request,
            LocalDate date
    ) {
        if (date.isBefore(request.rangeStart())
                || date.isAfter(request.rangeEnd())) {
            throw new IllegalArgumentException(
                    "Tushare response date outside request");
        }
    }

    public static String sourceInstrumentId(
            String symbol,
            String exchange
    ) {
        return "TUSHARE:SECURITY:" + tsCode(symbol, exchange);
    }

    public static String rawSourceIdentity(
            String symbol,
            String exchange
    ) {
        return sourceInstrumentId(symbol, exchange);
    }

    public static String factorSourceIdentity(
            String symbol,
            String exchange
    ) {
        return "TUSHARE:ADJ_FACTOR:"
                + tsCode(symbol, exchange);
    }

    public static String calendarSourceIdentity(String exchange) {
        if (!"SSE".equals(exchange) && !"SZSE".equals(exchange)) {
            throw new IllegalArgumentException(
                    "invalid Tushare exchange");
        }
        return "TUSHARE:TRADE_CAL:" + exchange;
    }

    private static String tsCode(String symbol, String exchange) {
        if (symbol == null || !symbol.matches("[0-9]{6}")) {
            throw new IllegalArgumentException(
                    "invalid Tushare symbol");
        }
        return switch (exchange) {
            case "SSE" -> symbol + ".SH";
            case "SZSE" -> symbol + ".SZ";
            default -> throw new IllegalArgumentException(
                    "invalid Tushare exchange");
        };
    }

    private static boolean isMainBoard(
            String symbol,
            String exchange
    ) {
        return switch (exchange) {
            case "SSE" -> symbol.matches("60[0135][0-9]{3}");
            case "SZSE" -> symbol.matches("00[0123][0-9]{3}");
            default -> false;
        };
    }

    private static String providerDate(LocalDate date) {
        return PROVIDER_DATE.format(date);
    }

    private static QualifiedMarketField qualified(
            BigDecimal value,
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new QualifiedMarketField(
                value,
                value == null
                        ? FieldQualification.MISSING
                        : FieldQualification.PRESENT_VERIFIED,
                unit,
                semantic);
    }

    private static final class RowView {
        private final Map<String, Integer> indexes;
        private final List<JsonNode> values;

        private RowView(
                Map<String, Integer> indexes,
                List<JsonNode> values
        ) {
            this.indexes = Map.copyOf(indexes);
            this.values = List.copyOf(values);
        }

        private JsonNode value(String field) {
            Integer index = indexes.get(field);
            if (index == null || index >= values.size()) {
                throw new IllegalArgumentException(
                        "missing Tushare field " + field);
            }
            return values.get(index);
        }

        private String text(String field) {
            String value = nullableText(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "invalid Tushare text field " + field);
            }
            return value;
        }

        private String nullableText(String field) {
            JsonNode value = value(field);
            if (value.isNull()
                    || value.isTextual() && value.asText().isBlank()) {
                return null;
            }
            if (!value.isTextual()) {
                throw new IllegalArgumentException(
                        "invalid Tushare text field " + field);
            }
            return value.asText();
        }

        private BigDecimal decimal(String field) {
            BigDecimal value = nullableDecimal(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing Tushare decimal field " + field);
            }
            return value;
        }

        private BigDecimal nullableDecimal(String field) {
            JsonNode value = value(field);
            if (value.isNull()) return null;
            if (value.isNumber()) return value.decimalValue();
            if (value.isTextual() && value.asText().isBlank()) return null;
            if (value.isTextual()
                    && value.asText().matches(
                    "-?[0-9]+(?:\\.[0-9]+)?")) {
                return new BigDecimal(value.asText());
            }
            throw new IllegalArgumentException(
                    "invalid Tushare decimal field " + field);
        }

        private int integer(String field) {
            JsonNode value = value(field);
            if (!value.canConvertToInt()) {
                throw new IllegalArgumentException(
                        "invalid Tushare integer field " + field);
            }
            return value.intValue();
        }

        private LocalDate date(String field) {
            LocalDate value = nullableDate(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing Tushare date field " + field);
            }
            return value;
        }

        private LocalDate nullableDate(String field) {
            String value = nullableText(field);
            if (value == null) {
                return null;
            }
            try {
                return LocalDate.parse(value, PROVIDER_DATE);
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException(
                        "invalid Tushare date field " + field, error);
            }
        }
    }
}
