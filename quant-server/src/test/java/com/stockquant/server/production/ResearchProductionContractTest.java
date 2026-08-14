package com.stockquant.server.production;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchProductionContractTest {
    @Test
    void launcherAndBrokerUseOnlyFixedSecretFreeOperation() throws Exception {
        String launcher = read("scripts/start-stock-quant-pro.ps1");
        String broker = read("scripts/host-broker/stock-quant-host-broker.ps1");
        String invoker = read("scripts/host-broker/"
                + "invoke-stock-quant-host-broker.ps1");

        assertTrue(launcher.contains("START_RESEARCH_PRODUCTION"));
        assertTrue(launcher.contains("127.0.0.1:8080/api/system/health"));
        assertTrue(launcher.contains("$Action -eq 'Backup'"));
        assertTrue(launcher.contains("$ErrorActionPreference = 'Continue'"));
        assertTrue(launcher.contains("$javaExitCode = $LASTEXITCODE"));
        assertTrue(launcher.contains("Wait-StockQuantHostBrokerRecovery"));
        assertTrue(launcher.contains("Read-StockQuantHostBrokerHeartbeat"));
        assertTrue(launcher.contains("CHECKING_RESIDENT_BROKER"));
        assertTrue(launcher.contains("[ValidateRange(10, 900)]"));
        assertTrue(launcher.contains("[int] $TimeoutSeconds = 600"));
        assertTrue(launcher.contains("ConsecutiveSamples"));
        assertTrue(launcher.contains(
                "Get-Process -Id $candidateProcessId"));
        assertTrue(launcher.contains("ConsecutiveSamples -lt 2"));
        assertTrue(launcher.contains(
                "$readBrokerHeartbeat = Get-Command"));
        assertTrue(launcher.contains("& $readBrokerHeartbeat"));
        assertTrue(launcher.contains("}.GetNewClosure()"));
        assertTrue(launcher.contains(
                "-HeartbeatProbe $heartbeatProbe"));
        assertTrue(launcher.contains(
                "STOCK_QUANT_PRODUCTION_STATUS=ACTION_REQUIRED"));
        assertTrue(launcher.contains("Get-ScheduledTask"));
        assertTrue(launcher.contains(
                "Assert-StockQuantHostBrokerTaskDefinition"));
        assertFalse(launcher.contains("schtasks"));
        assertFalse(launcher.contains("Start-ScheduledTask"));
        assertFalse(launcher.contains("DB_PASSWORD"));
        assertFalse(launcher.contains("TUSHARE_TOKEN"));
        assertTrue(invoker.contains("maximum.provider.requests' = '0'"));
        assertTrue(invoker.contains("provider' = 'NONE'"));
        assertTrue(broker.contains("quant-server-1.3.1-research-production.jar"));
        assertTrue(broker.contains("M6_PRODUCTION_PROCESS_IDENTITY_INVALID"));
        assertTrue(broker.contains("productionMaximumRestarts = 3"));
        assertTrue(broker.contains("Invoke-ResearchProductionRecovery"));
        assertTrue(broker.contains("backend.autostart.json"));
        assertTrue(broker.contains("backend.recovery-status.json"));
        assertTrue(broker.contains("M6_PRODUCTION_RECOVERED"));
        assertTrue(broker.contains("M6_PRODUCTION_AUTOSTART_WRITE_FAILED"));
        assertFalse(broker.contains(
                "[IO.File]::Replace($temporary, $productionAutostartFile, $null)"));
        assertTrue(broker.contains("/api/system/lifecycle/stop"));
        assertFalse(broker.contains("-Command"));
        assertFalse(broker.contains("Invoke-Expression"));
        assertTrue(broker.contains(
                "codex/1.4.0-v1.0.2-startup-self-heal-fix"));
        assertTrue(invoker.contains(
                "codex/1.4.0-v1.0.2-startup-self-heal-fix"));

        String selfHeal = read("scripts/StockQuantStartupSelfHeal.psm1");
        assertTrue(selfHeal.contains("HOST_BROKER_NOT_RUNNING"));
        assertTrue(selfHeal.contains("RECOVERED"));
        assertTrue(selfHeal.contains("TIMEOUT"));
        assertTrue(selfHeal.contains("HOST_BROKER_TASK_NOT_INSTALLED"));
        assertTrue(selfHeal.contains(
                "HOST_BROKER_TASK_DEFINITION_INVALID"));
        assertFalse(selfHeal.contains("Start-ScheduledTask"));
        assertFalse(selfHeal.contains("schtasks"));
    }

    @Test
    void productionRunnerOwnsMigrationAndNeverEnablesTrading() throws Exception {
        String runner = read("src/main/java/com/stockquant/server/production/"
                + "StockQuantResearchProductionRunner.java");
        String controller = read("src/main/java/com/stockquant/server/"
                + "production/SystemHealthController.java");
        String health = read("src/main/java/com/stockquant/server/production/"
                + "SystemHealthService.java");

        assertTrue(runner.contains("ProductionSecretAudit.install()"));
        assertTrue(runner.indexOf("ProductionSecretAudit.install()")
                < runner.indexOf("readResearchDatabasePassword()"));
        assertTrue(runner.contains("Flyway.configure()"));
        assertTrue(runner.contains("after != 17"));
        assertTrue(controller.contains("@GetMapping(\"/health\")"));
        assertTrue(controller.contains("@PostMapping(\"/backups\")"));
        assertFalse(controller.toLowerCase().contains("trade"));
        assertTrue(health.contains("\"realTrading\", false"));
        String lifecycle = read("src/main/java/com/stockquant/server/"
                + "production/ProductionLifecycleController.java");
        assertTrue(lifecycle.contains("127.0.0.1"));
        assertTrue(lifecycle.contains("System.exit(status)"));
        assertFalse(lifecycle.toLowerCase().contains("trade"));

        String dailyJob = read("src/main/java/com/stockquant/server/job/"
                + "DailyScanJob.java");
        String riskJob = read("src/main/java/com/stockquant/server/job/"
                + "PortfolioRiskJob.java");
        assertTrue(dailyJob.contains("quant.jobs"));
        assertTrue(riskJob.contains("quant.jobs"));
        assertTrue(runner.contains("\"quant.jobs.enabled\", \"false\""));
        assertTrue(runner.contains("m6FixedRuntime"));
        assertTrue(runner.contains("stockquant.market-facts.tushare.mode"));
        assertTrue(runner.contains("\"DISABLED\""));
        assertFalse(runner.contains(".properties("));
    }

    @Test
    void systemHealthUsesTheResidentBrokerHeartbeatContract() throws Exception {
        String health = read("src/main/java/com/stockquant/server/production/"
                + "SystemHealthService.java");
        assertTrue(health.contains("base.resolve(\"heartbeat.json\")"));
        assertFalse(health.contains("health/heartbeat.json"));
        assertTrue(health.contains("ProjectNonShadowCostCny"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
