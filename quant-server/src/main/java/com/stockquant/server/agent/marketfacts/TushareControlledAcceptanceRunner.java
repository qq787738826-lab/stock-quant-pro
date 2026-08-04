package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Decision;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecutor.PendingExecution;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.AuditResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveRegistry;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceSecretChannel.SecretValue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot non-Spring entry point for a future user-approved F1F-B2 run.
 *
 * <p>This class manually constructs an exact component whitelist. It never
 * starts the normal application bootstrap, component scanning, a web server,
 * default Flyway, scheduling or any downstream research stage.</p>
 */
public final class TushareControlledAcceptanceRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;

    private TushareControlledAcceptanceRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new ProductionEnvironment()));
    }

    static int run(String[] args, RunnerEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        AtomicReference<ExecutionHandle> prepared = new AtomicReference<>();
        try {
            environment.prepareE2eDryRunBeforeAudit(args);
            Captured<ExecutionHandle> captured;
            try {
                captured = TushareControlledAcceptanceOutputAudit
                        .captureControlledProcess(registry -> {
                            ExecutionHandle handle = prepare(
                                    args, environment, registry);
                            prepared.set(handle);
                            return handle;
                        });
            } catch (TushareControlledAcceptanceOutputAudit
                     .CapturedExecutionException error) {
                ExecutionHandle handle = prepared.getAndSet(null);
                if (handle != null) {
                    try {
                        handle.failCapturedAudit(
                                error.auditResult(), error);
                    } finally {
                        handle.close();
                    }
                }
                writeSafeFailure(error);
                return EXIT_REJECTED;
            }
            try (ExecutionHandle handle = captured.value()) {
                prepared.set(null);
                try {
                    Decision decision = handle.complete(captured.auditResult());
                    return captured.auditResult().clean()
                            && handle.successfulExit(decision)
                            ? EXIT_SUCCESS : EXIT_REJECTED;
                } catch (Throwable error) {
                    handle.fail(error);
                    return EXIT_REJECTED;
                }
            }
        } catch (Throwable error) {
            writeSafeFailure(error);
            return EXIT_REJECTED;
        }
    }

    private static void writeSafeFailure(Throwable error) {
        Throwable current = error;
        String generic = null;
        StringBuilder types = new StringBuilder();
        while (current != null) {
            if (!types.isEmpty()) {
                types.append('>');
            }
            types.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                if (message.startsWith("TUSHARE_")
                        || message.startsWith("F1F_")) {
                    System.err.println(
                            "TUSHARE_CONTROLLED_ACCEPTANCE_SAFE_FAILURE=" + message);
                    System.err.println(
                            "TUSHARE_CONTROLLED_ACCEPTANCE_SAFE_FAILURE_TYPES=" + types);
                    return;
                }
                generic = message;
            }
            current = current.getCause();
        }
        System.err.println(
                "TUSHARE_CONTROLLED_ACCEPTANCE_SAFE_FAILURE="
                        + (generic == null ? "CONTROLLED_EXECUTION_FAILED" : generic));
        System.err.println(
                "TUSHARE_CONTROLLED_ACCEPTANCE_SAFE_FAILURE_TYPES=" + types);
    }

    private static ExecutionHandle prepare(
            String[] args,
            RunnerEnvironment environment,
            SensitiveRegistry registry
    ) throws Exception {
        ThreadSnapshot threads = ThreadSnapshot.capture();
        RuntimeDatabase database = null;
        ExecutionHandle execution = null;
        try {
            TushareControlledAcceptanceLaunchPlan plan =
                    environment.loadPlan(args);
            VerifiedBuildProof proof = environment.loadBuildProof(plan);
            plan.validateBuildProof(proof);
            TushareControlledAcceptanceAuthorization authorization =
                    plan.authorization(proof);

            if (plan.e2eDryRun()) {
                registerE2eDryRunSyntheticSecrets(registry);
                database = environment.openE2eDryRunDatabase(plan);
            } else {
                try (SecretValue password = environment.secretChannel()
                        .readDatabasePassword()) {
                    char[] auditCopy = password.copy();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD, auditCopy);
                    } finally {
                        Arrays.fill(auditCopy, '\0');
                    }
                    char[] dataSourceCopy = password.copy();
                    try {
                        database = environment.openDatabase(plan, dataSourceCopy);
                    } finally {
                        Arrays.fill(dataSourceCopy, '\0');
                    }
                }
            }

            environment.initializeGovernance(
                    database, authorization, proof);

            if (plan.e2eDryRun()) {
                execution = environment.executeE2eDryRun(
                        database, plan, authorization, proof);
            } else {
                try (SecretValue token = environment.secretChannel().readTushareToken()) {
                    char[] auditCopy = token.copy();
                    try {
                        registry.register(SensitiveKind.TUSHARE_TOKEN, auditCopy);
                    } finally {
                        Arrays.fill(auditCopy, '\0');
                    }
                    char[] runtimeCopy = token.copy();
                    try {
                        execution = environment.execute(
                                database, plan, authorization, proof, runtimeCopy);
                    } finally {
                        Arrays.fill(runtimeCopy, '\0');
                    }
                }
            }
            execution.closeBeforeFinalAudit();
            threads.verifyNoNewNonDaemonThreads();
            return execution;
        } catch (Throwable error) {
            if (execution != null) {
                try {
                    execution.fail(error);
                } finally {
                    execution.close();
                }
            } else if (database != null) {
                database.close();
            }
            throw error;
        }
    }

    private static void registerE2eDryRunSyntheticSecrets(
            SensitiveRegistry registry
    ) {
        char[] databasePassword =
                "E2E_DRY_RUN_DATABASE_PASSWORD".toCharArray();
        char[] providerToken = "E2E_DRY_RUN_FAKE_TOKEN".toCharArray();
        try {
            registry.register(SensitiveKind.DATABASE_PASSWORD, databasePassword);
            registry.register(SensitiveKind.TUSHARE_TOKEN, providerToken);
        } finally {
            Arrays.fill(databasePassword, '\0');
            Arrays.fill(providerToken, '\0');
        }
    }

    interface RunnerEnvironment {
        default void prepareE2eDryRunBeforeAudit(String[] args) {
            // Only the packaged production environment supports this
            // network-free, fresh-database test bootstrap.
        }

        VerifiedBuildProof loadBuildProof(
                TushareControlledAcceptanceLaunchPlan plan);

        TushareControlledAcceptanceLaunchPlan loadPlan(String[] args);

        TushareControlledAcceptanceSecretChannel secretChannel();

        RuntimeDatabase openDatabase(
                TushareControlledAcceptanceLaunchPlan plan,
                char[] password
        );

        default RuntimeDatabase openE2eDryRunDatabase(
                TushareControlledAcceptanceLaunchPlan plan
        ) {
            throw new IllegalStateException(
                    "TUSHARE_E2E_DRY_RUN_ENVIRONMENT_UNAVAILABLE");
        }

        default void prepareE2eDryRunDatabase(
                RuntimeDatabase database,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof
        ) {
            throw new IllegalStateException(
                    "TUSHARE_E2E_DRY_RUN_ENVIRONMENT_UNAVAILABLE");
        }

        void initializeGovernance(
                RuntimeDatabase database,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof
        );

        ExecutionHandle execute(
                RuntimeDatabase database,
                TushareControlledAcceptanceLaunchPlan plan,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof,
                char[] token
        );

        default ExecutionHandle executeE2eDryRun(
                RuntimeDatabase database,
                TushareControlledAcceptanceLaunchPlan plan,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof
        ) {
            throw new IllegalStateException(
                    "TUSHARE_E2E_DRY_RUN_ENVIRONMENT_UNAVAILABLE");
        }
    }

    interface RuntimeDatabase extends AutoCloseable {
        javax.sql.DataSource dataSource();

        @Override
        void close();
    }

    interface ExecutionHandle extends AutoCloseable {
        void closeBeforeFinalAudit();

        Decision complete(AuditResult audit);

        default void fail(Throwable error) {
            Objects.requireNonNull(error, "error");
        }

        default void failCapturedAudit(AuditResult audit, Throwable error) {
            Objects.requireNonNull(audit, "audit");
            fail(error);
        }

        default boolean successfulExit(Decision decision) {
            return Objects.requireNonNull(decision, "decision")
                    .reducedResearchOperationalReady();
        }

        @Override
        void close();
    }

    static final class ProductionEnvironment implements RunnerEnvironment {
        private final TushareControlledAcceptanceSecretChannel secrets;

        ProductionEnvironment() {
            this.secrets = null;
        }

        ProductionEnvironment(TushareControlledAcceptanceSecretChannel secrets) {
            this.secrets = Objects.requireNonNull(secrets, "secrets");
        }

        @Override
        public VerifiedBuildProof loadBuildProof(
                TushareControlledAcceptanceLaunchPlan plan
        ) {
            VerifiedBuildProof proof =
                    TushareControlledAcceptanceBuildProof
                            .loadCurrentExecutorArtifact(plan.buildProofPath());
            boolean eligible = plan.e2eDryRun()
                    ? proof.e2eDryRunEligible()
                    : proof.governanceEligible();
            if (!eligible) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_FORMAL_BUILD_REQUIRED");
            }
            return proof;
        }

        @Override
        public TushareControlledAcceptanceLaunchPlan loadPlan(String[] args) {
            if (args == null || args.length != 1
                    || !args[0].startsWith("--authorization-file=")
                    || args[0].length() == "--authorization-file=".length()) {
                throw new IllegalArgumentException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_LAUNCH_ARGUMENTS_INVALID");
            }
            return TushareControlledAcceptanceLaunchPlan.load(Path.of(
                    args[0].substring("--authorization-file=".length())));
        }

        @Override
        public TushareControlledAcceptanceSecretChannel secretChannel() {
            return secrets == null
                    ? TushareControlledAcceptanceSecretChannel.consoleOnly()
                    : secrets;
        }

        @Override
        public RuntimeDatabase openDatabase(
                TushareControlledAcceptanceLaunchPlan plan,
                char[] password
        ) {
            TushareControlledAcceptanceDataSource source =
                    new TushareControlledAcceptanceDataSource(
                            plan.databasePort(), plan.sslMode(), password);
            return runtimeDatabase(source);
        }

        @Override
        public void prepareE2eDryRunBeforeAudit(String[] args) {
            TushareControlledAcceptanceLaunchPlan plan = loadPlan(args);
            if (!plan.e2eDryRun()) {
                return;
            }
            VerifiedBuildProof proof = loadBuildProof(plan);
            plan.validateBuildProof(proof);
            TushareControlledAcceptanceAuthorization authorization =
                    plan.authorization(proof);
            try (RuntimeDatabase database = openE2eDryRunDatabase(plan)) {
                prepareE2eDryRunDatabase(database, authorization, proof);
            }
        }

        @Override
        public RuntimeDatabase openE2eDryRunDatabase(
                TushareControlledAcceptanceLaunchPlan plan
        ) {
            if (!plan.e2eDryRun()) {
                throw new IllegalStateException(
                        "TUSHARE_E2E_DRY_RUN_AUTHORIZATION_REQUIRED");
            }
            char[] syntheticPassword = "E2E_DRY_RUN_DATABASE_PASSWORD".toCharArray();
            TushareControlledAcceptanceDataSource source;
            try {
                source = new TushareControlledAcceptanceDataSource(
                        plan.databasePort(), plan.sslMode(), syntheticPassword);
            } finally {
                Arrays.fill(syntheticPassword, '\0');
            }
            return runtimeDatabase(source);
        }

        @Override
        public void prepareE2eDryRunDatabase(
                RuntimeDatabase database,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof
        ) {
            TushareControlledAcceptanceE2eDryRunDatabase.initialize(
                    database.dataSource(), authorization, buildProof);
        }

        @Override
        public void initializeGovernance(
                RuntimeDatabase database,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof
        ) {
            TushareControlledAcceptanceDatabaseGuard.migrateGovernance(
                    database.dataSource(),
                    TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE,
                    authorization,
                    buildProof);
        }

        @Override
        public ExecutionHandle execute(
                RuntimeDatabase database,
                TushareControlledAcceptanceLaunchPlan plan,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof,
                char[] token
        ) {
            TushareControlledAcceptanceComponents components =
                    TushareControlledAcceptanceComponents.create(
                            database.dataSource(), token, Clock.systemUTC());
            return startExecution(database, plan, authorization, buildProof,
                    components, ExecutionSource.REAL_CONTROLLED_ACCEPTANCE);
        }

        @Override
        public ExecutionHandle executeE2eDryRun(
                RuntimeDatabase database,
                TushareControlledAcceptanceLaunchPlan plan,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof
        ) {
            if (!plan.e2eDryRun() || !buildProof.e2eDryRunEligible()
                    || authorization.userApproval()
                    != TushareControlledAcceptanceAuthorization.UserApproval.E2E_DRY_RUN) {
                throw new IllegalStateException(
                        "TUSHARE_E2E_DRY_RUN_AUTHORIZATION_REQUIRED");
            }
            TushareControlledAcceptanceComponents components =
                    TushareControlledAcceptanceComponents.createE2eDryRun(
                            database.dataSource(), Clock.systemUTC());
            return startExecution(database, plan, authorization, buildProof,
                    components, ExecutionSource.TEST);
        }

        private ExecutionHandle startExecution(
                RuntimeDatabase database,
                TushareControlledAcceptanceLaunchPlan plan,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof buildProof,
                TushareControlledAcceptanceComponents components,
                ExecutionSource source
        ) {
            TushareControlledAcceptanceExecutor executor = components.executor();
            PendingExecution pending;
            try {
                pending = executor.executeBeforeFinalAudit(
                        authorization, plan.command(), buildProof, source);
            } catch (Throwable error) {
                try {
                    executor.interruptUnexpected(
                            authorization.acceptanceId(), error);
                } catch (Throwable recoveryFailure) {
                    error.addSuppressed(recoveryFailure);
                } finally {
                    components.close();
                    database.close();
                }
                throw error;
            }
            return new ExecutionHandle() {
                private boolean componentsClosed;

                @Override
                public void closeBeforeFinalAudit() {
                    if (!componentsClosed) {
                        components.close();
                        componentsClosed = true;
                    }
                }

                @Override
                public Decision complete(AuditResult audit) {
                    return executor.completeAfterAudit(pending, audit);
                }

                @Override
                public void fail(Throwable error) {
                    try {
                        executor.interruptUnexpected(
                                authorization.acceptanceId(), error);
                    } catch (Throwable recoveryFailure) {
                        error.addSuppressed(recoveryFailure);
                    }
                }

                @Override
                public void failCapturedAudit(
                        AuditResult audit,
                        Throwable error
                ) {
                    try {
                        executor.completeAfterAudit(pending, audit);
                    } catch (Throwable auditFailure) {
                        error.addSuppressed(auditFailure);
                        fail(error);
                    }
                }

                @Override
                public boolean successfulExit(Decision decision) {
                    if (source == ExecutionSource.TEST) {
                        return decision.status()
                                == TushareControlledAcceptanceExecution.ExecutionStatus
                                .SUCCEEDED_CANDIDATE
                                && decision.qualification()
                                == TushareControlledAcceptanceExecution
                                .EvidenceQualification.TEST_ONLY_CANDIDATE
                                && !decision.reducedResearchOperationalReady();
                    }
                    return ExecutionHandle.super.successfulExit(decision);
                }

                @Override
                public void close() {
                    try {
                        closeBeforeFinalAudit();
                    } finally {
                        database.close();
                    }
                }
            };
        }

        private static RuntimeDatabase runtimeDatabase(
                TushareControlledAcceptanceDataSource source
        ) {
            return new RuntimeDatabase() {
                @Override
                public javax.sql.DataSource dataSource() {
                    return source;
                }

                @Override
                public void close() {
                    source.close();
                }
            };
        }
    }

    private record ThreadSnapshot(Set<Long> nonDaemonThreadIds) {
        static ThreadSnapshot capture() {
            return new ThreadSnapshot(nonDaemonThreads());
        }

        void verifyNoNewNonDaemonThreads() {
            Set<Long> current = nonDaemonThreads();
            current.removeAll(nonDaemonThreadIds);
            if (!current.isEmpty()) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_BACKGROUND_THREAD_DETECTED");
            }
        }

        private static Set<Long> nonDaemonThreads() {
            Set<Long> result = new LinkedHashSet<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && !thread.isDaemon()) {
                    result.add(thread.getId());
                }
            }
            return result;
        }
    }
}
