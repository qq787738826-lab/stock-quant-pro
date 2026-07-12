package com.stockquant.server.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class PortfolioService {
    private final JdbcClient jdbc;
    public PortfolioService(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Map<String,Object> summary() {
        BigDecimal cash = jdbc.sql("select cash from portfolio_accounts where id=1").query(BigDecimal.class).optional().orElse(BigDecimal.valueOf(100000));
        List<Map<String,Object>> positions = jdbc.sql("select symbol, quantity, average_cost, last_price, market_value, unrealized_pnl from positions order by market_value desc").query().listOfRows();
        BigDecimal market = positions.stream().map(x -> (BigDecimal)x.get("market_value")).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("cash", cash, "marketValue", market, "totalAsset", cash.add(market), "positions", positions);
    }
    @Transactional public long createManualOrder(String symbol, String side, int quantity, BigDecimal price) {
        if (quantity <= 0 || quantity % 100 != 0) throw new IllegalArgumentException("A股委托数量必须是100股的整数倍");
        return jdbc.sql("insert into manual_orders(symbol, side, quantity, limit_price, status, created_at) values (:s,:side,:q,:p,'PENDING_CONFIRM',:t) returning id")
                .param("s", symbol).param("side", side).param("q", quantity).param("p", price).param("t", LocalDateTime.now()).query(Long.class).single();
    }
    @Transactional public void confirm(long id) { jdbc.sql("update manual_orders set status='CONFIRMED', confirmed_at=now() where id=:id and status='PENDING_CONFIRM'").param("id",id).update(); }
    public List<Map<String,Object>> orders() { return jdbc.sql("select * from manual_orders order by created_at desc limit 100").query().listOfRows(); }
}
