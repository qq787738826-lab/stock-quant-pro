package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareM3AgentResearchSmokeResult.Audit;
import com.stockquant.server.agent.marketfacts.TushareM3AgentResearchSmokeResult.DatabaseSnapshot;
import com.stockquant.server.agent.marketfacts.TushareM3AgentResearchSmokeResult.Result;
import com.stockquant.server.agent.marketfacts.TushareM3AgentResearchSmokeResult.ResultFile;
import com.stockquant.server.agent.research.AgentPromptCatalog;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import com.stockquant.server.agent.research.AgentResearchModels.RuntimeLimits;
import com.stockquant.server.agent.research.AgentResearchReportFiles;
import com.stockquant.server.agent.research.AgentResearchRuntime;
import com.stockquant.server.agent.research.AgentResearchToolGateway;
import com.stockquant.server.agent.research.DeterministicFakeModelAdapter;
import com.stockquant.server.agent.research.M1AgentResearchDatasetSource;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter
        .FailureDiagnostics;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Fixed-scope, read-only, non-Spring packaged runner for the M3 smoke. */
public final class TushareM3AgentResearchManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    static final String RESULT_VERSION =
            TushareM3AgentResearchSmokeResult.VERSION;
    private static final int FORMAL_DATABASE_PORT = 38_432;
    private static final String RESULT_ARG = "--result-file=";
    private static final String REPORT_DIRECTORY_ARG = "--report-directory=";
    private static final String EXECUTION_ARG = "--execution-id=";
    private static final String PORT_ARG = "--database-port=";
    private static final String MODE_ARG = "--execution-mode=";
    private static final String COST_ARG = "--maximum-cost-cny=";

    private TushareM3AgentResearchManualRunner() {
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
        Path reportPath = null;
        Audit audit = Audit.notRun();
        try {
            launch = Arguments.parse(args);
            proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            validateProof(proof, launch);
            Path artifact = TushareControlledAcceptanceBuildProof
                    .requireSingleJarClasspath(System.getProperty(
                            "java.class.path"));
            validatePaths(launch, Objects.requireNonNull(
                    artifact.getParent()));
            resultFile = ResultFile.reserve(launch.resultFile(),
                    artifact.getParent(), result("RUNNING", launch, proof,
                            startedAt, startedAt, null, null, Audit.notRun(),
                            null, "M3_RUNNING"));

            Arguments boundLaunch = launch;
            Captured<Execution> captured = boundLaunch.executionMode()
                    .usesBailian()
                    ? captureBailianExecution(boundLaunch, clock)
                    : captureFakeModelExecution(boundLaunch, clock);
            execution = captured.value();
            audit = audit(captured.auditResult());
            if (!audit.clean()) {
                throw invalid("M3_OUTPUT_AUDIT_FAILED");
            }
            reportPath = new AgentResearchReportFiles(
                    launch.reportDirectory(),
                    new ObjectMapper().findAndRegisterModules())
                    .write(execution.report());
            resultFile.write(result("SUCCEEDED", launch, proof, startedAt,
                    clock.instant(), execution, reportPath, audit,
                    execution.modelDiagnostics(), "M3_SUCCEEDED"));
            System.out.println("M3_AGENT_RESEARCH_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException capturedFailure) {
            audit = capturedFailure.auditResult() == null
                    ? Audit.notRun() : audit(capturedFailure.auditResult());
            String reason = safeCode(capturedFailure.getCause());
            FailureDiagnostics diagnostics = modelDiagnostics(
                    capturedFailure.getCause());
            writeFailure(resultFile, launch, proof, startedAt, clock,
                    execution, reportPath, audit, diagnostics, reason);
            safeFailure(reason);
            return EXIT_REJECTED;
        } catch (Throwable error) {
            String reason = safeCode(error);
            FailureDiagnostics diagnostics = modelDiagnostics(error);
            writeFailure(resultFile, launch, proof, startedAt, clock,
                    execution, reportPath, audit, diagnostics, reason);
            safeFailure(reason);
            return EXIT_REJECTED;
        }
    }

    private static Captured<Execution> captureFakeModelExecution(
            Arguments launch,
            Clock clock
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureDatabaseOnlyProcess(registry -> {
                    if (launch.executionMode() == ExecutionMode.E2E_DRY_RUN) {
                        char[] password = syntheticPassword();
                        try {
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            return execute(launch, password, null, clock);
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
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            return execute(launch, password, null, clock);
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    }
                });
    }

    private static Captured<Execution> captureBailianExecution(
            Arguments launch,
            Clock clock
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureM3BailianResearchProcess(registry -> {
                    try (SecretProvider secrets =
                                 CompositeSecretProvider.formalLocal(
                                         Mode.WINDOWS_CREDENTIAL_MANAGER);
                         SecretValue database =
                                  secrets.readResearchDatabasePassword();
                         SecretValue bailian = secrets.readBailianApiKey()) {
                        char[] password = database.copy();
                        char[] apiKey = bailian.copy();
                        try {
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            registry.register(SensitiveKind.BAILIAN_API_KEY,
                                    apiKey);
                            return execute(launch, password, apiKey, clock);
                        } finally {
                            Arrays.fill(password, '\0');
                            Arrays.fill(apiKey, '\0');
                        }
                    }
                });
    }

    private static Execution execute(
            Arguments launch,
            char[] password,
            char[] bailianApiKey,
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
                throw invalid("M3_DATABASE_READ_ONLY_REQUIRED");
            }
            DatabaseSnapshot before = snapshot(jdbc);
            PitMarketFactRepository repository = new PitMarketFactRepository(
                    jdbc, new ObjectMapper().findAndRegisterModules());
            TushareM1ResearchDatasetService m1 =
                    new TushareM1ResearchDatasetService(repository, jdbc);
            var source = new M1AgentResearchDatasetSource(
                    new TushareM2StrategyResearchDatasetAdapter(m1));
            AgentResearchToolGateway gateway = new AgentResearchToolGateway(
                    source, new DefaultStrategyResearchApi(),
                    BacktestConfig.standard(), clock);
            ResearchReport report;
            OpenAiResponsesModelAdapter bailianAdapter =
                    launch.executionMode().usesBailian()
                            ? OpenAiResponsesModelAdapter.bailian(
                            Objects.requireNonNull(bailianApiKey,
                                    "bailianApiKey"), Duration.ofSeconds(45),
                            launch.maximumCostCny())
                            : null;
            ModelAdapter adapter = bailianAdapter == null
                    ? new DeterministicFakeModelAdapter() : bailianAdapter;
            try (AgentResearchRuntime runtime = new AgentResearchRuntime(
                    gateway, adapter,
                    new AgentPromptCatalog(), clock)) {
                report = runtime.run(task(launch.executionId(),
                        clock.instant(), launch.executionMode()));
            }
            validateReport(report, launch.executionMode(),
                    launch.maximumCostCny());
            DatabaseSnapshot after = snapshot(jdbc);
            if (!before.equals(after)) {
                throw invalid("M3_PERMANENT_DATABASE_MUTATION_DETECTED");
            }
            return new Execution(report, before, after,
                    bailianAdapter == null ? null
                            : bailianAdapter.diagnostics());
        }
    }

    static ResearchTask task(
            String executionId,
            Instant knowledgeCutoff,
            ExecutionMode executionMode
    ) {
        List<Security> securities = List.of(
                new Security("600000", "SSE"),
                new Security("000001", "SZSE")).stream().sorted().toList();
        return new ResearchTask("M3TASK_" + executionId,
                "Compare representative long-only strategies using the "
                        + "accepted M1 research dataset and M2 backtest "
                        + "engine; quantify risk and preserve limitations.",
                securities, LocalDate.of(2025, 1, 2),
                LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 10),
                knowledgeCutoff, securities.get(0), List.of(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("symbol", "ALL", "targetWeight", "0.80")),
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "2", "longWindow", "5",
                                "targetWeight", "0.30")),
                new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                        Map.of("lookback", "3", "entryDeviation", "0.02",
                                "exitDeviation", "0.00",
                                "targetWeight", "0.30")),
                new StrategySpec(StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                        Map.of("lookback", "3", "topN", "1",
                                "rebalanceEvery", "2",
                                "targetGrossExposure", "0.60"))),
                new RuntimeLimits(2, 8, 16,
                        executionMode.usesBailian()
                                ? Duration.ofMinutes(8)
                                : Duration.ofSeconds(30)));
    }

    static ResearchTask task(String executionId, Instant knowledgeCutoff) {
        return task(executionId, knowledgeCutoff,
                ExecutionMode.E2E_DRY_RUN);
    }

    private static void validateReport(
            ResearchReport report,
            ExecutionMode executionMode,
            BigDecimal maximumCostCny
    ) {
        Set<AgentRole> roles = report.agentRuns().stream()
                .map(value -> value.agentRole()).collect(Collectors.toSet());
        boolean bailian = executionMode.usesBailian();
        if (!roles.equals(Set.of(AgentRole.values()))
                || report.toolCallCount() != 4
                || report.modelCallCount() != 13
                || !report.dataset().typedFactReadback()
                || !report.dataset().systemKnowledgeReadback()
                || !report.dataset().dataQualityPassed()
                || !report.dataset().noFutureDataLeakage()
                || !report.dataset().formulaOnlyQfq()
                || report.strategyExperiments().experiments().size() != 4
                || report.strategyExperiments().experiments().stream()
                .anyMatch(value -> !value.accountingInvariant()
                        || !value.lookAheadGuard())
                || !report.risk().accountingPassed()
                || !report.risk().lookAheadPassed()
                || !report.criticReview().correctionApplied()
                || !report.researchOnly() || report.providerCalled()
                || report.shadowStarted() || report.tradingStarted()
                || report.deterministic() == bailian
                || bailian && (report.totalModelUsage().inputTokens() <= 0
                || report.totalModelUsage().outputTokens() <= 0
                || report.totalModelUsage().estimatedCost().signum() <= 0
                || report.totalModelUsage().estimatedCost().compareTo(
                maximumCostCny) > 0
                || !"CNY".equals(report.totalModelUsage().costCurrency())
                || report.agentRuns().stream().anyMatch(value ->
                !"BAILIAN".equals(value.modelProvider())
                        || !OpenAiResponsesModelAdapter.BAILIAN_MODEL.equals(
                        value.model())))
                || !bailian && (report.totalModelUsage().estimatedCost()
                .compareTo(BigDecimal.ZERO) != 0
                || !"NONE".equals(
                report.totalModelUsage().costCurrency()))) {
            throw invalid("M3_RESEARCH_REPORT_NOT_ELIGIBLE");
        }
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
            throw invalid("M3_DATABASE_SNAPSHOT_INVALID");
        }
        return value;
    }

    private static void validateProof(
            VerifiedBuildProof proof,
            Arguments launch
    ) {
        boolean eligible = launch.executionMode() == ExecutionMode.E2E_DRY_RUN
                ? proof.e2eDryRunEligible()
                : proof.m3StageEligible() || proof.governanceEligible();
        if (!eligible || !TushareControlledAcceptanceBuildProof
                .M3_RUNNER_START_CLASS.equals(proof.runnerStartClass())
                || launch.executionMode().formal()
                && launch.databasePort() != FORMAL_DATABASE_PORT
                || launch.executionMode() == ExecutionMode.E2E_DRY_RUN
                && launch.databasePort() == FORMAL_DATABASE_PORT) {
            throw invalid("M3_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static void validatePaths(Arguments launch, Path artifactRoot) {
        Path root = artifactRoot.toAbsolutePath().normalize();
        Path report = launch.reportDirectory().toAbsolutePath().normalize();
        if (!report.startsWith(root) || report.equals(root)
                || containsAi(report)) {
            throw invalid("M3_REPORT_DIRECTORY_INVALID");
        }
    }

    private static Result result(
            String status,
            Arguments launch,
            VerifiedBuildProof proof,
            Instant startedAt,
            Instant completedAt,
            Execution execution,
            Path reportPath,
            Audit audit,
            FailureDiagnostics modelDiagnostics,
            String reason
    ) {
        return new Result(RESULT_VERSION, status, launch.executionId(),
                proof.gitCommit(), proof.actualArtifactSha256(),
                proof.runnerStartClass(), startedAt, completedAt,
                execution == null ? null : execution.report(),
                reportPath == null ? null : reportPath.toString(),
                execution != null,
                execution != null && execution.before().equals(
                        execution.after()),
                execution == null ? null : execution.before(),
                execution == null ? null : execution.after(), audit,
                0, 0, modelDiagnostics, reason);
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
            Path reportPath,
            Audit audit,
            FailureDiagnostics modelDiagnostics,
            String reason
    ) {
        if (file == null || launch == null || proof == null) {
            return;
        }
        try {
            file.write(result("FAILED", launch, proof, startedAt,
                    clock.instant(), execution, reportPath, audit,
                    modelDiagnostics, reason));
        } catch (Throwable ignored) {
            // Reserved RUNNING evidence remains fail-closed.
        }
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null;
                value = value.getCause()) {
            String message = value.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
        }
        return "M3_AGENT_RESEARCH_EXECUTION_FAILED";
    }

    private static FailureDiagnostics modelDiagnostics(Throwable error) {
        return OpenAiResponsesModelAdapter.failureDiagnostics(error)
                .orElse(null);
    }

    private static void safeFailure(String reason) {
        System.err.println("M3_AGENT_RESEARCH_FAILURE_REASON=" + reason);
    }

    private static char[] syntheticPassword() {
        return "M3_E2E_DRY_RUN_DATABASE_PASSWORD".toCharArray();
    }

    private static boolean containsAi(Path path) {
        for (Path value : path) {
            if (".ai".equalsIgnoreCase(value.toString())) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private record Execution(
            ResearchReport report,
            DatabaseSnapshot before,
            DatabaseSnapshot after,
            FailureDiagnostics modelDiagnostics
    ) {
    }

    record Arguments(
            Path resultFile,
            Path reportDirectory,
            String executionId,
            int databasePort,
            ExecutionMode executionMode,
            BigDecimal maximumCostCny
    ) {
        static Arguments parse(String[] args) {
            if (args == null || args.length != 6) {
                throw invalid("M3_ARGUMENTS_INVALID");
            }
            Path result = null;
            Path reportDirectory = null;
            String executionId = null;
            Integer port = null;
            ExecutionMode mode = null;
            BigDecimal maximumCost = null;
            for (String value : args) {
                if (value != null && value.startsWith(RESULT_ARG)
                        && result == null) {
                    result = Path.of(value.substring(RESULT_ARG.length()));
                } else if (value != null
                        && value.startsWith(REPORT_DIRECTORY_ARG)
                        && reportDirectory == null) {
                    reportDirectory = Path.of(value.substring(
                            REPORT_DIRECTORY_ARG.length()));
                } else if (value != null && value.startsWith(EXECUTION_ARG)
                        && executionId == null) {
                    executionId = value.substring(EXECUTION_ARG.length());
                } else if (value != null && value.startsWith(PORT_ARG)
                        && port == null) {
                    try {
                        port = Integer.parseInt(value.substring(
                                PORT_ARG.length()));
                    } catch (NumberFormatException error) {
                        throw invalid("M3_ARGUMENTS_INVALID");
                    }
                } else if (value != null && value.startsWith(MODE_ARG)
                        && mode == null) {
                    try {
                        mode = ExecutionMode.valueOf(value.substring(
                                MODE_ARG.length()));
                    } catch (IllegalArgumentException error) {
                        throw invalid("M3_ARGUMENTS_INVALID");
                    }
                } else if (value != null && value.startsWith(COST_ARG)
                        && maximumCost == null) {
                    try {
                        maximumCost = new BigDecimal(value.substring(
                                COST_ARG.length()));
                    } catch (NumberFormatException error) {
                        throw invalid("M3_ARGUMENTS_INVALID");
                    }
                } else {
                    throw invalid("M3_ARGUMENTS_INVALID");
                }
            }
            if (result == null || reportDirectory == null
                    || executionId == null || !executionId.matches(
                    "M3SMOKE_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || port == null || port <= 0 || port > 65_535
                    || mode == null || maximumCost == null
                    || maximumCost.signum() <= 0
                    || maximumCost.compareTo(OpenAiResponsesModelAdapter
                    .M3_BAILIAN_HARD_COST_LIMIT_CNY) > 0
                    || containsAi(result)
                    || containsAi(reportDirectory)) {
                throw invalid("M3_ARGUMENTS_INVALID");
            }
            return new Arguments(result, reportDirectory, executionId, port,
                    mode, maximumCost);
        }
    }

    enum ExecutionMode {
        E2E_DRY_RUN,
        FORMAL_LOCAL,
        FORMAL_LOCAL_BAILIAN;

        boolean usesBailian() {
            return this == FORMAL_LOCAL_BAILIAN;
        }

        boolean formal() {
            return this != E2E_DRY_RUN;
        }
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
                    throw new SQLException("M3_DATABASE_READ_ONLY_REQUIRED");
                }
                return connection;
            } catch (Throwable error) {
                connection.close();
                if (error instanceof SQLException sql) {
                    throw sql;
                }
                throw new SQLException("M3_DATABASE_READ_ONLY_REQUIRED",
                        error);
            }
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            throw new SQLFeatureNotSupportedException(
                    "M3_EXPLICIT_DATABASE_CREDENTIALS_FORBIDDEN");
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
