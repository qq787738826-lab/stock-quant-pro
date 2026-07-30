package com.stockquant.server.agent.marketfacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Offline validator for the reduced Tushare QFQ contract.
 *
 * <p>This is not a production ingestion or QFQ runtime entry. It validates
 * the provider/date/factor boundary and delegates all arithmetic to
 * {@link QfqPriceMath}. Dividend evidence is deliberately absent.
 */
public final class TushareReducedResearchQfqContract {

    private static final String PROVIDER_CODE = "TUSHARE_PRO";

    private TushareReducedResearchQfqContract() {
    }

    public static List<ResearchQfqPoint> validateAndCalculate(
            String rawProviderCode,
            String factorProviderCode,
            List<ResearchRawPrice> rawPrices,
            Map<LocalDate, BigDecimal> factors,
            LocalDate requestedEndDate
    ) {
        if (!PROVIDER_CODE.equals(rawProviderCode)
                || !PROVIDER_CODE.equals(factorProviderCode)
                || !rawProviderCode.equals(factorProviderCode)) {
            throw new IllegalArgumentException(
                    "TUSHARE_QFQ_CROSS_PROVIDER_FORBIDDEN");
        }
        Objects.requireNonNull(rawPrices, "rawPrices");
        Objects.requireNonNull(factors, "factors");
        Objects.requireNonNull(requestedEndDate, "requestedEndDate");
        if (rawPrices.isEmpty()) {
            throw new IllegalArgumentException(
                    "TUSHARE_QFQ_RAW_SERIES_EMPTY");
        }
        BigDecimal anchor = factors.get(requestedEndDate);
        requirePositive(anchor, "TUSHARE_QFQ_ANCHOR_FACTOR_UNAVAILABLE");

        Set<LocalDate> dates = new LinkedHashSet<>();
        List<ResearchQfqPoint> result =
                new ArrayList<>(rawPrices.size());
        for (ResearchRawPrice raw : rawPrices) {
            Objects.requireNonNull(raw, "rawPrice");
            if (raw.tradeDate().isAfter(requestedEndDate)) {
                throw new IllegalArgumentException(
                        "TUSHARE_QFQ_TRADE_DATE_AFTER_ANCHOR");
            }
            if (!dates.add(raw.tradeDate())) {
                throw new IllegalArgumentException(
                        "TUSHARE_QFQ_DUPLICATE_TRADE_DATE");
            }
            if (raw.rawPrice().signum() <= 0) {
                throw new IllegalArgumentException(
                        "TUSHARE_QFQ_RAW_PRICE_INVALID");
            }
            BigDecimal factor = factors.get(raw.tradeDate());
            requirePositive(
                    factor, "TUSHARE_QFQ_DAILY_FACTOR_UNAVAILABLE");
            result.add(new ResearchQfqPoint(
                    raw.tradeDate(),
                    QfqPriceMath.calculate(
                            raw.rawPrice(), factor, anchor)));
        }
        return List.copyOf(result);
    }

    private static void requirePositive(
            BigDecimal value,
            String absentCode
    ) {
        if (value == null) {
            throw new IllegalArgumentException(absentCode);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "TUSHARE_QFQ_FACTOR_INVALID");
        }
    }

    public record ResearchRawPrice(
            LocalDate tradeDate,
            BigDecimal rawPrice
    ) {
        public ResearchRawPrice {
            Objects.requireNonNull(tradeDate, "tradeDate");
            Objects.requireNonNull(rawPrice, "rawPrice");
        }
    }

    public record ResearchQfqPoint(
            LocalDate tradeDate,
            BigDecimal qfqPrice
    ) {
        public ResearchQfqPoint {
            Objects.requireNonNull(tradeDate, "tradeDate");
            Objects.requireNonNull(qfqPrice, "qfqPrice");
        }
    }
}
