package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockQuantHostBrokerContractTest {

    @Test
    void installerRegistersOneFixedInteractiveResidentTask() throws Exception {
        String installer = read("scripts/host-broker/"
                + "install-stock-quant-host-broker.ps1");
        String taskDefinition = read("scripts/host-broker/"
                + "StockQuantHostBroker.TaskDefinition.psm1");

        assertTrue(installer.contains("$taskName = 'StockQuantLocalBroker'"));
        assertTrue(installer.contains("stock-quant-host-broker.ps1"));
        assertTrue(installer.contains(
                "Invoke-StockQuantHostBrokerTaskRegistrationTransaction"));
        assertTrue(taskDefinition.contains("New-ScheduledTaskPrincipal"));
        assertTrue(taskDefinition.contains("-LogonType Interactive"));
        assertTrue(taskDefinition.contains("-RunLevel Limited"));
        assertTrue(taskDefinition.contains("Register-ScheduledTask"));
        assertTrue(taskDefinition.contains(
                "Assert-StockQuantHostBrokerTaskDefinition"));
        assertTrue(taskDefinition.contains(
                "New-ScheduledTaskTrigger -AtLogOn -User $UserId"));
        assertTrue(taskDefinition.contains(
                "New-ScheduledTaskTrigger -Once"));
        assertTrue(taskDefinition.contains(
                "-RepetitionInterval $script:ExpectedWatchdogInterval"));
        assertTrue(taskDefinition.contains(
                "$watchdogTrigger.Repetition.StopAtDurationEnd = $false"));
        assertFalse(taskDefinition.contains("-AtStartup"));
        assertFalse(taskDefinition.contains("-LogonType Password"));
        assertFalse(taskDefinition.contains("/RP"));
        assertTrue(taskDefinition.contains("$triggers.Count -ne 2"));
        assertTrue(taskDefinition.contains("AllowDemandStart"));
        assertTrue(installer.contains("STOCK_QUANT_HOST_BROKER_TRIGGERS=2"));
        assertTrue(installer.contains(
                "WATCHDOG_EVERY_1_MINUTE"));
        assertTrue(installer.contains(
                "STOCK_QUANT_HOST_BROKER_MULTIPLE_INSTANCES=IgnoreNew"));
        assertTrue(installer.contains("STOCK_QUANT_HOST_BROKER_AUTOSTART=true"));
        assertTrue(installer.contains(
                "STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false"));
        assertTrue(taskDefinition.contains("-RestartCount"));
        assertTrue(taskDefinition.contains("-RestartInterval"));
        assertTrue(installer.contains("[switch] $Uninstall"));
    }

    @Test
    void installerRequiresAdministratorAndNeverInvokesCodexCli()
            throws Exception {
        String installer = read("scripts/host-broker/"
                + "install-stock-quant-host-broker.ps1");
        String hostSmoke = read("scripts/host-broker/"
                + "test-stock-quant-host-broker-host-smoke.ps1");

        assertTrue(installer.contains(
                "WindowsBuiltInRole]::Administrator"));
        assertTrue(installer.contains(
                "STOCK_QUANT_HOST_BROKER_ADMINISTRATOR_REQUIRED"));
        assertTrue(installer.contains(
                "-not $isAdministrator -and -not $WhatIfPreference"));
        assertTrue(installer.contains("$hostIdentity.Name"));
        assertTrue(installer.contains("$credentialStatusScript -Status"));
        assertTrue(installer.contains(
                "StockQuant/ResearchDbPassword=PRESENT"));
        assertTrue(installer.contains("StockQuant/TushareToken=PRESENT"));
        assertTrue(installer.contains(
                "STOCK_QUANT_HOST_BROKER_CODEX_CLI_REQUIRED=false"));
        for (String script : List.of(installer, hostSmoke)) {
            assertFalse(script.contains("& codex"));
            assertFalse(script.contains("codex sandbox"));
            assertFalse(script.contains("codex exec"));
            assertFalse(script.contains("Get-StockQuantSandboxIdentity"));
        }
        assertFalse(installer.contains("Set-TaskRunAcl"));
        assertFalse(installer.contains("SetSecurityDescriptor"));
        assertFalse(installer.contains("Set-Acl"));
    }

    @Test
    void noCodexHarnessChecksCredentialsWithoutProviderOrDatabaseWrites()
            throws Exception {
        String harness = read("scripts/host-broker/"
                + "test-stock-quant-host-broker-installer-no-codex.ps1");
        String hostSmoke = read("scripts/host-broker/"
                + "test-stock-quant-host-broker-host-smoke.ps1");
        String secretSetup = read("scripts/set-stock-quant-secrets.ps1");
        String presenceCheck = section(secretSetup,
                "function Test-CredentialExists",
                "function Write-SecureCredential");

        assertTrue(harness.contains("$env:PATH = $minimalPath -join ';'"));
        assertTrue(harness.contains("Get-Command codex"));
        assertTrue(harness.contains("$installer -WhatIf"));
        assertTrue(harness.contains("$hostSmoke -ExpectedCommit"));
        assertTrue(hostSmoke.contains(
                "StockQuant/ResearchDbPassword=PRESENT"));
        assertTrue(hostSmoke.contains("StockQuant/TushareToken=PRESENT"));
        assertTrue(hostSmoke.contains(
                "HOST_SMOKE_PROVIDER_CALLS=0"));
        assertTrue(hostSmoke.contains(
                "HOST_SMOKE_PERMANENT_DATABASE_WRITES=0"));
        assertTrue(presenceCheck.contains("CredRead"));
        assertTrue(presenceCheck.contains("CredFree"));
        assertFalse(presenceCheck.contains("CredentialBlob"));
        assertFalse(presenceCheck.contains("PtrToString"));
    }

    @Test
    void taskDefinitionUsesIndependentReasonsAndSidNormalization()
            throws Exception {
        String taskDefinition = read("scripts/host-broker/"
                + "StockQuantHostBroker.TaskDefinition.psm1");

        for (String reason : List.of(
                "TASK_NAME_MISMATCH",
                "TASK_ACTION_COUNT_MISMATCH",
                "TASK_ACTION_EXECUTE_MISMATCH",
                "TASK_ACTION_ARGUMENTS_MISMATCH",
                "TASK_ACTION_WORKING_DIRECTORY_MISMATCH",
                "TASK_PRINCIPAL_USER_MISMATCH",
                "TASK_LOGON_TYPE_MISMATCH",
                "TASK_RUN_LEVEL_MISMATCH",
                "TASK_TRIGGER_COUNT_MISMATCH",
                "TASK_TRIGGER_TYPE_MISMATCH",
                "TASK_TRIGGER_USER_MISMATCH",
                "TASK_TRIGGER_ENABLED_MISMATCH",
                "TASK_WATCHDOG_TRIGGER_TYPE_MISMATCH",
                "TASK_WATCHDOG_TRIGGER_ENABLED_MISMATCH",
                "TASK_WATCHDOG_START_BOUNDARY_MISMATCH",
                "TASK_WATCHDOG_END_BOUNDARY_MISMATCH",
                "TASK_WATCHDOG_INTERVAL_MISMATCH",
                "TASK_WATCHDOG_DURATION_MISMATCH",
                "TASK_WATCHDOG_STOP_AT_DURATION_END_MISMATCH",
                "TASK_EXECUTION_TIME_LIMIT_MISMATCH",
                "TASK_ALLOW_DEMAND_START_MISMATCH",
                "TASK_START_WHEN_AVAILABLE_MISMATCH",
                "TASK_RESTART_SETTINGS_MISMATCH",
                "TASK_SETTINGS_MISMATCH")) {
            assertTrue(taskDefinition.contains(
                    "STOCK_QUANT_HOST_BROKER_" + reason), reason);
        }
        assertFalse(taskDefinition.contains("TASK_DEFINITION_INVALID"));
        assertTrue(taskDefinition.contains("ConvertTo-StockQuantPrincipalSid"));
        assertTrue(taskDefinition.contains("SecurityIdentifier"));
        assertTrue(taskDefinition.contains("$env:COMPUTERNAME"));
        assertTrue(taskDefinition.contains("'InteractiveToken'"));
        assertTrue(taskDefinition.contains("'LeastPrivilege'"));
        assertTrue(taskDefinition.contains("ExpandEnvironmentVariables"));
        assertTrue(taskDefinition.contains("GetFullPath"));
    }

    @Test
    void registrationTransactionRestoresExistingAndRemovesOnlyExactTask()
            throws Exception {
        String taskDefinition = read("scripts/host-broker/"
                + "StockQuantHostBroker.TaskDefinition.psm1");
        String roundTrip = read("scripts/host-broker/"
                + "test-stock-quant-host-broker-install-roundtrip.ps1");

        assertTrue(taskDefinition.contains(
                "'^StockQuantHostBrokerRoundTrip_[A-F0-9]{32}$'"));
        assertTrue(taskDefinition.contains("Export-ScheduledTask"));
        assertTrue(taskDefinition.contains("$existingXml"));
        assertTrue(taskDefinition.contains("-Xml $existingXml -Force"));
        assertTrue(taskDefinition.contains("Get-StockQuantCanonicalXml"));
        assertTrue(taskDefinition.contains(
                "STOCK_QUANT_HOST_BROKER_TASK_ROLLBACK_FAILED"));
        assertFalse(taskDefinition.contains("Unregister-ScheduledTask *"));
        assertFalse(taskDefinition.contains("-TaskName *"));
        assertTrue(roundTrip.contains("Register-ScheduledTask"));
        assertTrue(roundTrip.contains("Export-ScheduledTask"));
        assertTrue(roundTrip.contains("Unregister-ScheduledTask"));
        assertTrue(roundTrip.contains("never executed"));
        assertTrue(roundTrip.contains("PROVIDER_CALLS=0"));
        assertTrue(roundTrip.contains("PERMANENT_DATABASE_WRITES=0"));
        assertTrue(roundTrip.contains("CREDENTIAL_READS=0"));
        assertTrue(roundTrip.contains("MSFT_TaskTimeTrigger"));
        assertTrue(roundTrip.contains("Repetition.Interval"));
        assertTrue(roundTrip.contains("PT1M"));
        assertTrue(roundTrip.contains("Logon-only migration probe"));
    }

    @Test
    void watchdogRoundTripProvesPeriodicRecoveryWithoutDemandStart()
            throws Exception {
        String watchdog = read("scripts/host-broker/"
                + "test-stock-quant-host-broker-watchdog-roundtrip.ps1");

        assertTrue(watchdog.contains("StockQuantHostBrokerRoundTrip_"));
        assertTrue(watchdog.contains("-WatchdogStartAt $watchdogStartAt"));
        assertTrue(watchdog.contains("Start-ScheduledTask"));
        assertEquals(1, occurrences(watchdog, "Start-ScheduledTask"));
        assertTrue(watchdog.contains(
                "Stop-Process -Id $firstProcessId -Force"));
        assertTrue(watchdog.indexOf("Start-ScheduledTask")
                < watchdog.indexOf("Stop-Process -Id $firstProcessId"));
        assertFalse(watchdog.substring(watchdog.indexOf(
                "Stop-Process -Id $firstProcessId")).contains(
                "Start-ScheduledTask"));
        assertTrue(watchdog.contains("Get-ScheduledTaskInfo"));
        assertTrue(watchdog.contains("WATCHDOG_AUTO_RECOVERY_TIMEOUT"));
        assertTrue(watchdog.contains("WATCHDOG_SINGLE_INSTANCE=PASS"));
        assertTrue(watchdog.contains("IDLE_CREDENTIAL_READS=0"));
        assertTrue(watchdog.contains("REAL_PROVIDER_CALLS=0"));
        assertTrue(watchdog.contains("PERMANENT_DATABASE_WRITES=0"));
        assertTrue(watchdog.contains("WATCHDOG_RESIDUALS=0"));
        assertFalse(watchdog.contains("StockQuant/ResearchDbPassword"));
        assertFalse(watchdog.contains("StockQuant/TushareToken"));
    }

    @Test
    void fixedInvokerOnlyWritesRequestAndWaitsForResidentBroker()
            throws Exception {
        String script = read("scripts/host-broker/"
                + "invoke-stock-quant-host-broker.ps1");
        String parameters = section(script, "param(", "$ErrorActionPreference");

        assertTrue(parameters.contains("[ValidateSet("));
        assertFalse(parameters.contains("Password"));
        assertFalse(parameters.contains("Token"));
        assertFalse(parameters.contains("Command"));
        assertFalse(parameters.contains("ScriptPath"));
        assertTrue(script.contains("CODEX_SANDBOX_REQUIRED"));
        assertTrue(script.contains("$isCodexSandbox"));
        assertTrue(script.contains("$isResidentUser"));
        assertTrue(script.contains(
                "$identity -ceq [string]$Heartbeat.windowsUser"));
        assertFalse(script.contains(
                "$Operation -ne 'RUN_M2_STRATEGY_RESEARCH_SMOKE'"));
        assertTrue(script.contains(
                "git ls-remote --exit-code origin $remoteRef"));
        assertTrue(script.contains(
                "STOCK_QUANT_HOST_BROKER_GIT_REMOTE_QUERY_FAILED"));
        assertFalse(script.contains("git fetch"));
        assertTrue(script.contains("$tracking -ne $remote"));
        assertTrue(script.contains("Read-StockQuantHostBrokerHeartbeat"));
        assertTrue(script.contains("HOST_BROKER_NOT_RUNNING")
                || read("scripts/host-broker/StockQuantHostBroker.Protocol.psm1")
                .contains("HOST_BROKER_NOT_RUNNING"));
        assertFalse(script.contains("schtasks"));
        assertFalse(script.contains("Get-ScheduledTask"));
        assertFalse(script.contains("Start-ScheduledTask"));
        assertFalse(script.contains("Invoke-Expression"));
        assertFalse(script.contains("ScriptBlock]::Create"));
        assertTrue(script.contains("Write-StockQuantHostBrokerRequest"));
        assertTrue(script.contains("no.retry' = 'true'"));
    }

    @Test
    void protocolIsExactAtomicExpiringAndRepositoryScoped() throws Exception {
        String protocol = read("scripts/host-broker/"
                + "StockQuantHostBroker.Protocol.psm1");

        for (String operation : List.of(
                "CHECK_CREDENTIAL_STATUS", "RUN_FAKE_E2E", "RUN_DAY001",
                "CHECK_BAILIAN_CREDENTIAL_STATUS",
                "RUN_M3_AGENT_RESEARCH_SMOKE",
                "RUN_M4_SHADOW_RESEARCH",
                "START_RESEARCH_PRODUCTION",
                "STOP_RESEARCH_PRODUCTION",
                "CHECK_RESEARCH_PRODUCTION_STATUS",
                "READ_SANITIZED_RESULT")) {
            assertTrue(protocol.contains("'" + operation + "'"), operation);
        }
        for (String field : List.of(
                "request.id", "git.commit", "jar.path", "jar.sha256",
                "authorization.file", "day001.mode", "security.symbol",
                "security.exchange", "trade.date", "database.host",
                "provider.endpoints",
                "maximum.provider.requests", "created.at", "expires.at",
                "execution.source", "no.retry")) {
            assertTrue(protocol.contains("'" + field + "'"), field);
        }
        assertTrue(protocol.contains("stock-quant-host-broker"));
        assertTrue(protocol.contains("'requests'"));
        assertTrue(protocol.contains("'results'"));
        assertTrue(protocol.contains("'heartbeat.json'"));
        assertTrue(protocol.contains("STOCK_QUANT_HOST_BROKER_RESIDENT_V1"));
        assertTrue(protocol.contains("Read-StockQuantHostBrokerHeartbeat"));
        assertTrue(protocol.contains("[IO.FileShare]::Delete"));
        assertTrue(protocol.contains(
                "Local\\StockQuantHostBrokerHeartbeatWriter"));
        assertTrue(protocol.contains("HEARTBEAT_WRITE_FAILED"));
        assertTrue(protocol.contains("[IO.File]::Move"));
        assertTrue(protocol.contains("REQUEST_ID_ALREADY_USED"));
        assertTrue(protocol.contains("REQUEST_EXPIRED"));
        assertTrue(protocol.contains("REQUEST_FIELDS_INVALID"));
        assertTrue(protocol.contains("REQUEST_SECRET_FIELD_FORBIDDEN"));
        assertTrue(protocol.contains(".ai"));
    }

    @Test
    void residentBrokerClaimsRequestsAndNeverAcceptsDynamicExecution()
            throws Exception {
        String script = read("scripts/host-broker/"
                + "stock-quant-host-broker.ps1");

        assertTrue(script.contains("param()"));
        assertTrue(script.contains("Local\\StockQuantLocalBroker"));
        assertTrue(script.contains(".request.properties"));
        assertTrue(script.contains(".processing.properties"));
        assertTrue(script.contains(".processed.properties"));
        assertTrue(script.contains("[IO.File]::Move"));
        assertTrue(script.contains("while ($true)"));
        assertTrue(script.contains("Write-BrokerHeartbeat -State IDLE"));
        assertTrue(script.contains("Write-BrokerHeartbeat -State BUSY"));
        assertTrue(script.contains("Start-Sleep -Milliseconds"));
        assertTrue(script.contains("Start-BusyHeartbeatPump"));
        assertTrue(script.contains("Stop-BusyHeartbeatPump"));
        assertTrue(script.contains("Register-ObjectEvent"));
        assertTrue(script.contains("RUN_DAY001"));
        assertTrue(script.contains("RUN_FAKE_E2E"));
        assertTrue(script.contains("CHECK_BAILIAN_CREDENTIAL_STATUS"));
        assertTrue(script.contains("RUN_M3_AGENT_RESEARCH_SMOKE"));
        assertTrue(script.contains("RUN_M4_SHADOW_RESEARCH"));
        assertTrue(script.contains("BailianCredentialHealthProbe"));
        assertTrue(script.contains("qwen3.7-plus"));
        assertTrue(script.contains("Get-M3BailianStageBudget"));
        assertTrue(script.contains("M3_BAILIAN_STAGE_BUDGET_EXHAUSTED"));
        assertTrue(script.contains("M3_BAILIAN_TRANCHE_2"));
        assertTrue(script.contains("approvalMarker"));
        assertTrue(script.contains(
                "ConvertTo-StockQuantM3CallTelemetrySummary"));
        assertTrue(script.contains("legacyFailureReserve"));
        assertTrue(script.contains("Get-M4MonthlyBudget"));
        assertTrue(script.contains("run-m4-shadow-research.ps1"));
        assertTrue(script.contains("modelProviderMessageCategory"));
        assertFalse(script.contains("Invoke-Expression"));
        String production = section(script,
                "function Resolve-ResearchProductionJavaExecutable",
                "function Read-SanitizedBrokerResult");
        assertTrue(production.contains(
                "Resolve-ResearchProductionJavaExecutable"));
        assertTrue(production.contains(
                "Start-Process -FilePath $javaExecutable"));
        assertTrue(production.contains("java\\.home"));
        assertTrue(production.contains("M6_JAVA_17_RUNTIME_INVALID"));
        assertTrue(production.contains("-ArgumentList @('-jar'"));
        assertFalse(production.contains("Credential"));
        assertFalse(production.contains("Provider"));
        assertFalse(script.contains("ScriptBlock]::Create"));
        assertFalse(script.contains("-Command"));
    }

    @Test
    void residentLifecycleTestsAutoClaimAndNeverReplayClaimedRequests()
            throws Exception {
        String lifecycle = read("scripts/host-broker/"
                + "test-stock-quant-host-broker-resident.ps1");
        String support = read("scripts/host-broker/"
                + "StockQuantHostBroker.TestSupport.psm1");

        assertTrue(lifecycle.contains("RESIDENT_AUTO_CLAIM=PASS"));
        assertTrue(lifecycle.contains("HEARTBEAT_CONTENTION=PASS"));
        assertTrue(lifecycle.contains("CLAIMED_REPLAY_COUNT=0"));
        assertTrue(lifecycle.contains("REQUEST_EXPIRED"));
        assertTrue(lifecycle.contains("command.text=forbidden"));
        assertTrue(lifecycle.contains("README.md"));
        assertTrue(lifecycle.contains("IDLE_CREDENTIAL_READS=0"));
        assertTrue(lifecycle.contains("REAL_PROVIDER_CALLS=0"));
        assertTrue(support.contains("Start-Process"));
        assertTrue(support.contains("Stop-Process -Id $processId"));
        assertFalse(support.contains("schtasks"));
    }

    @Test
    void fakeOperationCannotTouchCredentialsAndDay001RequiresPreflight()
            throws Exception {
        String script = read("scripts/host-broker/"
                + "stock-quant-host-broker.ps1");
        String build = read("scripts/prepare-f1f-b2-build-proof.ps1");
        String packagedE2e = read(
                "scripts/run-reduced-research-day001-e2e-dry-run.ps1");
        String fake = section(script, "function Invoke-FakeE2e",
                "function Invoke-Day001");
        String day001 = section(script, "function Invoke-Day001",
                "function Read-SanitizedBrokerResult");
        String postgresStart = section(packagedE2e,
                "function Start-TemporaryPostgres", "function Assert-Exact");
        String expectedFailureRunner = section(packagedE2e,
                "function Invoke-JavaRunner", "function Assert-SuccessResult");

        assertTrue(script.contains(
                "run-reduced-research-day001-e2e-dry-run.ps1"));
        assertTrue(fake.contains("$fakeE2eScript"));
        assertTrue(fake.contains("REAL_PROVIDER_CALLS=0"));
        assertFalse(fake.contains("$LASTEXITCODE"));
        assertTrue(fake.contains("$ErrorActionPreference = 'Continue'"));
        assertTrue(fake.contains(
                "$ErrorActionPreference = $previousErrorActionPreference"));
        assertTrue(fake.contains("ForEach-Object { [string]$_ }"));
        assertFalse(fake.contains("Credential"));
        assertFalse(fake.contains("set-stock-quant-secrets"));
        assertTrue(day001.indexOf("Assert-UserApprovedPreflight")
                < day001.indexOf("$hostRunnerScript"));
        assertTrue(day001.contains("providerCallCount -ne 3"));
        assertTrue(day001.contains("retryCount -ne 0"));
        assertTrue(build.contains("[Environment]::OSVersion.Platform"));
        assertFalse(build.contains("$IsLinux"));
        assertFalse(build.contains("$IsMacOS"));
        assertTrue(postgresStart.contains("-RedirectStandardOutput"));
        assertTrue(postgresStart.contains("-RedirectStandardError"));
        assertTrue(postgresStart.contains("[void]$process.Handle"));
        assertTrue(postgresStart.contains("$process.WaitForExit()"));
        assertTrue(postgresStart.contains("$script:started = $true"));
        assertFalse(postgresStart.contains("-Wait"));
        assertTrue(expectedFailureRunner.contains(
                "$ErrorActionPreference = 'Continue'"));
        assertTrue(expectedFailureRunner.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(expectedFailureRunner.contains("return $exitCode"));
    }

    @Test
    void directFormalRunnerRejectsSandboxAndBrokerResultIsSanitized()
            throws Exception {
        String direct = read("scripts/run-stock-quant-local-automation.ps1");
        String protocol = read("scripts/host-broker/"
                + "StockQuantHostBroker.Protocol.psm1");

        assertTrue(direct.contains("STOCK_QUANT_HOST_BROKER_REQUIRED"));
        assertTrue(direct.indexOf("$stage = 'HOST_CONTEXT'")
                < direct.indexOf("$stage = 'PATH_VALIDATION'"));
        assertTrue(protocol.contains("RESULT_SECRET_FIELD_FORBIDDEN"));
        assertTrue(protocol.contains("ConvertTo-Json"));
        assertFalse(protocol.contains("ConvertFrom-SecureString"));
        assertFalse(protocol.contains("SetEnvironmentVariable"));
    }

    @Test
    void codexProfilesHaveNoDirectTushareNetworkPermission() throws Exception {
        String config = read("../.codex/config.toml");

        assertTrue(config.contains("[permissions.stock_quant_formal_runner]"));
        assertTrue(config.contains("extends = \"stock_quant_local\""));
        assertFalse(config.contains("api.tushare.pro"));
        assertFalse(config.contains("danger-full-access"));
    }

    @Test
    void sevenGovernanceStatesRemainUnchanged() throws Exception {
        String state = read("../docs/agent-team/CURRENT_STATE.md");
        for (String expected : List.of(
                "CONTROLLED_ACCEPTANCE_STATUS=PASSED",
                "REDUCED_RESEARCH_OPERATIONAL_READY=true",
                "F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE",
                "FREE_PRODUCT_PREVIEW_GATE=PASS",
                "FREE_PROVIDER_VALIDATION_GATE=BLOCKED",
                "PAID_PROVIDER_UPGRADE_DECISION=PENDING",
                "IFIND_TRIAL_ACTIVATION_GATE=BLOCKED")) {
            assertTrue(state.contains(expected), expected);
        }
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, start + " -> " + end);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
