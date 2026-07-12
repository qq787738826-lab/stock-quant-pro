package com.stockquant.server.api;

import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.BacktestModels.Request;
import com.stockquant.server.service.MarketDataService;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController @RequestMapping("/api/backtests")
public class BacktestController {
    private final BacktestEngine engine; private final MarketDataService data;
    public BacktestController(BacktestEngine engine, MarketDataService data) { this.engine=engine; this.data=data; }
    record Body(String symbol, BigDecimal initialCapital, Integer maxHoldingDays) {}
    @PostMapping public ApiResponse<?> run(@RequestBody Body b) {
        Request req = new Request(b.initialCapital()==null?BigDecimal.valueOf(100000):b.initialCapital(), b.maxHoldingDays()==null?10:b.maxHoldingDays(),
                BigDecimal.valueOf(.05), BigDecimal.valueOf(.08), BigDecimal.valueOf(.04), BigDecimal.valueOf(.0003), BigDecimal.valueOf(.0005));
        return ApiResponse.ok(engine.run(data.history(b.symbol()==null?"600000":b.symbol(), 500), req));
    }
}
