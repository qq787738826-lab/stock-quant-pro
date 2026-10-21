package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerAutomaticMarketDataMaintenanceStatusQueryTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 21);
    private static final String REQUEST_ID =
            "SQHB_20261021T083000Z_123456789ABC";

    @Test
    void durableRequestAndProcessingStatesRecoverAfterRefresh(
            @TempDir Path root
    ) throws Exception {
        Path requests = Files.createDirectories(root.resolve("requests"));
        Files.createDirectories(root.resolve("results"));
        MarketDataReadyGate gate = ignored -> MarketDataReadyGate.ReadyResult
                .skip("AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                        REQUEST_ID);
        var query = new BrokerAutomaticMarketDataMaintenanceStatusQuery(
                new ObjectMapper(), gate, root);
        Path request = requests.resolve(REQUEST_ID + ".request.properties");
        Files.writeString(request, "safe=true\n");

        assertEquals(Status.SUBMITTED, query.query(DATE).status());

        Files.delete(request);
        Files.writeString(requests.resolve(
                REQUEST_ID + ".processing.properties"), "safe=true\n");

        assertEquals(Status.IN_PROGRESS, query.query(DATE).status());

        Files.delete(requests.resolve(
                REQUEST_ID + ".processing.properties"));
        Files.writeString(requests.resolve(
                REQUEST_ID + ".processed.properties"), "safe=true\n");
        MarketDataReadyGate processedGate = ignored ->
                MarketDataReadyGate.ReadyResult.skip(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_MISSING",
                        REQUEST_ID);
        var processedQuery =
                new BrokerAutomaticMarketDataMaintenanceStatusQuery(
                        new ObjectMapper(), processedGate, root);

        assertEquals(Status.IN_PROGRESS,
                processedQuery.query(DATE).status());
    }

    @Test
    void successfulReadyResultExposesOnlySanitizedOptionalFields(
            @TempDir Path root
    ) throws Exception {
        Files.createDirectories(root.resolve("requests"));
        Path results = Files.createDirectories(root.resolve("results"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.writeValue(results.resolve(REQUEST_ID
                + ".automatic-daily-market-data-maintenance.json").toFile(),
                Map.of(
                        "finalLatestCompleteTradeDate", "2026-10-20",
                        "finalLatestCommonCompletedOpenTradeDate",
                        "2026-10-20",
                        "finalMissingTradeDates", List.of(),
                        "finalPartialTradeDates", List.of(),
                        "tushareProviderCallCount", 6));
        MarketDataReadyGate gate = ignored ->
                MarketDataReadyGate.ReadyResult.ready(REQUEST_ID);
        var query = new BrokerAutomaticMarketDataMaintenanceStatusQuery(
                mapper, gate, root);

        var result = query.query(DATE);

        assertEquals(Status.SUCCEEDED, result.status());
        assertTrue(result.readyForShadow());
        assertEquals(LocalDate.of(2026, 10, 20),
                result.latestCompleteTradeDate());
        assertEquals(List.of(), result.missingTradeDates());
        assertEquals(6, result.providerCallCount());
    }

    @Test
    void terminalFailurePreservesReadyGateReason(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve("requests"));
        Files.createDirectories(root.resolve("results"));
        MarketDataReadyGate gate = ignored -> MarketDataReadyGate.ReadyResult
                .skip("TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE", REQUEST_ID);
        var query = new BrokerAutomaticMarketDataMaintenanceStatusQuery(
                new ObjectMapper(), gate, root);

        var result = query.query(DATE);

        assertEquals(Status.FAILED, result.status());
        assertEquals("TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE",
                result.reason());
    }
}
