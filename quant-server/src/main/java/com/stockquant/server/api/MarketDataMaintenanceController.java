package com.stockquant.server.api;

import com.stockquant.server.production.ManualMarketDataMaintenanceService;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** HTTP facade for one deterministic formal maintenance request. */
@RestController
@RequestMapping("/api/data/maintenance")
@ConditionalOnProperty(
        prefix = "stockquant.market-data-maintenance.scheduler",
        name = "enabled", havingValue = "true")
public final class MarketDataMaintenanceController {
    private final ManualMarketDataMaintenanceService service;

    public MarketDataMaintenanceController(
            ManualMarketDataMaintenanceService service
    ) {
        this.service = service;
    }

    @PostMapping("/sync-latest")
    public ApiResponse<?> syncLatest() {
        return ApiResponse.ok(Response.from(service.syncLatest()));
    }

    @GetMapping("/status")
    public ApiResponse<?> status() {
        return ApiResponse.ok(Response.from(service.status()));
    }

    public record Response(
            String status,
            String requestId,
            String tradeDate,
            String reason,
            boolean readyForShadow,
            String latestCompleteTradeDate,
            String latestCommonCompletedOpenTradeDate,
            List<String> missingTradeDates,
            List<String> partialTradeDates,
            Integer providerCallCount
    ) {
        static Response from(
                AutomaticMarketDataMaintenanceStatusQuery.Snapshot value
        ) {
            return new Response(value.status().name(), value.requestId(),
                    value.tradeDate().toString(), value.reason(),
                    value.readyForShadow(),
                    date(value.latestCompleteTradeDate()),
                    date(value.latestCommonCompletedOpenTradeDate()),
                    dates(value.missingTradeDates()),
                    dates(value.partialTradeDates()),
                    value.providerCallCount());
        }

        private static String date(LocalDate value) {
            return value == null ? null : value.toString();
        }

        private static List<String> dates(List<LocalDate> values) {
            return values == null ? null
                    : values.stream().map(LocalDate::toString).toList();
        }
    }
}
