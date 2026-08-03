package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationService.DatabasePreparationException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationService.PreparationReport;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.AuditResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.CapturedExecutionException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveRegistry;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceSecretChannel.SecretValue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * One-shot non-Spring entry for preparing the dedicated research database.
 * The default PREPARATION_ONLY mode validates the frozen path without opening
 * a database; formal mutation must be selected explicitly after user approval.
 */
public final class TushareControlledAcceptanceDatabasePreparer {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    static final String ENTRY_VERSION = "F1F_B2_DATABASE_PREPARER_V1";
    static final String START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareControlledAcceptanceDatabasePreparer";

    private TushareControlledAcceptanceDatabasePreparer() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new ProductionEnvironment()));
    }

    static int run(String[] args, PreparationEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        try {
            Captured<PreparationReport> captured =
                    TushareControlledAcceptanceOutputAudit
                            .captureDatabasePreparationProcess(
                                    registry -> prepare(args, environment, registry));
            AuditResult audit = captured.auditResult();
            if (!audit.captureComplete() || !audit.clean()) {
                renderFailure(false,
                        TushareControlledAcceptanceDatabasePreparationService.Phase
                                .NON_SECRET_PLAN_VALIDATED,
                        "TUSHARE_DATABASE_PREPARATION_OUTPUT_AUDIT_FAILED",
                        audit);
                return EXIT_REJECTED;
            }
            System.out.println(captured.value().render(true));
            return EXIT_SUCCESS;
        } catch (CapturedExecutionException error) {
            DatabasePreparationException preparationFailure =
                    findPreparationFailure(error);
            boolean mutated = preparationFailure != null
                    && preparationFailure.targetMutated();
            var phase = preparationFailure == null
                    ? TushareControlledAcceptanceDatabasePreparationService.Phase
                    .NON_SECRET_PLAN_VALIDATED
                    : preparationFailure.phase();
            String reason = preparationFailure == null
                    ? safeReason(error.getCause()) : preparationFailure.safeCode();
            renderFailure(mutated, phase, reason, error.auditResult());
            return EXIT_REJECTED;
        } catch (Throwable error) {
            renderFailure(false,
                    TushareControlledAcceptanceDatabasePreparationService.Phase
                            .NON_SECRET_PLAN_VALIDATED,
                    safeReason(error), null);
            return EXIT_REJECTED;
        }
    }

    private static PreparationReport prepare(
            String[] args,
            PreparationEnvironment environment,
            SensitiveRegistry registry
    ) throws Exception {
        ThreadSnapshot threads = ThreadSnapshot.capture();
        TushareControlledAcceptanceDatabasePreparationPlan plan =
                environment.loadPlan(args);
        if (!plan.databaseExecutionAllowed()) {
            PreparationReport report = environment.validateOnly(plan);
            threads.verifyNoNewNonDaemonThreads();
            return report;
        }

        registry.requireDatabasePreparationSecrets();
        char[] adminCopy;
        try (SecretValue administrator = environment.secretChannel()
                .readAdministratorDatabasePassword()) {
            register(registry, SensitiveKind.ADMINISTRATOR_DATABASE_PASSWORD,
                    administrator);
            adminCopy = administrator.copy();
        }
        try {
            PreparationReport report = environment.prepare(plan, adminCopy,
                    () -> readDedicatedSecret(environment, registry),
                    bootstrap -> register(
                            registry,
                            SensitiveKind.DEDICATED_BOOTSTRAP_PASSWORD,
                            bootstrap));
            threads.verifyNoNewNonDaemonThreads();
            return report;
        } finally {
            Arrays.fill(adminCopy, '\0');
        }
    }

    private static char[] readDedicatedSecret(
            PreparationEnvironment environment,
            SensitiveRegistry registry
    ) {
        try (SecretValue dedicated = environment.secretChannel()
                .readDedicatedDatabasePassword()) {
            register(registry, SensitiveKind.DEDICATED_DATABASE_PASSWORD,
                    dedicated);
            return dedicated.copy();
        }
    }

    private static void register(
            SensitiveRegistry registry,
            SensitiveKind kind,
            SecretValue value
    ) {
        char[] copy = value.copy();
        try {
            registry.register(kind, copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    private static void register(
            SensitiveRegistry registry,
            SensitiveKind kind,
            char[] value
    ) {
        char[] copy = value.clone();
        try {
            registry.register(kind, copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    private static void renderFailure(
            boolean mutated,
            TushareControlledAcceptanceDatabasePreparationService.Phase phase,
            String reason,
            AuditResult audit
    ) {
        String safe = reason != null && reason.matches("TUSHARE_[A-Z0-9_]+")
                ? reason : "TUSHARE_DATABASE_PREPARATION_FAILED";
        System.out.println("DATABASE_PREPARATION_STATUS="
                + (mutated ? "INCOMPLETE_NOT_APPROVED"
                : "DATABASE_PREPARATION_REJECTED"));
        System.out.println("FAILED_PHASE=" + phase);
        System.out.println("REASON_CODE=" + safe);
        System.out.println("OUTPUT_AUDIT_CLEAN="
                + (audit != null && audit.captureComplete() && audit.clean()));
        System.out.println("AUTOMATIC_ROLLBACK_PERFORMED=false");
    }

    private static DatabasePreparationException findPreparationFailure(
            Throwable error
    ) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DatabasePreparationException preparation) {
                return preparation;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String safeReason(Throwable error) {
        if (error != null && error.getMessage() != null
                && error.getMessage().matches("TUSHARE_[A-Z0-9_]+")) {
            return error.getMessage();
        }
        return "TUSHARE_DATABASE_PREPARATION_FAILED";
    }

    interface PreparationEnvironment {
        TushareControlledAcceptanceDatabasePreparationPlan loadPlan(String[] args);

        TushareControlledAcceptanceSecretChannel secretChannel();

        PreparationReport validateOnly(
                TushareControlledAcceptanceDatabasePreparationPlan plan);

        PreparationReport prepare(
                TushareControlledAcceptanceDatabasePreparationPlan plan,
                char[] administratorPassword,
                TushareControlledAcceptanceDatabasePreparationService
                        .DedicatedPasswordSupplier dedicatedPasswordSupplier,
                TushareControlledAcceptanceDatabasePreparationService
                        .BootstrapSecretRegistrar bootstrapSecretRegistrar);
    }

    static final class ProductionEnvironment implements PreparationEnvironment {
        private final TushareControlledAcceptanceSecretChannel secrets;
        private final TushareControlledAcceptanceDatabasePreparationService service;

        ProductionEnvironment() {
            this(null, new TushareControlledAcceptanceDatabasePreparationService(
                    Clock.systemUTC()));
        }

        ProductionEnvironment(
                TushareControlledAcceptanceSecretChannel secrets,
                TushareControlledAcceptanceDatabasePreparationService service
        ) {
            this.secrets = secrets;
            this.service = Objects.requireNonNull(service, "service");
        }

        @Override
        public TushareControlledAcceptanceDatabasePreparationPlan loadPlan(
                String[] args
        ) {
            TushareControlledAcceptanceDatabasePreparationPlan plan =
                    TushareControlledAcceptanceDatabasePreparationPlan.parse(args);
            verifyCurrentArtifact(plan.expectedCommit());
            return plan;
        }

        @Override
        public TushareControlledAcceptanceSecretChannel secretChannel() {
            return secrets == null
                    ? TushareControlledAcceptanceSecretChannel.consoleOnly()
                    : secrets;
        }

        @Override
        public PreparationReport validateOnly(
                TushareControlledAcceptanceDatabasePreparationPlan plan
        ) {
            return service.validateOnly(plan);
        }

        @Override
        public PreparationReport prepare(
                TushareControlledAcceptanceDatabasePreparationPlan plan,
                char[] administratorPassword,
                TushareControlledAcceptanceDatabasePreparationService
                        .DedicatedPasswordSupplier dedicatedPasswordSupplier,
                TushareControlledAcceptanceDatabasePreparationService
                        .BootstrapSecretRegistrar bootstrapSecretRegistrar
        ) {
            return service.prepare(plan, administratorPassword,
                    dedicatedPasswordSupplier, bootstrapSecretRegistrar);
        }
    }

    static void verifyCurrentArtifact(String expectedCommit) {
        Path artifact = TushareControlledAcceptanceBuildProof
                .requireSingleJarClasspath(System.getProperty("java.class.path"));
        try (JarFile jar = new JarFile(artifact.toFile())) {
            if (jar.getManifest() == null) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_ARTIFACT_MANIFEST_INVALID");
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            if (!START_CLASS.equals(attributes.getValue("Start-Class"))
                    || !expectedCommit.equals(attributes.getValue(
                    "Stock-Quant-Database-Preparation-Commit"))
                    || !ENTRY_VERSION.equals(attributes.getValue(
                    "Stock-Quant-Database-Preparation-Entry-Version"))) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_ARTIFACT_BINDING_INVALID");
            }
        } catch (IOException error) {
            throw new IllegalStateException(
                    "TUSHARE_DATABASE_PREPARATION_ARTIFACT_UNREADABLE", error);
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
                        "TUSHARE_DATABASE_PREPARATION_BACKGROUND_THREAD_DETECTED");
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
