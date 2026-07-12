package com.stockquant.core.indicator;

import com.stockquant.core.domain.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class Indicators {
    private static final int SCALE = 6;
    private Indicators() {}

    public static BigDecimal sma(List<Bar> bars, int period) {
        requireSize(bars, period);
        return bars.subList(bars.size() - period, bars.size()).stream()
                .map(Bar::close).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal averageVolume(List<Bar> bars, int period) {
        requireSize(bars, period);
        long sum = bars.subList(bars.size() - period, bars.size()).stream().mapToLong(Bar::volume).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal rsi(List<Bar> bars, int period) {
        requireSize(bars, period + 1);
        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        int start = bars.size() - period;
        for (int i = start; i < bars.size(); i++) {
            BigDecimal diff = bars.get(i).close().subtract(bars.get(i - 1).close());
            if (diff.signum() >= 0) gain = gain.add(diff); else loss = loss.add(diff.abs());
        }
        if (loss.signum() == 0) return BigDecimal.valueOf(100);
        BigDecimal rs = gain.divide(loss, SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP));
    }

    public static BigDecimal atr(List<Bar> bars, int period) {
        requireSize(bars, period + 1);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar now = bars.get(i); Bar prev = bars.get(i - 1);
            BigDecimal tr = now.high().subtract(now.low()).max(now.high().subtract(prev.close()).abs())
                    .max(now.low().subtract(prev.close()).abs());
            total = total.add(tr);
        }
        return total.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal highestClose(List<Bar> bars, int period) {
        requireSize(bars, period);
        return bars.subList(bars.size() - period, bars.size()).stream().map(Bar::close).max(BigDecimal::compareTo).orElseThrow();
    }

    private static void requireSize(List<Bar> bars, int size) {
        if (bars == null || bars.size() < size) throw new IllegalArgumentException("K线数量不足，需要至少 " + size + " 条");
    }
}
