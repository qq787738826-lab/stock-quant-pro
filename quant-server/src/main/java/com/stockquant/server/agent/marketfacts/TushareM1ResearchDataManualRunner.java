package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveRegistry;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataResult.AuditSummary;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataResult.DatasetSummary;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataResult.FinalStatus;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataResult.ResultFile;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Independent, one-shot, non-Spring M1 research-data runner. */
public final class TushareM1ResearchDataManualRunner {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_REJECTED = 20;
    private static final String AUTH_ARG = "--authorization-file=";
    private static final String RESULT_ARG = "--result-file=";
    private static final String SECRET_ARG = "--secret-mode=";

    private TushareM1ResearchDataManualRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        Instant startedAt = clock.instant();
        TushareM1ResearchDataAuthorization authorization = null;
        ResultFile resultFile = null;
        Progress progress = new Progress();
        try {
            Arguments launch = Arguments.parse(args);
            authorization = TushareM1ResearchDataAuthorization.load(
                    launch.authorizationFile());
            authorization.validateAt(clock);
            VerifiedBuildProof proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact(authorization.buildProofPath());
            authorization.validateBuildProof(proof);
            resultFile = ResultFile.reserve(
                    launch.resultFile(),
                    TushareM1ResearchDataResult.placeholder(
                            authorization, startedAt));
            consumeAuthorization(launch.authorizationFile(), authorization,
                    startedAt);
            prepareE2eDatabase(authorization, proof);

            TushareM1ResearchDataAuthorization authorized = authorization;
            Captured<TushareM1ResearchDataModels.RunEvidence> captured =
                    TushareControlledAcceptanceOutputAudit
                            .captureControlledProcess(registry -> execute(
                                    authorized, registry, clock, progress,
                                    launch.secretMode()));
            AuditSummary audit = AuditSummary.from(captured.auditResult());
            if (!audit.passed()) {
                resultFile.write(failure(authorization,
                        FinalStatus.FAILED_OUTPUT_AUDIT, startedAt,
                        clock.instant(), progress, audit,
                        "TUSHARE_M1_OUTPUT_AUDIT_FAILED"));
                safeFailure(FinalStatus.FAILED_OUTPUT_AUDIT,
                        "TUSHARE_M1_OUTPUT_AUDIT_FAILED");
                return EXIT_REJECTED;
            }
            TushareM1ResearchDataModels.RunEvidence evidence =
                    captured.value();
            progress.record(evidence);
            resultFile.write(TushareM1ResearchDataResult.success(
                    authorization, startedAt, clock.instant(), evidence, audit));
            System.out.println("TUSHARE_M1_RESEARCH_DATA_STATUS=SUCCEEDED");
            return EXIT_SUCCESS;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException capturedFailure) {
            Throwable cause = capturedFailure.getCause();
            AuditSummary audit = capturedFailure.auditResult() == null
                    ? AuditSummary.notRun()
                    : AuditSummary.from(capturedFailure.auditResult());
            String code = safeCode(cause);
            FinalStatus status = audit.captureComplete() && !audit.clean()
                    ? FinalStatus.FAILED_OUTPUT_AUDIT
                    : classify(cause, progress);
            writeFailure(resultFile, authorization, startedAt, clock,
                    progress, audit, status, code);
            safeFailure(status, code);
            return EXIT_REJECTED;
        } catch (Throwable error) {
            String code = safeCode(error);
            FinalStatus status = classify(error, progress);
            writeFailure(resultFile, authorization, startedAt, clock,
                    progress, AuditSummary.notRun(), status, code);
            safeFailure(status, code);
            return EXIT_REJECTED;
        }
    }

    private static TushareM1ResearchDataModels.RunEvidence execute(
            TushareM1ResearchDataAuthorization authorization,
            SensitiveRegistry registry,
            Clock clock,
            Progress progress,
            Mode secretMode
    ) {
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
                components = TushareDedicatedResearchRuntimeComponents
                        .createE2eDryRun(database.dataSource(), clock,
                                new TushareControlledAcceptanceE2eDryRunGateway(
                                        e2eFailAtCall(
                                                authorization.maximumProviderRequests())));
            } else {
                try (SecretProvider secrets =
                             CompositeSecretProvider.formalLocal(secretMode)) {
                    try (SecretValue password =
                                 secrets.readResearchDatabasePassword()) {
                        char[] audit = password.copy();
                        try {
                            registry.register(
                                    SensitiveKind.DATABASE_PASSWORD, audit);
                        } finally {
                            Arrays.fill(audit, '\0');
                        }
                        char[] copy = password.copy();
                        try {
                            database = openDatabase(authorization, copy);
                        } finally {
                            Arrays.fill(copy, '\0');
                        }
                    }
                    verifyDatabase(database.dataSource());
                    try (SecretValue token = secrets.readTushareToken()) {
                        char[] audit = token.copy();
                        try {
                            registry.register(SensitiveKind.TUSHARE_TOKEN, audit);
                        } finally {
                            Arrays.fill(audit, '\0');
                        }
                        char[] copy = token.copy();
                        try {
                            components = TushareDedicatedResearchRuntimeComponents
                                    .create(database.dataSource(), copy, clock);
                        } finally {
                            Arrays.fill(copy, '\0');
                        }
                    }
                }
            }
            progress.providerPhase = true;
            try {
                return components.m1ResearchDataService().run(
                        TushareDedicatedResearchBatchAuthorization
                                .m1ResearchData(), authorization.command());
            } finally {
                progress.providerCalls = Math.toIntExact(Math.min(
                        authorization.maximumProviderRequests(),
                        components.totalProviderAttemptCount()));
            }
        } catch (Throwable error) {
            GatewayException gateway = find(error, GatewayException.class);
            if (gateway != null) {
                progress.providerCalls = Math.max(progress.providerCalls,
                        Math.min(authorization.maximumProviderRequests(),
                                gateway.providerCallCount()));
                progress.retryCount = Math.max(progress.retryCount,
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

    private static void prepareE2eDatabase(
            TushareM1ResearchDataAuthorization authorization,
            VerifiedBuildProof proof
    ) {
        if (!authorization.e2eDryRun()) {
            return;
        }
        char[] password = syntheticDatabasePassword();
        try (RuntimeDatabase database = openDatabase(authorization, password)) {
            TushareReducedResearchDay001E2eDatabase.initializeM1(
                    database.dataSource(), authorization, proof);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void consumeAuthorization(
            Path authorizationFile,
            TushareM1ResearchDataAuthorization authorization,
            Instant consumedAt
    ) {
        Path marker = Path.of(authorizationFile.toAbsolutePath().normalize()
                + ".consumed");
        String content = "authorization.version="
                + TushareM1ResearchDataAuthorization.VERSION + '\n'
                + "run.id=" + authorization.runId() + '\n'
                + "authorization.fingerprint=" + authorization.fingerprint()
                + '\n' + "consumed.at=" + consumedAt + '\n';
        try {
            Files.writeString(marker, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_AUTHORIZATION_ALREADY_CONSUMED", error);
        }
    }

    private static TushareM1ResearchDataResult failure(
            TushareM1ResearchDataAuthorization authorization,
            FinalStatus status,
            Instant startedAt,
            Instant completedAt,
            Progress progress,
            AuditSummary audit,
            String code
    ) {
        return TushareM1ResearchDataResult.failure(
                authorization, status, startedAt, completedAt,
                progress.endpointCalls(authorization.securities().size()),
                progress.providerCalls, progress.retryCount,
                progress.batchIds, progress.received,
                progress.appended, progress.idempotent,
                progress.dataset, audit, code);
    }

    private static void writeFailure(
            ResultFile resultFile,
            TushareM1ResearchDataAuthorization authorization,
            Instant startedAt,
            Clock clock,
            Progress progress,
            AuditSummary audit,
            FinalStatus status,
            String code
    ) {
        if (resultFile == null || authorization == null) {
            return;
        }
        try {
            resultFile.write(failure(authorization, status, startedAt,
                    clock.instant(), progress, audit, code));
        } catch (Throwable ignored) {
            safeFailure(FinalStatus.FAILED_OUTPUT_AUDIT,
                    "TUSHARE_M1_RESULT_WRITE_FAILED");
        }
    }

    private static FinalStatus classify(Throwable error, Progress progress) {
        if (isInterrupted(error)) {
            Thread.currentThread().interrupt();
            return FinalStatus.INTERRUPTED;
        }
        if (find(error, GatewayException.class) != null
                || safeCode(error).startsWith("TUSHARE_HTTP_")
                || safeCode(error).startsWith("TUSHARE_API_")
                || safeCode(error).startsWith("TUSHARE_PERMISSION_")
                || safeCode(error).startsWith("TUSHARE_CREDENTIAL_")
                || safeCode(error).startsWith("TUSHARE_ACCOUNT_")) {
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
        if (code.contains("CAPTURE") || code.contains("PERSISTENCE")
                || code.contains("TRANSACTION")) {
            return FinalStatus.FAILED_PERSISTENCE;
        }
        return progress.providerCalls == 0
                ? FinalStatus.FAILED_PRE_PROVIDER
                : FinalStatus.FAILED_VALIDATION;
    }

    private static boolean isInterrupted(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (value instanceof InterruptedException) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted();
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        }
        return null;
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            String message = value.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
        }
        return "TUSHARE_M1_EXECUTION_FAILED";
    }

    private static void safeFailure(FinalStatus status, String code) {
        System.err.println("TUSHARE_M1_FAILURE_STAGE=" + status.name());
        System.err.println("TUSHARE_M1_FAILURE_REASON=" + code);
    }

    private static void verifyDatabase(DataSource dataSource) {
        new TushareDedicatedResearchPersistenceGuard(
                new JdbcTemplate(dataSource),
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE)
                .verifyBeforeProvider();
    }

    private static RuntimeDatabase openDatabase(
            TushareM1ResearchDataAuthorization authorization,
            char[] password
    ) {
        return new RuntimeDatabase(new TushareControlledAcceptanceDataSource(
                authorization.databasePort(), authorization.sslMode(), password));
    }

    private static int e2eFailAtCall(int maximum) {
        String configured = System.getProperty(
                "stockquant.m1.e2e.fail-at-call", "-1");
        try {
            int value = Integer.parseInt(configured);
            if (value == -1 || value >= 1 && value <= maximum) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Converted to a safe code below.
        }
        throw new IllegalArgumentException(
                "TUSHARE_M1_E2E_FAILURE_POINT_INVALID");
    }

    private static char[] syntheticDatabasePassword() {
        return "M1_E2E_DRY_RUN_DATABASE_PASSWORD".toCharArray();
    }

    private static char[] syntheticProviderToken() {
        return "E2E_DRY_RUN_FAKE_TOKEN".toCharArray();
    }

    private record RuntimeDatabase(
            TushareControlledAcceptanceDataSource source
    ) implements AutoCloseable {
        DataSource dataSource() {
            return source;
        }

        @Override
        public void close() {
            source.close();
        }
    }

    static final class Progress {
        private boolean providerPhase;
        private int providerCalls;
        private int retryCount;
        private List<Long> batchIds = List.of();
        private int received;
        private int appended;
        private int idempotent;
        private DatasetSummary dataset = DatasetSummary.notRun();

        void record(TushareM1ResearchDataModels.RunEvidence evidence) {
            providerCalls = evidence.providerCallCount();
            retryCount = evidence.retryCount();
            batchIds = evidence.captureBatchIds();
            received = evidence.receivedFactCount();
            appended = evidence.appendedObservationCount();
            idempotent = evidence.idempotentChainTailCount();
            dataset = DatasetSummary.from(evidence.dataset());
        }

        Map<String, Integer> endpointCalls(int securityCount) {
            int attempts = Math.max(0,
                    Math.min(securityCount * 3, providerCalls));
            int complete = attempts / 3;
            int remainder = attempts % 3;
            return TushareM1ResearchDataResult.calls(
                    complete + (remainder >= 1 ? 1 : 0),
                    complete + (remainder >= 2 ? 1 : 0),
                    complete);
        }
    }

    private record Arguments(
            Path authorizationFile,
            Path resultFile,
            Mode secretMode
    ) {
        static Arguments parse(String[] args) {
            if (args == null || args.length < 2 || args.length > 3) {
                throw invalidArgs();
            }
            Path auth = null;
            Path result = null;
            Mode mode = Mode.WINDOWS_CREDENTIAL_MANAGER;
            boolean modeSeen = false;
            for (String value : args) {
                if (value != null && value.startsWith(AUTH_ARG)
                        && value.length() > AUTH_ARG.length() && auth == null) {
                    auth = Path.of(value.substring(AUTH_ARG.length()));
                } else if (value != null && value.startsWith(RESULT_ARG)
                        && value.length() > RESULT_ARG.length()
                        && result == null) {
                    result = Path.of(value.substring(RESULT_ARG.length()));
                } else if (value != null && value.startsWith(SECRET_ARG)
                        && value.length() > SECRET_ARG.length() && !modeSeen) {
                    mode = Mode.parse(value.substring(SECRET_ARG.length()));
                    modeSeen = true;
                } else {
                    throw invalidArgs();
                }
            }
            if (auth == null || result == null || forbidden(auth)
                    || forbidden(result)) {
                throw invalidArgs();
            }
            return new Arguments(auth.toAbsolutePath().normalize(),
                    result.toAbsolutePath().normalize(), mode);
        }

        private static boolean forbidden(Path path) {
            for (Path segment : path.toAbsolutePath().normalize()) {
                if (".ai".equalsIgnoreCase(segment.toString())) {
                    return true;
                }
            }
            return false;
        }

        private static IllegalArgumentException invalidArgs() {
            return new IllegalArgumentException(
                    "TUSHARE_M1_LAUNCH_ARGUMENTS_INVALID");
        }
    }
}
