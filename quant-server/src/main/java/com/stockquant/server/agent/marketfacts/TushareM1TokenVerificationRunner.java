package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayDiagnostic;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.AuditResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fixed one-shot, one-request M1 Tushare credential verifier. */
public final class TushareM1TokenVerificationRunner {
    private static final String AUTH_ARG = "--authorization-file=";
    private static final String RESULT_ARG = "--result-file=";
    private static final String SECRET_ARG = "--secret-mode=";
    private static final String RESULT_VERSION =
            "M1_TUSHARE_TOKEN_VERIFICATION_RESULT_V1";

    private TushareM1TokenVerificationRunner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        Instant startedAt = clock.instant();
        TushareM1TokenVerificationAuthorization authorization = null;
        Path resultFile = null;
        Progress progress = new Progress();
        try {
            Arguments launch = Arguments.parse(args);
            resultFile = launch.resultFile();
            authorization = TushareM1TokenVerificationAuthorization.load(
                    launch.authorizationFile());
            authorization.validateAt(clock);
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact(
                            authorization.buildProofPath());
            authorization.validateBuildProof(proof);
            reserve(resultFile, result(authorization, "RUNNING", startedAt,
                    startedAt, progress, Audit.notRun(), null));
            consume(launch.authorizationFile(), authorization, startedAt);

            TushareM1TokenVerificationAuthorization approved = authorization;
            Captured<TushareMarketFactProvider.M1TokenVerification> captured =
                    TushareControlledAcceptanceOutputAudit
                            .captureProviderOnlyProcess(registry -> execute(
                                    approved, launch.secretMode(), registry,
                                    progress));
            Audit audit = Audit.from(captured.auditResult());
            if (!audit.clean()) {
                write(resultFile, result(authorization,
                        "FAILED_OUTPUT_AUDIT", startedAt, clock.instant(),
                        progress, audit,
                        "TUSHARE_M1_TOKEN_VERIFICATION_OUTPUT_AUDIT_FAILED"));
                failure("FAILED_OUTPUT_AUDIT",
                        "TUSHARE_M1_TOKEN_VERIFICATION_OUTPUT_AUDIT_FAILED");
                return 20;
            }
            progress.record(captured.value());
            if (!progress.targetRowPresent) {
                throw new IllegalStateException(
                        "TUSHARE_M1_TOKEN_VERIFICATION_TARGET_ROW_MISSING");
            }
            write(resultFile, result(authorization, "SUCCEEDED", startedAt,
                    clock.instant(), progress, audit, null));
            System.out.println(
                    "TUSHARE_M1_TOKEN_VERIFICATION_STATUS=SUCCEEDED");
            return 0;
        } catch (TushareControlledAcceptanceOutputAudit
                 .CapturedExecutionException capturedFailure) {
            Throwable cause = capturedFailure.getCause();
            progress.record(find(cause, GatewayException.class));
            Audit audit = Audit.from(capturedFailure.auditResult());
            String code = safeCode(cause);
            String status = progress.providerCalls > 0
                    ? "FAILED_PROVIDER" : "FAILED_PRE_PROVIDER";
            safeWrite(resultFile, authorization, startedAt, clock.instant(),
                    progress, audit, status, code);
            failure(status, code);
            return 20;
        } catch (Throwable error) {
            progress.record(find(error, GatewayException.class));
            String code = safeCode(error);
            String status = progress.providerCalls > 0
                    ? "FAILED_VALIDATION" : "FAILED_PRE_PROVIDER";
            safeWrite(resultFile, authorization, startedAt, clock.instant(),
                    progress, Audit.notRun(), status, code);
            failure(status, code);
            return 20;
        }
    }

    private static TushareMarketFactProvider.M1TokenVerification execute(
            TushareM1TokenVerificationAuthorization authorization,
            Mode mode,
            TushareControlledAcceptanceOutputAudit.SensitiveRegistry registry,
            Progress progress
    ) {
        TushareMarketFactProperties properties = null;
        try (SecretProvider provider = CompositeSecretProvider.formalLocal(mode);
             SecretValue secret = provider.readTushareToken()) {
            char[] audit = secret.copy();
            try {
                registry.register(SensitiveKind.TUSHARE_TOKEN, audit);
            } finally {
                Arrays.fill(audit, '\0');
            }
            char[] token = secret.copy();
            try {
                properties = new TushareMarketFactProperties();
                properties.setMode(
                        TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
                properties.setMaximumRateLimitRetries(0);
                properties.setToken(token);
                properties.validateFrozenContract();
            } finally {
                Arrays.fill(token, '\0');
            }
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule());
            TushareEndpointRateLimitPolicy policy =
                    new TushareEndpointRateLimitPolicy(properties);
            TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(policy);
            TushareMarketFactProvider market = new TushareMarketFactProvider(
                    mapper, properties,
                    new TushareHttpApiGateway(mapper, properties, limiter));
            var security = authorization.security();
            TushareManualBoundedSession session =
                    TushareManualBoundedSession.m1TokenVerification(
                            security.symbol(), security.exchange(),
                            authorization.tradeDate());
            MarketFactRequest request = new MarketFactRequest(
                    RunNamespace.FORMAL, TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.sourceInstrumentId(
                            security.symbol(), security.exchange()),
                    security.symbol(), security.exchange(),
                    authorization.tradeDate(), authorization.tradeDate(),
                    Set.of(FactType.RAW_DAILY_BAR), Duration.ofSeconds(30));
            long before = market.f1cRateLimitContract()
                    .totalRateLimitedCallCount();
            try {
                var result = market.verifyM1Token(request, session);
                long after = market.f1cRateLimitContract()
                        .totalRateLimitedCallCount();
                if (session.consumedBusinessRequests() != 1
                        || after - before != 1
                        || result.providerCallCount() != 1
                        || result.retryCount() != 0) {
                    throw new IllegalStateException(
                            "TUSHARE_M1_TOKEN_VERIFICATION_CALL_CONTRACT_INVALID");
                }
                progress.record(result);
                return result;
            } catch (GatewayException error) {
                progress.record(error);
                throw error;
            }
        } finally {
            if (properties != null) {
                properties.clearToken();
            }
        }
    }

    private static VerificationResult result(
            TushareM1TokenVerificationAuthorization authorization,
            String status,
            Instant startedAt,
            Instant completedAt,
            Progress progress,
            Audit audit,
            String failureCode
    ) {
        GatewayDiagnostic diagnostic = progress.diagnostic;
        return new VerificationResult(
                RESULT_VERSION, authorization.verificationId(), status,
                authorization.gitCommit(), authorization.artifactSha256(),
                "daily", authorization.security().symbol(),
                authorization.security().exchange(), authorization.tradeDate(),
                progress.providerCalls, progress.retryCount,
                authorization.stageProviderCallsBefore(),
                authorization.stageProviderCallsBefore()
                        + progress.providerCalls,
                authorization.cumulativeProviderCallsBefore()
                        + progress.providerCalls,
                progress.fieldCount, progress.rowCount,
                progress.mappedRowCount, progress.targetRowPresent,
                diagnostic == null
                        ? progress.providerCalls == 1 ? 200 : null
                        : diagnostic.httpStatus(),
                diagnostic == null
                        ? progress.providerCalls == 1 ? 0 : null
                        : diagnostic.providerCode(),
                diagnostic == null
                        ? progress.providerCalls == 1 ? "SUCCESS" : "NOT_RUN"
                        : diagnostic.providerMessageCategory(),
                diagnostic == null ? progress.providerCalls == 1
                        : diagnostic.responseJsonValid(),
                audit, failureCode, startedAt, completedAt,
                "CONSUMED", false,
                Map.of("databaseConnected", false,
                        "databaseWritten", false,
                        "providerAutostart", false));
    }

    private static void reserve(Path path, VerificationResult placeholder) {
        write(path, placeholder, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void write(Path path, VerificationResult value) {
        write(path, value, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void write(
            Path path,
            VerificationResult value,
            StandardOpenOption... options
    ) {
        try {
            String json = new ObjectMapper().registerModule(new JavaTimeModule())
                    .writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(path.toAbsolutePath().normalize(), json + '\n',
                    StandardCharsets.UTF_8, options);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_SERIALIZATION_FAILED");
        } catch (FileAlreadyExistsException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_ALREADY_EXISTS");
        } catch (AccessDeniedException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_ACCESS_DENIED");
        } catch (NoSuchFileException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_PARENT_MISSING");
        } catch (IOException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_FILE_WRITE_FAILED");
        }
    }

    private static void safeWrite(
            Path resultFile,
            TushareM1TokenVerificationAuthorization authorization,
            Instant startedAt,
            Instant completedAt,
            Progress progress,
            Audit audit,
            String status,
            String code
    ) {
        if (resultFile == null || authorization == null) {
            return;
        }
        try {
            write(resultFile, result(authorization, status, startedAt,
                    completedAt, progress, audit, code));
        } catch (Throwable ignored) {
            failure("FAILED_OUTPUT_AUDIT",
                    "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_WRITE_FAILED");
        }
    }

    private static void consume(
            Path authorizationFile,
            TushareM1TokenVerificationAuthorization authorization,
            Instant consumedAt
    ) {
        Path marker = Path.of(authorizationFile.toAbsolutePath().normalize()
                + ".consumed");
        String content = "authorization.version="
                + TushareM1TokenVerificationAuthorization.VERSION + '\n'
                + "verification.id=" + authorization.verificationId() + '\n'
                + "authorization.fingerprint=" + authorization.fingerprint()
                + '\n' + "consumed.at=" + consumedAt + '\n';
        try {
            Files.writeString(marker, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_AUTH_ALREADY_CONSUMED");
        }
    }

    private static String safeCode(Throwable error) {
        GatewayException gateway = find(error, GatewayException.class);
        if (gateway != null && gateway.safeCode() != null
                && gateway.safeCode().matches("[A-Z][A-Z0-9_]{7,127}")) {
            return gateway.safeCode();
        }
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (value.getMessage() != null && value.getMessage().matches(
                    "[A-Z][A-Z0-9_]{7,127}")) {
                return value.getMessage();
            }
        }
        return "TUSHARE_M1_TOKEN_VERIFICATION_FAILED";
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        }
        return null;
    }

    private static void failure(String stage, String code) {
        System.err.println("TUSHARE_M1_TOKEN_VERIFICATION_FAILURE_STAGE="
                + stage);
        System.err.println("TUSHARE_M1_TOKEN_VERIFICATION_FAILURE_REASON="
                + code);
    }

    private record Arguments(Path authorizationFile, Path resultFile, Mode secretMode) {
        static Arguments parse(String[] args) {
            if (args == null || args.length != 3) {
                throw invalid();
            }
            Path auth = null;
            Path result = null;
            Mode mode = null;
            for (String value : args) {
                if (value != null && value.startsWith(AUTH_ARG)
                        && value.length() > AUTH_ARG.length() && auth == null) {
                    auth = Path.of(value.substring(AUTH_ARG.length()));
                } else if (value != null && value.startsWith(RESULT_ARG)
                        && value.length() > RESULT_ARG.length()
                        && result == null) {
                    result = Path.of(value.substring(RESULT_ARG.length()));
                } else if (value != null && value.startsWith(SECRET_ARG)
                        && value.length() > SECRET_ARG.length() && mode == null) {
                    mode = Mode.parse(value.substring(SECRET_ARG.length()));
                } else {
                    throw invalid();
                }
            }
            if (auth == null || result == null
                    || mode != Mode.WINDOWS_CREDENTIAL_MANAGER) {
                throw invalid();
            }
            return new Arguments(auth.toAbsolutePath().normalize(),
                    result.toAbsolutePath().normalize(), mode);
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_ARGUMENT_INVALID");
        }
    }

    private static final class Progress {
        private int providerCalls;
        private int retryCount;
        private int fieldCount;
        private int rowCount;
        private int mappedRowCount;
        private boolean targetRowPresent;
        private GatewayDiagnostic diagnostic;

        void record(TushareMarketFactProvider.M1TokenVerification value) {
            if (value != null) {
                providerCalls = value.providerCallCount();
                retryCount = value.retryCount();
                fieldCount = value.fieldCount();
                rowCount = value.rowCount();
                mappedRowCount = value.mappedRowCount();
                targetRowPresent = value.targetRowPresent();
            }
        }

        void record(GatewayException error) {
            if (error != null) {
                providerCalls = Math.max(providerCalls,
                        error.providerCallCount());
                retryCount = Math.max(retryCount,
                        error.rateLimitRetryCount());
                diagnostic = error.diagnostic();
            }
        }
    }

    record Audit(boolean captureComplete, boolean clean, int hitCount) {
        static Audit from(AuditResult value) {
            return value == null ? notRun()
                    : new Audit(value.captureComplete(), value.clean(),
                    value.hits().size());
        }

        static Audit notRun() {
            return new Audit(false, false, 0);
        }
    }

    record VerificationResult(
            String schemaVersion,
            String verificationId,
            String status,
            String gitCommit,
            String artifactSha256,
            String endpoint,
            String symbol,
            String exchange,
            java.time.LocalDate tradeDate,
            int providerCallCount,
            int retryCount,
            int stageProviderCallsBefore,
            int stageProviderCallsAfter,
            int cumulativeProviderCallsAfter,
            int fieldCount,
            int rowCount,
            int mappedRowCount,
            boolean targetRowPresent,
            Integer httpStatus,
            Integer providerCode,
            String providerMessageCategory,
            boolean responseJsonValid,
            Audit outputAudit,
            String safeFailureCode,
            Instant startedAt,
            Instant completedAt,
            String authorizationState,
            boolean providerAutostart,
            Map<String, Boolean> prohibitedEffects
    ) {
    }
}
