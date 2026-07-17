package com.stockquant.server.agent.service;

import com.stockquant.core.domain.Bar;
import com.stockquant.core.indicator.Indicators;
import com.stockquant.server.agent.repository.AgentContextReadRepository.DailyBarRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AgentTechnicalMetricsService {

    public static final int REQUIRED_BARS = 61;
    public static final String FORMULA_VERSION = "JAVA_INDICATORS_V1";

    public TechnicalMetrics evaluate(List<DailyBarRecord> dailyBars) {
        int invalidBarCount = (int) dailyBars.stream().filter(bar -> !isValid(bar)).count();
        if (invalidBarCount > 0) {
            return TechnicalMetrics.unavailable("INVALID_LOCAL_DAILY_BARS", dailyBars.size(), invalidBarCount);
        }
        if (dailyBars.size() < REQUIRED_BARS) {
            return TechnicalMetrics.unavailable("INSUFFICIENT_LOCAL_DAILY_BARS", dailyBars.size(), 0);
        }

        List<Bar> bars = dailyBars.stream().map(AgentTechnicalMetricsService::toBar).toList();
        return new TechnicalMetrics(
                true,
                null,
                dailyBars.size(),
                0,
                Indicators.sma(bars, 5),
                Indicators.sma(bars, 20),
                Indicators.sma(bars, 60),
                Indicators.rsi(bars, 14),
                Indicators.atr(bars, 14),
                Indicators.averageVolume(bars, 20),
                Indicators.highestClose(bars, 20)
        );
    }

    public static boolean isValid(DailyBarRecord bar) {
        return positive(bar.open())
                && positive(bar.high())
                && positive(bar.low())
                && positive(bar.close())
                && bar.high().compareTo(bar.open()) >= 0
                && bar.high().compareTo(bar.close()) >= 0
                && bar.high().compareTo(bar.low()) >= 0
                && bar.low().compareTo(bar.open()) <= 0
                && bar.low().compareTo(bar.close()) <= 0
                && bar.volume() >= 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static Bar toBar(DailyBarRecord value) {
        return new Bar(
                value.symbol(), value.tradeDate(), value.open(), value.high(), value.low(),
                value.close(), value.volume(), value.amount(), value.turnoverRate()
        );
    }

    public record TechnicalMetrics(
            boolean available,
            String reasonCode,
            int actualBars,
            int invalidBarCount,
            BigDecimal ma5,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal rsi14,
            BigDecimal atr14,
            BigDecimal averageVolume20,
            BigDecimal highestClose20
    ) {
        static TechnicalMetrics unavailable(String reasonCode, int actualBars, int invalidBarCount) {
            return new TechnicalMetrics(
                    false, reasonCode, actualBars, invalidBarCount,
                    null, null, null, null, null, null, null
            );
        }
    }
}
