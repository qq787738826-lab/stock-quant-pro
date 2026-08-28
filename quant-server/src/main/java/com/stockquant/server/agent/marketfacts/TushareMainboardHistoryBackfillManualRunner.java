package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.MainboardHistoryBackfillSanitizedResult.Result;
import com.stockquant.server.agent.marketfacts.MainboardHistoryBackfillSanitizedResult.ResultFile;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fixed data-only 250-session history backfill; never starts Spring or Agents. */
public final class TushareMainboardHistoryBackfillManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final int FORMAL_PORT = 38_432;

    private TushareMainboardHistoryBackfillManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Instant startedAt = clock.instant();
        Arguments launch = null;
        ResultFile resultFile = null;
        String commit = "UNKNOWN";
        var progress = new MainboardHistoryBackfillService.Progress();
        try {
            launch = Arguments.parse(args);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            commit = proof.gitCommit();
            validateProof(proof, launch);
            resultFile = ResultFile.reserve(launch.resultFile(), failure(
                    launch, commit, startedAt, startedAt, progress,
                    "MAINBOARD_HISTORY_BACKFILL_RUNNING", false));
            Arguments bound = launch;
            Captured<MainboardHistoryBackfillService.Outcome> captured =
                    launch.mode() == ExecutionMode.FAKE
                            ? fake(bound, clock, progress)
                            : formal(bound, clock, progress);
            if (!captured.auditResult().clean()) {
                throw invalid(
                        "MAINBOARD_HISTORY_BACKFILL_OUTPUT_AUDIT_FAILED");
            }
            Result success = success(launch, commit, startedAt,
                    captured.value(), progress, true,
                    launch.mode() == ExecutionMode.FAKE);
            resultFile.write(success);
            System.out.println(
                    "MAINBOARD_250_SESSION_HISTORY_BACKFILL_STATUS=SUCCEEDED");
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

    private static Captured<MainboardHistoryBackfillService.Outcome> fake(
            Arguments launch,
            Clock clock,
            MainboardHistoryBackfillService.Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureControlledProcess(registry -> {
                    char[] password = "MAINBOARD_250_E2E_DB".toCharArray();
                    char[] token = "MAINBOARD_250_E2E_TOKEN".toCharArray();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD,
                                password);
                        registry.register(SensitiveKind.TUSHARE_TOKEN, token);
                        return execute(launch, password, token, clock,
                                progress);
                    } finally {
                        Arrays.fill(password, '\0');
                        Arrays.fill(token, '\0');
                    }
                });
    }

    private static Captured<MainboardHistoryBackfillService.Outcome> formal(
            Arguments launch,
            Clock clock,
            MainboardHistoryBackfillService.Progress progress
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

    private static MainboardHistoryBackfillService.Outcome execute(
            Arguments launch,
            char[] password,
            char[] token,
            Clock clock,
            MainboardHistoryBackfillService.Progress progress
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
                if (launch.mode() == ExecutionMode.FAKE) {
                    seedFakePrerequisites(jdbc, components, launch, clock);
                }
                var service = new MainboardHistoryBackfillService(jdbc,
                        new ObjectMapper().findAndRegisterModules(),
                        components.mainboardUniverseCaptureService(), clock);
                return service.execute(launch.anchorTradeDate(),
                        launch.expectedMissingSessions(),
                        launch.maximumProviderRequests(), launch.gitCommit(),
                        progress);
            }
        }
    }

    private static void seedFakePrerequisites(
            JdbcTemplate jdbc,
            TushareDedicatedResearchRuntimeComponents components,
            Arguments launch,
            Clock clock
    ) {
        var universes = new ResearchUniverseMainboardRepository(jdbc);
        if (universes.latest().isPresent()) return;
        LocalDate calendarStart = launch.anchorTradeDate().minusDays(450);
        var setup = components.mainboardUniverseCaptureService().capture(
                null, true, Set.of(), calendarStart,
                launch.anchorTradeDate(), true, launch.gitCommit(),
                MainboardHistoryBackfillService.PROVIDER_TIMEOUT, 0);
        var snapshot = setup.snapshot();
        var loader = new ResearchUniverseMainboardDatasetLoader(
                new PitMarketFactRepository(jdbc,
                        new ObjectMapper().findAndRegisterModules()));
        List<LocalDate> open = loader.commonOpenDatesThrough(
                launch.anchorTradeDate(), clock.instant());
        if (open.size() < MainboardHistoryBackfillService.TARGET_SESSIONS) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_FAKE_CALENDAR_INVALID");
        }
        Set<LocalDate> initial60 = Set.copyOf(open.subList(open.size() - 60,
                open.size()));
        components.mainboardUniverseCaptureService().capture(snapshot, false,
                initial60, initial60.stream().min(LocalDate::compareTo)
                        .orElseThrow(), launch.anchorTradeDate(), false,
                launch.gitCommit(),
                MainboardHistoryBackfillService.PROVIDER_TIMEOUT, 0);
    }

    private static Result success(
            Arguments launch,
            String commit,
            Instant startedAt,
            MainboardHistoryBackfillService.Outcome outcome,
            MainboardHistoryBackfillService.Progress progress,
            boolean auditClean,
            boolean fake
    ) {
        var plan = outcome.plan();
        var snapshot = outcome.snapshot().snapshot();
        int missing = plan.missingTradeDates().size();
        BigDecimal average = missing == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(outcome.dailyAdded()).divide(
                BigDecimal.valueOf(missing), 2, RoundingMode.HALF_UP);
        return new Result(MainboardHistoryBackfillSanitizedResult.VERSION,
                "SUCCEEDED", launch.executionId(), commit, startedAt,
                outcome.completedAt(), plan.anchorTradeDate(),
                plan.rangeStart(), plan.rangeEnd(),
                MainboardHistoryBackfillService.TARGET_SESSIONS,
                plan.originalCompleteSessions(), missing,
                plan.targetTradeDates(), plan.missingTradeDates(),
                progress.completedTradeDates,
                outcome.final250().completeSessions(), true,
                outcome.milestone120().missingTradeDates().size(), true,
                outcome.final250().missingTradeDates().size(),
                outcome.final250().partialDates(),
                outcome.final250().duplicateCount(),
                snapshot.universeVersion(), snapshot.snapshotId(),
                snapshot.memberFingerprint(), snapshot.memberCount(),
                snapshot.sseCount(), snapshot.szseCount(), snapshot.stCount(),
                outcome.providerCalls(), endpoint(outcome, "daily"),
                endpoint(outcome, "adj_factor"),
                endpoint(outcome, "stock_basic"),
                endpoint(outcome, "trade_cal"), outcome.retryCount(),
                plan.networkRecoveryBudget(), plan.maximumProviderRequests(),
                outcome.batchIds(), outcome.dailyAdded(),
                outcome.factorAdded(), outcome.appended(),
                outcome.idempotent(), average,
                outcome.final250().knownAtValid(), true,
                "POST_HOC_RESEARCH", "PIT_PARTIAL", true,
                0, 0, 0, 0, 0, auditClean, fake, true, false, null);
    }

    private static int endpoint(
            MainboardHistoryBackfillService.Outcome outcome,
            String name
    ) {
        return outcome.endpointCallCounts().getOrDefault(name, 0);
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
            throw invalid("MAINBOARD_HISTORY_BACKFILL_SCHEMA_VERSION_INVALID");
        }
    }

    private static void validateProof(
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof proof,
            Arguments launch
    ) {
        if (!launch.gitCommit().equals(proof.gitCommit())
                || !TushareControlledAcceptanceBuildProof
                .MAINBOARD_HISTORY_BACKFILL_RUNNER_START_CLASS.equals(
                        proof.runnerStartClass())
                || !(launch.mode() == ExecutionMode.FAKE
                ? proof.e2eDryRunEligible()
                : proof.mainboardHistoryBackfillEligible())) {
            throw invalid(
                    "MAINBOARD_HISTORY_BACKFILL_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static void writeFailure(
            ResultFile resultFile,
            Arguments launch,
            String commit,
            Instant startedAt,
            Clock clock,
            MainboardHistoryBackfillService.Progress progress,
            Throwable error,
            boolean auditClean
    ) {
        String reason = safeCode(error);
        if (resultFile != null && launch != null) {
            resultFile.write(failure(launch, commit, startedAt,
                    clock.instant(), progress, reason, auditClean));
        }
        System.err.println(
                "MAINBOARD_HISTORY_BACKFILL_FAILURE_REASON=" + reason);
    }

    private static Result failure(
            Arguments launch,
            String commit,
            Instant startedAt,
            Instant completedAt,
            MainboardHistoryBackfillService.Progress progress,
            String reason,
            boolean auditClean
    ) {
        var plan = progress.plan;
        var snapshot = plan == null ? null : plan.snapshot().snapshot();
        List<LocalDate> target = plan == null ? List.of()
                : plan.targetTradeDates();
        List<LocalDate> missing = plan == null ? List.of()
                : plan.missingTradeDates();
        int original = plan == null ? 0 : plan.originalCompleteSessions();
        int dailyCalls = progress.endpointCallCounts.getOrDefault("daily", 0);
        int factorCalls = progress.endpointCallCounts.getOrDefault(
                "adj_factor", 0);
        return new Result(MainboardHistoryBackfillSanitizedResult.VERSION,
                "FAILED", launch.executionId(), commit, startedAt,
                completedAt, launch.anchorTradeDate(),
                plan == null ? null : plan.rangeStart(),
                plan == null ? null : plan.rangeEnd(),
                launch.targetSessions(), original,
                launch.expectedMissingSessions(), target, missing,
                progress.completedTradeDates,
                original + progress.completedTradeDates.size(), false,
                Math.max(0, MainboardHistoryBackfillService
                        .MILESTONE_SESSIONS - original
                        - progress.completedTradeDates.size()), false,
                Math.max(0, launch.targetSessions() - original
                        - progress.completedTradeDates.size()), 0, 0,
                snapshot == null ? "RESEARCH_UNIVERSE_MAINBOARD_V1"
                        : snapshot.universeVersion(),
                snapshot == null ? null : snapshot.snapshotId(),
                snapshot == null ? null : snapshot.memberFingerprint(),
                snapshot == null ? 0 : snapshot.memberCount(),
                snapshot == null ? 0 : snapshot.sseCount(),
                snapshot == null ? 0 : snapshot.szseCount(),
                snapshot == null ? 0 : snapshot.stCount(),
                progress.providerCalls, dailyCalls, factorCalls,
                progress.endpointCallCounts.getOrDefault("stock_basic", 0),
                progress.endpointCallCounts.getOrDefault("trade_cal", 0),
                progress.retryCount,
                MainboardHistoryBackfillService.MAXIMUM_NETWORK_RECOVERIES,
                launch.maximumProviderRequests(), progress.batchIds,
                0, 0, progress.appended, progress.idempotent,
                BigDecimal.ZERO, false, false, "POST_HOC_RESEARCH",
                "PIT_PARTIAL", false, 0, 0, 0, 0, 0, auditClean,
                launch.mode() == ExecutionMode.FAKE, true, false, reason);
    }

    private static String safeCode(Throwable error) {
        for (Throwable current = error; current != null;
             current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.matches(
                    "[A-Z][A-Z0-9_]{3,127}")) return message;
        }
        return "MAINBOARD_HISTORY_BACKFILL_EXECUTION_FAILED";
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    enum ExecutionMode { FAKE, FORMAL }

    record Arguments(
            Path resultFile,
            String executionId,
            String gitCommit,
            LocalDate anchorTradeDate,
            int targetSessions,
            int expectedMissingSessions,
            int databasePort,
            int maximumProviderRequests,
            int networkRecoveryBudget,
            ExecutionMode mode
    ) {
        static Arguments parse(String[] args) {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw invalid(
                            "MAINBOARD_HISTORY_BACKFILL_ARGUMENTS_INVALID");
                }
                int split = arg.indexOf('=');
                if (values.put(arg.substring(2, split),
                        arg.substring(split + 1)) != null) {
                    throw invalid(
                            "MAINBOARD_HISTORY_BACKFILL_ARGUMENTS_INVALID");
                }
            }
            if (!values.keySet().equals(java.util.Set.of("result-file",
                    "execution-id", "git-commit", "anchor-trade-date",
                    "target-sessions", "expected-missing-sessions",
                    "database-port", "maximum-provider-requests",
                    "network-recovery-budget", "execution-mode"))) {
                throw invalid(
                        "MAINBOARD_HISTORY_BACKFILL_ARGUMENTS_INVALID");
            }
            try {
                Arguments value = new Arguments(
                        Path.of(values.get("result-file")),
                        values.get("execution-id"), values.get("git-commit"),
                        LocalDate.parse(values.get("anchor-trade-date")),
                        Integer.parseInt(values.get("target-sessions")),
                        Integer.parseInt(values.get(
                                "expected-missing-sessions")),
                        Integer.parseInt(values.get("database-port")),
                        Integer.parseInt(values.get(
                                "maximum-provider-requests")),
                        Integer.parseInt(values.get(
                                "network-recovery-budget")),
                        ExecutionMode.valueOf(values.get("execution-mode")
                                .toUpperCase(Locale.ROOT)));
                value.validate();
                return value;
            } catch (RuntimeException error) {
                throw invalid(
                        "MAINBOARD_HISTORY_BACKFILL_ARGUMENTS_INVALID");
            }
        }

        private void validate() {
            int expectedMaximum = expectedMissingSessions * 2
                    + networkRecoveryBudget;
            if (!executionId.matches(
                    "MBH250_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || !gitCommit.matches("[0-9a-f]{40}")
                    || targetSessions
                    != MainboardHistoryBackfillService.TARGET_SESSIONS
                    || expectedMissingSessions < 1
                    || expectedMissingSessions >= targetSessions
                    || databasePort < 1 || databasePort > 65_535
                    || mode == ExecutionMode.FORMAL
                    && databasePort != FORMAL_PORT
                    || mode == ExecutionMode.FAKE
                    && databasePort == FORMAL_PORT
                    || networkRecoveryBudget
                    != MainboardHistoryBackfillService
                    .MAXIMUM_NETWORK_RECOVERIES
                    || maximumProviderRequests != expectedMaximum
                    || maximumProviderRequests
                    > TushareManualBoundedSession
                    .MAINBOARD_UNIVERSE_MAX_PROVIDER_REQUESTS) {
                throw invalid(
                        "MAINBOARD_HISTORY_BACKFILL_ARGUMENTS_INVALID");
            }
        }
    }
}
