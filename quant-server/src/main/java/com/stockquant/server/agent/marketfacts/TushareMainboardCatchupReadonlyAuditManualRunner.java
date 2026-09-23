package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.MainboardCatchupReadonlyAuditSanitizedResult.DateResult;
import com.stockquant.server.agent.marketfacts.MainboardCatchupReadonlyAuditSanitizedResult.Result;
import com.stockquant.server.agent.marketfacts.MainboardCatchupReadonlyAuditSanitizedResult.ResultFile;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** Fixed database-only audit; it cannot construct or call a Provider. */
public final class TushareMainboardCatchupReadonlyAuditManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final int FORMAL_PORT = 38_432;

    private TushareMainboardCatchupReadonlyAuditManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Instant startedAt = clock.instant();
        Arguments launch = null;
        ResultFile resultFile = null;
        String commit = "UNKNOWN";
        try {
            launch = Arguments.parse(args);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            commit = proof.gitCommit();
            validateProof(proof, launch);
            resultFile = ResultFile.reserve(launch.resultFile(), failure(
                    launch, commit, startedAt, startedAt,
                    "MAINBOARD_CATCHUP_AUDIT_RUNNING", false));
            Captured<MainboardCatchupReadonlyAuditService.Outcome> captured =
                    launch.mode() == ExecutionMode.FAKE
                            ? fake(launch, clock) : formal(launch, clock);
            if (!captured.auditResult().clean()) {
                throw invalid("MAINBOARD_CATCHUP_AUDIT_OUTPUT_AUDIT_FAILED");
            }
            Result succeeded = success(launch, commit, startedAt,
                    clock.instant(), captured.value(), true);
            resultFile.write(succeeded);
            System.out.println(
                    "MAINBOARD_CATCHUP_READONLY_AUDIT_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException error) {
            writeFailure(resultFile, launch, commit, startedAt, clock,
                    error.getCause(), error.auditResult() != null
                            && error.auditResult().clean());
            return EXIT_REJECTED;
        } catch (Throwable error) {
            writeFailure(resultFile, launch, commit, startedAt, clock,
                    error, false);
            return EXIT_REJECTED;
        }
    }

    private static Captured<MainboardCatchupReadonlyAuditService.Outcome>
    fake(Arguments launch, Clock clock) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureDatabaseOnlyProcess(registry -> {
                    char[] password =
                            "MAINBOARD_CATCHUP_AUDIT_E2E_DB".toCharArray();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD,
                                password);
                        return execute(launch, password, clock);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                });
    }

    private static Captured<MainboardCatchupReadonlyAuditService.Outcome>
    formal(Arguments launch, Clock clock) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureDatabaseOnlyProcess(registry -> {
                    try (SecretProvider secrets =
                                 CompositeSecretProvider.formalLocal(
                                         Mode.WINDOWS_CREDENTIAL_MANAGER);
                         SecretValue database =
                                 secrets.readResearchDatabasePassword()) {
                        char[] password = database.copy();
                        try {
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            return execute(launch, password, clock);
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    }
                });
    }

    private static MainboardCatchupReadonlyAuditService.Outcome execute(
            Arguments launch,
            char[] password,
            Clock clock
    ) {
        try (ReadOnlyDataSource dataSource = new ReadOnlyDataSource(
                launch.databasePort(), password)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            transaction.setReadOnly(true);
            transaction.setIsolationLevel(
                    TransactionDefinition.ISOLATION_REPEATABLE_READ);
            MainboardCatchupReadonlyAuditService.Outcome value = transaction
                    .execute(status -> {
                        new TushareDedicatedResearchPersistenceGuard(jdbc,
                                TushareDedicatedResearchPersistenceGuard
                                        .DATABASE_PURPOSE)
                                .verifyTransactional();
                        requireSchema(jdbc);
                        return new MainboardCatchupReadonlyAuditService(jdbc,
                                new ObjectMapper().findAndRegisterModules(),
                                clock).execute(launch.currentLedger(),
                                launch.ledgerLimit());
                    });
            if (value == null || !value.readOnlyTransaction()) {
                throw invalid("MAINBOARD_CATCHUP_AUDIT_RESULT_INVALID");
            }
            return value;
        }
    }

    private static void requireSchema(JdbcTemplate jdbc) {
        Integer version = jdbc.queryForObject("""
                SELECT COALESCE(max(version::integer), 0)
                  FROM tushare_research.flyway_schema_history WHERE success
                """, Integer.class);
        if (version == null || version != 18) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_SCHEMA_VERSION_INVALID");
        }
    }

    private static void validateProof(
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof proof,
            Arguments launch
    ) {
        if (!launch.gitCommit().equals(proof.gitCommit())
                || !TushareControlledAcceptanceBuildProof
                .MAINBOARD_CATCHUP_READONLY_AUDIT_RUNNER_START_CLASS.equals(
                        proof.runnerStartClass())
                || !(launch.mode() == ExecutionMode.FAKE
                ? proof.e2eDryRunEligible()
                : proof.mainboardCatchupReadonlyAuditEligible())) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static Result success(
            Arguments launch,
            String commit,
            Instant startedAt,
            Instant completedAt,
            MainboardCatchupReadonlyAuditService.Outcome outcome,
            boolean auditClean
    ) {
        var snapshot = outcome.snapshot().snapshot();
        List<DateResult> dates = outcome.dateAudits().stream().map(value ->
                new DateResult(value.tradeDate(), value.status().name(),
                        value.reasonCodes(), value.dailyRowCount(),
                        value.adjustmentFactorRowCount(),
                        value.dailySecurityCount(),
                        value.adjustmentFactorSecurityCount(),
                        value.activeSecurityCount(), value.duplicateCount(),
                        value.securitySetsEqual(), value.dailyComplete(),
                        value.adjustmentFactorComplete(),
                        value.knownAtValid())).toList();
        return new Result(
                MainboardCatchupReadonlyAuditSanitizedResult.VERSION,
                "SUCCEEDED", launch.executionId(), commit, startedAt,
                completedAt, outcome.calendarMaxSse(),
                outcome.calendarMaxSzse(),
                outcome.latestCommonCompletedOpenTradeDate(),
                outcome.latestCompleteTradeDate(),
                outcome.commonOpenTradeDates(), dates,
                outcome.missingTradeDates(), outcome.partialTradeDates(),
                outcome.completeTradeDates(),
                outcome.missingTradeDates().size(),
                outcome.partialTradeDates().size(),
                outcome.completeTradeDates().size(),
                snapshot.universeVersion(), snapshot.memberCount(),
                snapshot.sseCount(), snapshot.szseCount(), snapshot.stCount(),
                outcome.factSecurityCount(), outcome.currentLedger(),
                outcome.ledgerLimit(), outcome.plannedBaseCalls(),
                outcome.networkRecoveryBudget(),
                outcome.projectedWorstCaseLedger(),
                outcome.projectedWithinLimit(), 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, outcome.readOnlyTransaction(), auditClean,
                launch.mode() == ExecutionMode.FAKE, true, false, null);
    }

    private static void writeFailure(
            ResultFile resultFile,
            Arguments launch,
            String commit,
            Instant startedAt,
            Clock clock,
            Throwable error,
            boolean auditClean
    ) {
        String reason = safeCode(error);
        if (resultFile != null && launch != null) {
            resultFile.write(failure(launch, commit, startedAt,
                    clock.instant(), reason, auditClean));
        }
        System.err.println(
                "MAINBOARD_CATCHUP_READONLY_AUDIT_FAILURE_REASON=" + reason);
    }

    private static Result failure(
            Arguments launch,
            String commit,
            Instant startedAt,
            Instant completedAt,
            String reason,
            boolean auditClean
    ) {
        return new Result(
                MainboardCatchupReadonlyAuditSanitizedResult.VERSION,
                "FAILED", launch.executionId(), commit, startedAt,
                completedAt, null, null, null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), 0, 0, 0,
                ResearchUniverseMainboard.VERSION, 0, 0, 0, 0, 0,
                launch.currentLedger(), launch.ledgerLimit(), 0,
                MainboardCatchupReadonlyAuditService.NETWORK_RECOVERY_BUDGET,
                launch.currentLedger(), false, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, false, auditClean,
                launch.mode() == ExecutionMode.FAKE, true, false, reason);
    }

    private static String safeCode(Throwable error) {
        for (Throwable current = error; current != null;
             current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.matches(
                    "[A-Z][A-Z0-9_]{3,127}")) return message;
        }
        return "MAINBOARD_CATCHUP_AUDIT_EXECUTION_FAILED";
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    enum ExecutionMode { FAKE, FORMAL }

    record Arguments(
            Path resultFile,
            String executionId,
            String gitCommit,
            int databasePort,
            int currentLedger,
            int ledgerLimit,
            ExecutionMode mode
    ) {
        static Arguments parse(String[] args) {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw invalid("MAINBOARD_CATCHUP_AUDIT_ARGUMENTS_INVALID");
                }
                int split = arg.indexOf('=');
                if (values.put(arg.substring(2, split),
                        arg.substring(split + 1)) != null) {
                    throw invalid("MAINBOARD_CATCHUP_AUDIT_ARGUMENTS_INVALID");
                }
            }
            if (!values.keySet().equals(java.util.Set.of("result-file",
                    "execution-id", "git-commit", "database-port",
                    "current-ledger", "ledger-limit", "execution-mode"))) {
                throw invalid("MAINBOARD_CATCHUP_AUDIT_ARGUMENTS_INVALID");
            }
            try {
                Arguments value = new Arguments(
                        Path.of(values.get("result-file")),
                        values.get("execution-id"),
                        values.get("git-commit"),
                        Integer.parseInt(values.get("database-port")),
                        Integer.parseInt(values.get("current-ledger")),
                        Integer.parseInt(values.get("ledger-limit")),
                        ExecutionMode.valueOf(values.get("execution-mode")
                                .toUpperCase(Locale.ROOT)));
                value.validate();
                return value;
            } catch (RuntimeException error) {
                throw invalid("MAINBOARD_CATCHUP_AUDIT_ARGUMENTS_INVALID");
            }
        }

        private void validate() {
            if (!executionId.matches(
                    "MBAUDIT_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || !gitCommit.matches("[0-9a-f]{40}")
                    || databasePort < 1 || databasePort > 65_535
                    || mode == ExecutionMode.FORMAL
                    && databasePort != FORMAL_PORT
                    || mode == ExecutionMode.FAKE
                    && databasePort == FORMAL_PORT
                    || currentLedger < 0 || ledgerLimit < 1
                    || currentLedger > ledgerLimit) {
                throw invalid("MAINBOARD_CATCHUP_AUDIT_ARGUMENTS_INVALID");
            }
        }
    }

    private static final class ReadOnlyDataSource
            implements DataSource, AutoCloseable {
        private final TushareControlledAcceptanceDataSource delegate;

        private ReadOnlyDataSource(int port, char[] password) {
            delegate = new TushareControlledAcceptanceDataSource(port,
                    TushareControlledAcceptanceDataSource.SslMode
                            .DISABLE_LOCAL_ONLY, password);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            try {
                connection.setReadOnly(true);
                if (!connection.isReadOnly()) {
                    throw new SQLException(
                            "MAINBOARD_CATCHUP_AUDIT_READ_ONLY_REQUIRED");
                }
                return connection;
            } catch (Throwable error) {
                connection.close();
                if (error instanceof SQLException sql) throw sql;
                throw new SQLException(
                        "MAINBOARD_CATCHUP_AUDIT_READ_ONLY_REQUIRED", error);
            }
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            throw new SQLFeatureNotSupportedException(
                    "MAINBOARD_CATCHUP_AUDIT_EXPLICIT_CREDENTIALS_FORBIDDEN");
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface != null && iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException(
                    "MAINBOARD_CATCHUP_AUDIT_UNWRAP_REJECTED");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface != null && iface.isInstance(this);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
