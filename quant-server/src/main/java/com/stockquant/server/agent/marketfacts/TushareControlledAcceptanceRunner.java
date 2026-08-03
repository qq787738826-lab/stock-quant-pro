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
        try {
            Captured<ExecutionHandle> captured =
                    TushareControlledAcceptanceOutputAudit.captureControlledProcess(
                            registry -> prepare(args, environment, registry));
            try (ExecutionHandle handle = captured.value()) {
                if (!captured.auditResult().captureComplete()
                        || !captured.auditResult().clean()) {
                    return EXIT_REJECTED;
                }
                Decision decision = handle.complete(captured.auditResult());
                return decision.reducedResearchOperationalReady()
                        ? EXIT_SUCCESS : EXIT_REJECTED;
            }
        } catch (Throwable ignored) {
            return EXIT_REJECTED;
        }
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

            environment.initializeGovernance(
                    database, authorization, proof);

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
            execution.closeBeforeFinalAudit();
            threads.verifyNoNewNonDaemonThreads();
            return execution;
        } catch (Throwable error) {
            if (execution != null) {
                execution.close();
            } else if (database != null) {
                database.close();
            }
            throw error;
        }
    }

    interface RunnerEnvironment {
        VerifiedBuildProof loadBuildProof(
                TushareControlledAcceptanceLaunchPlan plan);

        TushareControlledAcceptanceLaunchPlan loadPlan(String[] args);

        TushareControlledAcceptanceSecretChannel secretChannel();

        RuntimeDatabase openDatabase(
                TushareControlledAcceptanceLaunchPlan plan,
                char[] password
        );

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
    }

    interface RuntimeDatabase extends AutoCloseable {
        javax.sql.DataSource dataSource();

        @Override
        void close();
    }

    interface ExecutionHandle extends AutoCloseable {
        void closeBeforeFinalAudit();

        Decision complete(AuditResult audit);

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
            if (!proof.governanceEligible()) {
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
            TushareControlledAcceptanceExecutor executor = components.executor();
            PendingExecution pending;
            try {
                pending = executor.executeBeforeFinalAudit(
                        authorization, plan.command(), buildProof,
                        ExecutionSource.REAL_CONTROLLED_ACCEPTANCE);
            } catch (Throwable error) {
                components.close();
                database.close();
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
                public void close() {
                    try {
                        closeBeforeFinalAudit();
                    } finally {
                        database.close();
                    }
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
