package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveRegistry;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Authorization.AuthorizationMode;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Authorization.Day001Mode;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.ResultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TushareReducedResearchManualRunnerTest {
    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void successfulManualRunWritesOnlyOperationalResultAndReturnsZero()
            throws Exception {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.IDEMPOTENCY_VERIFICATION),
                ExecutionBehavior.SUCCESS);
        Path result = temporary.resolve("success.json");

        int exit = TushareReducedResearchManualRunner.run(
                arguments(result), environment);

        assertEquals(0, exit);
        JsonNode json = new ObjectMapper().readTree(result.toFile());
        assertEquals("SUCCEEDED", json.path("status").asText());
        assertEquals(3, json.path("providerCallCount").asInt());
        assertEquals(0, json.path("retryCount").asInt());
        assertEquals(3, json.path("existingChainTailCount").asInt());
        assertTrue(json.path("outputAudit").path("clean").asBoolean());
        assertFalse(json.path("passedAcceptanceStatusProduced").asBoolean());
        assertFalse(json.path("operationalReadinessModified").asBoolean());
        assertEquals(1, environment.executeCount);
        assertEquals(1, environment.consumeCount);
    }

    @Test
    void expiredAuthorizationStopsBeforeBuildConsumptionAndExecution() {
        var expired = TushareReducedResearchDay001AuthorizationTest.authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(600), NOW.minusSeconds(1));
        var environment = new FakeEnvironment(expired, ExecutionBehavior.SUCCESS);

        int exit = TushareReducedResearchManualRunner.run(
                arguments(temporary.resolve("expired.json")), environment);

        assertEquals(20, exit);
        assertEquals(0, environment.buildCount);
        assertEquals(0, environment.consumeCount);
        assertEquals(0, environment.executeCount);
    }

    @Test
    void buildMismatchStopsBeforeConsumptionProviderAndResultReservation() {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.SUCCESS);
        environment.buildMismatch = true;
        Path result = temporary.resolve("build-mismatch.json");

        int exit = TushareReducedResearchManualRunner.run(
                arguments(result), environment);

        assertEquals(20, exit);
        assertEquals(1, environment.buildCount);
        assertEquals(0, environment.consumeCount);
        assertEquals(0, environment.executeCount);
        assertFalse(Files.exists(result));
    }

    @Test
    void databaseFailureIsPersistedAsFailedPreProvider() throws Exception {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.DATABASE_FAILURE);
        Path result = temporary.resolve("database-failure.json");

        int exit = TushareReducedResearchManualRunner.run(
                arguments(result), environment);

        assertEquals(20, exit);
        JsonNode json = new ObjectMapper().readTree(result.toFile());
        assertEquals("FAILED_PRE_PROVIDER", json.path("status").asText());
        assertEquals(0, json.path("providerCallCount").asInt());
        assertEquals("TUSHARE_REDUCED_RESEARCH_DATABASE_MISMATCH",
                json.path("safeFailureCode").asText());
        assertEquals(1, environment.executeCount);
    }

    @Test
    void secretOutputFailsTheAuditWithoutProducingAcceptancePass()
            throws Exception {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.IDEMPOTENCY_VERIFICATION),
                ExecutionBehavior.LEAK_SECRET);
        Path result = temporary.resolve("audit-failure.json");

        int exit = TushareReducedResearchManualRunner.run(
                arguments(result), environment);

        assertEquals(20, exit);
        JsonNode json = new ObjectMapper().readTree(result.toFile());
        assertEquals("FAILED_OUTPUT_AUDIT", json.path("status").asText());
        assertFalse(json.path("outputAudit").path("clean").asBoolean());
        assertTrue(json.path("outputAudit").path("hitCount").asInt() > 0);
        assertFalse(json.path("passedAcceptanceStatusProduced").asBoolean());
        assertFalse(json.path("operationalReadinessModified").asBoolean());
    }

    @Test
    void alreadyConsumedAuthorizationStopsBeforeExecution() throws Exception {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.SUCCESS);
        environment.alreadyConsumed = true;
        Path result = temporary.resolve("consumed.json");

        int exit = TushareReducedResearchManualRunner.run(
                arguments(result), environment);

        assertEquals(20, exit);
        JsonNode json = new ObjectMapper().readTree(result.toFile());
        assertEquals("FAILED_PRE_PROVIDER", json.path("status").asText());
        assertEquals("TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_ALREADY_CONSUMED",
                json.path("safeFailureCode").asText());
        assertEquals(0, environment.executeCount);
    }

    @Test
    void aiPathsAndMissingArgumentsAreRejectedBeforeAuthorizationRead() {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.SUCCESS);

        assertEquals(20, TushareReducedResearchManualRunner.run(
                new String[]{"--authorization-file="
                        + temporary.resolve(".ai/auth.properties"),
                        "--result-file=" + temporary.resolve("result.json")},
                environment));
        assertEquals(20, TushareReducedResearchManualRunner.run(
                new String[0], environment));
        assertEquals(0, environment.loadCount);
        assertEquals(0, environment.executeCount);
    }

    @Test
    void credentialManagerIsDefaultAndConsoleRequiresExplicitMode() {
        var defaultEnvironment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.SUCCESS);
        var consoleEnvironment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.SUCCESS);

        assertEquals(20, TushareReducedResearchManualRunner.run(
                new String[]{"--authorization-file="
                        + temporary.resolve("default-auth.properties"),
                        "--result-file=" + temporary.resolve("default.json")},
                defaultEnvironment));
        assertEquals(Mode.WINDOWS_CREDENTIAL_MANAGER,
                defaultEnvironment.secretMode);
        assertEquals(20, TushareReducedResearchManualRunner.run(
                new String[]{"--authorization-file="
                        + temporary.resolve("console-auth.properties"),
                        "--result-file=" + temporary.resolve("console.json"),
                        "--secret-mode=CONSOLE"}, consoleEnvironment));
        assertEquals(Mode.CONSOLE, consoleEnvironment.secretMode);
    }

    @Test
    void invalidSecretModeStopsBeforeAuthorizationRead() {
        var environment = new FakeEnvironment(
                authorization(Day001Mode.NEW_CAPTURE),
                ExecutionBehavior.SUCCESS);

        assertEquals(20, TushareReducedResearchManualRunner.run(
                new String[]{"--authorization-file="
                        + temporary.resolve("authorization.properties"),
                        "--result-file=" + temporary.resolve("result.json"),
                        "--secret-mode=AUTO"}, environment));
        assertEquals(0, environment.loadCount);
        assertEquals(0, environment.executeCount);
    }

    @Test
    void entryRemainsNonSpringAndCannotReachAcceptanceOrAutomation()
            throws Exception {
        String runner = Files.readString(Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareReducedResearchManualRunner.java"));
        String components = Files.readString(Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareDedicatedResearchRuntimeComponents.java"));
        String launch = Files.readString(Path.of(
                "scripts/run-reduced-research-day001.ps1"));

        for (String source : List.of(runner, components)) {
            assertFalse(source.contains("SpringApplication"));
            assertFalse(source.contains("@Component"));
            assertFalse(source.contains("@Service"));
            assertFalse(source.contains("@Bean"));
            assertFalse(source.contains("@Scheduled"));
            assertFalse(source.contains("@Controller"));
            assertFalse(source.contains("ApplicationRunner"));
            assertFalse(source.contains("CommandLineRunner"));
        }
        assertFalse(runner.contains("TushareControlledAcceptanceExecutor"));
        assertFalse(runner.contains("migrateGovernance"));
        assertFalse(runner.contains("QuantServerApplication"));
        assertFalse(launch.contains("-jar"));
        assertTrue(launch.contains("TushareReducedResearchManualRunner"));
        assertTrue(launch.contains("PropertiesLauncher"));
    }

    private String[] arguments(Path result) {
        return new String[]{
                "--authorization-file=" + temporary.resolve("authorization.properties"),
                "--result-file=" + result
        };
    }

    private static TushareReducedResearchDay001Authorization authorization(
            Day001Mode mode
    ) {
        return TushareReducedResearchDay001AuthorizationTest.authorization(
                AuthorizationMode.E2E_DRY_RUN, mode, 55_432,
                NOW.minusSeconds(5), NOW.plusSeconds(600));
    }

    private enum ExecutionBehavior {
        SUCCESS,
        DATABASE_FAILURE,
        LEAK_SECRET
    }

    private static final class FakeEnvironment
            implements TushareReducedResearchManualRunner.RunnerEnvironment {
        private final TushareReducedResearchDay001Authorization authorization;
        private final ExecutionBehavior behavior;
        private int loadCount;
        private int buildCount;
        private int consumeCount;
        private int executeCount;
        private boolean buildMismatch;
        private boolean alreadyConsumed;
        private Mode secretMode;

        private FakeEnvironment(
                TushareReducedResearchDay001Authorization authorization,
                ExecutionBehavior behavior
        ) {
            this.authorization = authorization;
            this.behavior = behavior;
        }

        @Override
        public Clock clock() {
            return CLOCK;
        }

        @Override
        public TushareReducedResearchDay001Authorization loadAuthorization(
                Path path
        ) {
            loadCount++;
            return authorization;
        }

        @Override
        public VerifiedBuildProof loadBuildProof(
                TushareReducedResearchDay001Authorization ignored
        ) {
            buildCount++;
            return null;
        }

        @Override
        public void validateBuildProof(
                TushareReducedResearchDay001Authorization ignored,
                VerifiedBuildProof proof
        ) {
            if (buildMismatch) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_INVALID");
            }
        }

        @Override
        public ResultFile reserveResult(
                Path path,
                TushareReducedResearchDay001Result initial
        ) {
            return ResultFile.reserve(path, initial);
        }

        @Override
        public void consumeAuthorization(
                Path authorizationFile,
                TushareReducedResearchDay001Authorization ignored,
                Instant consumedAt
        ) {
            if (alreadyConsumed) {
                throw new IllegalStateException(
                        "TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_ALREADY_CONSUMED");
            }
            consumeCount++;
        }

        @Override
        public TushareReducedResearchManualRunner.ExecutionEvidence execute(
                TushareReducedResearchDay001Authorization ignored,
                VerifiedBuildProof proof,
                SensitiveRegistry registry,
                Instant startedAt,
                TushareReducedResearchManualRunner.ExecutionProgress progress,
                Mode secretMode
        ) {
            executeCount++;
            this.secretMode = secretMode;
            char[] databaseSecret = "TEST_DATABASE_SECRET_123".toCharArray();
            char[] token = "TEST_PROVIDER_TOKEN_456".toCharArray();
            registry.register(SensitiveKind.DATABASE_PASSWORD, databaseSecret);
            registry.register(SensitiveKind.TUSHARE_TOKEN, token);
            if (behavior == ExecutionBehavior.DATABASE_FAILURE) {
                throw new IllegalStateException(
                        "TUSHARE_REDUCED_RESEARCH_DATABASE_MISMATCH");
            }
            if (behavior == ExecutionBehavior.LEAK_SECRET) {
                System.err.println(new String(token));
            }
            DatabaseReadbackEvidence readback = new DatabaseReadbackEvidence(
                    4, List.of(1L, 2L, 3L), Map.of(
                    FactType.RAW_DAILY_BAR, 1,
                    FactType.ADJUSTMENT_FACTOR, 1,
                    FactType.TRADING_CALENDAR, 1),
                    NOW, NOW.minusSeconds(60), NOW.minusSeconds(60),
                    NOW.minusSeconds(60), NOW.minusSeconds(60),
                    100, 101, "stock_quant_research", "stock_quant_research",
                    "tushare_research", true, true, true, 3);
            return new TushareReducedResearchManualRunner.ExecutionEvidence(
                    4, 3, 0, 0, 3, 1, readback);
        }
    }
}
