package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchQfqBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure semantic validator for one security in an M1 historical window. */
final class TushareM1ResearchWindowValidator {
    private TushareM1ResearchWindowValidator() {
    }

    static ValidatedWindow validate(
            MarketFactResponse response,
            SecuritySelection security,
            TushareM1ResearchWindowCommand command
    ) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(command, "command");
        String instrument = TushareMarketFactProvider.sourceInstrumentId(
                security.symbol(), security.exchange());
        if (!response.complete() || !response.errors().isEmpty()
                || response.runNamespace() != RunNamespace.FORMAL
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.providerCode())
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.sourceCode())
                || !TushareMarketFactProvider.ADAPTER_VERSION.equals(
                response.adapterVersion())
                || !instrument.equals(response.sourceInstrumentId())
                || !command.rangeStart().equals(response.requestedStart())
                || !command.rangeEnd().equals(response.requestedEnd())
                || !response.corporateActions().isEmpty()) {
            throw invalid("TUSHARE_M1_PROVIDER_RESPONSE_INVALID");
        }
        int calls = metadataInt(response.providerMetadata(),
                "providerCallCount");
        int retries = metadataInt(response.providerMetadata(),
                "rateLimitRetryCount");
        if (calls != 3 || retries != 0) {
            throw invalid("TUSHARE_M1_PROVIDER_CALL_CONTRACT_INVALID");
        }

        List<RawDailyBar> raw = response.rawDailyBars().stream()
                .sorted(Comparator.comparing(RawDailyBar::tradeDate))
                .toList();
        List<AdjustmentFactor> factors = response.adjustmentFactors().stream()
                .sorted(Comparator.comparing(
                        AdjustmentFactor::factorEffectiveTradeDate))
                .toList();
        List<TradingCalendar> calendar = response.tradingCalendar().stream()
                .sorted(Comparator.comparing(TradingCalendar::calendarDate))
                .toList();
        Map<LocalDate, RawDailyBar> rawByDate = uniqueRaw(raw, security);
        Map<LocalDate, AdjustmentFactor> factorByDate =
                uniqueFactors(factors, security);
        Map<LocalDate, TradingCalendar> calendarByDate =
                uniqueCalendar(calendar, security);

        Set<LocalDate> naturalDates = naturalDates(command);
        if (!calendarByDate.keySet().equals(naturalDates)) {
            throw invalid("TUSHARE_M1_CALENDAR_WINDOW_INCOMPLETE");
        }
        Set<LocalDate> openDates = new LinkedHashSet<>();
        for (TradingCalendar value : calendar) {
            String expectedSession = value.open() ? "REGULAR" : "CLOSED";
            if (!expectedSession.equals(value.sessionCode())) {
                throw invalid("TUSHARE_M1_CALENDAR_SESSION_INVALID");
            }
            if (value.open()) {
                openDates.add(value.calendarDate());
            }
        }
        if (openDates.isEmpty()
                || !rawByDate.keySet().equals(openDates)
                || !factorByDate.keySet().equals(openDates)) {
            throw invalid("TUSHARE_M1_FACT_WINDOW_INCOMPLETE");
        }
        LocalDate lastOpen = openDates.stream().max(LocalDate::compareTo)
                .orElseThrow();
        if (!lastOpen.equals(command.anchorTradeDate())) {
            throw invalid("TUSHARE_M1_ANCHOR_TRADE_DATE_INVALID");
        }
        BigDecimal anchorFactor = factorByDate.get(lastOpen).factor();
        requirePositive(anchorFactor, "TUSHARE_M1_QFQ_ANCHOR_INVALID");
        List<TushareDedicatedResearchQfqBar> qfq = raw.stream()
                .map(value -> {
                    AdjustmentFactor factor = factorByDate.get(
                            value.tradeDate());
                    requirePositive(factor.factor(),
                            "TUSHARE_M1_QFQ_FACTOR_INVALID");
                    return new TushareDedicatedResearchQfqBar(
                            value.tradeDate(),
                            QfqPriceMath.calculate(value.open(),
                                    factor.factor(), anchorFactor),
                            QfqPriceMath.calculate(value.high(),
                                    factor.factor(), anchorFactor),
                            QfqPriceMath.calculate(value.low(),
                                    factor.factor(), anchorFactor),
                            QfqPriceMath.calculate(value.close(),
                                    factor.factor(), anchorFactor));
                }).toList();
        int expectedRecords = raw.size() + factors.size() + calendar.size();
        if (response.recordCount() != expectedRecords
                || qfq.size() != raw.size()) {
            throw invalid("TUSHARE_M1_RECORD_COUNT_INVALID");
        }
        return new ValidatedWindow(
                raw, factors, calendar, qfq, calls, retries,
                expectedRecords, openDates.size(),
                calendar.size() - openDates.size());
    }

    private static Map<LocalDate, RawDailyBar> uniqueRaw(
            List<RawDailyBar> values,
            SecuritySelection security
    ) {
        Map<LocalDate, RawDailyBar> result = new LinkedHashMap<>();
        String sourceIdentity = TushareMarketFactProvider.rawSourceIdentity(
                security.symbol(), security.exchange());
        for (RawDailyBar value : values) {
            if (!security.symbol().equals(value.symbol())
                    || !security.exchange().equals(value.exchange())
                    || !sourceIdentity.equals(value.sourceIdentity())
                    || !validOhlc(value)
                    || result.put(value.tradeDate(), value) != null) {
                throw invalid("TUSHARE_M1_RAW_DAILY_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<LocalDate, AdjustmentFactor> uniqueFactors(
            List<AdjustmentFactor> values,
            SecuritySelection security
    ) {
        Map<LocalDate, AdjustmentFactor> result = new LinkedHashMap<>();
        String sourceIdentity = TushareMarketFactProvider.factorSourceIdentity(
                security.symbol(), security.exchange());
        for (AdjustmentFactor value : values) {
            if (!security.symbol().equals(value.symbol())
                    || !sourceIdentity.equals(value.sourceIdentity())
                    || value.factor() == null || value.factor().signum() <= 0
                    || result.put(value.factorEffectiveTradeDate(), value)
                    != null) {
                throw invalid("TUSHARE_M1_ADJUSTMENT_FACTOR_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<LocalDate, TradingCalendar> uniqueCalendar(
            List<TradingCalendar> values,
            SecuritySelection security
    ) {
        Map<LocalDate, TradingCalendar> result = new LinkedHashMap<>();
        String sourceIdentity =
                TushareMarketFactProvider.calendarSourceIdentity(
                        security.exchange());
        for (TradingCalendar value : values) {
            if (!security.exchange().equals(value.exchange())
                    || !sourceIdentity.equals(value.sourceIdentity())
                    || result.put(value.calendarDate(), value) != null) {
                throw invalid("TUSHARE_M1_TRADING_CALENDAR_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    private static Set<LocalDate> naturalDates(
            TushareM1ResearchWindowCommand command
    ) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (LocalDate date = command.rangeStart();
                !date.isAfter(command.rangeEnd()); date = date.plusDays(1)) {
            result.add(date);
        }
        return Set.copyOf(result);
    }

    private static boolean validOhlc(RawDailyBar value) {
        return positive(value.open()) && positive(value.high())
                && positive(value.low()) && positive(value.close())
                && value.high().compareTo(value.open()) >= 0
                && value.high().compareTo(value.low()) >= 0
                && value.high().compareTo(value.close()) >= 0
                && value.low().compareTo(value.open()) <= 0
                && value.low().compareTo(value.high()) <= 0
                && value.low().compareTo(value.close()) <= 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void requirePositive(BigDecimal value, String code) {
        if (!positive(value)) {
            throw invalid(code);
        }
    }

    private static int metadataInt(JsonNode metadata, String name) {
        JsonNode value = metadata == null ? null : metadata.get(name);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw invalid("TUSHARE_M1_PROVIDER_METADATA_INVALID");
        }
        return value.intValue();
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    record ValidatedWindow(
            List<RawDailyBar> rawDailyBars,
            List<AdjustmentFactor> factors,
            List<TradingCalendar> calendar,
            List<TushareDedicatedResearchQfqBar> qfqBars,
            int providerCallCount,
            int retryCount,
            int expectedRecordCount,
            int openDateCount,
            int closedDateCount
    ) {
        ValidatedWindow {
            rawDailyBars = List.copyOf(rawDailyBars);
            factors = List.copyOf(factors);
            calendar = List.copyOf(calendar);
            qfqBars = List.copyOf(qfqBars);
        }
    }
}
