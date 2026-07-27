package com.stockquant.server.agent.shadow.api;

import com.stockquant.server.agent.shadow.AgentShadowBatchService;
import com.stockquant.server.agent.shadow.AgentShadowMetricsService;
import com.stockquant.server.agent.shadow.AgentShadowModels.MetricsFilter;
import com.stockquant.server.agent.shadow.AgentShadowReviewService;
import com.stockquant.server.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/agent-team/shadow")
public class AgentShadowController {

    private final AgentShadowBatchService batchService;
    private final AgentShadowMetricsService metricsService;
    private final AgentShadowReviewService reviewService;

    public AgentShadowController(
            AgentShadowBatchService batchService,
            AgentShadowMetricsService metricsService,
            AgentShadowReviewService reviewService
    ) {
        this.batchService = batchService;
        this.metricsService = metricsService;
        this.reviewService = reviewService;
    }

    @GetMapping("/status")
    public ApiResponse<?> status() {
        return ApiResponse.ok(batchService.featureStatus());
    }

    @PostMapping("/batches")
    public ApiResponse<?> createBatch(
            @Valid @RequestBody CreateShadowBatchRequest request
    ) {
        return ApiResponse.ok(batchService.createManual(
                request.tradeDate(),
                request.selectionMode(),
                request.explicitSymbols(),
                request.maxSymbols(),
                request.createdBy()));
    }

    @GetMapping("/batches")
    public ApiResponse<?> batches(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(batchService.batches(limit));
    }

    @GetMapping("/batches/{batchId}")
    public ApiResponse<?> batch(@PathVariable long batchId) {
        return ApiResponse.ok(batchService.batch(batchId));
    }

    @GetMapping("/batches/{batchId}/items")
    public ApiResponse<?> items(@PathVariable long batchId) {
        return ApiResponse.ok(batchService.items(batchId));
    }

    @PostMapping("/batches/{batchId}/cancel")
    public ApiResponse<?> cancel(@PathVariable long batchId) {
        return ApiResponse.ok(batchService.cancel(batchId));
    }

    @GetMapping("/metrics")
    public ApiResponse<?> metrics(
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String ruleVersion,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String symbol
    ) {
        return ApiResponse.ok(metricsService.metrics(new MetricsFilter(
                fromDate, toDate, ruleVersion, batchId, symbol)));
    }

    @GetMapping("/drift")
    public ApiResponse<?> drift(
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String ruleVersion,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String symbol
    ) {
        return ApiResponse.ok(metricsService.drift(new MetricsFilter(
                fromDate, toDate, ruleVersion, batchId, symbol)));
    }

    @PostMapping("/items/{itemId}/reviews")
    public ApiResponse<?> addReview(
            @PathVariable long itemId,
            @Valid @RequestBody CreateShadowReviewRequest request
    ) {
        return ApiResponse.ok(reviewService.add(
                itemId,
                request.label(),
                request.note(),
                request.reviewer(),
                request.supersedesReviewId()));
    }

    @GetMapping("/items/{itemId}/reviews")
    public ApiResponse<?> reviews(@PathVariable long itemId) {
        return ApiResponse.ok(reviewService.reviews(itemId));
    }
}
