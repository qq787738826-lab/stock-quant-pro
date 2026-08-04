package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.AuditResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveRegistry;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.SymbolResearchResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchBatchResult;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.CheckResult;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.FinalStatus;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.OutputAuditSummary;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.QfqSummary;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.ResultFile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Independent, one-shot, non-Spring entry point for reduced-research Day 001.
 *
 * <p>This runner never invokes the F1F-B2 acceptance executor or governance
 * state machine. It does not start Spring Boot, a web server, a Controller,
 * scheduler, Agent, Shadow, backtest, F2B/F3 or trading.</p>
 */
public final class TushareReducedResearchManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final String AUTHORIZATION_ARGUMENT = "--authorization-file=";
    private static final String RESULT_ARGUMENT = "--result-file=";
    private static final String SECRET_MODE_ARGUMENT = "--secret-mode=";

    private TushareReducedResearchManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new ProductionEnvironment()));
    }

    static int run(String[] args, RunnerEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        Instant startedAt = environment.clock().instant();
        TushareReducedResearchDay001Authorization authorization = null;
        ResultFile resultFile = null;
        ExecutionProgress progress = new ExecutionProgress();
        try {
            RunnerArguments launch = RunnerArguments.parse(args);
            authorization = environment.loadAuthorization(
                    launch.authorizationFile());
            authorization.validateAt(environment.clock());
            VerifiedBuildProof proof = environment.loadBuildProof(authorization);
            environment.validateBuildProof(authorization, proof);

            resultFile = environment.reserveResult(
                    launch.resultFile(),
                    TushareReducedResearchDay001Result.interruptedPlaceholder(
                            authorization, startedAt));
            environment.consumeAuthorization(
                    launch.authorizationFile(), authorization, startedAt);
            environment.prepareE2eDryRunDatabase(authorization, proof);

            TushareReducedResearchDay001Authorization authorizedRun =
                    authorization;
            Captured<ExecutionEvidence> captured =
                    TushareControlledAcceptanceOutputAudit
                            .captureControlledProcess(registry ->
                                    environment.execute(
                                            authorizedRun, proof, registry,
                                            startedAt, progress,
                                            launch.secretMode()));
            OutputAuditSummary audit = OutputAuditSummary.from(
                    captured.auditResult());
            Instant completedAt = environment.clock().instant();
            if (!audit.successful()) {
                resultFile.write(failureResult(
                        authorization, FinalStatus.FAILED_OUTPUT_AUDIT,
                        startedAt, completedAt, progress, audit,
                        "TUSHARE_REDUCED_RESEARCH_OUTPUT_AUDIT_FAILED"));
                writeSafeFailure(FinalStatus.FAILED_OUTPUT_AUDIT,
                        "TUSHARE_REDUCED_RESEARCH_OUTPUT_AUDIT_FAILED");
                return EXIT_REJECTED;
            }
            ExecutionEvidence evidence = captured.value();
            evidence.validateFor(authorization);
            resultFile.write(TushareReducedResearchDay001Result.success(
                    authorization, startedAt, completedAt,
                    evidence.batchId(), evidence.appendedCount(),
                    evidence.idempotentCount(), QfqSummary.passed(), audit));
            System.out.println("TUSHARE_REDUCED_RESEARCH_DAY001_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException capturedFailure) {
            String code = safeCode(capturedFailure.getCause());
            AuditResult capturedAudit = capturedFailure.auditResult();
            OutputAuditSummary audit = capturedAudit == null
                    ? OutputAuditSummary.notRun()
                    : OutputAuditSummary.from(capturedAudit);
            FinalStatus status = audit.captureComplete() && !audit.clean()
                    ? FinalStatus.FAILED_OUTPUT_AUDIT
                    : classify(capturedFailure.getCause(), progress);
            writeFailureIfReserved(environment, resultFile, authorization,
                    status, startedAt, progress, audit, code);
            writeSafeFailure(status, code);
            return EXIT_REJECTED;
        } catch (Throwable error) {
            String code = safeCode(error);
            FinalStatus status = classify(error, progress);
            writeFailureIfReserved(environment, resultFile, authorization,
                    status, startedAt, progress,
                    OutputAuditSummary.notRun(), code);
            writeSafeFailure(status, code);
            return EXIT_REJECTED;
        }
    }

    private static void writeFailureIfReserved(
            RunnerEnvironment environment,
            ResultFile resultFile,
            TushareReducedResearchDay001Authorization authorization,
            FinalStatus status,
            Instant startedAt,
            ExecutionProgress progress,
            OutputAuditSummary audit,
            String code
    ) {
        if (resultFile == null || authorization == null) {
            return;
        }
        try {
            resultFile.write(failureResult(
                    authorization, status, startedAt,
                    environment.clock().instant(), progress, audit, code));
        } catch (Throwable resultFailure) {
            writeSafeFailure(FinalStatus.FAILED_OUTPUT_AUDIT,
                    "TUSHARE_REDUCED_RESEARCH_RESULT_WRITE_FAILED");
        }
    }

    private static TushareReducedResearchDay001Result failureResult(
            TushareReducedResearchDay001Authorization authorization,
            FinalStatus status,
            Instant startedAt,
            Instant completedAt,
            ExecutionProgress progress,
            OutputAuditSummary audit,
            String code
    ) {
        CheckResult readback = progress.readbackPassed
                ? CheckResult.PASSED
                : progress.batchId == null ? CheckResult.NOT_RUN : CheckResult.FAILED;
        QfqSummary qfq = progress.qfqPassed
                ? QfqSummary.passed() : QfqSummary.notRun();
        return TushareReducedResearchDay001Result.failure(
                authorization, status, startedAt, completedAt,
                progress.endpointCalls(), progress.providerCalls,
                progress.retryCount, progress.batchId,
                progress.appendedCount, progress.idempotentCount,
                readback, readback, qfq, audit, code);
    }

    private static FinalStatus classify(
            Throwable error,
            ExecutionProgress progress
    ) {
        if (isInterrupted(error)) {
            Thread.currentThread().interrupt();
            return FinalStatus.INTERRUPTED;
        }
        if (find(error, GatewayException.class) != null) {
            return FinalStatus.FAILED_PROVIDER;
        }
        if (find(error, SQLException.class) != null) {
            return progress.providerCalls == 0
                    ? FinalStatus.FAILED_PRE_PROVIDER
                    : FinalStatus.FAILED_PERSISTENCE;
        }
        String code = safeCode(error);
        if (code.contains("OUTPUT_AUDIT")) {
            return FinalStatus.FAILED_OUTPUT_AUDIT;
        }
        if (code.contains("READBACK") || code.contains("VALIDATION")
                || code.contains("FACT_WINDOW") || code.contains("QFQ")
                || code.contains("CALL_CONTRACT") || code.contains("RESULT_INVALID")
                || code.contains("MODE_RESULT_MISMATCH")) {
            return FinalStatus.FAILED_VALIDATION;
        }
        if (code.contains("CAPTURE") || code.contains("PERSISTENCE")
                || code.contains("TRANSACTION") || code.contains("DATABASE_WRITE")) {
            return FinalStatus.FAILED_PERSISTENCE;
        }
        return progress.providerCalls == 0
                ? FinalStatus.FAILED_PRE_PROVIDER
                : FinalStatus.FAILED_VALIDATION;
    }

    private static boolean isInterrupted(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private static <T extends Throwable> T find(
            Throwable error,
            Class<T> type
    ) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static String safeCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
            current = current.getCause();
        }
        return "TUSHARE_REDUCED_RESEARCH_EXECUTION_FAILED";
    }

    private static void writeSafeFailure(FinalStatus status, String code) {
        String safe = code != null && code.matches("[A-Z][A-Z0-9_]{7,127}")
                ? code : "TUSHARE_REDUCED_RESEARCH_EXECUTION_FAILED";
        System.err.println("TUSHARE_REDUCED_RESEARCH_FAILURE_STAGE="
                + Objects.requireNonNull(status, "status").name());
        System.err.println("TUSHARE_REDUCED_RESEARCH_FAILURE_REASON=" + safe);
        System.err.println("TUSHARE_REDUCED_RESEARCH_SAFE_FAILURE=" + safe);
    }

    interface RunnerEnvironment {
        Clock clock();

        TushareReducedResearchDay001Authorization loadAuthorization(Path path);

        VerifiedBuildProof loadBuildProof(
                TushareReducedResearchDay001Authorization authorization);

        default void validateBuildProof(
                TushareReducedResearchDay001Authorization authorization,
                VerifiedBuildProof proof
        ) {
            authorization.validateBuildProof(proof);
        }

        ResultFile reserveResult(
                Path path,
                TushareReducedResearchDay001Result initial);

        void consumeAuthorization(
                Path authorizationFile,
                TushareReducedResearchDay001Authorization authorization,
                Instant consumedAt);

        default void prepareE2eDryRunDatabase(
                TushareReducedResearchDay001Authorization authorization,
                VerifiedBuildProof proof
        ) {
            // Unit environments do not bootstrap databases.
        }

        ExecutionEvidence execute(
                TushareReducedResearchDay001Authorization authorization,
                VerifiedBuildProof proof,
                SensitiveRegistry registry,
                Instant startedAt,
                ExecutionProgress progress,
                Mode secretMode
        ) throws Exception;
    }

    static final class ProductionEnvironment implements RunnerEnvironment {
        private final Clock clock;

        ProductionEnvironment() {
            this(Clock.systemUTC());
        }

        ProductionEnvironment(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public Clock clock() {
            return clock;
        }

        @Override
        public TushareReducedResearchDay001Authorization loadAuthorization(
                Path path
        ) {
            return TushareReducedResearchDay001Authorization.load(path);
        }

        @Override
        public VerifiedBuildProof loadBuildProof(
                TushareReducedResearchDay001Authorization authorization
        ) {
            return TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact(authorization.buildProofPath());
        }

        @Override
        public ResultFile reserveResult(
                Path path,
                TushareReducedResearchDay001Result initial
        ) {
            return ResultFile.reserve(path, initial);
        }

        @Override
        public void consumeAuthorization(
                Path authorizationFile,
                TushareReducedResearchDay001Authorization authorization,
                Instant consumedAt
        ) {
            Path marker = Path.of(authorizationFile.toAbsolutePath().normalize()
                    + ".consumed");
            String content = "authorization.version="
                    + TushareReducedResearchDay001Authorization.VERSION + '\n'
                    + "run.id=" + authorization.runId() + '\n'
                    + "authorization.fingerprint=" + authorization.fingerprint() + '\n'
                    + "consumed.at=" + consumedAt + '\n';
            try {
                Files.writeString(marker, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException error) {
                throw new IllegalStateException(
                        "TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_ALREADY_CONSUMED",
                        error);
            }
        }

        @Override
        public void prepareE2eDryRunDatabase(
                TushareReducedResearchDay001Authorization authorization,
                VerifiedBuildProof proof
        ) {
            if (!authorization.e2eDryRun()) {
                return;
            }
            char[] password = syntheticDatabasePassword();
            try (RuntimeDatabase database = openDatabase(
                    authorization, password)) {
                TushareReducedResearchDay001E2eDatabase.initialize(
                        database.dataSource(), authorization, proof);
            } finally {
                Arrays.fill(password, '\0');
            }
        }

        @Override
        public ExecutionEvidence execute(
                TushareReducedResearchDay001Authorization authorization,
                VerifiedBuildProof proof,
                SensitiveRegistry registry,
                Instant startedAt,
                ExecutionProgress progress,
                Mode secretMode
        ) {
            Objects.requireNonNull(proof, "proof");
            RuntimeDatabase database = null;
            TushareDedicatedResearchRuntimeComponents components = null;
            try {
                if (authorization.e2eDryRun()) {
                    char[] password = syntheticDatabasePassword();
                    char[] token = syntheticProviderToken();
                    try {
                        registry.register(SensitiveKind.DATABASE_PASSWORD, password);
                        registry.register(SensitiveKind.TUSHARE_TOKEN, token);
                        database = openDatabase(authorization, password);
                    } finally {
                        Arrays.fill(password, '\0');
                        Arrays.fill(token, '\0');
                    }
                    verifyDatabase(database.dataSource());
                    int failAtCall = e2eFailAtCall();
                    components = TushareDedicatedResearchRuntimeComponents
                            .createE2eDryRun(
                                    database.dataSource(), clock,
                                    new TushareControlledAcceptanceE2eDryRunGateway(
                                            failAtCall));
                } else {
                    try (SecretProvider secrets =
                                 CompositeSecretProvider.formalLocal(secretMode)) {
                        try (SecretValue password = secrets
                                .readResearchDatabasePassword()) {
                            char[] auditCopy = password.copy();
                            try {
                                registry.register(
                                        SensitiveKind.DATABASE_PASSWORD, auditCopy);
                            } finally {
                                Arrays.fill(auditCopy, '\0');
                            }
                            char[] dataSourceCopy = password.copy();
                            try {
                                database = openDatabase(
                                        authorization, dataSourceCopy);
                            } finally {
                                Arrays.fill(dataSourceCopy, '\0');
                            }
                        }
                        verifyDatabase(database.dataSource());
                        try (SecretValue token = secrets.readTushareToken()) {
                            char[] auditCopy = token.copy();
                            try {
                                registry.register(
                                        SensitiveKind.TUSHARE_TOKEN, auditCopy);
                            } finally {
                                Arrays.fill(auditCopy, '\0');
                            }
                            char[] runtimeCopy = token.copy();
                            try {
                                components = TushareDedicatedResearchRuntimeComponents
                                        .create(database.dataSource(), runtimeCopy, clock);
                            } finally {
                                Arrays.fill(runtimeCopy, '\0');
                            }
                        }
                    }
                }

                progress.providerPhase = true;
                TushareDedicatedResearchBatchResult batch;
                try {
                    batch = components.batchService().run(
                            TushareDedicatedResearchBatchAuthorization
                                    .manualPersonalResearch(),
                            authorization.command());
                } finally {
                    progress.providerCalls = Math.toIntExact(
                            Math.min(3, components.totalProviderAttemptCount()));
                }
                progress.recordBatch(batch);
                SymbolResearchResult symbol = batch.symbolResults().get(0);
                long batchId = symbol.captureResult().batchId();
                Instant readbackAt = clock.instant();
                DatabaseReadbackEvidence readback = components.readbackService()
                        .readAndVerify(
                                batchId, batch.observedAt(), startedAt, readbackAt,
                                batch.databaseIdentity(),
                                symbol.sourceInstrumentId(),
                                authorization.security().symbol(),
                                authorization.security().exchange(),
                                authorization.tradeDate());
                if (!readback.committedReadbackVerified()
                        || !readback.currentBatchFactReferencesVerified()
                        || !readback.exactMicrosecondMatch()
                        || readback.idempotentReferenceCount()
                        != batch.idempotentCount()) {
                    throw new IllegalStateException(
                            "TUSHARE_REDUCED_RESEARCH_READBACK_INVALID");
                }
                progress.readbackPassed = true;
                return new ExecutionEvidence(
                        batchId, batch.providerCallCount(), batch.retryCount(),
                        batch.appendedCount(), batch.idempotentCount(),
                        symbol.qfqBars().size(), readback);
            } catch (Throwable error) {
                GatewayException gateway = find(error, GatewayException.class);
                if (gateway != null) {
                    progress.providerCalls = Math.max(
                            progress.providerCalls,
                            Math.min(3, gateway.providerCallCount()));
                    progress.retryCount = Math.max(
                            progress.retryCount,
                            gateway.rateLimitRetryCount());
                }
                throw error;
            } finally {
                if (components != null) {
                    components.close();
                }
                if (database != null) {
                    database.close();
                }
            }
        }

        private static void verifyDatabase(DataSource dataSource) {
            new TushareDedicatedResearchPersistenceGuard(
                    new JdbcTemplate(dataSource),
                    TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                    .verifyBeforeProvider();
        }

        private static RuntimeDatabase openDatabase(
                TushareReducedResearchDay001Authorization authorization,
                char[] password
        ) {
            TushareControlledAcceptanceDataSource source =
                    new TushareControlledAcceptanceDataSource(
                            authorization.databasePort(), authorization.sslMode(),
                            password);
            return new RuntimeDatabase(source);
        }

        private static int e2eFailAtCall() {
            String configured = System.getProperty(
                    "stockquant.reduced-research.e2e.fail-at-call", "-1");
            try {
                int value = Integer.parseInt(configured);
                if (value == -1 || value >= 1 && value <= 3) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Converted to one safe code below.
            }
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_E2E_FAILURE_POINT_INVALID");
        }

        private static char[] syntheticDatabasePassword() {
            return "E2E_DRY_RUN_DATABASE_PASSWORD".toCharArray();
        }

        private static char[] syntheticProviderToken() {
            return "E2E_DRY_RUN_FAKE_TOKEN".toCharArray();
        }
    }

    private record RuntimeDatabase(
            TushareControlledAcceptanceDataSource source
    ) implements AutoCloseable {
        RuntimeDatabase {
            Objects.requireNonNull(source, "source");
        }

        DataSource dataSource() {
            return source;
        }

        @Override
        public void close() {
            source.close();
        }
    }

    record ExecutionEvidence(
            long batchId,
            int providerCallCount,
            int retryCount,
            int appendedCount,
            int idempotentCount,
            int qfqBarCount,
            DatabaseReadbackEvidence readback
    ) {
        ExecutionEvidence {
            Objects.requireNonNull(readback, "readback");
        }

        void validateFor(
                TushareReducedResearchDay001Authorization authorization
        ) {
            boolean modeMatches = switch (authorization.day001Mode()) {
                case NEW_CAPTURE -> appendedCount == 3 && idempotentCount == 0;
                case IDEMPOTENCY_VERIFICATION ->
                        appendedCount == 0 && idempotentCount == 3;
            };
            if (batchId <= 0 || providerCallCount != 3 || retryCount != 0
                    || appendedCount < 0 || idempotentCount < 0
                    || appendedCount + idempotentCount != 3
                    || qfqBarCount != 1 || !modeMatches
                    || readback.batchId() != batchId
                    || !readback.committedReadbackVerified()
                    || !readback.currentBatchFactReferencesVerified()) {
                throw new IllegalStateException(
                        "TUSHARE_REDUCED_RESEARCH_MODE_RESULT_MISMATCH");
            }
        }
    }

    static final class ExecutionProgress {
        private boolean providerPhase;
        private int providerCalls;
        private int retryCount;
        private Long batchId;
        private int appendedCount;
        private int idempotentCount;
        private boolean qfqPassed;
        private boolean readbackPassed;

        void recordBatch(TushareDedicatedResearchBatchResult batch) {
            SymbolResearchResult symbol = batch.symbolResults().get(0);
            providerCalls = batch.providerCallCount();
            retryCount = batch.retryCount();
            batchId = symbol.captureResult().batchId();
            appendedCount = batch.appendedCount();
            idempotentCount = batch.idempotentCount();
            qfqPassed = symbol.qfqBars().size() == 1;
        }

        Map<String, Integer> endpointCalls() {
            int attempts = Math.max(0, Math.min(3, providerCalls));
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            result.put("daily", attempts >= 1 ? 1 : 0);
            result.put("adj_factor", attempts >= 2 ? 1 : 0);
            result.put("trade_cal", attempts >= 3 ? 1 : 0);
            return result;
        }
    }

    private record RunnerArguments(
            Path authorizationFile,
            Path resultFile,
            Mode secretMode
    ) {
        static RunnerArguments parse(String[] args) {
            if (args == null || args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_LAUNCH_ARGUMENTS_INVALID");
            }
            Path authorization = null;
            Path result = null;
            Mode secretMode = Mode.WINDOWS_CREDENTIAL_MANAGER;
            boolean secretModeSeen = false;
            for (String argument : args) {
                if (argument != null && argument.startsWith(AUTHORIZATION_ARGUMENT)
                        && argument.length() > AUTHORIZATION_ARGUMENT.length()
                        && authorization == null) {
                    authorization = Path.of(argument.substring(
                            AUTHORIZATION_ARGUMENT.length()));
                } else if (argument != null && argument.startsWith(RESULT_ARGUMENT)
                        && argument.length() > RESULT_ARGUMENT.length()
                        && result == null) {
                    result = Path.of(argument.substring(RESULT_ARGUMENT.length()));
                } else if (argument != null
                        && argument.startsWith(SECRET_MODE_ARGUMENT)
                        && argument.length() > SECRET_MODE_ARGUMENT.length()
                        && !secretModeSeen) {
                    secretMode = Mode.parse(argument.substring(
                            SECRET_MODE_ARGUMENT.length()));
                    secretModeSeen = true;
                } else {
                    throw new IllegalArgumentException(
                            "TUSHARE_REDUCED_RESEARCH_LAUNCH_ARGUMENTS_INVALID");
                }
            }
            if (authorization == null || result == null
                    || containsAiSegment(authorization)
                    || containsAiSegment(result)) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_LAUNCH_ARGUMENTS_INVALID");
            }
            return new RunnerArguments(
                    authorization.toAbsolutePath().normalize(),
                    result.toAbsolutePath().normalize(), secretMode);
        }

        private static boolean containsAiSegment(Path path) {
            for (Path segment : path.toAbsolutePath().normalize()) {
                if (".ai".equalsIgnoreCase(segment.toString())) {
                    return true;
                }
            }
            return false;
        }
    }
}
