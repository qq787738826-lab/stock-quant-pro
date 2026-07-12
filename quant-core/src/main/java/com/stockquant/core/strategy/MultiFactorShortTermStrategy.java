package com.stockquant.core.strategy;

import com.stockquant.core.domain.Bar;
import com.stockquant.core.domain.Signal;
import com.stockquant.core.indicator.Indicators;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class MultiFactorShortTermStrategy {
    public Signal evaluate(List<Bar> bars) {
        if (bars == null || bars.size() < 61) throw new IllegalArgumentException("至少需要61个交易日数据");
        Bar last = bars.get(bars.size() - 1);
        BigDecimal ma5 = Indicators.sma(bars, 5);
        BigDecimal ma20 = Indicators.sma(bars, 20);
        BigDecimal ma60 = Indicators.sma(bars, 60);
        BigDecimal rsi = Indicators.rsi(bars, 14);
        BigDecimal avgVol20 = Indicators.averageVolume(bars, 20);
        BigDecimal atr14 = Indicators.atr(bars, 14);
        BigDecimal previousHigh20 = bars.subList(bars.size() - 21, bars.size() - 1).stream()
                .map(Bar::close).max(BigDecimal::compareTo).orElse(last.close());

        BigDecimal score = BigDecimal.ZERO;
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        if (last.close().compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0) {
            score = score.add(BigDecimal.valueOf(25)); reasons.add("中短期趋势向上");
        } else risks.add("趋势结构未形成多头排列");

        if (last.close().compareTo(ma5) > 0) { score = score.add(BigDecimal.valueOf(8)); reasons.add("收盘价位于5日均线上方"); }
        if (rsi.compareTo(BigDecimal.valueOf(50)) >= 0 && rsi.compareTo(BigDecimal.valueOf(72)) <= 0) {
            score = score.add(BigDecimal.valueOf(18)); reasons.add("RSI动量健康");
        } else if (rsi.compareTo(BigDecimal.valueOf(78)) > 0) risks.add("短线指标过热");

        BigDecimal volumeRatio = BigDecimal.valueOf(last.volume()).divide(avgVol20, 4, RoundingMode.HALF_UP);
        if (volumeRatio.compareTo(BigDecimal.valueOf(1.25)) >= 0 && volumeRatio.compareTo(BigDecimal.valueOf(3)) <= 0) {
            score = score.add(BigDecimal.valueOf(15)); reasons.add("成交量温和放大");
        } else if (volumeRatio.compareTo(BigDecimal.valueOf(4)) > 0) risks.add("成交量异常放大，存在冲高回落风险");

        if (last.close().compareTo(previousHigh20) >= 0) { score = score.add(BigDecimal.valueOf(20)); reasons.add("突破近20日收盘高点"); }
        BigDecimal atrPct = atr14.divide(last.close(), 6, RoundingMode.HALF_UP);
        if (atrPct.compareTo(BigDecimal.valueOf(0.015)) >= 0 && atrPct.compareTo(BigDecimal.valueOf(0.06)) <= 0) {
            score = score.add(BigDecimal.valueOf(9)); reasons.add("波动率适合短线交易");
        } else risks.add("波动率不处于理想区间");

        BigDecimal actionThreshold = BigDecimal.valueOf(70);
        String action = score.compareTo(actionThreshold) >= 0 ? "BUY" : score.compareTo(BigDecimal.valueOf(55)) >= 0 ? "WATCH" : "AVOID";
        return new Signal(last.symbol(), last.tradeDate(), action, score.min(BigDecimal.valueOf(100)), last.close(), reasons, risks);
    }
}
