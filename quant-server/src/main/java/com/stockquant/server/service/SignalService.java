package com.stockquant.server.service;

import com.stockquant.core.domain.Signal;
import com.stockquant.core.domain.TradePlan;
import com.stockquant.core.strategy.MultiFactorShortTermStrategy;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class SignalService {
    private static final List<String> DEFAULT_UNIVERSE = List.of("600000","600036","601318","601166","601398","601088","600028","600309","000001","000858","002415","002594");
    private final MarketDataService marketDataService; private final MultiFactorShortTermStrategy strategy;
    public SignalService(MarketDataService marketDataService, MultiFactorShortTermStrategy strategy) { this.marketDataService = marketDataService; this.strategy = strategy; }

    public List<Signal> dailySignals(int limit) {
        return DEFAULT_UNIVERSE.stream().map(s -> strategy.evaluate(marketDataService.history(s, 100)))
                .sorted(Comparator.comparing(Signal::score).reversed()).limit(Math.max(1, Math.min(limit, 50))).toList();
    }

    public List<TradePlan> plans(int limit) {
        return dailySignals(limit).stream().filter(s -> !"AVOID".equals(s.action())).map(this::toPlan).toList();
    }

    private TradePlan toPlan(Signal s) {
        BigDecimal p=s.referencePrice();
        return new TradePlan(s.symbol(), LocalDate.now(), s.score(), p.multiply(BigDecimal.valueOf(.99)).setScale(2, RoundingMode.HALF_UP),
                p.multiply(BigDecimal.valueOf(1.01)).setScale(2, RoundingMode.HALF_UP), p.multiply(BigDecimal.valueOf(.95)).setScale(2, RoundingMode.HALF_UP),
                p.multiply(BigDecimal.valueOf(1.08)).setScale(2, RoundingMode.HALF_UP), p.multiply(BigDecimal.valueOf(1.12)).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(.20), 2, String.join("；", s.reasons()));
    }
}
