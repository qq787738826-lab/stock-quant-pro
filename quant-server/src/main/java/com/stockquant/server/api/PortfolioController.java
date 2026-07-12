package com.stockquant.server.api;

import com.stockquant.server.service.PortfolioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController @RequestMapping("/api/portfolio")
public class PortfolioController {
    private final PortfolioService service;
    public PortfolioController(PortfolioService service) { this.service=service; }
    record OrderBody(@Pattern(regexp="\\d{6}") String symbol, @Pattern(regexp="BUY|SELL") String side, @Min(100) int quantity, @DecimalMin("0.01") BigDecimal price) {}
    @GetMapping public ApiResponse<?> summary() { return ApiResponse.ok(service.summary()); }
    @GetMapping("/orders") public ApiResponse<?> orders() { return ApiResponse.ok(service.orders()); }
    @PostMapping("/orders") public ApiResponse<?> create(@Valid @RequestBody OrderBody b) { return ApiResponse.ok(service.createManualOrder(b.symbol,b.side,b.quantity,b.price)); }
    @PostMapping("/orders/{id}/confirm") public ApiResponse<?> confirm(@PathVariable long id) { service.confirm(id); return ApiResponse.ok("已人工确认，仍需在券商客户端核对并提交"); }
}
