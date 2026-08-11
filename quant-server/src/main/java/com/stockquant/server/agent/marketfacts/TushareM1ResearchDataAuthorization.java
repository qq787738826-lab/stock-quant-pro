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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Strict, non-secret, one-shot authorization for one M1 research window. */
record TushareM1ResearchDataAuthorization(
        String runId,
        String gitCommit,
        String artifactSha256,
        Path buildProofPath,
        List<SecuritySelection> securities,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        LocalDate anchorTradeDate,
        TushareM1ResearchWindowCommand.Mode mode,
        int databasePort,
        SslMode sslMode,
        int stageProviderCallsBefore,
        Instant issuedAt,
        Instant expiresAt,
        String userApprovalReference,
        AuthorizationMode authorizationMode
) {
    static final String VERSION = "M1_RESEARCH_DATA_AUTHORIZATION_V1";
    static final String STATUS_USER_APPROVED = "USER_APPROVED";
    static final String STATUS_E2E_DRY_RUN = "E2E_DRY_RUN";
    static final String PROVIDER = "TUSHARE";
    static final String EXECUTION_SOURCE = "M1_RESEARCH_DATA_MANUAL";
    static final String PURPOSE = "M1_RESEARCH_DATA_READY";
    static final String DATABASE_HOST = "127.0.0.1";
    static final int PRODUCTION_DATABASE_PORT = 38_432;
    static final int HISTORICAL_PROVIDER_CALL_BASELINE = 34;
    static final int STAGE_PROVIDER_CALL_LIMIT = 30;
    static final int CUMULATIVE_PROVIDER_CALL_LIMIT = 64;
    static final Duration MAXIMUM_VALIDITY = Duration.ofMinutes(30);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "authorization.status", "authorization.version", "run.id",
            "git.commit", "artifact.sha256", "build.proof.path", "provider",
            "securities", "range.start", "range.end", "anchor.trade.date",
            "mode", "endpoints", "endpoint.daily.requests",
            "endpoint.adj_factor.requests", "endpoint.trade_cal.requests",
            "maximum.provider.requests", "retry.budget", "redirects",
            "provider.historical.baseline", "provider.stage.limit",
            "provider.cumulative.limit", "provider.stage.calls.before",
            "database.host", "database.port", "database.name", "database.user",
            "database.ssl.mode", "schema.name", "issued.at", "expires.at",
            "purpose", "execution.source", "user.approval.reference");

    TushareM1ResearchDataAuthorization {
        runId = require(runId, "[A-Z0-9_-]{8,64}");
        gitCommit = require(gitCommit, "[0-9a-f]{40}");
        artifactSha256 = require(artifactSha256, "[0-9a-f]{64}");
        buildProofPath = Objects.requireNonNull(buildProofPath, "buildProofPath")
                .toAbsolutePath().normalize();
        securities = List.copyOf(Objects.requireNonNull(
                securities, "securities"));
        rangeStart = Objects.requireNonNull(rangeStart, "rangeStart");
        rangeEnd = Objects.requireNonNull(rangeEnd, "rangeEnd");
        anchorTradeDate = Objects.requireNonNull(
                anchorTradeDate, "anchorTradeDate");
        mode = Objects.requireNonNull(mode, "mode");
        sslMode = Objects.requireNonNull(sslMode, "sslMode");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        userApprovalReference = require(
                userApprovalReference, "[A-Z0-9_-]{8,96}");
        authorizationMode = Objects.requireNonNull(
                authorizationMode, "authorizationMode");
        TushareM1ResearchWindowCommand command =
                new TushareM1ResearchWindowCommand(
                        securities, rangeStart, rangeEnd, anchorTradeDate,
                        mode, Duration.ofSeconds(30));
        Duration validity = Duration.between(issuedAt, expiresAt);
        if (databasePort <= 0 || databasePort > 65_535
                || validity.isZero() || validity.isNegative()
                || validity.compareTo(MAXIMUM_VALIDITY) > 0
                || stageProviderCallsBefore < 0
                || stageProviderCallsBefore + command.expectedProviderRequests()
                > STAGE_PROVIDER_CALL_LIMIT
                || authorizationMode == AuthorizationMode.USER_APPROVED
                && databasePort != PRODUCTION_DATABASE_PORT
                || authorizationMode == AuthorizationMode.E2E_DRY_RUN
                && databasePort == PRODUCTION_DATABASE_PORT) {
            throw invalid();
        }
    }

    static TushareM1ResearchDataAuthorization load(Path file) {
        Objects.requireNonNull(file, "file");
        Properties properties = new Properties();
        try {
            Map<String, String> strict = new LinkedHashMap<>();
            for (String line : Files.readAllLines(
                    file.toAbsolutePath().normalize(), StandardCharsets.UTF_8)) {
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
                    "TUSHARE_M1_AUTHORIZATION_UNREADABLE", error);
        }
        return from(properties);
    }

    static TushareM1ResearchDataAuthorization from(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.stringPropertyNames().equals(REQUIRED_KEYS)
                || properties.stringPropertyNames().stream().anyMatch(
                TushareM1ResearchDataAuthorization::secretLikeKey)) {
            throw invalid();
        }
        exact(properties, "authorization.version", VERSION);
        exact(properties, "provider", PROVIDER);
        exact(properties, "endpoints", "daily,adj_factor,trade_cal");
        exact(properties, "retry.budget", "0");
        exact(properties, "redirects", "NEVER");
        exact(properties, "provider.historical.baseline", "34");
        exact(properties, "provider.stage.limit", "30");
        exact(properties, "provider.cumulative.limit", "64");
        exact(properties, "database.host", DATABASE_HOST);
        exact(properties, "database.name",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE);
        exact(properties, "database.user",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_USER);
        exact(properties, "schema.name",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA);
        exact(properties, "purpose", PURPOSE);
        exact(properties, "execution.source", EXECUTION_SOURCE);
        AuthorizationMode authorizationMode;
        String status = properties.getProperty("authorization.status");
        if (STATUS_USER_APPROVED.equals(status)) {
            authorizationMode = AuthorizationMode.USER_APPROVED;
        } else if (STATUS_E2E_DRY_RUN.equals(status)) {
            authorizationMode = AuthorizationMode.E2E_DRY_RUN;
            exact(properties, "user.approval.reference",
                    "NOT_APPLICABLE_E2E_DRY_RUN");
        } else {
            throw invalid();
        }
        try {
            List<SecuritySelection> securities = parseSecurities(
                    properties.getProperty("securities"));
            int expected = securities.size() * 3;
            exact(properties, "endpoint.daily.requests",
                    Integer.toString(securities.size()));
            exact(properties, "endpoint.adj_factor.requests",
                    Integer.toString(securities.size()));
            exact(properties, "endpoint.trade_cal.requests",
                    Integer.toString(securities.size()));
            exact(properties, "maximum.provider.requests",
                    Integer.toString(expected));
            return new TushareM1ResearchDataAuthorization(
                    properties.getProperty("run.id"),
                    properties.getProperty("git.commit"),
                    properties.getProperty("artifact.sha256"),
                    Path.of(properties.getProperty("build.proof.path")),
                    securities,
                    LocalDate.parse(properties.getProperty("range.start")),
                    LocalDate.parse(properties.getProperty("range.end")),
                    LocalDate.parse(properties.getProperty("anchor.trade.date")),
                    TushareM1ResearchWindowCommand.Mode.valueOf(
                            properties.getProperty("mode")),
                    Integer.parseInt(properties.getProperty("database.port")),
                    SslMode.valueOf(properties.getProperty("database.ssl.mode")
                            .toUpperCase(Locale.ROOT)),
                    Integer.parseInt(properties.getProperty(
                            "provider.stage.calls.before")),
                    Instant.parse(properties.getProperty("issued.at")),
                    Instant.parse(properties.getProperty("expires.at")),
                    properties.getProperty("user.approval.reference"),
                    authorizationMode);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_AUTHORIZATION_INVALID", error);
        }
    }

    void validateAt(Clock clock) {
        Instant now = Objects.requireNonNull(clock, "clock").instant();
        LocalDate today = LocalDate.now(clock.withZone(MARKET_ZONE));
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_AUTHORIZATION_EXPIRED");
        }
        if (!rangeEnd.isBefore(today)) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_RANGE_NOT_ENDED");
        }
    }

    void validateBuildProof(VerifiedBuildProof proof) {
        Objects.requireNonNull(proof, "proof").validate();
        boolean eligible = authorizationMode == AuthorizationMode.E2E_DRY_RUN
                ? proof.e2eDryRunEligible()
                : proof.governanceEligible() || proof.m1StageEligible();
        if (!eligible
                || !TushareControlledAcceptanceBuildProof.M1_RUNNER_START_CLASS
                .equals(proof.runnerStartClass())
                || !gitCommit.equals(proof.gitCommit())
                || !artifactSha256.equals(proof.actualArtifactSha256())) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_BUILD_PROOF_INVALID");
        }
    }

    TushareM1ResearchWindowCommand command() {
        return new TushareM1ResearchWindowCommand(
                securities, rangeStart, rangeEnd, anchorTradeDate, mode,
                Duration.ofSeconds(30));
    }

    int maximumProviderRequests() {
        return command().expectedProviderRequests();
    }

    int cumulativeProviderCallsBefore() {
        return HISTORICAL_PROVIDER_CALL_BASELINE + stageProviderCallsBefore;
    }

    boolean e2eDryRun() {
        return authorizationMode == AuthorizationMode.E2E_DRY_RUN;
    }

    String fingerprint() {
        String material = runId + '|' + gitCommit + '|' + artifactSha256 + '|'
                + securities + '|' + rangeStart + '|' + rangeEnd + '|'
                + anchorTradeDate + '|' + mode + '|' + databasePort + '|'
                + stageProviderCallsBefore + '|' + issuedAt + '|' + expiresAt
                + '|' + userApprovalReference + '|' + authorizationMode;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<SecuritySelection> parseSecurities(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        List<SecuritySelection> result = new ArrayList<>();
        for (String item : value.split(",", -1)) {
            String[] parts = item.split(":", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            result.add(new SecuritySelection(parts[0], parts[1]));
        }
        if (result.isEmpty()
                || result.size() > TushareManualBoundedSession.M1_MAX_SYMBOLS) {
            throw invalid();
        }
        return List.copyOf(result);
    }

    private static boolean secretLikeKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("token") || lower.contains("password")
                || lower.contains("secret") || lower.contains("jdbc");
    }

    private static void exact(Properties properties, String key, String value) {
        if (!value.equals(properties.getProperty(key))) {
            throw invalid();
        }
    }

    private static String require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("TUSHARE_M1_AUTHORIZATION_INVALID");
    }

    enum AuthorizationMode {
        USER_APPROVED,
        E2E_DRY_RUN
    }
}
