package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardCatchupReadonlyAuditContractTest {

    @Test
    void serviceReadsOnlyFixedCalendarFactAndUniverseRepositories()
            throws Exception {
        String service = read("src/main/java/com/stockquant/server/agent/"
                + "marketfacts/MainboardCatchupReadonlyAuditService.java");

        assertTrue(service.contains("SHOW transaction_read_only"));
        assertTrue(service.contains("findLatestCalendarAsOf"));
        assertTrue(service.contains("findRawBarsForSnapshotAsOf"));
        assertTrue(service.contains("findFactorsForSnapshotAsOf"));
        assertTrue(service.contains("MainboardDailyFactIntegrity.evaluate"));
        for (String forbidden : new String[]{
                "research_selection_runs", "shadow_research_runs",
                "shadow_paper_", "agent_evaluation_", "INSERT ",
                "UPDATE ", "DELETE ", "TRUNCATE "}) {
            assertFalse(service.contains(forbidden), forbidden);
        }
    }

    @Test
    void runnerHasNoProviderClientAndFormalPathsAreBrokerFixed()
            throws Exception {
        String runner = read("src/main/java/com/stockquant/server/agent/"
                + "marketfacts/"
                + "TushareMainboardCatchupReadonlyAuditManualRunner.java");
        String wrapper = read(
                "scripts/run-mainboard-catchup-readonly-audit.ps1");
        String invoker = read("scripts/host-broker/"
                + "invoke-stock-quant-host-broker.ps1");
        String broker = read("scripts/host-broker/"
                + "stock-quant-host-broker.ps1");

        assertTrue(runner.contains("transaction.setReadOnly(true)"));
        assertTrue(runner.contains("SHOW transaction_read_only")
                || read("src/main/java/com/stockquant/server/agent/"
                + "marketfacts/MainboardCatchupReadonlyAuditService.java")
                .contains("SHOW transaction_read_only"));
        for (String forbidden : new String[]{
                "TushareMarketFactGateway", "HttpClient", ".capture(",
                "readTushareToken", "readBailianApiKey"}) {
            assertFalse(runner.contains(forbidden), forbidden);
        }
        assertTrue(wrapper.contains(
                "stock-quant-host-broker\\results\\$requestId."));
        assertTrue(wrapper.contains("$ExecutionMode -eq 'FORMAL'"));
        assertTrue(wrapper.contains("Read-StockQuantHostBrokerHeartbeat"));
        assertTrue(wrapper.contains("[int]$heartbeat.processId -ne $PID"));
        assertTrue(wrapper.contains(
                "MAINBOARD_CATCHUP_AUDIT_BROKER_CONTEXT_REQUIRED"));
        assertTrue(invoker.contains(
                "$PSBoundParameters.ContainsKey('ArtifactPath')"));
        assertTrue(invoker.contains(
                "MAINBOARD_CATCHUP_AUDIT_CLIENT_PATH_FORBIDDEN"));
        int auditStart = broker.indexOf(
                "function Invoke-MainboardCatchupReadonlyAudit");
        int auditEnd = broker.indexOf(
                "function Invoke-MainboardHistoryBackfill", auditStart);
        assertTrue(auditStart >= 0 && auditEnd > auditStart);
        assertFalse(broker.substring(auditStart, auditEnd).contains(
                "sanitizedResult"));
    }

    @Test
    void sanitizedResultExcludesSecretsAndUniverseIdentifiers()
            throws Exception {
        String result = read("src/main/java/com/stockquant/server/agent/"
                + "marketfacts/"
                + "MainboardCatchupReadonlyAuditSanitizedResult.java");

        for (String forbidden : new String[]{
                "password", "token", "credential", "jdbc",
                "universeSnapshotId", "universeMemberFingerprint"}) {
            assertFalse(result.toLowerCase().contains(
                    forbidden.toLowerCase()), forbidden);
        }
        assertTrue(result.contains("missingTradeDates"));
        assertTrue(result.contains("partialTradeDates"));
        assertTrue(result.contains("completeTradeDates"));
        assertTrue(result.contains("reasonCodes"));
        assertTrue(result.contains("currentLedger"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
