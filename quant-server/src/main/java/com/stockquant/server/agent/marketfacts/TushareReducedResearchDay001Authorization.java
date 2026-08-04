package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDataSource.SslMode;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Strict, non-secret, one-shot authorization for reduced-research Day 001. */
record TushareReducedResearchDay001Authorization(
        String runId,
        String gitCommit,
        String artifactSha256,
        Path buildProofPath,
        SecuritySelection security,
        LocalDate tradeDate,
        Day001Mode day001Mode,
        int databasePort,
        SslMode sslMode,
        Instant issuedAt,
        Instant expiresAt,
        String userApprovalReference,
        AuthorizationMode authorizationMode
) {
    static final String VERSION = "REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1";
    static final String STATUS_USER_APPROVED = "USER_APPROVED";
    static final String STATUS_E2E_DRY_RUN = "E2E_DRY_RUN";
    static final String PROVIDER = "TUSHARE";
    static final String EXECUTION_SOURCE = "REDUCED_RESEARCH_MANUAL_DAY001";
    static final String PURPOSE = "3A_R3B_RR_DAY001";
    static final String DATABASE_HOST = "127.0.0.1";
    static final int PRODUCTION_DATABASE_PORT = 38_432;
    static final Duration MAXIMUM_VALIDITY = Duration.ofMinutes(30);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "authorization.status", "authorization.version", "run.id",
            "git.commit", "artifact.sha256", "build.proof.path",
            "provider", "security.symbol", "security.exchange", "trade.date",
            "day001.mode", "endpoints", "endpoint.daily.requests",
            "endpoint.adj_factor.requests", "endpoint.trade_cal.requests",
            "maximum.provider.requests", "retry.budget", "redirects",
            "database.host", "database.port", "database.name", "database.user",
            "database.ssl.mode", "schema.name", "issued.at", "expires.at",
            "purpose", "execution.source", "user.approval.reference");

    TushareReducedResearchDay001Authorization {
        runId = requireRunId(runId);
        gitCommit = requireCommit(gitCommit);
        artifactSha256 = requireSha256(artifactSha256);
        buildProofPath = Objects.requireNonNull(buildProofPath, "buildProofPath")
                .toAbsolutePath().normalize();
        security = Objects.requireNonNull(security, "security");
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        day001Mode = Objects.requireNonNull(day001Mode, "day001Mode");
        sslMode = Objects.requireNonNull(sslMode, "sslMode");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        userApprovalReference = requireApprovalReference(userApprovalReference);
        authorizationMode = Objects.requireNonNull(
                authorizationMode, "authorizationMode");
        Duration validity = Duration.between(issuedAt, expiresAt);
        if (databasePort <= 0 || databasePort > 65_535
                || validity.isZero() || validity.isNegative()
                || validity.compareTo(MAXIMUM_VALIDITY) > 0
                || authorizationMode == AuthorizationMode.USER_APPROVED
                && databasePort != PRODUCTION_DATABASE_PORT
                || authorizationMode == AuthorizationMode.E2E_DRY_RUN
                && databasePort == PRODUCTION_DATABASE_PORT) {
            throw invalid();
        }
    }

    static TushareReducedResearchDay001Authorization load(Path file) {
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
                    "TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_UNREADABLE", error);
        }
        return from(properties);
    }

    static TushareReducedResearchDay001Authorization from(
            Properties properties
    ) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.stringPropertyNames().equals(REQUIRED_KEYS)
                || properties.stringPropertyNames().stream().anyMatch(
                TushareReducedResearchDay001Authorization::secretLikeKey)) {
            throw invalid();
        }
        requireExact(properties, "authorization.version", VERSION);
        requireExact(properties, "provider", PROVIDER);
        requireExact(properties, "endpoints", "daily,adj_factor,trade_cal");
        requireExact(properties, "endpoint.daily.requests", "1");
        requireExact(properties, "endpoint.adj_factor.requests", "1");
        requireExact(properties, "endpoint.trade_cal.requests", "1");
        requireExact(properties, "maximum.provider.requests", "3");
        requireExact(properties, "retry.budget", "0");
        requireExact(properties, "redirects", "NEVER");
        requireExact(properties, "database.host", DATABASE_HOST);
        requireExact(properties, "database.name",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE);
        requireExact(properties, "database.user",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_USER);
        requireExact(properties, "schema.name",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA);
        requireExact(properties, "purpose", PURPOSE);
        requireExact(properties, "execution.source", EXECUTION_SOURCE);

        AuthorizationMode authorizationMode;
        String status = properties.getProperty("authorization.status");
        if (STATUS_USER_APPROVED.equals(status)) {
            authorizationMode = AuthorizationMode.USER_APPROVED;
        } else if (STATUS_E2E_DRY_RUN.equals(status)) {
            authorizationMode = AuthorizationMode.E2E_DRY_RUN;
            requireExact(properties, "user.approval.reference",
                    "NOT_APPLICABLE_E2E_DRY_RUN");
        } else {
            throw invalid();
        }
        try {
            return new TushareReducedResearchDay001Authorization(
                    properties.getProperty("run.id"),
                    properties.getProperty("git.commit"),
                    properties.getProperty("artifact.sha256"),
                    Path.of(properties.getProperty("build.proof.path")),
                    new SecuritySelection(
                            properties.getProperty("security.symbol"),
                            properties.getProperty("security.exchange")),
                    LocalDate.parse(properties.getProperty("trade.date")),
                    Day001Mode.valueOf(properties.getProperty("day001.mode")),
                    Integer.parseInt(properties.getProperty("database.port")),
                    SslMode.valueOf(properties.getProperty(
                            "database.ssl.mode").toUpperCase(Locale.ROOT)),
                    Instant.parse(properties.getProperty("issued.at")),
                    Instant.parse(properties.getProperty("expires.at")),
                    properties.getProperty("user.approval.reference"),
                    authorizationMode);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_INVALID", error);
        }
    }

    void validateAt(Clock clock) {
        Instant now = Objects.requireNonNull(clock, "clock").instant();
        LocalDate marketToday = LocalDate.now(clock.withZone(MARKET_ZONE));
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_EXPIRED");
        }
        if (!tradeDate.isBefore(marketToday)) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_TRADE_DATE_NOT_ENDED");
        }
    }

    void validateBuildProof(VerifiedBuildProof proof) {
        Objects.requireNonNull(proof, "proof").validate();
        boolean eligible = authorizationMode == AuthorizationMode.E2E_DRY_RUN
                ? proof.e2eDryRunEligible() : proof.governanceEligible();
        if (!eligible || !gitCommit.equals(proof.gitCommit())
                || !artifactSha256.equals(proof.actualArtifactSha256())) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_INVALID");
        }
    }

    TushareDedicatedResearchBatchCommand command() {
        return new TushareDedicatedResearchBatchCommand(
                tradeDate, List.of(security), Duration.ofSeconds(30));
    }

    boolean e2eDryRun() {
        return authorizationMode == AuthorizationMode.E2E_DRY_RUN;
    }

    String fingerprint() {
        String material = runId + '|' + gitCommit + '|' + artifactSha256 + '|'
                + security.providerInstrumentId() + '|' + tradeDate + '|'
                + day001Mode + '|' + databasePort + '|' + issuedAt + '|'
                + expiresAt + '|' + userApprovalReference + '|'
                + authorizationMode;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private static String requireRunId(String value) {
        if (value == null || !value.matches("[A-Z0-9_-]{8,64}")) {
            throw invalid();
        }
        return value;
    }

    private static String requireCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw invalid();
        }
        return value;
    }

    private static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        return value;
    }

    private static String requireApprovalReference(String value) {
        if (value == null || !value.matches("[A-Z0-9_-]{8,96}")) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_INVALID");
    }

    enum Day001Mode {
        NEW_CAPTURE,
        IDEMPOTENCY_VERIFICATION
    }

    enum AuthorizationMode {
        USER_APPROVED,
        E2E_DRY_RUN
    }
}
