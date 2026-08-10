package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockQuantHostBrokerContractTest {

    @Test
    void installerRegistersOneFixedInteractiveDemandOnlyTask() throws Exception {
        String script = read("scripts/host-broker/"
                + "install-stock-quant-host-broker.ps1");

        assertTrue(script.contains("$taskName = 'StockQuantLocalBroker'"));
        assertTrue(script.contains("stock-quant-host-broker.ps1"));
        assertTrue(script.contains("New-ScheduledTaskPrincipal"));
        assertTrue(script.contains("-LogonType Interactive"));
        assertTrue(script.contains("-RunLevel Limited"));
        assertTrue(script.contains("Register-ScheduledTask"));
        assertTrue(script.contains(
                "Assert-TaskDefinition -Task $definition"));
        assertFalse(script.contains("New-ScheduledTaskTrigger"));
        assertFalse(script.contains("-LogonType Password"));
        assertFalse(script.contains("/RP"));
        assertTrue(script.contains("$triggers.Count -ne 0"));
        assertTrue(script.contains("STOCK_QUANT_HOST_BROKER_TRIGGERS=0"));
        assertTrue(script.contains("[switch] $Uninstall"));
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
    void sandboxInvokerCanOnlyTriggerFixedTaskWithNonSecretRequest()
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
        assertTrue(script.contains(
                "& schtasks.exe /Run /TN 'StockQuantLocalBroker'"));
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
        assertTrue(protocol.contains("[IO.File]::Move"));
        assertTrue(protocol.contains("REQUEST_ID_ALREADY_USED"));
        assertTrue(protocol.contains("REQUEST_EXPIRED"));
        assertTrue(protocol.contains("REQUEST_FIELDS_INVALID"));
        assertTrue(protocol.contains("REQUEST_SECRET_FIELD_FORBIDDEN"));
        assertTrue(protocol.contains(".ai"));
    }

    @Test
    void brokerClaimsOneRequestAndNeverAcceptsDynamicExecution() throws Exception {
        String script = read("scripts/host-broker/"
                + "stock-quant-host-broker.ps1");

        assertTrue(script.contains("param()"));
        assertTrue(script.contains("Local\\StockQuantLocalBroker"));
        assertTrue(script.contains(".request.properties"));
        assertTrue(script.contains(".processing.properties"));
        assertTrue(script.contains(".processed.properties"));
        assertTrue(script.contains("[IO.File]::Move"));
        assertTrue(script.contains("RUN_DAY001"));
        assertTrue(script.contains("RUN_FAKE_E2E"));
        assertFalse(script.contains("Invoke-Expression"));
        assertFalse(script.contains("Start-Process"));
        assertFalse(script.contains("ScriptBlock]::Create"));
        assertFalse(script.contains("-Command"));
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

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
