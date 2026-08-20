package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.MainboardDailyIncrementSanitizedResult.Result;
import com.stockquant.server.agent.marketfacts.MainboardDailyIncrementSanitizedResult.ResultFile;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fixed data-only main-board increment; never starts Spring or an Agent. */
public final class TushareMainboardDailyIncrementManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final int FORMAL_PORT = 38_432;

    private TushareMainboardDailyIncrementManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Instant startedAt = clock.instant();
        Arguments launch = null;
        ResultFile resultFile = null;
        String commit = "UNKNOWN";
        MainboardDailyIncrementService.Progress progress =
                new MainboardDailyIncrementService.Progress();
        try {
            launch = Arguments.parse(args);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            commit = proof.gitCommit();
            validateProof(proof, launch);
            resultFile = ResultFile.reserve(launch.resultFile(), failure(
                    launch, commit, startedAt, startedAt, progress,
                    "MAINBOARD_DAILY_INCREMENT_RUNNING", false));
            Arguments bound = launch;
            Captured<MainboardDailyIncrementService.Outcome> captured =
                    launch.mode() == ExecutionMode.FAKE
                            ? fake(bound, clock, progress)
                            : formal(bound, clock, progress);
            if (!captured.auditResult().clean()) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_OUTPUT_AUDIT_FAILED");
            }
            var outcome = captured.value();
            var snapshot = outcome.snapshot().snapshot();
            resultFile.write(new Result(
                    MainboardDailyIncrementSanitizedResult.VERSION,
                    "SUCCEEDED", launch.executionId(), commit,
                    launch.tradeDate(), startedAt, clock.instant(),
                    snapshot.universeVersion(), snapshot.snapshotId(),
                    snapshot.memberFingerprint(), snapshot.memberCount(),
                    snapshot.sseCount(), snapshot.szseCount(),
                    snapshot.stCount(), outcome.providerCalls(),
                    outcome.providerCalls() > 0 ? 1 : 0,
                    outcome.providerCalls() > 1 ? 1 : 0,
                    outcome.retryCount(), outcome.batchIds(),
                    outcome.dailyAdded(), outcome.factorAdded(),
                    outcome.appended(), outcome.idempotent(),
                    outcome.dailyVisible(), outcome.factorVisible(),
                    outcome.validation().duplicateCount(),
                    outcome.validation().coverageComplete(),
                    outcome.validation().knownAtValid(), true, true,
                    outcome.latestCompleteDate(), 0, 0, 0, 0, 0,
                    true, launch.mode() == ExecutionMode.FAKE, true,
                    false, null));
            System.out.println("MAINBOARD_DAILY_INCREMENT_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException error) {
            writeFailure(resultFile, launch, commit, startedAt, clock,
                    progress, error.getCause(), error.auditResult() != null
                            && error.auditResult().clean());
            return EXIT_REJECTED;
        } catch (Throwable error) {
            writeFailure(resultFile, launch, commit, startedAt, clock,
                    progress, error, false);
            return EXIT_REJECTED;
        }
    }

    private static Captured<MainboardDailyIncrementService.Outcome> fake(
            Arguments launch,
            Clock clock,
            MainboardDailyIncrementService.Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureControlledProcess(registry -> {
                    char[] password = "MAINBOARD_INCREMENT_E2E_DB".toCharArray();
                    char[] token = "MAINBOARD_INCREMENT_E2E_TOKEN".toCharArray();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD,
                                password);
                        registry.register(SensitiveKind.TUSHARE_TOKEN, token);
                        return execute(launch, password, token, clock, progress);
                    } finally {
                        Arrays.fill(password, '\0');
                        Arrays.fill(token, '\0');
                    }
                });
    }

    private static Captured<MainboardDailyIncrementService.Outcome> formal(
            Arguments launch,
            Clock clock,
            MainboardDailyIncrementService.Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureControlledProcess(registry -> {
                    try (SecretProvider secrets =
                                 CompositeSecretProvider.formalLocal(
                                         Mode.WINDOWS_CREDENTIAL_MANAGER);
                         SecretValue database =
                                 secrets.readResearchDatabasePassword();
                         SecretValue tushare = secrets.readTushareToken()) {
                        char[] password = database.copy();
                        char[] token = tushare.copy();
                        try {
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            registry.register(SensitiveKind.TUSHARE_TOKEN,
                                    token);
                            return execute(launch, password, token, clock,
                                    progress);
                        } finally {
                            Arrays.fill(password, '\0');
                            Arrays.fill(token, '\0');
                        }
                    }
                });
    }

    private static MainboardDailyIncrementService.Outcome execute(
            Arguments launch,
            char[] password,
            char[] token,
            Clock clock,
            MainboardDailyIncrementService.Progress progress
    ) {
        try (TushareControlledAcceptanceDataSource dataSource =
                     new TushareControlledAcceptanceDataSource(
                             launch.databasePort(),
                             TushareControlledAcceptanceDataSource.SslMode
                                     .DISABLE_LOCAL_ONLY, password)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (launch.mode() == ExecutionMode.FORMAL) verifyDedicated(jdbc);
            Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .loggers(SilentFlywayLogCreator.class.getName())
                    .load().migrate();
            verifyDedicated(jdbc);
            requireSchema(jdbc);
            try (var components = launch.mode() == ExecutionMode.FAKE
                    ? TushareDedicatedResearchRuntimeComponents
                    .createE2eDryRun(dataSource, clock)
                    : TushareDedicatedResearchRuntimeComponents.create(
                    dataSource, token.clone(), clock)) {
                if (launch.mode() == ExecutionMode.FAKE
                        && new ResearchUniverseMainboardRepository(jdbc)
                        .latest().isEmpty()) {
                    // Package-level dry runs start from an isolated V18
                    // database. Seed only the immutable fake universe and
                    // calendar prerequisites; the operation under test still
                    // performs exactly daily + adj_factor (two calls).
                    components.mainboardUniverseCaptureService().capture(
                            null, true, Set.of(), launch.tradeDate(),
                            launch.tradeDate(), true, launch.gitCommit(),
                            MainboardDailyIncrementService.PROVIDER_TIMEOUT,
                            0);
                }
                var service = new MainboardDailyIncrementService(jdbc,
                        new ObjectMapper().findAndRegisterModules(),
                        components.mainboardUniverseCaptureService(), clock);
                return service.execute(launch.tradeDate(),
                        launch.gitCommit(), progress);
            }
        }
    }

    private static void verifyDedicated(JdbcTemplate jdbc) {
        new TushareDedicatedResearchPersistenceGuard(jdbc,
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                .verifyBeforeProvider();
    }

    private static void requireSchema(JdbcTemplate jdbc) {
        Integer version = jdbc.queryForObject("""
                SELECT COALESCE(max(version::integer), 0)
                  FROM tushare_research.flyway_schema_history WHERE success
                """, Integer.class);
        if (version == null || version != 18) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_SCHEMA_VERSION_INVALID");
        }
    }

    private static void validateProof(
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof proof,
            Arguments launch
    ) {
        if (!launch.gitCommit().equals(proof.gitCommit())
                || !TushareControlledAcceptanceBuildProof
                .MAINBOARD_DAILY_INCREMENT_RUNNER_START_CLASS.equals(
                        proof.runnerStartClass())
                || !(launch.mode() == ExecutionMode.FAKE
                ? proof.e2eDryRunEligible()
                : proof.mainboardDailyIncrementEligible())) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static void writeFailure(
            ResultFile resultFile,
            Arguments launch,
            String commit,
            Instant startedAt,
            Clock clock,
            MainboardDailyIncrementService.Progress progress,
            Throwable error,
            boolean auditClean
    ) {
        String reason = safeCode(error);
        if (resultFile != null && launch != null) {
            resultFile.write(failure(launch, commit, startedAt,
                    clock.instant(), progress, reason, auditClean));
        }
        System.err.println("MAINBOARD_DAILY_INCREMENT_FAILURE_REASON=" + reason);
    }

    private static Result failure(
            Arguments launch,
            String commit,
            Instant startedAt,
            Instant completedAt,
            MainboardDailyIncrementService.Progress progress,
            String reason,
            boolean auditClean
    ) {
        int dailyCalls = progress.providerCalls > 0 ? 1 : 0;
        int factorCalls = progress.providerCalls > 1 ? 1 : 0;
        return new Result(MainboardDailyIncrementSanitizedResult.VERSION,
                "FAILED", launch.executionId(), commit, launch.tradeDate(),
                startedAt, completedAt, ResearchUniverseMainboard.VERSION,
                null, null, 0, 0, 0, 0, progress.providerCalls,
                dailyCalls, factorCalls, progress.retryCount,
                progress.batchIds, 0, 0, progress.appended,
                progress.idempotent, 0, 0, 0, false, false, false,
                false, null, 0, 0, 0, 0, 0, auditClean,
                launch.mode() == ExecutionMode.FAKE, true, false, reason);
    }

    private static String safeCode(Throwable error) {
        for (Throwable current = error; current != null;
             current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.matches(
                    "[A-Z][A-Z0-9_]{3,127}")) return message;
        }
        return "MAINBOARD_DAILY_INCREMENT_EXECUTION_FAILED";
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    enum ExecutionMode { FAKE, FORMAL }

    record Arguments(
            Path resultFile,
            String executionId,
            String gitCommit,
            LocalDate tradeDate,
            int databasePort,
            int maximumProviderRequests,
            ExecutionMode mode
    ) {
        static Arguments parse(String[] args) {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw invalid("MAINBOARD_DAILY_INCREMENT_ARGUMENTS_INVALID");
                }
                int split = arg.indexOf('=');
                if (values.put(arg.substring(2, split),
                        arg.substring(split + 1)) != null) {
                    throw invalid("MAINBOARD_DAILY_INCREMENT_ARGUMENTS_INVALID");
                }
            }
            if (!values.keySet().equals(java.util.Set.of("result-file",
                    "execution-id", "git-commit", "trade-date",
                    "database-port", "maximum-provider-requests",
                    "execution-mode"))) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_ARGUMENTS_INVALID");
            }
            try {
                Arguments value = new Arguments(
                        Path.of(values.get("result-file")),
                        values.get("execution-id"), values.get("git-commit"),
                        LocalDate.parse(values.get("trade-date")),
                        Integer.parseInt(values.get("database-port")),
                        Integer.parseInt(values.get(
                                "maximum-provider-requests")),
                        ExecutionMode.valueOf(values.get("execution-mode")
                                .toUpperCase(Locale.ROOT)));
                value.validate();
                return value;
            } catch (RuntimeException error) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_ARGUMENTS_INVALID");
            }
        }

        private void validate() {
            if (!executionId.matches(
                    "MBINC_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || !gitCommit.matches("[0-9a-f]{40}")
                    || databasePort < 1 || databasePort > 65_535
                    || mode == ExecutionMode.FORMAL
                    && databasePort != FORMAL_PORT
                    || mode == ExecutionMode.FAKE
                    && databasePort == FORMAL_PORT
                    || maximumProviderRequests != 2) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_ARGUMENTS_INVALID");
            }
        }
    }
}
