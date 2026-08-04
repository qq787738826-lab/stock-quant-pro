package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceSecretChannel.SecretValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Console-only, provider-free recovery for one stranded controlled execution. */
public final class TushareControlledAcceptanceRecoveryRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    static final String RECOVERY_REASON = "STRANDED_RUNNING_PROCESS_EXITED";

    private TushareControlledAcceptanceRecoveryRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        RecoveryPlan plan;
        try {
            plan = RecoveryPlan.parse(args);
        } catch (Throwable ignored) {
            return EXIT_REJECTED;
        }
        try {
            Captured<RecoveryResult> captured =
                    TushareControlledAcceptanceOutputAudit.captureControlledProcess(
                            registry -> recover(plan, registry));
            if (!captured.auditResult().captureComplete()
                    || !captured.auditResult().clean()) {
                return EXIT_REJECTED;
            }
            RecoveryResult result = captured.value();
            System.out.println("F1F_B2_RECOVERY_STATUS=" + result.status());
            System.out.println("F1F_B2_RECOVERY_FINALIZED_AT=" + result.finalizedAt());
            System.out.println("F1F_B2_RECOVERY_REASON=" + result.reason());
            System.out.println("F1F_B2_RECOVERY_APPLIED=" + result.applied());
            Files.writeString(plan.resultFile(),
                    "status=" + result.status() + '\n'
                            + "finalizedAt=" + result.finalizedAt() + '\n'
                            + "reason=" + result.reason() + '\n'
                            + "applied=" + result.applied() + '\n',
                    StandardCharsets.UTF_8);
            return EXIT_SUCCESS;
        } catch (Throwable error) {
            writeSafeFailure(plan.resultFile(), error);
            return EXIT_REJECTED;
        }
    }

    private static void writeSafeFailure(Path resultFile, Throwable error) {
        String reason = "TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_FAILED";
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                reason = message;
            }
            current = current.getCause();
        }
        try {
            Files.writeString(resultFile,
                    "status=FAILED\nreason=" + reason + '\n',
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // The process exit code remains authoritative when even the
            // caller-approved, secret-free result path cannot be written.
        }
    }

    private static RecoveryResult recover(
            RecoveryPlan plan,
            TushareControlledAcceptanceOutputAudit.SensitiveRegistry registry
    ) {
        TushareControlledAcceptanceDataSource source = null;
        try (SecretValue password = TushareControlledAcceptanceSecretChannel
                .consoleOnly().readDatabasePassword()) {
            char[] auditCopy = password.copy();
            try {
                registry.register(SensitiveKind.DATABASE_PASSWORD, auditCopy);
            } finally {
                Arrays.fill(auditCopy, '\0');
            }
            char[] absentProviderToken =
                    "RECOVERY_PROVIDER_TOKEN_NOT_READ".toCharArray();
            try {
                registry.register(SensitiveKind.TUSHARE_TOKEN,
                        absentProviderToken);
            } finally {
                Arrays.fill(absentProviderToken, '\0');
            }
            char[] connectionCopy = password.copy();
            try {
                source = new TushareControlledAcceptanceDataSource(
                        plan.databasePort(),
                        TushareControlledAcceptanceDataSource.SslMode.DISABLE_LOCAL_ONLY,
                        connectionCopy);
            } finally {
                Arrays.fill(connectionCopy, '\0');
            }
            JdbcTemplate jdbc = new JdbcTemplate(source);
            TushareDedicatedResearchPersistenceGuard dedicated =
                    new TushareDedicatedResearchPersistenceGuard(
                            jdbc, TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE);
            TushareControlledAcceptanceDatabaseGuard guard =
                    new TushareControlledAcceptanceDatabaseGuard(jdbc, dedicated);
            guard.verifyBeforeProvider();
            TushareControlledAcceptanceExecutionRepository repository =
                    new TushareControlledAcceptanceExecutionRepository(
                            jdbc,
                            new ObjectMapper().registerModule(new JavaTimeModule()),
                            new DataSourceTransactionManager(source),
                            Clock.systemUTC());
            boolean applied = repository.recoverStrandedRunning(
                    plan.acceptanceId(), RECOVERY_REASON);
            var stored = repository.find(plan.acceptanceId()).orElseThrow(() ->
                    blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RECORD_MISSING"));
            if (stored.status() != ExecutionStatus.INTERRUPTED
                    || stored.finalizedAt() == null
                    || !"RECOVERY".equals(stored.failureStage())
                    || !RECOVERY_REASON.equals(stored.safeFailureReason())
                    || stored.providerCallCount() != 0
                    || stored.retryCount() != 0
                    || stored.captureBatchId() != null) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_INVALID");
            }
            return new RecoveryResult(
                    stored.status(), stored.finalizedAt(),
                    stored.safeFailureReason(), applied);
        } finally {
            if (source != null) {
                source.close();
            }
        }
    }

    private record RecoveryPlan(
            String acceptanceId,
            int databasePort,
            Path resultFile
    ) {
        private RecoveryPlan {
            acceptanceId = TushareControlledAcceptanceExecution.safeId(acceptanceId);
            resultFile = Objects.requireNonNull(resultFile, "resultFile")
                    .toAbsolutePath().normalize();
            if (databasePort <= 0 || databasePort > 65_535) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_PORT_INVALID");
            }
            Path temp = Path.of(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath().normalize();
            if (!resultFile.startsWith(temp)
                    || !resultFile.getFileName().toString().startsWith(
                    "stock-quant-f1f-b2-recovery-result-")) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_RESULT_INVALID");
            }
        }

        static RecoveryPlan parse(String[] args) {
            if (args == null || args.length != 3) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_ARGUMENTS_INVALID");
            }
            String acceptanceId = value(args[0], "--acceptance-id=");
            String port = value(args[1], "--database-port=");
            Path resultFile = Path.of(value(args[2], "--result-file="));
            try {
                return new RecoveryPlan(
                        acceptanceId, Integer.parseInt(port), resultFile);
            } catch (NumberFormatException error) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_PORT_INVALID");
            }
        }

        private static String value(String argument, String prefix) {
            if (argument == null || !argument.startsWith(prefix)
                    || argument.length() == prefix.length()) {
                throw blocked(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_ARGUMENTS_INVALID");
            }
            return argument.substring(prefix.length());
        }
    }

    private record RecoveryResult(
            ExecutionStatus status,
            Instant finalizedAt,
            String reason,
            boolean applied
    ) {
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
