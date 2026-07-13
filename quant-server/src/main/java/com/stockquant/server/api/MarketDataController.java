package com.stockquant.server.api;

import com.stockquant.server.service.MarketDataCenterService;
import com.stockquant.server.service.ScanValidationService;
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
    private final ScanValidationService validationService;

    public MarketDataController(
            MarketDataCenterService service,
            ScanValidationService validationService
    ) {
        this.service = service;
        this.validationService = validationService;
    }

    public record ScanRequest(Integer scanLimit, Integer batchSize, Integer resultLimit) {}
    public record RetryRequest(Integer batchSize) {}
    public record UpdateRequest(Integer updateLimit, Integer batchSize) {}
    public record ValidationRequest(Integer topN, Integer maxHoldingDays) {}

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

    @PostMapping("/data/updates")
    public ApiResponse<?> startUpdate(@RequestBody(required = false) UpdateRequest request) {
        UpdateRequest body = request == null ? new UpdateRequest(0, 12) : request;
        long taskId = service.startIncrementalUpdate(body.updateLimit(), body.batchSize());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "QUEUED"));
    }

    @GetMapping("/data/updates/latest")
    public ApiResponse<?> latestUpdate() {
        return ApiResponse.ok(service.latestUpdateTask());
    }

    @GetMapping("/data/updates/{taskId}")
    public ApiResponse<?> updateTask(@PathVariable long taskId) {
        return ApiResponse.ok(service.updateTask(taskId));
    }

    @GetMapping("/data/updates/{taskId}/failures")
    public ApiResponse<?> updateFailures(@PathVariable long taskId) {
        return ApiResponse.ok(service.updateFailures(taskId));
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

    @PostMapping("/scans/{taskId}/retry")
    public ApiResponse<?> retryFailures(
            @PathVariable long taskId,
            @RequestBody(required = false) RetryRequest request
    ) {
        RetryRequest body = request == null ? new RetryRequest(6) : request;
        long retryTaskId = service.retryFailures(taskId, body.batchSize());
        return ApiResponse.ok(Map.of(
                "taskId", retryTaskId,
                "sourceTaskId", taskId,
                "status", "QUEUED"
        ));
    }

    @GetMapping("/scans/{taskId}")
    public ApiResponse<?> task(@PathVariable long taskId) {
        return ApiResponse.ok(service.task(taskId));
    }

    @GetMapping("/scans/{taskId}/results")
    public ApiResponse<?> taskResults(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean eligibleOnly
    ) {
        return ApiResponse.ok(service.taskResults(taskId, limit, eligibleOnly));
    }

    @GetMapping("/scans/{taskId}/failures")
    public ApiResponse<?> scanFailures(@PathVariable long taskId) {
        return ApiResponse.ok(service.scanFailures(taskId));
    }

    @GetMapping("/scans/history")
    public ApiResponse<?> history(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(service.taskHistory(limit));
    }

    @GetMapping("/scans/latest-task")
    public ApiResponse<?> latestTask() {
        return ApiResponse.ok(service.latestTask());
    }

    @GetMapping("/scans/latest-official-task")
    public ApiResponse<?> latestOfficialTask() {
        return ApiResponse.ok(service.latestOfficialTask());
    }

    @GetMapping("/scans/latest")
    public ApiResponse<?> latestResults(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.latestResults(limit));
    }

    @PostMapping("/scans/{taskId}/backtests")
    public ApiResponse<?> startBacktest(
            @PathVariable long taskId,
            @RequestBody(required = false) ValidationRequest request
    ) {
        ValidationRequest body = request == null
                ? new ValidationRequest(20, 10)
                : request;
        long validationTaskId = validationService.start(
                taskId,
                body.topN(),
                body.maxHoldingDays()
        );
        return ApiResponse.ok(Map.of(
                "taskId", validationTaskId,
                "scanTaskId", taskId,
                "status", "QUEUED"
        ));
    }

    @GetMapping("/scan-backtests/{taskId}")
    public ApiResponse<?> backtestTask(@PathVariable long taskId) {
        return ApiResponse.ok(validationService.task(taskId));
    }

    @GetMapping("/scan-backtests/{taskId}/results")
    public ApiResponse<?> backtestResults(@PathVariable long taskId) {
        return ApiResponse.ok(validationService.results(taskId));
    }

    @GetMapping("/scans/{scanTaskId}/backtests/latest")
    public ApiResponse<?> latestBacktest(@PathVariable long scanTaskId) {
        return ApiResponse.ok(validationService.latestForScan(scanTaskId));
    }
}
