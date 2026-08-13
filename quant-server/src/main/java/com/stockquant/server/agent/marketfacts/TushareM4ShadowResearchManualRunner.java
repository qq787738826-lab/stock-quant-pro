package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.research.DeterministicFakeModelAdapter;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter.FailureDiagnostics;
import com.stockquant.server.agent.shadowresearch.M4AsOfAgentResearchDatasetSource;
import com.stockquant.server.agent.shadowresearch.ShadowContinuousDailyMaintenanceService;
import com.stockquant.server.agent.shadowresearch.ShadowOutcomeService;
import com.stockquant.server.agent.shadowresearch.ShadowPaperPortfolioService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRequest;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.TriggerMode;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRuntime;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed one-shot M4 runner. It has no Spring Boot, scheduler or broker API. */
public final class TushareM4ShadowResearchManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final int FORMAL_PORT = 38_432;
    private static final BigDecimal HARD_COST_CNY = new BigDecimal("10.00");

    private TushareM4ShadowResearchManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Instant started = clock.instant();
        Arguments launch = null;
        TushareM4ShadowResearchResult.ResultFile resultFile = null;
        String commit = "UNKNOWN";
        Progress progress = new Progress();
        try {
            launch = Arguments.parse(args);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            commit = proof.gitCommit();
            validateProof(proof, launch);
            resultFile = TushareM4ShadowResearchResult.ResultFile.reserve(
                    launch.resultFile(), TushareM4ShadowResearchResult.failure(
                            launch.executionId(), commit, started, started,
                            0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, null,
                            "M4_RUNNING", false));
            Arguments bound = launch;
            Captured<Execution> captured = launch.mode() == ExecutionMode.FAKE
                    ? fake(bound, clock, progress)
                    : formal(bound, clock, progress);
            if (!captured.auditResult().clean()) {
                throw invalid("M4_OUTPUT_AUDIT_FAILED");
            }
            var execution = captured.value();
            if (execution.nonTradingDay()) {
                validateNonTradingDaySkip(launch, progress);
                resultFile.write(TushareM4ShadowResearchResult
                        .skippedNonTradingDay(launch.executionId(), commit,
                                started, clock.instant(), launch.tradeDate(),
                                progress.providerCalls, progress.retryCount,
                                launch.mode() == ExecutionMode.FAKE, true));
                System.out.println(
                        "M4_SHADOW_RESEARCH_STATUS=SKIPPED_NON_TRADING_DAY");
                return EXIT_SUCCESS;
            }
            validateResult(execution, launch, progress);
            resultFile.write(TushareM4ShadowResearchResult.success(
                    launch.executionId(), commit, started, clock.instant(),
                    execution.shadow(), progress.providerCalls,
                    progress.retryCount, launch.mode() == ExecutionMode.FAKE,
                    true, progress.modelDiagnostics,
                    execution.maintenance().paperExecution().fills().size()));
            System.out.println("M4_SHADOW_RESEARCH_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException capturedFailure) {
            String reason = safeCode(capturedFailure.getCause());
            if (resultFile != null) {
                resultFile.write(TushareM4ShadowResearchResult.failure(
                        launch == null ? "M4_UNKNOWN" : launch.executionId(),
                        commit, started, clock.instant(),
                        progress.providerCalls, progress.retryCount,
                        progress.modelProviderRequests(),
                        progress.inputTokens(), progress.outputTokens(),
                        progress.reasoningTokens(), progress.totalTokens(),
                        progress.accountedCost(), progress.modelDiagnostics,
                        reason, capturedFailure.auditResult() != null
                                && capturedFailure.auditResult().clean()));
            }
            System.err.println("M4_SHADOW_RESEARCH_FAILURE_REASON=" + reason);
            return EXIT_REJECTED;
        } catch (Throwable error) {
            String reason = safeCode(error);
            if (resultFile != null) {
                resultFile.write(TushareM4ShadowResearchResult.failure(
                        launch == null ? "M4_UNKNOWN" : launch.executionId(),
                        commit, started, clock.instant(),
                        progress.providerCalls, progress.retryCount,
                        progress.modelProviderRequests(),
                        progress.inputTokens(), progress.outputTokens(),
                        progress.reasoningTokens(), progress.totalTokens(),
                        progress.accountedCost(), progress.modelDiagnostics,
                        reason, false));
            }
            System.err.println("M4_SHADOW_RESEARCH_FAILURE_REASON=" + reason);
            return EXIT_REJECTED;
        }
    }

    private static Captured<Execution> fake(
            Arguments launch,
            Clock clock,
            Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureM4ShadowResearchE2eProcess(
                registry -> {
                    char[] password = "M4_E2E_DATABASE_PASSWORD".toCharArray();
                    char[] token = "M4_E2E_TUSHARE_TOKEN".toCharArray();
                    char[] key = "M4_E2E_BAILIAN_API_KEY".toCharArray();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD,
                                password);
                        registry.register(SensitiveKind.TUSHARE_TOKEN, token);
                        registry.register(SensitiveKind.BAILIAN_API_KEY, key);
                        Clock executionClock = factClock(launch, clock);
                        return execute(launch, password, token, null, clock,
                                progress, executionClock);
                    } finally {
                        Arrays.fill(password, '\0');
                        Arrays.fill(token, '\0');
                        Arrays.fill(key, '\0');
                    }
                });
    }

    private static Captured<Execution> formal(
            Arguments launch,
            Clock clock,
            Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureM4ShadowResearchProcess(registry -> {
                    try (SecretProvider secrets =
                                 CompositeSecretProvider.formalLocal(
                                         Mode.WINDOWS_CREDENTIAL_MANAGER);
                         SecretValue database =
                                 secrets.readResearchDatabasePassword();
                         SecretValue token = secrets.readTushareToken();
                         SecretValue bailian = secrets.readBailianApiKey()) {
                        char[] password = database.copy();
                        char[] tokenCopy = token.copy();
                        char[] key = bailian.copy();
                        try {
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            registry.register(SensitiveKind.TUSHARE_TOKEN,
                                    tokenCopy);
                            registry.register(SensitiveKind.BAILIAN_API_KEY,
                                    key);
                            return execute(launch, password, tokenCopy, key,
                                    clock, progress);
                        } finally {
                            Arrays.fill(password, '\0');
                            Arrays.fill(tokenCopy, '\0');
                            Arrays.fill(key, '\0');
                        }
                    }
                });
    }

    private static Execution execute(
            Arguments launch,
            char[] password,
            char[] token,
            char[] bailianKey,
            Clock clock,
            Progress progress
    ) {
        return execute(launch, password, token, bailianKey, clock, progress,
                clock);
    }

    private static Execution execute(
            Arguments launch,
            char[] password,
            char[] token,
            char[] bailianKey,
            Clock clock,
            Progress progress,
            Clock factClock
    ) {
        try (TushareControlledAcceptanceDataSource dataSource =
                     new TushareControlledAcceptanceDataSource(
                             launch.databasePort(),
                             TushareControlledAcceptanceDataSource.SslMode
                                     .DISABLE_LOCAL_ONLY,
                             password)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Flyway flyway = Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .loggers(SilentFlywayLogCreator.class.getName())
                    .load();
            if (launch.mode() == ExecutionMode.FORMAL) {
                verifyDedicated(jdbc);
            }
            flyway.migrate();
            verifyDedicated(jdbc);
            requireM4Schema(jdbc);
            var components = launch.mode() == ExecutionMode.FAKE
                    ? TushareDedicatedResearchRuntimeComponents
                    .createE2eDryRun(dataSource, factClock)
                    : TushareDedicatedResearchRuntimeComponents
                    .create(dataSource, token.clone(), clock);
            TushareM1ResearchDataModels.RunEvidence captured;
            try (components) {
                progress.providerPhase = true;
                if (launch.calendarAdmission()
                        == CalendarAdmission.UNKNOWN) {
                    TushareM4TradingCalendarAdmissionService.Admission calendar;
                    try {
                        calendar = components.m4CalendarAdmissionService()
                                .refresh(launch.tradeDate(),
                                        launch.calendarHorizonEnd(),
                                        Duration.ofSeconds(30));
                    } catch (TushareM4TradingCalendarAdmissionService
                             .CalendarAdmissionFailure error) {
                        progress.providerCalls = error.providerCalls();
                        progress.retryCount = error.retryCount();
                        throw error;
                    }
                    progress.providerCalls = calendar.providerCalls();
                    progress.retryCount = calendar.retryCount();
                    if (!calendar.open()) {
                        return Execution.skippedNonTradingDay();
                    }
                }
                captured = components.m1ResearchDataService().run(
                        TushareDedicatedResearchBatchAuthorization
                                .m1ResearchData(),
                        window(launch));
                progress.retryCount += captured.retryCount();
            } finally {
                progress.providerCalls = Math.toIntExact(
                        components.totalProviderAttemptCount());
            }
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            var facts = new PitMarketFactRepository(jdbc, mapper);
            var source = new M4AsOfAgentResearchDatasetSource(
                    new TushareM1AsOfDatasetLoader(facts));
            var repository = new ShadowResearchRepository(jdbc, mapper);
            var tx = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            var paper = new ShadowPaperPortfolioService(repository, tx);
            var observedDataset = TushareM2StrategyResearchDatasetAdapter
                    .adapt(captured.dataset()).dataset();
            var maintenance = new ShadowContinuousDailyMaintenanceService(
                    repository, paper, new ShadowOutcomeService(repository))
                    .maintain(launch.tradeDate(), observedDataset,
                            clock.instant());
            LocalDate nextTradeDate = new TushareM4NextOpenSessionResolver(
                    facts).resolve(launch.tradeDate(),
                    launch.calendarHorizonEnd(), clock.instant())
                    .orElse(null);
            Instant asOf = researchAsOf(launch, clock);
            LocalDate paperExecutionDate = paperExecutionDate(
                    nextTradeDate, asOf);
            var runtime = new ShadowResearchRuntime(repository, source, paper,
                    tx, clock);
            OpenAiResponsesModelAdapter bailian = null;
            ModelAdapter model;
            if (launch.mode() == ExecutionMode.FAKE) {
                model = new DeterministicFakeModelAdapter();
            } else {
                bailian = OpenAiResponsesModelAdapter.bailian(
                        Objects.requireNonNull(bailianKey,
                                "bailianKey").clone(),
                        Duration.ofSeconds(45), launch.maximumCostCny());
                model = bailian;
            }
            try {
                var shadow = runtime.run(request(launch, captured,
                                paperExecutionDate, asOf), model);
                if (bailian != null) {
                    progress.modelDiagnostics = bailian.diagnostics();
                }
                return Execution.completed(shadow, maintenance,
                        paperExecutionDate);
            } catch (Throwable error) {
                if (bailian != null) {
                    progress.modelDiagnostics = OpenAiResponsesModelAdapter
                            .failureDiagnostics(error)
                            .orElseGet(bailian::runtimeFailureDiagnostics);
                }
                throw error;
            }
        }
    }

    private static TushareM1ResearchWindowCommand window(Arguments launch) {
        List<SecuritySelection> securities = launch.securities().stream()
                .map(value -> new SecuritySelection(value.symbol(),
                        value.exchange())).toList();
        return new TushareM1ResearchWindowCommand(securities,
                launch.rangeStart(), launch.tradeDate(), launch.tradeDate(),
                launch.captureMode(), Duration.ofSeconds(30));
    }

    private static ShadowRequest request(
            Arguments launch,
            TushareM1ResearchDataModels.RunEvidence captured,
            LocalDate paperExecutionDate,
            Instant asOf
    ) {
        Instant nextExecution = paperExecutionDate == null ? null
                : com.stockquant.core.research.StrategyResearchModels
                .openInstant(paperExecutionDate);
        return new ShadowRequest(launch.triggerMode(), launch.tradeDate(),
                launch.rangeStart(), asOf, launch.securities(),
                launch.securities().get(0), strategies(), nextExecution,
                captured.providerCallCount(),
                "Perform evidence-bound seven-agent shadow research; freeze "
                        + "the conclusion and permit an empty paper portfolio.");
    }

    static LocalDate paperExecutionDate(
            LocalDate resolvedNextTradeDate,
            Instant researchAsOf
    ) {
        if (resolvedNextTradeDate == null) {
            return null;
        }
        Instant open = com.stockquant.core.research.StrategyResearchModels
                .openInstant(resolvedNextTradeDate);
        return open.isAfter(researchAsOf) ? resolvedNextTradeDate : null;
    }

    static Instant researchAsOf(Arguments launch, Clock clock) {
        return launch.triggerMode() == TriggerMode.HISTORICAL_REPLAY
                ? com.stockquant.core.research.StrategyResearchModels
                .closeInstant(launch.tradeDate())
                : clock.instant();
    }

    static Clock factClock(Arguments launch, Clock clock) {
        return launch.triggerMode() == TriggerMode.HISTORICAL_REPLAY
                ? Clock.fixed(researchAsOf(launch, clock),
                java.time.ZoneOffset.UTC)
                : clock;
    }

    private static List<StrategySpec> strategies() {
        return List.of(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("symbol", "ALL", "targetWeight", "0.80")),
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "5", "longWindow", "20",
                                "targetWeight", "0.25")),
                new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                        Map.of("lookback", "10", "entryDeviation", "0.02",
                                "exitDeviation", "0.00",
                                "targetWeight", "0.25")),
                new StrategySpec(StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                        Map.of("lookback", "20", "topN", "1",
                                "rebalanceEvery", "5",
                                "targetGrossExposure", "0.60")));
    }

    private static void validateResult(
            Execution execution,
            Arguments launch,
            Progress progress
    ) {
        ShadowResearchModels.ShadowExecutionResult result =
                execution.shadow();
        int expectedProviderCalls = launch.securities().size() * 3
                + (launch.calendarAdmission() == CalendarAdmission.UNKNOWN
                ? 2 : 0);
        if (progress.providerCalls != expectedProviderCalls
                || progress.retryCount != 0
                || !result.outputAuditClean()
                || !result.noFutureDataLeakage()
                || result.snapshot().report().agentRuns().stream()
                .map(value -> value.agentRole()).distinct().count() != 7
                || result.snapshot().report().toolCallCount() != 4
                || result.snapshot().report().tradingStarted()
                || result.snapshot().report().providerCalled()
                || result.portfolio().cash().signum() < 0
                || !execution.maintenance().historicalResearchUnchanged()
                || execution.maintenance().paperExecution().portfolio()
                .cash().signum() < 0
                || execution.nextTradeDate() != null
                && !result.run().paperExecutionTime().equals(
                com.stockquant.core.research.StrategyResearchModels
                        .openInstant(execution.nextTradeDate()))
                || result.conservativeCostCny().compareTo(
                launch.maximumCostCny()) > 0) {
            throw invalid("M4_RESULT_NOT_ELIGIBLE");
        }
    }

    private static void validateNonTradingDaySkip(
            Arguments launch,
            Progress progress
    ) {
        if (launch.calendarAdmission() != CalendarAdmission.UNKNOWN
                || progress.providerCalls != 2
                || progress.retryCount != 0
                || progress.modelProviderRequests() != 0
                || progress.totalTokens() != 0
                || progress.accountedCost().signum() != 0) {
            throw invalid("M4_NON_TRADING_DAY_SKIP_INVALID");
        }
    }

    private static void validateProof(
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof proof,
            Arguments launch
    ) {
        boolean eligible = launch.mode() == ExecutionMode.FAKE
                ? proof.e2eDryRunEligible()
                : proof.m4StageEligible() || proof.m6ShadowStageEligible()
                || proof.governanceEligible();
        if (!eligible || !TushareControlledAcceptanceBuildProof
                .M4_RUNNER_START_CLASS.equals(proof.runnerStartClass())
                || launch.mode() == ExecutionMode.FORMAL
                && launch.databasePort() != FORMAL_PORT
                || launch.mode() == ExecutionMode.FAKE
                && launch.databasePort() == FORMAL_PORT) {
            throw invalid("M4_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static void verifyDedicated(JdbcTemplate jdbc) {
        new TushareDedicatedResearchPersistenceGuard(jdbc,
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                .verifyBeforeProvider();
    }

    private static void requireM4Schema(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema=current_schema()
                   AND table_name IN (
                     'shadow_research_runs','shadow_research_snapshots',
                     'shadow_scheduler_dispatches',
                     'shadow_paper_portfolios','shadow_paper_positions',
                     'shadow_paper_orders',
                     'shadow_paper_fills','shadow_portfolio_snapshots',
                     'shadow_outcomes')
                """, Integer.class);
        if (!Integer.valueOf(9).equals(count)) {
            throw invalid("M4_DATABASE_MIGRATION_REQUIRED");
        }
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null;
                value = value.getCause()) {
            String message = value.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{3,127}")) {
                return message;
            }
        }
        return "M4_SHADOW_RESEARCH_FAILED";
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private record Execution(
            ShadowResearchModels.ShadowExecutionResult shadow,
            boolean nonTradingDay,
            ShadowContinuousDailyMaintenanceService.MaintenanceResult
                    maintenance,
            LocalDate nextTradeDate
    ) {
        private static Execution completed(
                ShadowResearchModels.ShadowExecutionResult shadow,
                ShadowContinuousDailyMaintenanceService.MaintenanceResult
                        maintenance,
                LocalDate nextTradeDate
        ) {
            return new Execution(Objects.requireNonNull(shadow, "shadow"),
                    false, Objects.requireNonNull(maintenance,
                    "maintenance"), nextTradeDate);
        }

        private static Execution skippedNonTradingDay() {
            return new Execution(null, true, null, null);
        }
    }

    private static final class Progress {
        private boolean providerPhase;
        private int providerCalls;
        private int retryCount;
        private FailureDiagnostics modelDiagnostics;

        private int modelProviderRequests() {
            return modelDiagnostics == null ? 0
                    : modelDiagnostics.networkCallCount();
        }

        private int inputTokens() {
            return modelDiagnostics == null ? 0
                    : modelDiagnostics.inputTokenCount();
        }

        private int outputTokens() {
            return modelDiagnostics == null ? 0
                    : modelDiagnostics.outputTokenCount();
        }

        private int reasoningTokens() {
            return modelDiagnostics == null ? 0
                    : modelDiagnostics.reasoningTokenCount();
        }

        private int totalTokens() {
            return modelDiagnostics == null ? 0
                    : modelDiagnostics.totalTokenCount();
        }

        private BigDecimal accountedCost() {
            return modelDiagnostics == null ? BigDecimal.ZERO
                    : modelDiagnostics.accountedCost();
        }
    }

    enum ExecutionMode { FAKE, FORMAL }

    record Arguments(
            Path resultFile,
            String executionId,
            int databasePort,
            ExecutionMode mode,
            List<Security> securities,
            LocalDate rangeStart,
            LocalDate tradeDate,
            CalendarAdmission calendarAdmission,
            LocalDate calendarHorizonEnd,
            TushareM1ResearchWindowCommand.Mode captureMode,
            TriggerMode triggerMode,
            BigDecimal maximumCostCny
    ) {
        static Arguments parse(String[] args) {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (String arg : args) {
                int split = arg.indexOf('=');
                if (!arg.startsWith("--") || split < 3
                        || values.put(arg.substring(2, split),
                        arg.substring(split + 1)) != null) {
                    throw invalid("M4_ARGUMENTS_INVALID");
                }
            }
            try {
                if (!values.keySet().equals(java.util.Set.of(
                        "result-file", "execution-id", "database-port",
                        "execution-mode", "securities", "range-start",
                        "trade-date", "next-trade-date",
                        "calendar-admission", "calendar-horizon-end",
                        "capture-mode",
                        "trigger-mode", "maximum-cost-cny"))) {
                    throw invalid("M4_ARGUMENTS_INVALID");
                }
                String id = values.get("execution-id");
                if (!id.matches("M4SHADOW_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")) {
                    throw invalid("M4_ARGUMENTS_INVALID");
                }
                List<Security> securities = Arrays.stream(
                                values.get("securities").split(","))
                        .map(value -> {
                            String[] parts = value.split(":");
                            if (parts.length != 2) {
                                throw invalid("M4_ARGUMENTS_INVALID");
                            }
                            return new Security(parts[0], parts[1]);
                        }).sorted().toList();
                String next = values.get("next-trade-date");
                BigDecimal cost = new BigDecimal(
                        values.get("maximum-cost-cny"));
                if (cost.signum() <= 0
                        || cost.compareTo(HARD_COST_CNY) > 0) {
                    throw invalid("M4_COST_LIMIT_INVALID");
                }
                CalendarAdmission admission = CalendarAdmission.valueOf(
                        values.get("calendar-admission"));
                LocalDate tradeDate = LocalDate.parse(
                        values.get("trade-date"));
                LocalDate horizon = LocalDate.parse(
                        values.get("calendar-horizon-end"));
                if (horizon.isBefore(tradeDate)
                        || !horizon.equals(tradeDate.plusDays(30))
                        || !"INTERNAL_CALENDAR".equals(next)) {
                    throw invalid("M4_ARGUMENTS_INVALID");
                }
                return new Arguments(Path.of(values.get("result-file")), id,
                        Integer.parseInt(values.get("database-port")),
                        ExecutionMode.valueOf(values.get("execution-mode")),
                        securities, LocalDate.parse(values.get("range-start")),
                        tradeDate,
                        admission, horizon,
                        TushareM1ResearchWindowCommand.Mode.valueOf(
                                values.get("capture-mode")),
                        TriggerMode.valueOf(values.get("trigger-mode")), cost);
            } catch (RuntimeException error) {
                if (error instanceof IllegalStateException state
                        && state.getMessage().startsWith("M4_")) {
                    throw state;
                }
                throw invalid("M4_ARGUMENTS_INVALID");
            }
        }
    }

    enum CalendarAdmission { KNOWN_OPEN, UNKNOWN }
}
