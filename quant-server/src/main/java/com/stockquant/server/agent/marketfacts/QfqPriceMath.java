package com.stockquant.server.agent.marketfacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Single Java authority for the frozen QFQ price formula.
 *
 * <p>Callers remain responsible for PIT selection, lineage, provider,
 * date-range and factor-validity gates. This class only applies the
 * already-frozen decimal arithmetic and rounding policy.
 */
public final class QfqPriceMath {

    public static final int DIVISION_SCALE = 16;
    public static final int PRICE_SCALE = 4;

    private QfqPriceMath() {
    }

    public static BigDecimal calculate(
            BigDecimal rawPrice,
            BigDecimal factorAtTradeDate,
            BigDecimal factorAtAnchorDate
    ) {
        Objects.requireNonNull(rawPrice, "rawPrice");
        Objects.requireNonNull(factorAtTradeDate, "factorAtTradeDate");
        Objects.requireNonNull(factorAtAnchorDate, "factorAtAnchorDate");
        return rawPrice.multiply(factorAtTradeDate)
                .divide(
                        factorAtAnchorDate,
                        DIVISION_SCALE,
                        RoundingMode.HALF_UP)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
