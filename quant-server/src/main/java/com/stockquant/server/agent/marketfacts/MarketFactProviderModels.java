package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider-neutral wire and domain records. No database entity is exposed here. */
public final class MarketFactProviderModels {

    private MarketFactProviderModels() {
    }

    public enum FactType {
        RAW_DAILY_BAR(PitMarketFactsContracts.RAW_DAILY_BAR_CONTRACT),
        ADJUSTMENT_FACTOR(PitMarketFactsContracts.ADJUSTMENT_FACTOR_CONTRACT),
        TRADING_CALENDAR(PitMarketFactsContracts.TRADING_CALENDAR_CONTRACT),
        CORPORATE_ACTION(PitMarketFactsContracts.CORPORATE_ACTION_CONTRACT);

        private final String contractVersion;

        FactType(String contractVersion) {
            this.contractVersion = contractVersion;
        }

        public String contractVersion() {
            return contractVersion;
        }
    }

    public enum RunNamespace {
        FORMAL, TEST, DEMO
    }

    public enum RevisionQualification {
        PROVIDER_VERIFIED,
        PROVIDER_UNVERIFIED,
        PROVIDER_UNAVAILABLE,
        SYSTEM_KNOWLEDGE_ONLY
    }

    public enum AssuranceLevel {
        PROVIDER_PIT_VERIFIED,
        SYSTEM_KNOWLEDGE_PIT
    }

    public enum UsageQualification {
        TEST_DEMO_ONLY,
        RESEARCH_ONLY,
        LICENSED_INTERNAL
    }

    public enum ProviderErrorType {
        INVALID_REQUEST,
        EMPTY,
        PARTIAL,
        RATE_LIMITED,
        TIMEOUT,
        STRUCTURE_CHANGED,
        UNAVAILABLE,
        TRIAL_GATE_BLOCKED
    }

    public enum CorporateActionType {
        CASH_DIVIDEND,
        STOCK_DIVIDEND,
        CAPITALIZATION,
        RIGHTS_ISSUE,
        SPLIT,
        REVERSE_SPLIT,
        OTHER
    }

    public record ProviderCapability(
            String providerContractVersion,
            String providerCode,
            String adapterVersion,
            Set<FactType> supportedFactTypes,
            boolean revisionIdAvailable,
            boolean snapshotIdAvailable,
            boolean providerPublishedAtAvailable,
            boolean providerUpdatedAtAvailable,
            boolean historicalVersionsQueryable,
            boolean localPersistenceAllowed,
            boolean historicalReplayAllowed,
            boolean backtestAllowed,
            boolean agentUseAllowed,
            int maximumSymbolsPerRequest,
            int maximumNaturalDaysPerRequest,
            Duration minimumRequestInterval,
            Map<String, String> fieldUnits,
            Map<String, Integer> decimalScales,
            JsonNode coverage,
            JsonNode licensing,
            JsonNode rateLimit
    ) {
        public ProviderCapability {
            require(PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION
                    .equals(providerContractVersion), "provider contract");
            providerCode = text(providerCode, "providerCode");
            adapterVersion = text(adapterVersion, "adapterVersion");
            supportedFactTypes = Set.copyOf(required(supportedFactTypes,
                    "supportedFactTypes"));
            require(!supportedFactTypes.isEmpty(), "supportedFactTypes");
            require(maximumSymbolsPerRequest > 0, "maximumSymbolsPerRequest");
            require(maximumNaturalDaysPerRequest > 0, "maximumNaturalDaysPerRequest");
            minimumRequestInterval = required(minimumRequestInterval,
                    "minimumRequestInterval");
            require(!minimumRequestInterval.isNegative(), "minimumRequestInterval");
            fieldUnits = Map.copyOf(required(fieldUnits, "fieldUnits"));
            decimalScales = Map.copyOf(required(decimalScales, "decimalScales"));
            Set<String> numericFields = Set.of(
                    "price", "volume", "amount", "turnoverRate", "factor");
            require(fieldUnits.keySet().containsAll(numericFields),
                    "fieldUnits");
            require(decimalScales.keySet().containsAll(numericFields),
                    "decimalScales");
            require(decimalScales.values().stream()
                            .allMatch(scale -> scale != null
                                    && scale >= 0 && scale <= 18),
                    "decimalScales");
            coverage = object(coverage, "coverage");
            licensing = object(licensing, "licensing");
            rateLimit = object(rateLimit, "rateLimit");
        }
    }

