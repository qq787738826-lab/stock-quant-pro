package com.stockquant.server.service;

import com.stockquant.core.domain.Bar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class MarketDataService {
    private final RestClient client;
    public MarketDataService(RestClient.Builder builder, @Value("${quant.ai-service-url}") String baseUrl) { this.client = builder.baseUrl(baseUrl).build(); }

    public List<Bar> history(String symbol, int days) {
        try {
            List<Map<String,Object>> rows = client.get().uri("/market/history/{symbol}?days={days}", symbol, days).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (rows != null && !rows.isEmpty()) return rows.stream().map(r -> new Bar(
                    String.valueOf(r.get("symbol")), LocalDate.parse(String.valueOf(r.get("tradeDate"))), bd(r.get("open")), bd(r.get("high")), bd(r.get("low")), bd(r.get("close")),
                    Long.parseLong(String.valueOf(r.get("volume"))), bd(r.get("amount")), bd(r.getOrDefault("turnoverRate", 0)))).toList();
        } catch (Exception ignored) { }
        return demoBars(symbol, Math.max(days, 80));
    }

    private List<Bar> demoBars(String symbol, int days) {
        List<Bar> bars = new ArrayList<>(); Random random = new Random(symbol.hashCode()); BigDecimal price = BigDecimal.valueOf(8 + Math.abs(symbol.hashCode() % 1200) / 100.0);
        LocalDate date = LocalDate.now().minusDays(days + 30L);
        while (bars.size() < days) {
            date = date.plusDays(1); if (date.getDayOfWeek().getValue() >= 6) continue;
            double ret = random.nextGaussian() * 0.018 + 0.0015;
            BigDecimal open = price.multiply(BigDecimal.valueOf(1 + random.nextGaussian() * 0.005));
            price = price.multiply(BigDecimal.valueOf(1 + ret)).max(BigDecimal.ONE);
            BigDecimal high = open.max(price).multiply(BigDecimal.valueOf(1.01)); BigDecimal low = open.min(price).multiply(BigDecimal.valueOf(0.99));
            long volume = 600_000 + random.nextInt(2_000_000);
            bars.add(new Bar(symbol, date, open, high, low, price, volume, price.multiply(BigDecimal.valueOf(volume)), BigDecimal.valueOf(1.8)));
        }
        return bars;
    }
    private BigDecimal bd(Object value) { return new BigDecimal(String.valueOf(value)); }
}
