package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchSmokeResult.Audit;
import com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchSmokeResult.DatabaseSnapshot;
import com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchSmokeResult.Result;
import com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchSmokeResult.ResultFile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/** Fixed-scope, read-only, non-Spring runner for the M2 M1-data smoke. */
public final class TushareM2StrategyResearchManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    static final String RESULT_VERSION =
            TushareM2StrategyResearchSmokeResult.VERSION;
    private static final int FORMAL_DATABASE_PORT = 38_432;
    private static final String RESULT_ARG = "--result-file=";
    private static final String EXECUTION_ARG = "--execution-id=";
    private static final String PORT_ARG = "--database-port=";
    private static final String MODE_ARG = "--execution-mode=";

    private TushareM2StrategyResearchManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        Instant startedAt = clock.instant();
        ResultFile resultFile = null;
        VerifiedBuildProof proof = null;
        Arguments launch = null;
        Execution execution = null;
        Audit audit = Audit.notRun();
        try {
            launch = Arguments.parse(args);
            proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            validateProof(proof, launch);
            Path artifact = TushareControlledAcceptanceBuildProof
                    .requireSingleJarClasspath(System.getProperty(
                            "java.class.path"));
            resultFile = ResultFile.reserve(launch.resultFile(),
                    Objects.requireNonNull(artifact.getParent()),
                    result("RUNNING", launch, proof, startedAt, startedAt,
                            null, Audit.notRun(), "M2_RUNNING"));

            Arguments boundLaunch = launch;
            Captured<Execution> captured =
                    TushareControlledAcceptanceOutputAudit
                            .captureDatabaseOnlyProcess(registry -> {
                                if (boundLaunch.executionMode()
                                        == ExecutionMode.E2E_DRY_RUN) {
                                    char[] password = syntheticPassword();
                                    try {
                                        registry.register(
                                                SensitiveKind.DATABASE_PASSWORD,
                                                password);
                                        return execute(boundLaunch, password,
                                                clock);
                                    } finally {
                                        Arrays.fill(password, '\0');
                                    }
                                }
                                try (SecretProvider secrets =
                                             CompositeSecretProvider.formalLocal(
                                                     Mode.WINDOWS_CREDENTIAL_MANAGER);
                                     SecretValue secret =
                                             secrets.readResearchDatabasePassword()) {
                                    char[] password = secret.copy();
                                    try {
                                        registry.register(
                                                SensitiveKind.DATABASE_PASSWORD,
                                                password);
                                        return execute(boundLaunch, password,
                                                clock);
                                    } finally {
                                        Arrays.fill(password, '\0');
                                    }
                                }
                            });
            execution = captured.value();
            audit = audit(captured.auditResult());
            if (!audit.clean()) {
                throw invalid("M2_OUTPUT_AUDIT_FAILED");
            }
            resultFile.write(result("SUCCEEDED", launch, proof, startedAt,
                    clock.instant(), execution, audit, "M2_SUCCEEDED"));
            System.out.println("M2_STRATEGY_RESEARCH_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException capturedFailure) {
            audit = capturedFailure.auditResult() == null
                    ? Audit.notRun() : audit(capturedFailure.auditResult());
            String reason = safeCode(capturedFailure.getCause());
            writeFailure(resultFile, launch, proof, startedAt, clock,
                    execution, audit, reason);
            safeFailure(reason);
            return EXIT_REJECTED;
        } catch (Throwable error) {
            String reason = safeCode(error);
            writeFailure(resultFile, launch, proof, startedAt, clock,
                    execution, audit, reason);
            safeFailure(reason);
            return EXIT_REJECTED;
        }
    }

    private static Execution execute(
            Arguments launch,
            char[] password,
            Clock clock
    ) {
        try (ReadOnlyDataSource dataSource = new ReadOnlyDataSource(
                launch.databasePort(), password)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            new TushareDedicatedResearchPersistenceGuard(jdbc,
                    TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                    .verifyBeforeProvider();
            Boolean readOnly = jdbc.execute(
                    (ConnectionCallback<Boolean>) Connection::isReadOnly);
            if (!Boolean.TRUE.equals(readOnly)) {
                throw invalid("M2_DATABASE_READ_ONLY_REQUIRED");
            }
            DatabaseSnapshot before = snapshot(jdbc);
            PitMarketFactRepository repository = new PitMarketFactRepository(
                    jdbc, new ObjectMapper().findAndRegisterModules());
            TushareM1ResearchDatasetService m1 =
                    new TushareM1ResearchDatasetService(repository, jdbc);
            TushareM2StrategyResearchSmokeService smoke =
                    new TushareM2StrategyResearchSmokeService(
                            new TushareM2StrategyResearchDatasetAdapter(m1));
            var result = smoke.run(command(), clock.instant());
            DatabaseSnapshot after = snapshot(jdbc);
            if (!before.equals(after)) {
                throw invalid("M2_PERMANENT_DATABASE_MUTATION_DETECTED");
            }
            return new Execution(result, before, after);
        }
    }

    private static TushareM1ResearchWindowCommand command() {
        return new TushareM1ResearchWindowCommand(List.of(
                new TushareDedicatedResearchBatchCommand.SecuritySelection(
                        "600000", "SSE"),
                new TushareDedicatedResearchBatchCommand.SecuritySelection(
                        "000001", "SZSE")),
                LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 1, 10),
                TushareM1ResearchWindowCommand.Mode.IDEMPOTENCY_VERIFICATION,
                Duration.ofMinutes(2));
    }

    private static DatabaseSnapshot snapshot(JdbcTemplate jdbc) {
        return new DatabaseSnapshot(
                count(jdbc, "pit_market_fact_batches"),
                count(jdbc, "pit_market_fact_observations"),
                count(jdbc, "raw_daily_bar_facts_v2"),
                count(jdbc, "adjustment_factor_facts_v1"),
                count(jdbc, "trading_calendar_facts_v1"));
    }

    private static long count(JdbcTemplate jdbc, String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table,
                Long.class);
        if (value == null || value < 0) {
            throw invalid("M2_DATABASE_SNAPSHOT_INVALID");
        }
        return value;
    }

    private static void validateProof(
            VerifiedBuildProof proof,
            Arguments launch
    ) {
        boolean eligible = launch.executionMode() == ExecutionMode.E2E_DRY_RUN
                ? proof.e2eDryRunEligible()
                : proof.m2StageEligible() || proof.governanceEligible();
        if (!eligible || !TushareControlledAcceptanceBuildProof
                .M2_RUNNER_START_CLASS.equals(proof.runnerStartClass())
                || launch.executionMode() == ExecutionMode.FORMAL_LOCAL
                && launch.databasePort() != FORMAL_DATABASE_PORT
                || launch.executionMode() == ExecutionMode.E2E_DRY_RUN
                && launch.databasePort() == FORMAL_DATABASE_PORT) {
            throw invalid("M2_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static Result result(
            String status,
            Arguments launch,
            VerifiedBuildProof proof,
            Instant startedAt,
            Instant completedAt,
            Execution execution,
            Audit audit,
            String reason
    ) {
        return new Result(RESULT_VERSION, status, launch.executionId(),
                proof.gitCommit(), proof.actualArtifactSha256(),
                proof.runnerStartClass(), startedAt, completedAt,
                execution == null ? null : execution.smoke(),
                execution != null,
                execution != null && execution.before().equals(
                        execution.after()),
                execution == null ? null : execution.before(),
                execution == null ? null : execution.after(), audit,
                0, 0, reason);
    }

    private static Audit audit(
            TushareControlledAcceptanceOutputAudit.AuditResult value
    ) {
        return new Audit(value.captureComplete(), value.clean(),
                value.hits().size());
    }

    private static void writeFailure(
            ResultFile file,
            Arguments launch,
            VerifiedBuildProof proof,
            Instant startedAt,
            Clock clock,
            Execution execution,
            Audit audit,
            String reason
    ) {
        if (file == null || launch == null || proof == null) {
            return;
        }
        try {
            file.write(result("FAILED", launch, proof, startedAt,
                    clock.instant(), execution, audit, reason));
        } catch (Throwable ignored) {
            // The reserved RUNNING evidence remains fail-closed.
        }
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            String message = value.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
        }
        return "M2_STRATEGY_RESEARCH_EXECUTION_FAILED";
    }

    private static void safeFailure(String reason) {
        System.err.println("M2_STRATEGY_RESEARCH_FAILURE_REASON=" + reason);
    }

    private static char[] syntheticPassword() {
        return "M2_E2E_DRY_RUN_DATABASE_PASSWORD".toCharArray();
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private record Execution(
            TushareM2StrategyResearchSmokeService.SmokeResult smoke,
            DatabaseSnapshot before,
            DatabaseSnapshot after
    ) {
    }

    record Arguments(
            Path resultFile,
            String executionId,
            int databasePort,
            ExecutionMode executionMode
    ) {
        static Arguments parse(String[] args) {
            if (args == null || args.length != 4) {
                throw invalid("M2_ARGUMENTS_INVALID");
            }
            Path result = null;
            String executionId = null;
            Integer port = null;
            ExecutionMode mode = null;
            for (String value : args) {
                if (value != null && value.startsWith(RESULT_ARG)
                        && result == null) {
                    result = Path.of(value.substring(RESULT_ARG.length()));
                } else if (value != null && value.startsWith(EXECUTION_ARG)
                        && executionId == null) {
                    executionId = value.substring(EXECUTION_ARG.length());
                } else if (value != null && value.startsWith(PORT_ARG)
                        && port == null) {
                    try {
                        port = Integer.parseInt(value.substring(
                                PORT_ARG.length()));
                    } catch (NumberFormatException error) {
                        throw invalid("M2_ARGUMENTS_INVALID");
                    }
                } else if (value != null && value.startsWith(MODE_ARG)
                        && mode == null) {
                    try {
                        mode = ExecutionMode.valueOf(value.substring(
                                MODE_ARG.length()));
                    } catch (IllegalArgumentException error) {
                        throw invalid("M2_ARGUMENTS_INVALID");
                    }
                } else {
                    throw invalid("M2_ARGUMENTS_INVALID");
                }
            }
            if (result == null || executionId == null
                    || !executionId.matches(
                    "M2SMOKE_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || port == null || port <= 0 || port > 65_535
                    || mode == null) {
                throw invalid("M2_ARGUMENTS_INVALID");
            }
            return new Arguments(result, executionId, port, mode);
        }
    }

    enum ExecutionMode {
        E2E_DRY_RUN,
        FORMAL_LOCAL
    }

    private static final class ReadOnlyDataSource
            implements DataSource, AutoCloseable {
        private final TushareControlledAcceptanceDataSource delegate;

        private ReadOnlyDataSource(int port, char[] password) {
            this.delegate = new TushareControlledAcceptanceDataSource(port,
                    TushareControlledAcceptanceDataSource.SslMode
                            .DISABLE_LOCAL_ONLY, password);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            try {
                connection.setReadOnly(true);
                if (!connection.isReadOnly()) {
                    throw new SQLException("M2_DATABASE_READ_ONLY_REQUIRED");
                }
                return connection;
            } catch (Throwable error) {
                connection.close();
                if (error instanceof SQLException sql) {
                    throw sql;
                }
                throw new SQLException("M2_DATABASE_READ_ONLY_REQUIRED",
                        error);
            }
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            throw new SQLFeatureNotSupportedException(
                    "M2_EXPLICIT_DATABASE_CREDENTIALS_FORBIDDEN");
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
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface != null && iface.isInstance(this)
                    || delegate.isWrapperFor(iface);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