    public record MarketFactRequest(
            RunNamespace runNamespace,
            String sourceCode,
            String sourceInstrumentId,
            String symbol,
            String exchange,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Set<FactType> factTypes,
            Duration timeout
    ) {
        public MarketFactRequest {
            runNamespace = required(runNamespace, "runNamespace");
            sourceCode = text(sourceCode, "sourceCode");
            sourceInstrumentId = text(sourceInstrumentId, "sourceInstrumentId");
            symbol = MarketFactProviderModels.symbol(symbol);
            exchange = MarketFactProviderModels.exchange(exchange);
            rangeStart = required(rangeStart, "rangeStart");
            rangeEnd = required(rangeEnd, "rangeEnd");
            require(!rangeEnd.isBefore(rangeStart), "request range");
            factTypes = Set.copyOf(required(factTypes, "factTypes"));
            require(!factTypes.isEmpty(), "factTypes");
            timeout = required(timeout, "timeout");
            require(!timeout.isNegative() && !timeout.isZero(), "timeout");
        }
    }

    /** Metadata emitted by the upstream provider. Local observation times are absent. */
    public record ProviderVersion(
            String providerDatasetVersion,
            String providerRevision,
            String providerSnapshotId,
            Instant providerPublishedAt,
            Instant providerUpdatedAt,
            RevisionQualification revisionQualification
    ) {
        public ProviderVersion {
            revisionQualification = required(revisionQualification,
                    "revisionQualification");
            providerDatasetVersion = nullableText(providerDatasetVersion,
                    "providerDatasetVersion");
            providerRevision = nullableText(providerRevision, "providerRevision");
            providerSnapshotId = nullableText(providerSnapshotId,
                    "providerSnapshotId");
            if (revisionQualification == RevisionQualification.PROVIDER_VERIFIED) {
                require(providerRevision != null, "verified providerRevision");
                require(providerPublishedAt != null, "verified providerPublishedAt");
            } else {
                require(providerRevision == null,
                        "unqualified providerRevision must be absent");
            }
        }
    }

    public record RawDailyBar(
            String symbol,
            String exchange,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            ProviderVersion version,
            JsonNode rawFields
    ) {
        public RawDailyBar {
            symbol = MarketFactProviderModels.symbol(symbol);
            exchange = MarketFactProviderModels.exchange(exchange);
            tradeDate = required(tradeDate, "tradeDate");
            open = positive(open, 18, 12, "open");
            high = positive(high, 18, 12, "high");
            low = positive(low, 18, 12, "low");
            close = positive(close, 18, 12, "close");
            require(high.compareTo(open) >= 0 && high.compareTo(low) >= 0
                    && high.compareTo(close) >= 0, "high");
            require(low.compareTo(open) <= 0 && low.compareTo(high) <= 0
                    && low.compareTo(close) <= 0, "low");
            volume = nonNegative(volume, 22, 8, "volume");
            amount = nullableNonNegative(amount, 22, 8, "amount");
            turnoverRate = nullableNonNegative(
                    turnoverRate, 8, 12, "turnoverRate");
            version = required(version, "version");
            rawFields = object(rawFields, "rawFields");
        }
    }

