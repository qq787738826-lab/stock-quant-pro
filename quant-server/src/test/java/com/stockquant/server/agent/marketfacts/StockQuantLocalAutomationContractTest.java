package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockQuantLocalAutomationContractTest {

    @Test
    void setupScriptUsesNativeSecureInputAndOnlyThreeCredentialTargets()
            throws Exception {
        String script = read("scripts/set-stock-quant-secrets.ps1");
        String parameters = script.substring(
                script.indexOf("param("),
                script.indexOf("$ErrorActionPreference"));

        assertFalse(parameters.contains("Password"));
        assertFalse(parameters.contains("Token"));
        assertTrue(script.contains("Read-Host"));
        assertTrue(script.contains("-AsSecureString"));
        assertTrue(script.contains("CredWriteW"));
        assertTrue(script.contains("CredReadW"));
        assertTrue(script.contains("CredFree"));
        assertTrue(script.contains("ZeroFreeCoTaskMemUnicode"));
        assertTrue(script.contains("StockQuant/ResearchDbPassword"));
        assertTrue(script.contains("StockQuant/TushareToken"));
        assertTrue(script.contains("StockQuant/BailianApiKey"));
        assertFalse(script.contains("OpenAi"));
        assertTrue(script.contains("OVERWRITE"));
        assertTrue(parameters.contains("ProviderOnly"));
        assertTrue(parameters.contains("BailianOnly"));
        assertTrue(parameters.contains("BailianStatus"));
        assertTrue(script.contains(
                "STOCK_QUANT_PROVIDER_CREDENTIAL_UPDATED=true"));
        assertTrue(script.contains("if (-not $ProviderOnly)"));
        assertTrue(script.contains("STOCK_QUANT_CREDENTIALS_READY"));
        assertFalse(script.contains("ConvertFrom-SecureString"));
        assertFalse(script.contains("SetEnvironmentVariable"));
        assertFalse(script.contains("WriteAllText"));
    }

    @Test
    void unifiedEntryOrdersPreflightBeforeCredentialAndRunner()
            throws Exception {
        String script = read("scripts/run-stock-quant-local-automation.ps1");
        int preflight = script.indexOf("$stage = 'ARTIFACT_PREFLIGHT'");
        int credentials = script.indexOf("$stage = 'CREDENTIAL_STATUS'");
        int runner = script.indexOf("$stage = 'DAY001_RUNNER'");
        int readback = script.indexOf("$stage = 'RESULT_READBACK'");

        assertTrue(preflight >= 0 && preflight < credentials);
        assertTrue(credentials < runner && runner < readback);
        assertTrue(script.contains("TushareReducedResearchDay001Preflight"));
        assertTrue(script.contains("TushareReducedResearchManualRunner"));
        assertTrue(script.contains("Get-FileHash -LiteralPath $artifact"));
        assertTrue(script.contains(
                "TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_PATH"));
        assertTrue(script.contains("127.0.0.1', 38432"));
        assertTrue(script.contains("git fetch --quiet origin"));
        assertTrue(script.contains("Get-Content -LiteralPath $result"));
        assertTrue(script.contains("STOCK_QUANT_AUTOMATION_FAILURE_STAGE"));
        assertTrue(script.contains("STOCK_QUANT_AUTOMATION_FAILURE_REASON"));
        assertFalse(script.contains("TushareControlledAcceptanceRunner"));
        assertFalse(script.contains("QuantServerApplication"));
        assertFalse(script.contains("Invoke-RestMethod"));
    }

    @Test
    void day001AndPreflightRemainPlainJavaWithoutAutomationRegistration()
            throws Exception {
        for (String source : List.of(
                read("src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareReducedResearchManualRunner.java"),
                read("src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareReducedResearchDay001Preflight.java"))) {
            assertFalse(source.contains("SpringApplication"));
            assertFalse(source.contains("@Component"));
            assertFalse(source.contains("@Service"));
            assertFalse(source.contains("@Bean"));
            assertFalse(source.contains("@Scheduled"));
            assertFalse(source.contains("@Controller"));
            assertFalse(source.contains("ApplicationRunner"));
            assertFalse(source.contains("CommandLineRunner"));
        }
    }

    @Test
    void buildProfileBindsDay001StartClassWithoutChangingF1Default()
            throws Exception {
        String build = read("scripts/prepare-f1f-b2-build-proof.ps1");
        String wrapper = read(
                "scripts/prepare-reduced-research-day001-build-proof.ps1");
        String launch = read("scripts/run-reduced-research-day001.ps1");

        assertTrue(build.contains("[string] $RunnerProfile = 'F1F_B2'"));
        assertTrue(build.contains("REDUCED_RESEARCH_DAY001"));
        assertTrue(build.contains("-Dstart-class=$runnerStartClass"));
        assertTrue(build.contains("TushareControlledAcceptanceRunner"));
        assertTrue(build.contains("TushareReducedResearchManualRunner"));
        assertTrue(wrapper.contains("-RunnerProfile REDUCED_RESEARCH_DAY001"));
        assertTrue(launch.contains(
                "quant-server-1.3.1-reduced-research-day001-runner.jar"));
        assertTrue(launch.contains(
                "[string] $SecretMode = 'WINDOWS_CREDENTIAL_MANAGER'"));
        assertTrue(launch.contains("--secret-mode=$SecretMode"));
    }

    @Test
    void projectCodexPolicyIsRepositoryScopedAndNeverDisablesSandbox()
            throws Exception {
        String config = read("../.codex/config.toml");
        String agents = read("../AGENTS.md");

        assertTrue(config.contains("default_permissions = \"stock_quant_local\""));
        assertTrue(config.contains("extends = \":read-only\""));
        assertTrue(config.contains("\"D:/GitHub/stock-quant-pro\" = \"write\""));
        assertTrue(config.contains("\".ai/**\" = \"deny\""));
        assertTrue(config.contains("\"**/.env\" = \"deny\""));
        assertTrue(config.contains("C:/Users/*/.ssh/**"));
        assertTrue(config.contains("Microsoft/Credentials/**"));
        assertTrue(config.contains("\"127.0.0.1\" = \"allow\""));
        assertTrue(config.contains("[permissions.stock_quant_formal_runner]"));
        assertFalse(config.contains("api.tushare.pro"));
        assertFalse(config.contains("danger-full-access"));
        assertTrue(agents.contains("run-stock-quant-local-automation.ps1"));
        assertTrue(agents.contains("不得枚举凭据"));
        assertTrue(agents.contains("暂存或上传"));
    }

    @Test
    void controlledBuildAndPostgresTestsKeepTemporaryWritesInRepository()
            throws Exception {
        for (String path : List.of(
                "scripts/prepare-f1f-b2-build-proof.ps1",
                "scripts/run-reduced-research-day001-e2e-dry-run.ps1",
                "scripts/run-f1f-b2-e2e-dry-run.ps1",
                "scripts/run-f1f-b2-transaction-postgres-tests.ps1",
                "scripts/run-f1f-b2-readback-postgres-tests.ps1")) {
            String script = read(path);
            assertTrue(script.contains("quant-server\\target"), path);
            assertFalse(script.contains("GetTempPath"), path);
            assertFalse(script.contains("$env:TEMP"), path);
        }
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

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
