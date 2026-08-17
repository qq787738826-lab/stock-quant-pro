package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.research.DeterministicFakeModelAdapter;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter.FailureDiagnostics;
import com.stockquant.server.agent.shadowresearch.ShadowPaperPortfolioService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.researchselection.ResearchSelectionDeepResearchService;
import com.stockquant.server.researchselection.ResearchSelectionEngine;
import com.stockquant.server.researchselection.ResearchSelectionAnchorResolver;
import com.stockquant.server.researchselection.ResearchSelectionFailureCategory;
import com.stockquant.server.researchselection.ResearchSelectionModels;
import com.stockquant.server.researchselection.ResearchSelectionModels.DataCoverage;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionResult;
import com.stockquant.server.researchselection.ResearchSelectionModels.Status;
import com.stockquant.server.researchselection.ResearchSelectionRepository;
import com.stockquant.server.researchselection.ResearchSelectionSanitizedResult;
import com.stockquant.server.researchselection.ResearchUniverseV1;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Fixed manual V1.0.1 current-as-of selection runner; never starts Spring. */
public final class TushareResearchSelectionManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final int FORMAL_PORT = 38_432;

    private TushareResearchSelectionManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Instant startedAt = clock.instant();
        Arguments launch = null;
        ResearchSelectionSanitizedResult.ResultFile resultFile = null;
        Progress progress = new Progress();
        progress.startedAt = startedAt;
        String commit = "UNKNOWN";
        try {
            launch = Arguments.parse(args);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact();
            commit = proof.gitCommit();
            validateProof(proof, launch);
            resultFile = ResearchSelectionSanitizedResult.ResultFile.reserve(
                    launch.resultFile(), ResearchSelectionSanitizedResult
                            .failure(launch.executionId(), commit, startedAt,
                                    startedAt, launch.selectionRunId(),
                                    launch.publicRunId(), 0, 0, 0, null,
                                    "RESEARCH_SELECTION_RUNNING", false));
            Arguments bound = launch;
            Captured<Execution> captured = launch.mode() == ExecutionMode.FAKE
                    ? fake(bound, clock, progress)
                    : formal(bound, clock, progress);
            if (!captured.auditResult().clean()) {
                throw invalid("RESEARCH_SELECTION_OUTPUT_AUDIT_FAILED");
            }
            Execution value = captured.value();
            resultFile.write(ResearchSelectionSanitizedResult.success(
                    launch.executionId(), commit, startedAt, clock.instant(),
                    value.selection(), progress.appended,
                    progress.idempotent, progress.modelDiagnostics,
                    launch.mode() == ExecutionMode.FAKE, true));
            System.out.println("RESEARCH_SELECTION_STATUS=SUCCEEDED");
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

    private static Captured<Execution> fake(
            Arguments launch, Clock clock, Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureM4ShadowResearchE2eProcess(registry -> {
                    char[] password = "SELECTION_E2E_DB_PASSWORD".toCharArray();
                    char[] token = "SELECTION_E2E_TUSHARE_TOKEN".toCharArray();
                    char[] key = "SELECTION_E2E_BAILIAN_KEY".toCharArray();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD,
                                password);
                        registry.register(SensitiveKind.TUSHARE_TOKEN, token);
                        registry.register(SensitiveKind.BAILIAN_API_KEY, key);
                        return execute(launch, password, token, null, clock,
                                progress);
                    } finally {
                        Arrays.fill(password, '\0');
                        Arrays.fill(token, '\0');
                        Arrays.fill(key, '\0');
                    }
                });
    }

    private static Captured<Execution> formal(
            Arguments launch, Clock clock, Progress progress
    ) throws Exception {
        return TushareControlledAcceptanceOutputAudit
                .captureM4ShadowResearchProcess(registry -> {
                    try (SecretProvider secrets =
                                 CompositeSecretProvider.formalLocal(
                                         Mode.WINDOWS_CREDENTIAL_MANAGER);
                         SecretValue database =
                                 secrets.readResearchDatabasePassword();
                         SecretValue tushare = secrets.readTushareToken();
                         SecretValue bailian = secrets.readBailianApiKey()) {
                        char[] password = database.copy();
                        char[] token = tushare.copy();
                        char[] key = bailian.copy();
                        try {
                            registry.register(SensitiveKind.DATABASE_PASSWORD,
                                    password);
                            registry.register(SensitiveKind.TUSHARE_TOKEN,
                                    token);
                            registry.register(SensitiveKind.BAILIAN_API_KEY,
                                    key);
                            return execute(launch, password, token, key,
                                    clock, progress);
                        } finally {
                            Arrays.fill(password, '\0');
                            Arrays.fill(token, '\0');
                            Arrays.fill(key, '\0');
                        }
                    }
                });
    }

    private static Execution execute(
            Arguments launch,
            char[] password,
            char[] token,
            char[] bailian,
            Clock clock,
            Progress progress
    ) {
        try (TushareControlledAcceptanceDataSource dataSource =
                     new TushareControlledAcceptanceDataSource(
                             launch.databasePort(),
                             TushareControlledAcceptanceDataSource.SslMode
                                     .DISABLE_LOCAL_ONLY, password)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (launch.mode() == ExecutionMode.FORMAL) {
                verifyDedicated(jdbc);
            }
            Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .loggers(SilentFlywayLogCreator.class.getName())
                    .load().migrate();
            verifyDedicated(jdbc);
            requireSchema(jdbc);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            var repository = new ResearchSelectionRepository(jdbc, mapper);
            if (launch.mode() == ExecutionMode.FAKE) {
                bootstrapFakeRunIfMissing(repository, launch, clock);
            }
            var config = repository.config(launch.selectionRunId());
            if (!config.publicRunId().equals(launch.publicRunId())
                    || !config.gitCommit().equals(launch.gitCommit())
                    || config.triggerMode() != launch.triggerMode()
                    || config.status() != Status.QUEUED) {
                throw invalid("RESEARCH_SELECTION_RUN_BINDING_INVALID");
            }
            var facts = new PitMarketFactRepository(jdbc, mapper);
            var loader = new TushareResearchUniverseDatasetLoader(facts);
            LocalDate anchor = ResearchSelectionAnchorResolver.resolve(loader,
                    config.request().auxiliaryWindow(),
                    config.researchAsOf());
            DataCoverage coverage;
            StageTransition stages = new StageTransition(repository,
                    launch.selectionRunId());
            TushareDedicatedResearchRuntimeComponents components =
                    launch.mode() == ExecutionMode.FAKE
                    ? TushareDedicatedResearchRuntimeComponents
                    .createE2eDryRun(dataSource, clock)
                    : TushareDedicatedResearchRuntimeComponents.create(
                            dataSource, token.clone(), clock);
            try {
                try (components) {
                    prepareData(loader, components, anchor,
                            config.request().auxiliaryWindow(),
                            config.researchAsOf(),
                            launch.maximumProviderRequests(), progress);
                }
                if (progress.providerCalls
                        > launch.maximumProviderRequests()) {
                    throw invalid(
                            "RESEARCH_SELECTION_PROVIDER_BUDGET_MISMATCH");
                }
                Instant actualAsOf = clock.instant();
                if (actualAsOf.isBefore(config.researchAsOf())) {
                    throw invalid("RESEARCH_SELECTION_CLOCK_INVALID");
                }
                if (!actualAsOf.equals(config.researchAsOf())) {
                    config = repository.advanceResearchAsOf(
                            launch.selectionRunId(), config.researchAsOf(),
                            actualAsOf);
                }
                anchor = ResearchSelectionAnchorResolver.resolve(loader,
                        config.request().auxiliaryWindow(),
                        config.researchAsOf());
                coverage = loader.load(ResearchUniverseV1.securities(),
                        config.request().auxiliaryWindow(), anchor,
                        config.researchAsOf()).coverage();
            } catch (Throwable error) {
                terminalize(repository, launch.selectionRunId(),
                        stages.current(), error, clock);
                throw error;
            }
            var shadowRepository = new ShadowResearchRepository(jdbc, mapper);
            var transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            var paper = new ShadowPaperPortfolioService(shadowRepository,
                    transaction);
            var deep = new ResearchSelectionDeepResearchService(
                    shadowRepository, paper, transaction, clock,
                    (signalDate, cutoff) -> new
                            TushareM4NextOpenSessionResolver(facts)
                            .resolveAfterResearchAsOf(signalDate,
                                    signalDate.plusDays(30), cutoff)
                            .map(com.stockquant.core.research
                                    .StrategyResearchModels::openInstant)
                            .orElse(null));
            var engine = new ResearchSelectionEngine(loader, clock, mapper);
            ModelAdapter model;
            OpenAiResponsesModelAdapter external = null;
            if (launch.mode() == ExecutionMode.FAKE) {
                model = new DeterministicFakeModelAdapter();
            } else {
                external = OpenAiResponsesModelAdapter.bailian(
                        Objects.requireNonNull(bailian, "bailian").clone(),
                        Duration.ofSeconds(45), launch.maximumCostCny());
                model = external;
            }
            try {
                var result = engine.run(launch.selectionRunId(),
                        launch.publicRunId(), config.request(), anchor,
                        config.researchAsOf(), launch.gitCommit(),
                        progress.providerCalls, 0, coverage, model, deep,
                        stages::advance, progress.startedNanos,
                        progress.startedAt);
                repository.complete(launch.selectionRunId(),
                        result.selection());
                if (external != null) {
                    progress.modelDiagnostics = external.diagnostics();
                }
                return new Execution(result.selection());
            } catch (Throwable error) {
                if (external != null) {
                    progress.modelDiagnostics = OpenAiResponsesModelAdapter
                            .failureDiagnostics(error).orElseGet(
                                    external::runtimeFailureDiagnostics);
                }
                terminalize(repository, launch.selectionRunId(),
                        stages.current(), error, clock);
                throw error;
            }
        }
    }

    private static void terminalize(
            ResearchSelectionRepository repository,
            long runId,
            Status current,
            Throwable error,
            Clock clock
    ) {
        String reason = safeCode(error);
        try {
            repository.fail(runId, current,
                    ResearchSelectionFailureCategory.from(reason), reason,
                    clock.instant());
        } catch (RuntimeException terminalFailure) {
            error.addSuppressed(terminalFailure);
        }
    }

    private static void prepareData(
            TushareResearchUniverseDatasetLoader loader,
            TushareDedicatedResearchRuntimeComponents components,
            LocalDate anchor,
            int window,
            Instant asOf,
            int authorizedProviderRequests,
            Progress progress
    ) {
        try {
            loader.load(ResearchUniverseV1.securities(), window, anchor,
                    asOf);
            return;
        } catch (TushareResearchUniverseDatasetLoader
                 .IncompleteUniverseException incomplete) {
            int required;
            if (incomplete.incrementalAnchorOnly()) {
                required = 2;
            } else {
                required = TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS;
            }
            requireBoundBudget(authorizedProviderRequests, required);
            capture(components, anchor, window, progress,
                    required == 2);
        } catch (IllegalStateException calendar) {
            if (!"RESEARCH_UNIVERSE_CALENDAR_WINDOW_INCOMPLETE".equals(
                    calendar.getMessage())) {
                throw calendar;
            }
            requireBoundBudget(authorizedProviderRequests,
                    TushareManualBoundedSession
                            .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS);
            capture(components, anchor, window, progress, false);
        }
    }

    private static void requireBoundBudget(int authorized, int required) {
        if (authorized != required) {
            throw invalid("RESEARCH_SELECTION_PROVIDER_BUDGET_BINDING_INVALID");
        }
    }

    private static void capture(
            TushareDedicatedResearchRuntimeComponents components,
            LocalDate anchor,
            int window,
            Progress progress,
            boolean dailyIncrement
    ) {
        try {
            TushareResearchUniverseCaptureService.CaptureEvidence evidence =
                    dailyIncrement
                    ? components.researchUniverseCaptureService()
                    .captureDailyIncrement(ResearchUniverseV1.securities(),
                            anchor, Duration.ofSeconds(30))
                    : components.researchUniverseCaptureService().capture(
                            ResearchUniverseV1.securities(),
                            rangeStart(anchor, window), anchor,
                            Duration.ofSeconds(30));
            progress.providerCalls += evidence.providerCallCount();
            progress.appended += evidence.appendedObservations();
            progress.idempotent += evidence.idempotentChainTailHits();
        } catch (TushareResearchUniverseCaptureService.CaptureFailure failure) {
            progress.providerCalls += failure.providerCallCount();
            throw failure;
        }
    }

    private static LocalDate rangeStart(LocalDate anchor, int window) {
        long naturalDays = Math.min(
                TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_MAX_MARKET_FACT_NATURAL_DAYS - 1L,
                Math.max(100L, (window * 8L + 4L) / 5L));
        return anchor.minusDays(naturalDays);
    }

    private static void verifyDedicated(JdbcTemplate jdbc) {
        new TushareDedicatedResearchPersistenceGuard(jdbc,
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                .verifyBeforeProvider();
    }

    /**
     * A packaged dry run starts from a disposable empty PostgreSQL cluster.
     * Formal mode can never enter this path and must bind a run created by
     * the production API before the Broker request exists.
     */
    private static void bootstrapFakeRunIfMissing(
            ResearchSelectionRepository repository,
            Arguments launch,
            Clock clock
    ) {
        try {
            repository.config(launch.selectionRunId());
        } catch (IllegalStateException missing) {
            if (!"RESEARCH_SELECTION_RUN_MISSING".equals(
                    missing.getMessage())) {
                throw missing;
            }
            var created = repository.create(launch.publicRunId(),
                    new ResearchSelectionModels.SelectionRequest(
                            launch.triggerMode(), 20, 60, 10, 5, true),
                    clock.instant(), launch.gitCommit());
            if (created.runId() != launch.selectionRunId()) {
                throw invalid("RESEARCH_SELECTION_FAKE_RUN_ID_INVALID");
            }
        }
    }

    private static void requireSchema(JdbcTemplate jdbc) {
        Integer version = jdbc.queryForObject("""
                SELECT COALESCE(max(version::integer), 0)
                  FROM tushare_research.flyway_schema_history WHERE success
                """, Integer.class);
        if (version == null || version != 17) {
            throw invalid("RESEARCH_SELECTION_SCHEMA_VERSION_INVALID");
        }
    }

    private static void validateProof(
            TushareControlledAcceptanceBuildProof.VerifiedBuildProof proof,
            Arguments launch
    ) {
        if (!launch.gitCommit().equals(proof.gitCommit())
                || !TushareControlledAcceptanceBuildProof
                .RESEARCH_SELECTION_RUNNER_START_CLASS.equals(
                        proof.runnerStartClass())
                || !(launch.mode() == ExecutionMode.FAKE
                ? proof.e2eDryRunEligible()
                : proof.researchSelectionEligible())) {
            throw invalid("RESEARCH_SELECTION_BUILD_PROOF_NOT_ELIGIBLE");
        }
    }

    private static void writeFailure(
            ResearchSelectionSanitizedResult.ResultFile resultFile,
            Arguments launch,
            String commit,
            Instant startedAt,
            Clock clock,
            Progress progress,
            Throwable error,
            boolean auditClean
    ) {
        String reason = safeCode(error);
        if (resultFile != null) {
            resultFile.write(ResearchSelectionSanitizedResult.failure(
                    launch == null ? "SELECT_UNKNOWN" : launch.executionId(),
                    commit, startedAt, clock.instant(),
                    launch == null ? 0 : launch.selectionRunId(),
                    launch == null ? "SELECT_UNKNOWN" : launch.publicRunId(),
                    progress.providerCalls, 0,
                    progress.modelDiagnostics == null ? 0
                            : progress.modelDiagnostics.networkCallCount(),
                    progress.modelDiagnostics, reason, auditClean));
        }
        System.err.println("RESEARCH_SELECTION_FAILURE_REASON=" + reason);
    }

    private static String safeCode(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message != null && message.matches(
                "[A-Z][A-Z0-9_]{3,127}") ? message
                : "RESEARCH_SELECTION_EXECUTION_FAILED";
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    enum ExecutionMode { FAKE, FORMAL }

    record Arguments(
            Path resultFile,
            String executionId,
            long selectionRunId,
            String publicRunId,
            String gitCommit,
            ResearchSelectionModels.TriggerMode triggerMode,
            int databasePort,
            int maximumProviderRequests,
            ExecutionMode mode,
            BigDecimal maximumCostCny
    ) {
        static Arguments parse(String[] args) {
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw invalid("RESEARCH_SELECTION_ARGUMENTS_INVALID");
                }
                int split = arg.indexOf('=');
                if (values.put(arg.substring(2, split),
                        arg.substring(split + 1)) != null) {
                    throw invalid("RESEARCH_SELECTION_ARGUMENTS_INVALID");
                }
            }
            if (!values.keySet().equals(java.util.Set.of("result-file",
                    "execution-id", "selection-run-id", "public-run-id",
                    "git-commit", "selection-trigger", "database-port",
                    "maximum-provider-requests", "execution-mode",
                    "maximum-cost-cny"))) {
                throw invalid("RESEARCH_SELECTION_ARGUMENTS_INVALID");
            }
            try {
                Arguments value = new Arguments(
                        Path.of(values.get("result-file")),
                        values.get("execution-id"),
                        Long.parseLong(values.get("selection-run-id")),
                        values.get("public-run-id"), values.get("git-commit"),
                        ResearchSelectionModels.TriggerMode.valueOf(
                                values.get("selection-trigger")
                                        .toUpperCase(Locale.ROOT)),
                        Integer.parseInt(values.get("database-port")),
                        Integer.parseInt(values.get(
                                "maximum-provider-requests")),
                        ExecutionMode.valueOf(values.get("execution-mode")
                                .toUpperCase(Locale.ROOT)),
                        new BigDecimal(values.get("maximum-cost-cny")));
                value.validate();
                return value;
            } catch (RuntimeException error) {
                throw invalid("RESEARCH_SELECTION_ARGUMENTS_INVALID");
            }
        }

        private void validate() {
            if (!executionId.matches(
                    "SELECTEXEC_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || selectionRunId < 1 || !publicRunId.matches(
                    "SELECT_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")
                    || !gitCommit.matches("[0-9a-f]{40}")
                    || databasePort < 1 || databasePort > 65_535
                    || mode == ExecutionMode.FORMAL
                    && databasePort != FORMAL_PORT
                    || mode == ExecutionMode.FAKE
                    && databasePort == FORMAL_PORT
                    || !List.of(0, 2,
                    TushareManualBoundedSession
                            .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS)
                    .contains(maximumProviderRequests)
                    || maximumCostCny.signum() <= 0
                    || maximumCostCny.compareTo(new BigDecimal("5.00")) > 0) {
                throw invalid("RESEARCH_SELECTION_ARGUMENTS_INVALID");
            }
        }
    }

    private record Execution(SelectionResult selection) {
    }

    private static final class Progress {
        private final long startedNanos = System.nanoTime();
        private Instant startedAt;
        private int providerCalls;
        private int appended;
        private int idempotent;
        private FailureDiagnostics modelDiagnostics;
    }

    private static final class StageTransition {
        private final ResearchSelectionRepository repository;
        private final long runId;
        private Status current = Status.QUEUED;

        private StageTransition(ResearchSelectionRepository repository,
                                long runId) {
            this.repository = repository;
            this.runId = runId;
        }

        private void advance(Status next) {
            repository.transition(runId, current, next);
            current = next;
        }

        private Status current() {
            return current;
        }
    }
}
