package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticMarketDataMaintenanceTest {
    private static final String COMMIT =
            "decb4773e58af5333800651c823cce214743122a";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 24);
    private static final Instant SCHEDULED =
            Instant.parse("2026-09-24T08:30:00Z");

    @Test
    void scheduleAndDeterministicIdentityAreFrozen() {
        var properties = enabledProperties();
        properties.validate();

        assertEquals("0 30 16 * * MON-FRI", properties.getCron());
        assertEquals("Asia/Shanghai", properties.getZone());
        assertEquals("SQHB_20260924T083000Z_9C93F160711B",
                AutomaticMarketDataMaintenanceRequestIds.forDate(
                        DATE, COMMIT));
        assertEquals(AutomaticMarketDataMaintenanceRequestIds.forDate(
                        DATE, COMMIT),
                AutomaticMarketDataMaintenanceRequestIds.forDate(
                        DATE, COMMIT));

        properties.setCron("0 20 17 * * MON-FRI");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void schedulerSubmitsOncePerInvocationAndNeverRetriesFailure() {
        AtomicInteger calls = new AtomicInteger();
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException(
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED");
                };
        var runtime = new AutomaticMarketDataMaintenanceRuntimeState();
        var scheduler = new AutomaticDailyMarketDataMaintenanceScheduler(
                gateway, enabledProperties(),
                Clock.fixed(SCHEDULED, ZoneOffset.UTC), runtime);

        scheduler.dispatchAfterClose();

        assertEquals(1, calls.get());
        assertEquals(DATE, runtime.snapshot().tradeDate());
        assertEquals(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED",
                runtime.snapshot().lastReason());
        assertEquals("NONE", runtime.snapshot().requestId());
    }

    @Test
    void brokerCommandIsFixedAndHasNoCommandOrSqlSurface() {
        List<String> command =
                PowerShellAutomaticMarketDataMaintenanceDispatchGateway
                        .brokerCommand(Path.of("powershell.exe"),
                                Path.of("invoke.ps1"),
                                "SQHB_20260924T083000Z_9C93F160711B",
                                DATE, 30);

        assertTrue(command.contains(
                "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE"));
        assertTrue(command.contains("-SubmitOnly"));
        assertTrue(command.contains("-MaximumProviderRequests"));
        assertTrue(command.contains("0"));
        assertFalse(command.contains("-Command"));
        assertFalse(command.stream().anyMatch(value -> value.toLowerCase()
                .contains("select ")));
        assertFalse(command.stream().anyMatch(value -> value.toLowerCase()
                .contains("schema")));
    }

    @Test
    void overlappingSchedulerInvocationNeverCreatesASecondDispatch()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_TEST_TIMEOUT");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "AUTOMATIC_MARKET_DATA_MAINTENANCE_TEST_INTERRUPTED");
                    }
                    return new AutomaticMarketDataMaintenanceDispatchGateway
                            .DispatchResult(
                            "SQHB_20260924T083000Z_9C93F160711B", true,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
                };
        var scheduler = new AutomaticDailyMarketDataMaintenanceScheduler(
                gateway, enabledProperties(),
                Clock.fixed(SCHEDULED, ZoneOffset.UTC),
                new AutomaticMarketDataMaintenanceRuntimeState());
        Thread first = new Thread(scheduler::dispatchAfterClose);

        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        scheduler.dispatchAfterClose();
        release.countDown();
        first.join(5_000);

        assertFalse(first.isAlive());
        assertEquals(1, calls.get());
    }

    @Test
    void readyGateRequiresStrictSuccessfulFinalAudit(@TempDir Path root)
            throws Exception {
        Path results = Files.createDirectories(root.resolve("results"));
        Files.createDirectories(root.resolve("requests"));
        String requestId = AutomaticMarketDataMaintenanceRequestIds.forDate(
                DATE, COMMIT);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        write(mapper, results.resolve(requestId + ".result.json"),
                terminal(requestId, "SUCCEEDED",
                        "STOCK_QUANT_HOST_BROKER_SUCCEEDED", 4));
        Path aggregate = results.resolve(requestId
                + ".automatic-daily-market-data-maintenance.json");
        Map<String, Object> result = successfulAggregate(requestId);
        write(mapper, aggregate, result);
        var gate = new BrokerResultMarketDataReadyGate(mapper, root,
                () -> COMMIT);

        MarketDataReadyGate.ReadyResult ready = gate.evaluate(DATE);

        assertTrue(ready.ready());
        assertEquals("AUTOMATIC_MARKET_DATA_READY", ready.reasonCode());

        result.put("finalMissingTradeDates", List.of("2026-09-24"));
        write(mapper, aggregate, result);
        MarketDataReadyGate.ReadyResult missing = gate.evaluate(DATE);
        assertFalse(missing.ready());
        assertEquals("AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_INVALID",
                missing.reasonCode());
    }

    @Test
    void brokerStateMakesDuplicateSubmissionAReadOnlyNoOp(
            @TempDir Path root
    ) throws Exception {
        Path requests = Files.createDirectories(root.resolve("requests"));
        Files.createDirectories(root.resolve("results"));
        String requestId = AutomaticMarketDataMaintenanceRequestIds.forDate(
                DATE, COMMIT);
        Files.writeString(requests.resolve(
                requestId + ".processing.properties"), "safe=true\n");

        assertEquals(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_ALREADY_SUBMITTED",
                PowerShellAutomaticMarketDataMaintenanceDispatchGateway
                        .existingReason(root, requestId).orElseThrow());
    }

    @Test
    void uncertainSubmitQueriesOnlyTheOriginalRequestIdentity(
            @TempDir Path root
    ) throws Exception {
        Path requests = Files.createDirectories(root.resolve("requests"));
        Files.createDirectories(root.resolve("results"));
        String requestId = AutomaticMarketDataMaintenanceRequestIds.forDate(
                DATE, COMMIT);
        Files.writeString(requests.resolve(
                requestId + ".processing.properties"), "safe=true\n");

        AutomaticMarketDataMaintenanceDispatchGateway.DispatchResult value =
                PowerShellAutomaticMarketDataMaintenanceDispatchGateway
                        .queryOriginalRequestState(root, requestId)
                        .orElseThrow();

        assertEquals(requestId, value.requestId());
        assertTrue(value.accepted());
        assertEquals(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_STATE_CONFIRMED",
                value.reason());
        assertTrue(PowerShellAutomaticMarketDataMaintenanceDispatchGateway
                .queryOriginalRequestState(root,
                        AutomaticMarketDataMaintenanceRequestIds.forDate(
                                DATE.plusDays(1), COMMIT))
                .isEmpty());
        try (var files = Files.list(requests)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void readyGatePropagatesSafeTerminalFailure(@TempDir Path root)
            throws Exception {
        Path results = Files.createDirectories(root.resolve("results"));
        Files.createDirectories(root.resolve("requests"));
        String requestId = AutomaticMarketDataMaintenanceRequestIds.forDate(
                DATE, COMMIT);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        write(mapper, results.resolve(requestId + ".result.json"),
                terminal(requestId, "FAILED",
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED",
                        0));
        var gate = new BrokerResultMarketDataReadyGate(mapper, root,
                () -> COMMIT);

        MarketDataReadyGate.ReadyResult value = gate.evaluate(DATE);

        assertFalse(value.ready());
        assertEquals(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED",
                value.reasonCode());
    }

    private static AutomaticMarketDataMaintenanceProperties
    enabledProperties() {
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static Map<String, Object> terminal(
            String requestId,
            String status,
            String reason,
            int calls
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "STOCK_QUANT_HOST_BROKER_RESULT_V1");
        value.put("requestId", requestId);
        value.put("operation",
                "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE");
        value.put("status", status);
        value.put("reason", reason);
        value.put("gitCommit", COMMIT);
        value.put("providerCallCount", calls);
        return value;
    }

    private static Map<String, Object> successfulAggregate(
            String requestId
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion",
                "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_RESULT_V1");
        value.put("status", "SUCCEEDED");
        value.put("requestId", requestId);
        value.put("executionId",
                requestId.replace("SQHB_", "MBMAINT_"));
        value.put("gitCommit", COMMIT);
        value.put("targetEndDate", DATE.toString());
        value.put("forwardAction", "APPENDED");
        value.put("calendarAfterSse", DATE.toString());
        value.put("calendarAfterSzse", DATE.toString());
        value.put("operationChain", List.of(
                "MAINBOARD_TRADE_CAL_FORWARD_INCREMENT",
                "MAINBOARD_CATCHUP_READONLY_AUDIT",
                "MAINBOARD_DAILY_INCREMENT",
                "MAINBOARD_CATCHUP_READONLY_AUDIT"));
        value.put("readyForShadow", true);
        value.put("finalLatestCompleteTradeDate", "2026-09-24");
        value.put("finalLatestCommonCompletedOpenTradeDate", "2026-09-24");
        value.put("finalMissingTradeDates", List.of());
        value.put("finalPartialTradeDates", List.of());
        value.put("duplicateCount", 0);
        value.put("tushareProviderCallCount", 4);
        value.put("tradeCalendarProviderCallCount", 2);
        value.put("dailyProviderCallCount", 1);
        value.put("adjustmentFactorProviderCallCount", 1);
        value.put("retryCount", 0);
        value.put("networkRecoveryBudget", 2);
        value.put("ledgerBefore", 419);
        value.put("ledgerLimit", 450);
        value.put("finalLedger", 423);
        value.put("modelCallCount", 0);
        value.put("researchSelectionRunsCreated", 0);
        value.put("shadowRunsCreated", 0);
        value.put("paperOrdersCreated", 0);
        value.put("evaluationRowsCreated", 0);
        value.put("historicalResearchClassification",
                "POST_HOC_RESEARCH");
        value.put("pitClassification", "PIT_PARTIAL");
        value.put("outputAuditClean", true);
        value.put("dataOnly", true);
        value.put("realTradingStarted", false);
        value.put("failureReason", null);
        return value;
    }

    private static void write(
            ObjectMapper mapper,
            Path path,
            Map<String, Object> value
    ) throws Exception {
        mapper.writeValue(path.toFile(), value);
    }
}
