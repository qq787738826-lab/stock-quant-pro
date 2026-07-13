package com.stockquant.server.api;

import com.stockquant.server.service.PortfolioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    public record OrderBody(
            @Pattern(regexp = "\\d{6}") String symbol,
            @Pattern(regexp = "BUY|SELL") String side,
            @Min(100) Integer quantity,
            @DecimalMin("0.01") BigDecimal price,
            Long tradePlanId
    ) {}

    public record ScanPlanBody(Long scanTaskId, @Pattern(regexp = "\\d{6}") String symbol) {}
    public record PlanOrderBody(Integer quantity, BigDecimal price) {}

    @GetMapping
    public ApiResponse<?> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/orders")
    public ApiResponse<?> orders() {
        return ApiResponse.ok(service.orders());
    }

    @GetMapping("/trades")
    public ApiResponse<?> trades() {
        return ApiResponse.ok(service.trades());
    }

    @GetMapping("/plans")
    public ApiResponse<?> plans(@RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.ok(service.plans(status));
    }

    @GetMapping("/equity")
    public ApiResponse<?> equity(@RequestParam(defaultValue = "180") int days) {
        return ApiResponse.ok(service.equityCurve(days));
    }

    @GetMapping("/risk-events")
    public ApiResponse<?> riskEvents(@RequestParam(defaultValue = "false") boolean resolved) {
        return ApiResponse.ok(service.riskEvents(resolved));
    }

    @PostMapping("/plans/from-scan")
    public ApiResponse<?> createPlanFromScan(@Valid @RequestBody ScanPlanBody body) {
        if (body.scanTaskId() == null) {
            throw new IllegalArgumentException("scanTaskId不能为空");
        }
        return ApiResponse.ok(Map.of("planId", service.createPlanFromScan(body.scanTaskId(), body.symbol())));
    }

    @PostMapping("/plans/{planId}/orders")
    public ApiResponse<?> createOrderFromPlan(
            @PathVariable long planId,
            @RequestBody(required = false) PlanOrderBody body
    ) {
        PlanOrderBody request = body == null ? new PlanOrderBody(null, null) : body;
        long orderId = service.createOrderFromPlan(
                planId,
                new PortfolioService.PlanOrderRequest(request.quantity(), request.price())
        );
        return ApiResponse.ok(Map.of("orderId", orderId));
    }

    @PostMapping("/plans/{planId}/cancel")
    public ApiResponse<?> cancelPlan(@PathVariable long planId) {
        service.cancelPlan(planId);
        return ApiResponse.ok("交易计划已取消");
    }

    @PostMapping("/orders")
    public ApiResponse<?> create(@Valid @RequestBody OrderBody body) {
        long id = service.createOrder(new PortfolioService.OrderRequest(
                body.symbol(), body.side(), body.quantity(), body.price(), body.tradePlanId()
        ));
        return ApiResponse.ok(Map.of("orderId", id));
    }

    @PostMapping("/orders/{id}/confirm")
    public ApiResponse<?> confirm(@PathVariable long id) {
        return ApiResponse.ok(service.confirm(id));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable long id) {
        service.cancelOrder(id);
        return ApiResponse.ok("委托已撤销");
    }

    @PostMapping("/refresh-risk")
    public ApiResponse<?> refreshRisk() {
        return ApiResponse.ok(service.refreshAndCheckRisk());
    }

    @PostMapping("/snapshot")
    public ApiResponse<?> snapshot() {
        return ApiResponse.ok(service.snapshot());
    }

    @PostMapping("/risk-events/{id}/resolve")
    public ApiResponse<?> resolveRisk(@PathVariable long id) {
        service.resolveRiskEvent(id);
        return ApiResponse.ok("风险事件已处理");
    }
}
