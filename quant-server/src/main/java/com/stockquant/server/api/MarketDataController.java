package com.stockquant.server.api;

import com.stockquant.server.service.MarketDataCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MarketDataController {

    private final MarketDataCenterService service;

    public MarketDataController(MarketDataCenterService service) {
        this.service = service;
    }

    public record ScanRequest(Integer scanLimit, Integer batchSize, Integer resultLimit) {}

    @GetMapping("/data/overview")
    public ApiResponse<?> overview() {
        return ApiResponse.ok(service.overview());
    }

    @PostMapping("/data/universe/sync")
    public ApiResponse<?> syncUniverse() {
        return ApiResponse.ok(service.syncUniverse());
    }

    @PostMapping("/data/history/sync")
    public ApiResponse<?> syncHistory(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "260") int days
    ) {
        return ApiResponse.ok(service.syncHistory(symbol, days));
    }

    @GetMapping("/market/securities")
    public ApiResponse<?> securities(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ApiResponse.ok(service.searchSecurities(keyword, page, size));
    }

    @PostMapping("/scans")
    public ApiResponse<?> startScan(@RequestBody(required = false) ScanRequest request) {
        ScanRequest body = request == null ? new ScanRequest(0, 12, 50) : request;
        long taskId = service.startScan(body.scanLimit(), body.batchSize(), body.resultLimit());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "QUEUED"));
    }

    @GetMapping("/scans/{taskId}")
    public ApiResponse<?> task(@PathVariable long taskId) {
        return ApiResponse.ok(service.task(taskId));
    }

    @GetMapping("/scans/latest-task")
    public ApiResponse<?> latestTask() {
        return ApiResponse.ok(service.latestTask());
    }

    @GetMapping("/scans/latest")
    public ApiResponse<?> latestResults(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.latestResults(limit));
    }
}
