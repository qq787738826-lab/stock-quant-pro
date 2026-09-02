package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.MainboardTradeCalendarBackfillSanitizedResult.Result;
import com.stockquant.server.agent.marketfacts.MainboardTradeCalendarBackfillSanitizedResult.ResultFile;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fixed data-only trade-calendar range capture; never starts Spring or Agents. */
public final class TushareMainboardTradeCalendarBackfillManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final int FORMAL_PORT = 38_432;

    private TushareMainboardTradeCalendarBackfillManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Instant startedAt = clock.instant();
        Arguments launch = null;
        ResultFile resultFile = null;
        String commit = "UNKNOWN";
        var progress = new MainboardTradeCalendarBackfillService.Progress();
        try {
            launch = Arguments.parse(args);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            commit = proof.gitCommit();
            validateProof(proof, launch);
            resultFile = ResultFile.reserve(launch.resultFile(), failure(
                    launch, commit, startedAt, startedAt, progress,
                    "MAINBOARD_TRADE_CAL_BACKFILL_RUNNING", false));
            Arguments bound = launch;
            Captured<MainboardTradeCalendarBackfillService.Outcome> captured =
                    launch.mode() == ExecutionMode.FAKE
                            ? fake(bound, clock, progress)
                            : formal(bound, clock, progress);
            if (!captured.auditResult().clean()) {
                throw invalid(
                        "MAINBOARD_TRADE_CAL_BACKFILL_OUTPUT_AUDIT_FAILED");
            }
            resultFile.write(success(launch, commit, startedAt,
                    captured.value(), true,
                    launch.mode() == ExecutionMode.FAKE));
            System.out.println(
                    "MAINBOARD_250_SESSION_TRADE_CAL_BACKFILL_STATUS=SUCCEEDED");
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

    private static Captured<MainboardTradeCalendarBackfillService.Outcome>
    fake(
            Arguments launch,
            Clock clock,
            MainboardTradeCalendarBackfillService.Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureControlledProcess(registry -> {
                    char[] password = "MAINBOARD_CALENDAR_E2E_DB".toCharArray();
                    char[] token = "MAINBOARD_CALENDAR_E2E_TOKEN".toCharArray();
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

    private static Captured<MainboardTradeCalendarBackfillService.Outcome>
    formal(
            Arguments launch,
            Clock clock,
            MainboardTradeCalendarBackfillService.Progress progress
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

    private static MainboardTradeCalendarBackfillService.Outcome execute(
            Arguments launch,
            char[] password,
            char[] token,
            Clock clock,
            MainboardTradeCalendarBackfillService.Progress progress
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
                    seedFakePrerequisites(jdbc, components, launch);
                }
                var service = new MainboardTradeCalendarBackfillService(jdbc,
                        new ObjectMapper().findAndRegisterModules(),
                        components.mainboardUniverseCaptureService(), clock);
                return service.execute(launch.anchorTradeDate(),
                        launch.rangeStart(), launch.rangeEnd(),
                        launch.maximumProviderRequests(),
                        launch.networkRecoveryBudget(), launch.gitCommit(),
                        progress);
            }
        }
    }

    private static void seedFakePrerequisites(
            JdbcTemplate jdbc,
            TushareDedicatedResearchRuntimeComponents components,
            Arguments launch
    ) {
        if (new ResearchUniverseMainboardRepository(jdbc).latest().isPresent()) {
            return;
        }
        LocalDate initialStart = lastWeekdayWindowStart(
                launch.anchorTradeDate(), 60);
        components.mainboardUniverseCaptureService().capture(null, true,
                Set.of(), initialStart, launch.anchorTradeDate(), true,
                launch.gitCommit(),
                MainboardTradeCalendarBackfillService.PROVIDER_TIMEOUT, 0);
    }

    static LocalDate lastWeekdayWindowStart(LocalDate anchor, int sessions) {
        LocalDate value = anchor;
        int found = 0;
        while (true) {
            if (value.getDayOfWeek() != DayOfWeek.SATURDAY
                    && value.getDayOfWeek() != DayOfWeek.SUNDAY) {
                found++;
                if (found == sessions) return value;
            }
            value = value.minusDays(1);
        }
    }

    private static Result success(
            Arguments launch,
            String commit,
            Instant startedAt,
            MainboardTradeCalendarBackfillService.Outcome outcome,
            boolean auditClean,
            boolean fake
    ) {
        var snapshot = outcome.snapshot().snapshot();
        var after = outcome.after();
        return new Result(
                MainboardTradeCalendarBackfillSanitizedResult.VERSION,
                "SUCCEEDED", launch.executionId(), commit, startedAt,
                outcome.completedAt(), launch.anchorTradeDate(),
                outcome.rangeStart(), outcome.rangeEnd(),
                MainboardTradeCalendarBackfillService
                        .MINIMUM_COMMON_OPEN_SESSIONS,
                MainboardTradeCalendarBackfillService.TARGET_SESSIONS,
                outcome.before().commonOpenDates().size(),
                after.commonOpenDates().size(),
                outcome.target250TradeDates(),
                after.latestCommonOpenDate(), snapshot.universeVersion(),
                snapshot.snapshotId(), snapshot.memberFingerprint(),
                snapshot.memberCount(), after.sseCalendarDates().size(),
                after.szseCalendarDates().size(),
                after.sseOpenDates().size(), after.szseOpenDates().size(),
                after.duplicateCount(), outcome.providerCalls(),
                outcome.calendarCallCountsByExchange().getOrDefault(
                        "SSE", 0),
                outcome.calendarCallCountsByExchange().getOrDefault(
                        "SZSE", 0), 0, 0, 0, outcome.retryCount(),
                launch.networkRecoveryBudget(),
                launch.maximumProviderRequests(), outcome.batchIds(),
                outcome.appended(), outcome.idempotent(),
                after.knownAtValid(), after.firstObservedAtValid(),
                after.lineageValid(), true, 0, 0, 0, 0, 0,
                auditClean, fake, true, false, null);
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
            throw invalid(
                    "MAINBOARD_TRADE_CAL_BACKFILL_SCHEMA_VERSION_INVALID");
        }
    }

    private static void validateProof(
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof proof,
            Arguments launch
    ) {
        if (!launch.gitCommit().equals(proof.gitCommit())
                || !TushareControlledAcceptanceBuildProof
                .MAINBOARD_TRADE_CAL_BACKFILL_RUNNER_START_CLASS.equals(
                        proof.runnerStartClass())
                || !(launch.mode() == ExecutionMode.FAKE
                ? proof.e2eDryRunEligible()
                : proof.mainboardTradeCalendarBackfillEligible())) {
            throw invalid(
                    "MAINBOARD_TRADE_CAL_BACKFILL_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static void writeFailure(
            ResultFile resultFile,
            Arguments launch,
            String commit,
            Instant startedAt,
            Clock clock,
            MainboardTradeCalendarBackfillService.Progress progress,
            Throwable error,
            boolean auditClean
    ) {
        String reason = safeCode(error);
        if (resultFile != null && launch != null) {
            resultFile.write(failure(launch, commit, startedAt,
                    clock.instant(), progress, reason, auditClean));
        }
        System.err.println(
                "MAINBOARD_TRADE_CAL_BACKFILL_FAILURE_REASON=" + reason);
    }

    private static Result failure(
            Arguments launch,
            String commit,
            Instant startedAt,
            Instant completedAt,
            MainboardTradeCalendarBackfillService.Progress progress,
            String reason,
            boolean auditClean
    ) {
        return new Result(
                MainboardTradeCalendarBackfillSanitizedResult.VERSION,
                "FAILED", launch.executionId(), commit, startedAt,
                completedAt, launch.anchorTradeDate(), launch.rangeStart(),
                launch.rangeEnd(), MainboardTradeCalendarBackfillService
                .MINIMUM_COMMON_OPEN_SESSIONS,
                MainboardTradeCalendarBackfillService.TARGET_SESSIONS,
                0, 0, List.of(), null, ResearchUniverseMainboard.VERSION,
                null, null, 0, 0, 0, 0, 0, 0,
                progress.providerCalls,
                progress.calendarCallCountsByExchange.getOrDefault("SSE", 0),
                progress.calendarCallCountsByExchange.getOrDefault("SZSE", 0),
                0, 0, 0, progress.retryCount,
                launch.networkRecoveryBudget(),
                launch.maximumProviderRequests(), progress.batchIds,
                progress.appended, progress.idempotent, false, false,
                false, false, 0, 0, 0, 0, 0, auditClean,
                launch.mode() == ExecutionMode.FAKE, true, false, reason);
    }

    private static String safeCode(Throwable error) {
        for (Throwable current = error; current != null;
             current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.matches(
                    "[A-Z][A-Z0-9_]{3,127}")) return message;
        }
        return "MAINBOARD_TRADE_CAL_BACKFILL_EXECUTION_FAILED";
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
            LocalDate rangeStart,
            LocalDate rangeEnd,
            int minimumCommonOpenSessions,
            int targetSessions,
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
                            "MAINBOARD_TRADE_CAL_BACKFILL_ARGUMENTS_INVALID");
                }
                int split = arg.indexOf('=');
                if (values.put(arg.substring(2, split),
                        arg.substring(split + 1)) != null) {
                    throw invalid(
                            "MAINBOARD_TRADE_CAL_BACKFILL_ARGUMENTS_INVALID");
                }
            }
            if (!values.keySet().equals(Set.of("result-file",
                    "execution-id", "git-commit", "anchor-trade-date",
                    "calendar-range-start", "calendar-range-end",
                    "minimum-common-open-sessions", "target-sessions",
                    "database-port", "maximum-provider-requests",
                    "network-recovery-budget", "execution-mode"))) {
                throw invalid(
                        "MAINBOARD_TRADE_CAL_BACKFILL_ARGUMENTS_INVALID");
            }
            try {
                Arguments value = new Arguments(
                        Path.of(values.get("result-file")),
                        values.get("execution-id"), values.get("git-commit"),
                        LocalDate.parse(values.get("anchor-trade-date")),
                        LocalDate.parse(values.get("calendar-range-start")),
                        LocalDate.parse(values.get("calendar-range-end")),
                        Integer.parseInt(values.get(
                                "minimum-common-open-sessions")),
                        Integer.parseInt(values.get("target-sessions")),
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
                        "MAINBOARD_TRADE_CAL_BACKFILL_ARGUMENTS_INVALID");
            }
        }

        private void validate() {
            if (!executionId.matches(
                    "MBTC250_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || !gitCommit.matches("[0-9a-f]{40}")
                    || !rangeEnd.equals(anchorTradeDate)
                    || !rangeStart.equals(anchorTradeDate.minusDays(
                    MainboardTradeCalendarBackfillService
                            .CALENDAR_LOOKBACK_DAYS))
                    || minimumCommonOpenSessions
                    != MainboardTradeCalendarBackfillService
                    .MINIMUM_COMMON_OPEN_SESSIONS
                    || targetSessions
                    != MainboardTradeCalendarBackfillService.TARGET_SESSIONS
                    || databasePort < 1 || databasePort > 65_535
                    || mode == ExecutionMode.FORMAL
                    && databasePort != FORMAL_PORT
                    || mode == ExecutionMode.FAKE
                    && databasePort == FORMAL_PORT
                    || maximumProviderRequests
                    != MainboardTradeCalendarBackfillService
                    .MAXIMUM_PROVIDER_REQUESTS
                    || networkRecoveryBudget
                    != MainboardTradeCalendarBackfillService
                    .MAXIMUM_NETWORK_RECOVERIES) {
                throw invalid(
                        "MAINBOARD_TRADE_CAL_BACKFILL_ARGUMENTS_INVALID");
            }
        }
    }
}
