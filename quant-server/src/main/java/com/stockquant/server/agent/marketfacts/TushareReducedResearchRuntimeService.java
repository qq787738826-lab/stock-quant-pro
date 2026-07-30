package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.RunCommand;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.RuntimeQualification;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.TushareReducedResearchQfqBar;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.TushareReducedResearchRunResult;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationStatus;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manual, single-symbol reduced-research runtime for a random F1C schema.
 *
 * <p>This service has no controller or scheduler entry. It obtains only raw
 * bars, adjustment factors and a trading calendar, persists those three facts
 * through the existing limited-personal FORMAL capture path, and returns the
 * formula-only QFQ projection in memory.</p>
 */
@Service
public final class TushareReducedResearchRuntimeService {

    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);
    private static final Set<String> SUCCESS_REASON_CODES = Set.of(
            "TUSHARE_REDUCED_RESEARCH_FORMULA_ONLY",
            "SYSTEM_KNOWLEDGE_ONLY",
            "PROVIDER_PIT_NOT_VERIFIED",
            "CORPORATE_ACTION_LINEAGE_INCOMPLETE",
            "FULL_QFQ_NOT_ELIGIBLE");

    private final TushareMarketFactProvider provider;
    private final TushareReducedResearchPersistenceGuard persistenceGuard;
    private final PitMarketFactCaptureService captureService;
    private final Clock clock;

    public TushareReducedResearchRuntimeService(
            TushareMarketFactProvider provider,
            TushareReducedResearchPersistenceGuard persistenceGuard,
            PitMarketFactCaptureService captureService,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.persistenceGuard = Objects.requireNonNull(
                persistenceGuard, "persistenceGuard");
        this.captureService = Objects.requireNonNull(
                captureService, "captureService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TushareReducedResearchRunResult run(
            TushareReducedResearchRuntimeAuthorization authorization,
            RunCommand command
    ) {
        Objects.requireNonNull(
                authorization, "runtimeAuthorization")
                .validateFrozen();
        Objects.requireNonNull(command, "command");
        validateQualification(provider.technicalQualification());

        TushareReducedResearchPersistenceGuard.Verification
                preProviderGuard = persistenceGuard.verify();
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1cIsolatedManual(
                        command.symbol(),
                        command.exchange(),
                        command.requestedStart(),
                        command.requestedEnd());
        MarketFactRequest request = new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        command.symbol(), command.exchange()),
                command.symbol(),
                command.exchange(),
                command.requestedStart(),
                command.requestedEnd(),
                FACT_TYPES,
                command.timeout());
        MarketFactResponse response =
                provider.fetchForIsolatedReducedResearch(
                        request, session);
        ValidatedSeries series = validateSeries(
                response, command, session);
        List<TushareReducedResearchQfqBar> qfqBars =
                calculateQfq(series, command.anchorTradeDate());

        TushareReducedResearchPersistenceGuard.Verification
                preCaptureGuard = persistenceGuard.verify();
        persistenceGuard.verifyUnchanged(
                preProviderGuard, preCaptureGuard);
        Instant observedAt =
                BacktestCanonicalHashService.microsecondInstant(
                        clock.instant());
        CaptureResult captureResult =
                captureService.captureAuthorizedLimitedPersonalFormal(
                        response,
                        observedAt,
                        LimitedPersonalFormalCaptureAuthorization
                                .tushareF1A());

        return new TushareReducedResearchRunResult(
                RuntimeQualification.REDUCED_RESEARCH_FORMULA_ONLY,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                series.providerCallCount(),
                series.retryCount(),
                session.consumedBusinessRequests(),
                response.sourceCode(),
                response.sourceInstrumentId(),
                response.requestedStart(),
                response.requestedEnd(),
                command.anchorTradeDate(),
                series.rawBars().size(),
                series.factors().size(),
                series.calendar().size(),
                qfqBars,
                captureResult,
                SUCCESS_REASON_CODES,
                provider.technicalQualification().routeDecision());
    }

    private static void validateQualification(
            TushareTechnicalQualification qualification
    ) {
        if (qualification.routeDecision()
                != RouteDecision.REDUCED_RESEARCH_ONLY
                || !qualification.reducedResearchContractReady()
                || qualification.fullTechnicalContractReady()
                || !qualification
                .reducedResearchIsolatedManualRuntimeReady()
                || qualification.reducedResearchProductionRuntimeReady()
                || qualification.qfqReducedResearchRuntimeQualification()
                != QualificationStatus.VERIFIED
                || qualification.qfqFullLineageRuntimeQualification()
                != QualificationStatus.PARTIAL
                || !qualification.endpointSpecificRateLimitEnforced()
                || !qualification
                .conservativeEndpointMinimumPolicyEnforced()
                || !qualification.isolatedSchemaGuardVerified()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_TECHNICAL_QUALIFICATION_INVALID");
        }
    }

    private static ValidatedSeries validateSeries(
            MarketFactResponse response,
            RunCommand command,
            TushareManualBoundedSession session
    ) {
        if (response == null || !response.complete()
                || !response.errors().isEmpty()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_PROVIDER_RESPONSE_INCOMPLETE");
        }
        int providerCallCount = metadataInt(
                response.providerMetadata(), "providerCallCount");
        int retryCount = metadataInt(
                response.providerMetadata(), "rateLimitRetryCount");
        if (providerCallCount != 3
                || retryCount != 0
                || session.consumedBusinessRequests() != 3
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.sourceCode())
                || !command.requestedStart().equals(
                response.requestedStart())
                || !command.requestedEnd().equals(
                response.requestedEnd())) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_PROVIDER_CALL_CONTRACT_INVALID");
        }

        List<RawDailyBar> rawBars = response.rawDailyBars().stream()
                .sorted(Comparator.comparing(RawDailyBar::tradeDate))
                .toList();
        List<AdjustmentFactor> factors =
                response.adjustmentFactors().stream()
                        .sorted(Comparator.comparing(
                                AdjustmentFactor
                                        ::factorEffectiveTradeDate))
                        .toList();
        List<TradingCalendar> calendar =
                response.tradingCalendar().stream()
                        .sorted(Comparator.comparing(
                                TradingCalendar::calendarDate))
                        .toList();
        if (rawBars.isEmpty()) {
            throw blocked("TUSHARE_REDUCED_RUNTIME_RAW_EMPTY");
        }

        Map<LocalDate, RawDailyBar> rawByDate =
                uniqueRaw(rawBars);
        Map<LocalDate, AdjustmentFactor> factorByDate =
                uniqueFactors(factors);
        Map<LocalDate, TradingCalendar> calendarByDate =
                uniqueCalendar(calendar);
        Set<LocalDate> expectedNaturalDates =
                requestedDates(command);
        if (!calendarByDate.keySet().equals(expectedNaturalDates)) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_CALENDAR_INCOMPLETE");
        }
        if (!factorByDate.containsKey(command.anchorTradeDate())) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_ANCHOR_INVALID");
        }
        Set<LocalDate> openDates = new LinkedHashSet<>();
        calendar.forEach(value -> {
            if (value.open()) {
                openDates.add(value.calendarDate());
            }
        });
        if (!rawByDate.keySet().equals(openDates)
                || !factorByDate.keySet().equals(openDates)) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_FACT_WINDOW_INCOMPLETE");
        }
        rawBars.forEach(bar -> {
            if (bar.tradeDate().isAfter(
                    command.anchorTradeDate())) {
                throw blocked(
                        "TUSHARE_QFQ_TRADE_DATE_AFTER_ANCHOR");
            }
        });
        LocalDate lastRawDate = rawBars.get(rawBars.size() - 1)
                .tradeDate();
        if (!command.anchorTradeDate().equals(lastRawDate)
                || !openDates.contains(command.anchorTradeDate())
                || !factorByDate.containsKey(
                command.anchorTradeDate())) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_ANCHOR_INVALID");
        }
        return new ValidatedSeries(
                rawBars,
                factors,
                calendar,
                factorByDate,
                providerCallCount,
                retryCount);
    }

    private static List<TushareReducedResearchQfqBar> calculateQfq(
            ValidatedSeries series,
            LocalDate anchorTradeDate
    ) {
        BigDecimal anchor = series.factorByDate()
                .get(anchorTradeDate).factor();
        requirePositive(
                anchor, "TUSHARE_QFQ_ANCHOR_FACTOR_UNAVAILABLE");
        List<TushareReducedResearchQfqBar> result =
                new ArrayList<>();
        for (RawDailyBar raw : series.rawBars()) {
            AdjustmentFactor factor =
                    series.factorByDate().get(raw.tradeDate());
            if (factor == null) {
                throw blocked(
                        "TUSHARE_QFQ_DAILY_FACTOR_UNAVAILABLE");
            }
            requirePositive(
                    factor.factor(), "TUSHARE_QFQ_FACTOR_INVALID");
            requirePositive(raw.open(), "TUSHARE_QFQ_RAW_PRICE_INVALID");
            requirePositive(raw.high(), "TUSHARE_QFQ_RAW_PRICE_INVALID");
            requirePositive(raw.low(), "TUSHARE_QFQ_RAW_PRICE_INVALID");
            requirePositive(raw.close(), "TUSHARE_QFQ_RAW_PRICE_INVALID");
            result.add(new TushareReducedResearchQfqBar(
                    raw.tradeDate(),
                    QfqPriceMath.calculate(
                            raw.open(), factor.factor(), anchor),
                    QfqPriceMath.calculate(
                            raw.high(), factor.factor(), anchor),
                    QfqPriceMath.calculate(
                            raw.low(), factor.factor(), anchor),
                    QfqPriceMath.calculate(
                            raw.close(), factor.factor(), anchor)));
        }
        return List.copyOf(result);
    }

    private static Map<LocalDate, RawDailyBar> uniqueRaw(
            List<RawDailyBar> values
    ) {
        Map<LocalDate, RawDailyBar> result = new LinkedHashMap<>();
        values.forEach(value -> {
            if (result.put(value.tradeDate(), value) != null) {
                throw blocked(
                        "TUSHARE_REDUCED_RUNTIME_DUPLICATE_RAW_DATE");
            }
        });
        return Map.copyOf(result);
    }

    private static Map<LocalDate, AdjustmentFactor> uniqueFactors(
            List<AdjustmentFactor> values
    ) {
        Map<LocalDate, AdjustmentFactor> result =
                new LinkedHashMap<>();
        values.forEach(value -> {
            if (result.put(
                    value.factorEffectiveTradeDate(), value) != null) {
                throw blocked(
                        "TUSHARE_REDUCED_RUNTIME_DUPLICATE_FACTOR_DATE");
            }
        });
        return Map.copyOf(result);
    }

    private static Map<LocalDate, TradingCalendar> uniqueCalendar(
            List<TradingCalendar> values
    ) {
        Map<LocalDate, TradingCalendar> result =
                new LinkedHashMap<>();
        values.forEach(value -> {
            if (result.put(value.calendarDate(), value) != null) {
                throw blocked(
                        "TUSHARE_REDUCED_RUNTIME_DUPLICATE_CALENDAR_DATE");
            }
        });
        return Map.copyOf(result);
    }

    private static Set<LocalDate> requestedDates(RunCommand command) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (LocalDate date = command.requestedStart();
                !date.isAfter(command.requestedEnd());
                date = date.plusDays(1)) {
            result.add(date);
        }
        return Set.copyOf(result);
    }

    private static int metadataInt(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw blocked(
                    "TUSHARE_REDUCED_RUNTIME_PROVIDER_METADATA_INVALID");
        }
        return value.intValue();
    }

    private static void requirePositive(
            BigDecimal value,
            String code
    ) {
        if (value == null || value.signum() <= 0) {
            throw blocked(code);
        }
    }

    private static RuntimeBlockedException blocked(String safeCode) {
        return new RuntimeBlockedException(safeCode);
    }

    private record ValidatedSeries(
            List<RawDailyBar> rawBars,
            List<AdjustmentFactor> factors,
            List<TradingCalendar> calendar,
            Map<LocalDate, AdjustmentFactor> factorByDate,
            int providerCallCount,
            int retryCount
    ) {
        private ValidatedSeries {
            rawBars = List.copyOf(rawBars);
            factors = List.copyOf(factors);
            calendar = List.copyOf(calendar);
            factorByDate = Map.copyOf(factorByDate);
        }
    }

    public static final class RuntimeBlockedException
            extends RuntimeException {
        private final String safeCode;

        RuntimeBlockedException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