    public record AdjustmentFactor(
            String symbol,
            LocalDate factorEffectiveTradeDate,
            String factorType,
            String coverageMode,
            BigDecimal factor,
            ProviderVersion version,
            JsonNode rawFields
    ) {
        public AdjustmentFactor {
            symbol = MarketFactProviderModels.symbol(symbol);
            factorEffectiveTradeDate = required(factorEffectiveTradeDate,
                    "factorEffectiveTradeDate");
            require(PitMarketFactsContracts.FACTOR_TYPE.equals(factorType),
                    "factorType");
            require(PitMarketFactsContracts.FACTOR_COVERAGE_MODE.equals(coverageMode),
                    "coverageMode");
            factor = positive(factor, 18, 18, "factor");
            version = required(version, "version");
            rawFields = object(rawFields, "rawFields");
        }
    }

    public record TradingCalendar(
            String exchange,
            LocalDate calendarDate,
            boolean open,
            String sessionCode,
            ProviderVersion version,
            JsonNode rawFields
    ) {
        public TradingCalendar {
            exchange = MarketFactProviderModels.exchange(exchange);
            calendarDate = required(calendarDate, "calendarDate");
            require(open ? "REGULAR".equals(sessionCode) : "CLOSED".equals(sessionCode),
                    "sessionCode");
            version = required(version, "version");
            rawFields = object(rawFields, "rawFields");
        }
    }

    public record CorporateAction(
            String sourceActionId,
            String symbol,
            CorporateActionType actionType,
            LocalDate announcementDate,
            LocalDate effectiveTradeDate,
            JsonNode terms,
            ProviderVersion version,
            JsonNode rawFields
    ) {
        public CorporateAction {
            sourceActionId = text(sourceActionId, "sourceActionId");
            symbol = MarketFactProviderModels.symbol(symbol);
            actionType = required(actionType, "actionType");
            effectiveTradeDate = required(effectiveTradeDate, "effectiveTradeDate");
            require(announcementDate == null
                    || !announcementDate.isAfter(effectiveTradeDate),
                    "announcementDate");
            terms = object(terms, "terms");
            version = required(version, "version");
            rawFields = object(rawFields, "rawFields");
        }
    }

    public record ProviderError(
            ProviderErrorType type,
            String code,
            String message,
            boolean retryable,
            Integer retryAfterSeconds
    ) {
        public ProviderError {
            type = required(type, "type");
            code = text(code, "code");
            message = text(message, "message");
            require(retryAfterSeconds == null || retryAfterSeconds >= 0,
                    "retryAfterSeconds");
        }
    }

    public record MarketFactResponse(
            String providerContractVersion,
            String providerCode,
            String adapterVersion,
            RunNamespace runNamespace,
            String sourceCode,
            String sourceInstrumentId,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            boolean complete,
            ProviderCapability capability,
            List<RawDailyBar> rawDailyBars,
            List<AdjustmentFactor> adjustmentFactors,
            List<TradingCalendar> tradingCalendar,
            List<CorporateAction> corporateActions,
            List<ProviderError> errors,
            JsonNode providerMetadata
    ) {
        public MarketFactResponse {
            require(PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION
                    .equals(providerContractVersion), "provider contract");
            providerCode = text(providerCode, "providerCode");
            adapterVersion = text(adapterVersion, "adapterVersion");
            runNamespace = required(runNamespace, "runNamespace");
            sourceCode = text(sourceCode, "sourceCode");
            sourceInstrumentId = text(sourceInstrumentId, "sourceInstrumentId");
            requestedStart = required(requestedStart, "requestedStart");
            requestedEnd = required(requestedEnd, "requestedEnd");
            require(!requestedEnd.isBefore(requestedStart), "response range");
            capability = required(capability, "capability");
            require(providerCode.equals(capability.providerCode()),
                    "capability providerCode");
            rawDailyBars = List.copyOf(required(rawDailyBars, "rawDailyBars"));
            adjustmentFactors = List.copyOf(required(adjustmentFactors,
                    "adjustmentFactors"));
            tradingCalendar = List.copyOf(required(tradingCalendar,
                    "tradingCalendar"));
            corporateActions = List.copyOf(required(corporateActions,
                    "corporateActions"));
            errors = List.copyOf(required(errors, "errors"));
            providerMetadata = object(providerMetadata, "providerMetadata");
            require(complete || !errors.isEmpty(), "partial response errors");
            require(!complete || errors.isEmpty(), "complete response errors");
        }

        public int recordCount() {
            return rawDailyBars.size() + adjustmentFactors.size()
                    + tradingCalendar.size() + corporateActions.size();
        }
    }

