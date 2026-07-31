package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Shared pure fact-semantic boundary for every F1E entry.
 *
 * <p>The normal runtime, immutable capture contract and transactional
 * capture pre-validation all call this validator. It therefore cannot be
 * bypassed by invoking the lower capture boundary directly.</p>
 */
public final class TushareDedicatedResearchFactValidator {

    private static final String SAFE_CODE =
            "TUSHARE_DEDICATED_RESEARCH_FACT_SEMANTICS_INVALID";

    private TushareDedicatedResearchFactValidator() {
    }

    public static ValidatedSymbolFacts validate(
            MarketFactResponse response,
            SecuritySelection security,
            LocalDate tradeDate
    ) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (!response.complete()
                || !response.errors().isEmpty()
                || response.runNamespace() != RunNamespace.FORMAL
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.providerCode())
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.sourceCode())
                || !TushareMarketFactProvider.ADAPTER_VERSION.equals(
                response.adapterVersion())
                || !TushareMarketFactProvider.sourceInstrumentId(
                security.symbol(), security.exchange()).equals(
                response.sourceInstrumentId())
                || !tradeDate.equals(response.requestedStart())
                || !tradeDate.equals(response.requestedEnd())
                || response.rawDailyBars().size() != 1
                || response.adjustmentFactors().size() != 1
                || response.tradingCalendar().size() != 1
                || !response.corporateActions().isEmpty()
                || response.recordCount() != 3) {
            throw invalid();
        }

        int providerCallCount = metadataInt(
                response.providerMetadata(), "providerCallCount");
        int retryCount = metadataInt(
                response.providerMetadata(), "rateLimitRetryCount");
        if (providerCallCount != 3 || retryCount != 0) {
            throw invalid();
        }

        RawDailyBar raw = response.rawDailyBars().get(0);
        AdjustmentFactor factor =
                response.adjustmentFactors().get(0);
        TradingCalendar calendar =
                response.tradingCalendar().get(0);
        if (!security.symbol().equals(raw.symbol())
                || !security.exchange().equals(raw.exchange())
                || !TushareMarketFactProvider.rawSourceIdentity(
                security.symbol(), security.exchange()).equals(
                raw.sourceIdentity())
                || !tradeDate.equals(raw.tradeDate())
                || !security.symbol().equals(factor.symbol())
                || !TushareMarketFactProvider.factorSourceIdentity(
                security.symbol(), security.exchange()).equals(
                factor.sourceIdentity())
                || !tradeDate.equals(
                factor.factorEffectiveTradeDate())
                || !security.exchange().equals(calendar.exchange())
                || !TushareMarketFactProvider.calendarSourceIdentity(
                security.exchange()).equals(
                calendar.sourceIdentity())
                || !tradeDate.equals(calendar.calendarDate())
                || !calendar.open()
                || !"REGULAR".equals(calendar.sessionCode())
                || !positive(factor.factor())
                || !validOhlc(raw)) {
            throw invalid();
        }
        return new ValidatedSymbolFacts(
                raw,
                factor,
                calendar,
                providerCallCount,
                retryCount);
    }

    private static boolean validOhlc(RawDailyBar raw) {
        return positive(raw.open())
                && positive(raw.high())
                && positive(raw.low())
                && positive(raw.close())
                && raw.high().compareTo(raw.open()) >= 0
                && raw.high().compareTo(raw.low()) >= 0
                && raw.high().compareTo(raw.close()) >= 0
                && raw.low().compareTo(raw.open()) <= 0
                && raw.low().compareTo(raw.high()) <= 0
                && raw.low().compareTo(raw.close()) <= 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static int metadataInt(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(SAFE_CODE);
    }

    public record ValidatedSymbolFacts(
            RawDailyBar raw,
            AdjustmentFactor factor,
            TradingCalendar calendar,
            int providerCallCount,
            int retryCount
    ) {
        public ValidatedSymbolFacts {
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(factor, "factor");
            Objects.requireNonNull(calendar, "calendar");
            if (providerCallCount != 3 || retryCount != 0) {
                throw invalid();
            }
        }
    }
}
