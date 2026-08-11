package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Strict, non-secret approval for one fixed M1 daily credential check. */
record TushareM1TokenVerificationAuthorization(
        String verificationId,
        String gitCommit,
        String artifactSha256,
        Path buildProofPath,
        SecuritySelection security,
        LocalDate tradeDate,
        int stageProviderCallsBefore,
        Instant issuedAt,
        Instant expiresAt,
        String userApprovalReference
) {
    static final String VERSION = "M1_TUSHARE_TOKEN_VERIFICATION_V1";
    static final String STATUS = "USER_APPROVED";
    static final String PURPOSE = "M1_RESEARCH_DATA_READY_TOKEN_VERIFICATION";
    static final String EXECUTION_SOURCE =
            "M1_TUSHARE_TOKEN_VERIFICATION_MANUAL";
    static final int HISTORICAL_BASELINE = 34;
    static final int STAGE_LIMIT = 30;
    static final int CUMULATIVE_LIMIT = 64;
    private static final Duration MAXIMUM_VALIDITY = Duration.ofMinutes(30);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "authorization.status", "authorization.version",
            "verification.id", "git.commit", "artifact.sha256",
            "build.proof.path", "provider", "endpoint",
            "security.symbol", "security.exchange", "trade.date",
            "endpoint.daily.requests", "maximum.provider.requests",
            "retry.budget", "redirects", "provider.historical.baseline",
            "provider.stage.limit", "provider.cumulative.limit",
            "provider.stage.calls.before", "issued.at", "expires.at",
            "purpose", "execution.source", "user.approval.reference");

    TushareM1TokenVerificationAuthorization {
        verificationId = require(verificationId, "M1TOKEN_[A-Z0-9_]{8,55}");
        gitCommit = require(gitCommit, "[0-9a-f]{40}");
        artifactSha256 = require(artifactSha256, "[0-9a-f]{64}");
        buildProofPath = Objects.requireNonNull(buildProofPath,
                "buildProofPath").toAbsolutePath().normalize();
        security = Objects.requireNonNull(security, "security");
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        userApprovalReference = require(userApprovalReference,
                "USER_APPROVED_M1_TOKEN_VERIFICATION");
        Duration validity = Duration.between(issuedAt, expiresAt);
        if (stageProviderCallsBefore < 0
                || stageProviderCallsBefore + 1 > STAGE_LIMIT
                || validity.isZero() || validity.isNegative()
                || validity.compareTo(MAXIMUM_VALIDITY) > 0) {
            throw invalid();
        }
    }

    static TushareM1TokenVerificationAuthorization load(Path file) {
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
                    "TUSHARE_M1_TOKEN_VERIFICATION_AUTH_UNREADABLE", error);
        }
        return from(properties);
    }

    static TushareM1TokenVerificationAuthorization from(
            Properties properties
    ) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.stringPropertyNames().equals(REQUIRED_KEYS)
                || properties.stringPropertyNames().stream().anyMatch(
                TushareM1TokenVerificationAuthorization::secretLikeKey)) {
            throw invalid();
        }
        exact(properties, "authorization.status", STATUS);
        exact(properties, "authorization.version", VERSION);
        exact(properties, "provider", "TUSHARE");
        exact(properties, "endpoint", "daily");
        exact(properties, "endpoint.daily.requests", "1");
        exact(properties, "maximum.provider.requests", "1");
        exact(properties, "retry.budget", "0");
        exact(properties, "redirects", "NEVER");
        exact(properties, "provider.historical.baseline", "34");
        exact(properties, "provider.stage.limit", "30");
        exact(properties, "provider.cumulative.limit", "64");
        exact(properties, "purpose", PURPOSE);
        exact(properties, "execution.source", EXECUTION_SOURCE);
        exact(properties, "user.approval.reference",
                "USER_APPROVED_M1_TOKEN_VERIFICATION");
        try {
            return new TushareM1TokenVerificationAuthorization(
                    properties.getProperty("verification.id"),
                    properties.getProperty("git.commit"),
                    properties.getProperty("artifact.sha256"),
                    Path.of(properties.getProperty("build.proof.path")),
                    new SecuritySelection(
                            properties.getProperty("security.symbol"),
                            properties.getProperty("security.exchange")),
                    LocalDate.parse(properties.getProperty("trade.date")),
                    Integer.parseInt(properties.getProperty(
                            "provider.stage.calls.before")),
                    Instant.parse(properties.getProperty("issued.at")),
                    Instant.parse(properties.getProperty("expires.at")),
                    properties.getProperty("user.approval.reference"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_AUTH_INVALID", error);
        }
    }

    void validateAt(Clock clock) {
        Instant now = Objects.requireNonNull(clock, "clock").instant();
        LocalDate today = LocalDate.now(clock.withZone(MARKET_ZONE));
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_AUTH_EXPIRED");
        }
        if (!tradeDate.isBefore(today)) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_DATE_NOT_ENDED");
        }
    }

    void validateBuildProof(VerifiedBuildProof proof) {
        Objects.requireNonNull(proof, "proof").validate();
        if (!proof.m1StageEligible()
                || !TushareControlledAcceptanceBuildProof
                .M1_RUNNER_START_CLASS.equals(proof.runnerStartClass())
                || !gitCommit.equals(proof.gitCommit())
                || !artifactSha256.equals(proof.actualArtifactSha256())) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_BUILD_PROOF_INVALID");
        }
    }

    String fingerprint() {
        String material = verificationId + '|' + gitCommit + '|'
                + artifactSha256 + '|' + security + '|' + tradeDate + '|'
                + stageProviderCallsBefore + '|' + issuedAt + '|' + expiresAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    int cumulativeProviderCallsBefore() {
        return HISTORICAL_BASELINE + stageProviderCallsBefore;
    }

    private static void exact(Properties values, String key, String expected) {
        if (!expected.equals(values.getProperty(key))) {
            throw invalid();
        }
    }

    private static String require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw invalid();
        }
        return value;
    }

    private static boolean secretLikeKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("token") || lower.contains("password")
                || lower.contains("secret") || lower.contains("credential");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_M1_TOKEN_VERIFICATION_AUTH_INVALID");
    }
}
