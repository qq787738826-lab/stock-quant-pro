package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDataSource.SslMode;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Strict non-secret launch metadata for one real controlled acceptance. */
record TushareControlledAcceptanceLaunchPlan(
        String acceptanceId,
        String codeBaselineCommit,
        String artifactSha256,
        Path buildProofPath,
        SecuritySelection security,
        LocalDate tradeDate,
        Instant issuedAt,
        Instant expiresAt,
        int databasePort,
        SslMode sslMode,
        String userApprovalReference,
        LaunchMode launchMode
) {
    static final String AUTHORIZATION_VERSION = "F1F_B2_AUTHORIZATION_V1";
    static final String AUTHORIZATION_STATUS = "USER_APPROVED";
    static final String E2E_DRY_RUN_STATUS = "E2E_DRY_RUN";
    static final String PURPOSE = "F1F_B2_CONTROLLED_ACCEPTANCE";
    static final String E2E_DRY_RUN_PURPOSE = "F1F_B2_E2E_DRY_RUN";
    static final String DATABASE_HOST = "127.0.0.1";
    static final String OBSOLETE_DRAFT_ACCEPTANCE_ID =
            "F1FB2_20260803_140506_96C6DFB7";
    static final Set<String> REQUIRED_KEYS = Set.of(
            "authorization.status", "authorization.version", "acceptance.id",
            "git.commit", "artifact.sha256", "build.proof.path", "provider.code",
            "security.symbol", "security.exchange", "trade.date",
            "endpoints", "maximum.provider.requests", "retry.budget",
            "database.host", "database.name", "database.user", "database.port",
            "database.ssl.mode", "schema.name", "base.schema.version",
            "governance.schema.version", "issued.at", "expires.at",
            "purpose", "execution.source", "user.approval.reference");

    TushareControlledAcceptanceLaunchPlan {
        acceptanceId = required(acceptanceId, "acceptanceId");
        codeBaselineCommit = TushareControlledAcceptanceExecution.commit(
                codeBaselineCommit);
        artifactSha256 = TushareControlledAcceptanceExecution.sha256(
                artifactSha256);
        buildProofPath = Objects.requireNonNull(buildProofPath, "buildProofPath")
                .toAbsolutePath().normalize();
        security = Objects.requireNonNull(security, "security");
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        sslMode = Objects.requireNonNull(sslMode, "sslMode");
        userApprovalReference = required(
                userApprovalReference, "userApprovalReference");
        launchMode = Objects.requireNonNull(launchMode, "launchMode");
        if (OBSOLETE_DRAFT_ACCEPTANCE_ID.equals(acceptanceId)
                || databasePort <= 0 || databasePort > 65_535
                || !expiresAt.isAfter(issuedAt)) {
            throw invalid();
        }
    }

    static TushareControlledAcceptanceLaunchPlan load(Path file) {
        Objects.requireNonNull(file, "file");
        Properties properties = new Properties();
        try {
            Map<String, String> strict = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(
                    file.toAbsolutePath().normalize(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0 || separator == line.length() - 1
                        || line.indexOf('=', separator + 1) >= 0) {
                    throw invalid();
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!key.equals(key.trim()) || !value.equals(value.trim())
                        || strict.putIfAbsent(key, value) != null) {
                    throw invalid();
                }
            }
            strict.forEach(properties::setProperty);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_AUTHORIZATION_UNREADABLE", error);
        }
        return from(properties);
    }

    static TushareControlledAcceptanceLaunchPlan from(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.stringPropertyNames().equals(REQUIRED_KEYS)
                || properties.stringPropertyNames().stream().anyMatch(
                TushareControlledAcceptanceLaunchPlan::secretLikeKey)) {
            throw invalid();
        }
        requireExact(properties, "authorization.version", AUTHORIZATION_VERSION);
        requireExact(properties, "provider.code", TushareMarketFactProvider.PROVIDER_CODE);
        requireExact(properties, "endpoints", "daily,adj_factor,trade_cal");
        requireExact(properties, "maximum.provider.requests", "3");
        requireExact(properties, "retry.budget", "0");
        requireExact(properties, "database.host", DATABASE_HOST);
        requireExact(properties, "database.name",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE);
        requireExact(properties, "database.user",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_USER);
        requireExact(properties, "schema.name",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA);
        requireExact(properties, "base.schema.version", "13");
        requireExact(properties, "governance.schema.version", "14");
        LaunchMode launchMode;
        if (AUTHORIZATION_STATUS.equals(
                properties.getProperty("authorization.status"))) {
            requireExact(properties, "purpose", PURPOSE);
            requireExact(properties, "execution.source",
                    ExecutionSource.REAL_CONTROLLED_ACCEPTANCE.name());
            launchMode = LaunchMode.REAL_CONTROLLED_ACCEPTANCE;
        } else if (E2E_DRY_RUN_STATUS.equals(
                properties.getProperty("authorization.status"))) {
            requireExact(properties, "purpose", E2E_DRY_RUN_PURPOSE);
            requireExact(properties, "execution.source", ExecutionSource.TEST.name());
            requireExact(properties, "user.approval.reference",
                    "NOT_APPLICABLE_E2E_DRY_RUN");
            launchMode = LaunchMode.E2E_DRY_RUN;
        } else {
            throw invalid();
        }
        try {
            return new TushareControlledAcceptanceLaunchPlan(
                    properties.getProperty("acceptance.id"),
                    properties.getProperty("git.commit"),
                    properties.getProperty("artifact.sha256"),
                    Path.of(properties.getProperty("build.proof.path")),
                    new SecuritySelection(
                            properties.getProperty("security.symbol"),
                            properties.getProperty("security.exchange")),
                    LocalDate.parse(properties.getProperty("trade.date")),
                    Instant.parse(properties.getProperty("issued.at")),
                    Instant.parse(properties.getProperty("expires.at")),
                    Integer.parseInt(properties.getProperty("database.port")),
                    SslMode.valueOf(properties.getProperty(
                            "database.ssl.mode").toUpperCase(Locale.ROOT)),
                    properties.getProperty("user.approval.reference"),
                    launchMode);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_AUTHORIZATION_INVALID", error);
        }
    }

    TushareControlledAcceptanceAuthorization authorization(
            VerifiedBuildProof buildProof
    ) {
        validateBuildProof(buildProof);
        return launchMode == LaunchMode.E2E_DRY_RUN
                ? TushareControlledAcceptanceAuthorization.issueE2eDryRunDurable(
                acceptanceId, codeBaselineCommit, artifactSha256, security,
                tradeDate, issuedAt, expiresAt)
                : TushareControlledAcceptanceAuthorization.issueUserApprovedDurable(
                acceptanceId, codeBaselineCommit, artifactSha256, security,
                tradeDate, issuedAt, expiresAt);
    }

    TushareDedicatedResearchBatchCommand command() {
        return new TushareDedicatedResearchBatchCommand(
                tradeDate, java.util.List.of(security), Duration.ofSeconds(30));
    }

    void validateBuildProof(VerifiedBuildProof buildProof) {
        Objects.requireNonNull(buildProof, "buildProof").validate();
        boolean eligible = launchMode == LaunchMode.E2E_DRY_RUN
                ? buildProof.e2eDryRunEligible()
                : buildProof.governanceEligible();
        if (!eligible
                || !codeBaselineCommit.equals(buildProof.gitCommit())
                || !artifactSha256.equals(buildProof.actualArtifactSha256())) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_INVALID");
        }
    }

    boolean e2eDryRun() {
        return launchMode == LaunchMode.E2E_DRY_RUN;
    }

    ExecutionSource executionSource() {
        return e2eDryRun() ? ExecutionSource.TEST
                : ExecutionSource.REAL_CONTROLLED_ACCEPTANCE;
    }

    private static boolean secretLikeKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("token") || lower.contains("password")
                || lower.contains("secret") || lower.contains("jdbc");
    }

    private static void requireExact(
            Properties properties,
            String key,
            String expected
    ) {
        if (!expected.equals(properties.getProperty(key))) {
            throw invalid();
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("invalid launch plan " + field);
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_CONTROLLED_ACCEPTANCE_AUTHORIZATION_INVALID");
    }

    enum LaunchMode {
        REAL_CONTROLLED_ACCEPTANCE,
        E2E_DRY_RUN
    }
}