    static String naturalKey(FactType type, Object fact) {
        return switch (type) {
            case RAW_DAILY_BAR -> {
                RawDailyBar value = (RawDailyBar) fact;
                yield "RAW_DAILY_BAR|" + value.symbol() + "|" + value.tradeDate();
            }
            case ADJUSTMENT_FACTOR -> {
                AdjustmentFactor value = (AdjustmentFactor) fact;
                yield "ADJUSTMENT_FACTOR|" + value.symbol() + "|"
                        + value.factorType() + "|"
                        + value.factorEffectiveTradeDate();
            }
            case TRADING_CALENDAR -> {
                TradingCalendar value = (TradingCalendar) fact;
                yield "TRADING_CALENDAR|" + value.exchange() + "|"
                        + value.calendarDate();
            }
            case CORPORATE_ACTION -> {
                CorporateAction value = (CorporateAction) fact;
                yield "CORPORATE_ACTION|" + value.symbol() + "|"
                        + value.sourceActionId();
            }
        };
    }

    static ProviderVersion version(Object fact) {
        if (fact instanceof RawDailyBar value) return value.version();
        if (fact instanceof AdjustmentFactor value) return value.version();
        if (fact instanceof TradingCalendar value) return value.version();
        if (fact instanceof CorporateAction value) return value.version();
        throw new IllegalArgumentException("unsupported market fact");
    }

    private static String symbol(String value) {
        require(value != null && value.matches("[0-9]{6}"), "symbol");
        return value;
    }

    private static String exchange(String value) {
        require("SSE".equals(value) || "SZSE".equals(value), "exchange");
        return value;
    }

    static String text(String value, String field) {
        require(value != null && !value.isBlank(), field);
        return value;
    }

    static String nullableText(String value, String field) {
        require(value == null || !value.isBlank(), field);
        return value;
    }

    private static BigDecimal positive(
            BigDecimal value,
            int maximumIntegerDigits,
            int maximumScale,
            String field
    ) {
        require(value != null && value.signum() > 0, field);
        return representable(
                value, maximumIntegerDigits, maximumScale, field);
    }

    private static BigDecimal nonNegative(
            BigDecimal value,
            int maximumIntegerDigits,
            int maximumScale,
            String field
    ) {
        require(value != null && value.signum() >= 0, field);
        return representable(
                value, maximumIntegerDigits, maximumScale, field);
    }

    private static BigDecimal nullableNonNegative(
            BigDecimal value,
            int maximumIntegerDigits,
            int maximumScale,
            String field
    ) {
        require(value == null || value.signum() >= 0, field);
        return value == null ? null : representable(
                value, maximumIntegerDigits, maximumScale, field);
    }

    private static BigDecimal representable(
            BigDecimal value,
            int maximumIntegerDigits,
            int maximumScale,
            String field
    ) {
        BigDecimal normalized = value.stripTrailingZeros();
        int scale = Math.max(0, normalized.scale());
        int integerDigits = Math.max(
                0, normalized.precision() - normalized.scale());
        require(scale <= maximumScale
                        && integerDigits <= maximumIntegerDigits,
                field + " precision");
        return value;
    }

    static JsonNode object(JsonNode value, String field) {
        require(value != null && value.isObject(), field);
        return value.deepCopy();
    }

    static <T> T required(T value, String field) {
        require(value != null, field);
        return value;
    }

    static void require(boolean condition, String field) {
        if (!condition) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
