package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabaseGuard.ControlledVerification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.Verification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot, user-approved authorization for a future F1F-B controlled
 * acceptance run.
 *
 * <p>This object deliberately contains no token, password or JDBC URL. It is
 * consumed before the delegated F1E runtime can make a provider call.</p>
 */
public final class TushareControlledAcceptanceAuthorization {

    public static final int MAXIMUM_PROVIDER_REQUESTS = 3;
    public static final int MAXIMUM_SYMBOLS = 1;
    public static final int MAXIMUM_NATURAL_DAYS = 1;
    public static final Set<ControlledEndpoint> REQUIRED_ENDPOINTS = Set.of(
            ControlledEndpoint.DAILY,
            ControlledEndpoint.ADJ_FACTOR,
            ControlledEndpoint.TRADE_CAL);

    private final String acceptanceId;
    private final String codeBaselineCommit;
    private final String providerCode;
    private final SecuritySelection security;
    private final LocalDate tradeDate;
    private final Set<ControlledEndpoint> endpoints;
    private final int maximumProviderRequests;
    private final RetryPermission retryPermission;
    private final String databaseIdentity;
    private final String databaseUser;
    private final String schemaName;
    private final int schemaVersion;
    private final String artifactSha256;
    private final String authorizationFingerprint;
    private final UserApproval userApproval;
    private final RuntimeBoundary runtimeBoundary;
    private final ReuseProtectionScope reuseProtectionScope;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    private TushareControlledAcceptanceAuthorization(
            String acceptanceId,
            String codeBaselineCommit,
            SecuritySelection security,
            LocalDate tradeDate,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this(acceptanceId, codeBaselineCommit, security, tradeDate,
                issuedAt, expiresAt, 13, null,
                ReuseProtectionScope.OBJECT_INSTANCE_CAS_ONLY);
    }

    private TushareControlledAcceptanceAuthorization(
            String acceptanceId,
            String codeBaselineCommit,
            SecuritySelection security,
            LocalDate tradeDate,
            Instant issuedAt,
            Instant expiresAt,
            int schemaVersion,
            String artifactSha256,
            ReuseProtectionScope reuseProtectionScope
    ) {
        this.acceptanceId = requireAcceptanceId(acceptanceId);
        this.codeBaselineCommit = requireCommit(codeBaselineCommit);
        this.providerCode = TushareMarketFactProvider.PROVIDER_CODE;
        this.security = Objects.requireNonNull(security, "security");
        this.tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        this.endpoints = REQUIRED_ENDPOINTS;
        this.maximumProviderRequests = MAXIMUM_PROVIDER_REQUESTS;
        this.retryPermission = RetryPermission.FORBIDDEN;
        this.databaseIdentity =
                TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE;
        this.databaseUser =
                TushareDedicatedResearchPersistenceGuard.REQUIRED_USER;
        this.schemaName =
                TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA;
        this.schemaVersion = schemaVersion;
        this.artifactSha256 = artifactSha256 == null ? null
                : requireSha256(artifactSha256);
        this.userApproval = UserApproval.CONFIRMED;
        this.runtimeBoundary = RuntimeBoundary.forbidAll();
        this.reuseProtectionScope = Objects.requireNonNull(
                reuseProtectionScope, "reuseProtectionScope");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.authorizationFingerprint = fingerprint();
        validateFrozen();
    }

    static TushareControlledAcceptanceAuthorization
    issueUserApprovedDurable(
            String acceptanceId,
            String codeBaselineCommit,
            String artifactSha256,
            SecuritySelection security,
            LocalDate tradeDate,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return new TushareControlledAcceptanceAuthorization(
                acceptanceId, codeBaselineCommit, security, tradeDate,
                issuedAt, expiresAt, 14, artifactSha256,
                ReuseProtectionScope.DURABLE_ACCEPTANCE_ID_RESERVATION);
    }

    static TushareControlledAcceptanceAuthorization
    issueUserApprovedOneShot(
            String acceptanceId,
            String codeBaselineCommit,
            SecuritySelection security,
            LocalDate tradeDate,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return new TushareControlledAcceptanceAuthorization(
                acceptanceId,
                codeBaselineCommit,
                security,
                tradeDate,
                issuedAt,
                expiresAt);
    }

    public void validateAndConsume(
            TushareDedicatedResearchBatchCommand command,
            String activeCodeBaseline,
            Instant now,
            Verification databaseVerification
    ) {
        validatePreflight(command, activeCodeBaseline, now);
        Objects.requireNonNull(databaseVerification, "databaseVerification");
        validateDatabase(databaseVerification);
        if (!consumed.compareAndSet(false, true)) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ALREADY_USED");
        }
    }

    public void validateAndConsumeDurable(
            TushareDedicatedResearchBatchCommand command,
            String activeCodeBaseline,
            Instant now,
            ControlledVerification databaseVerification
    ) {
        validatePreflight(command, activeCodeBaseline, now);
        Objects.requireNonNull(databaseVerification, "databaseVerification");
        validateDurableDatabase(databaseVerification);
        if (!consumed.compareAndSet(false, true)) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ALREADY_USED");
        }
    }

    public void validatePreflight(
            TushareDedicatedResearchBatchCommand command,
            String activeCodeBaseline,
            Instant now
    ) {
        validateFrozen();
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(now, "now");
        if (!codeBaselineCommit.equals(requireCommit(activeCodeBaseline))) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BASELINE_MISMATCH");
        }
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_AUTHORIZATION_EXPIRED");
        }
        if (command.securities().size() != MAXIMUM_SYMBOLS
                || !command.securities().get(0).equals(security)
                || !command.tradeDate().equals(tradeDate)
                || command.expectedProviderRequests()
                != MAXIMUM_PROVIDER_REQUESTS) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_SCOPE_MISMATCH");
        }
    }

    public void validateFrozen() {
        if (!TushareMarketFactProvider.PROVIDER_CODE.equals(providerCode)
                || !endpoints.equals(REQUIRED_ENDPOINTS)
                || maximumProviderRequests != MAXIMUM_PROVIDER_REQUESTS
                || retryPermission != RetryPermission.FORBIDDEN
                || !TushareDedicatedResearchPersistenceGuard
                .REQUIRED_DATABASE.equals(databaseIdentity)
                || !TushareDedicatedResearchPersistenceGuard
                .REQUIRED_USER.equals(databaseUser)
                || !TushareDedicatedResearchPersistenceGuard
                .REQUIRED_SCHEMA.equals(schemaName)
                || schemaVersion != (reuseProtectionScope
                == ReuseProtectionScope.DURABLE_ACCEPTANCE_ID_RESERVATION
                ? 14 : 13)
                || reuseProtectionScope
                == ReuseProtectionScope.DURABLE_ACCEPTANCE_ID_RESERVATION
                && artifactSha256 == null
                || reuseProtectionScope
                == ReuseProtectionScope.OBJECT_INSTANCE_CAS_ONLY
                && artifactSha256 != null
                || userApproval != UserApproval.CONFIRMED
                || !runtimeBoundary.allForbidden()
                || !expiresAt.isAfter(issuedAt)) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_AUTHORIZATION_INVALID");
        }
    }

    private void validateDatabase(Verification verification) {
        TushareDedicatedResearchPersistenceGuard.validateVerificationTarget(
                verification, false);
        if (!databaseIdentity.equals(verification.currentDatabase())
                || !databaseUser.equals(verification.currentUser())
                || !schemaName.equals(verification.currentSchema())
                || verification.appliedMigrations().size() != schemaVersion
                || verification.normalBusinessDatabaseAllowed()) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_MISMATCH");
        }
    }

    private void validateDurableDatabase(ControlledVerification verification) {
        Verification base = verification.baseVerification();
        TushareDedicatedResearchPersistenceGuard.validateVerificationTarget(base, false);
        if (!databaseIdentity.equals(base.currentDatabase())
                || !databaseUser.equals(base.currentUser())
                || !schemaName.equals(base.currentSchema())
                || !TushareDedicatedResearchPersistenceGuard.REQUIRED_MIGRATIONS.equals(
                base.appliedMigrations())
                || verification.controlledSchemaVersion() != schemaVersion
                || !TushareControlledAcceptanceDatabaseGuard.GOVERNANCE_MIGRATIONS.equals(
                verification.governanceMigrations())
                || base.normalBusinessDatabaseAllowed()) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_MISMATCH");
        }
    }

    public String acceptanceId() {
        return acceptanceId;
    }

    public String codeBaselineCommit() {
        return codeBaselineCommit;
    }

    public String providerCode() {
        return providerCode;
    }

    public SecuritySelection security() {
        return security;
    }

    public LocalDate tradeDate() {
        return tradeDate;
    }

    public Set<ControlledEndpoint> endpoints() {
        return endpoints;
    }

    public int maximumProviderRequests() {
        return maximumProviderRequests;
    }

    public RetryPermission retryPermission() {
        return retryPermission;
    }

    public String databaseIdentity() {
        return databaseIdentity;
    }

    public String databaseUser() {
        return databaseUser;
    }

    public String schemaName() {
        return schemaName;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public UserApproval userApproval() {
        return userApproval;
    }

    public RuntimeBoundary runtimeBoundary() {
        return runtimeBoundary;
    }

    public ReuseProtectionScope reuseProtectionScope() {
        return reuseProtectionScope;
    }

    public boolean durableConsumptionRecorded() {
        return reuseProtectionScope
                == ReuseProtectionScope.DURABLE_ACCEPTANCE_ID_RESERVATION;
    }

    public String artifactSha256() {
        return artifactSha256;
    }

    public String authorizationFingerprint() {
        return authorizationFingerprint;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean consumed() {
        return consumed.get();
    }

    @Override
    public String toString() {
        return "TushareControlledAcceptanceAuthorization["
                + "acceptanceId=" + acceptanceId
                + ", codeBaselineCommit=" + codeBaselineCommit
                + ", providerCode=" + providerCode
                + ", security=" + security.providerInstrumentId()
                + ", tradeDate=" + tradeDate
                + ", endpoints=" + endpoints
                + ", maximumProviderRequests=" + maximumProviderRequests
                + ", retryPermission=" + retryPermission
                + ", databaseIdentity=" + databaseIdentity
                + ", databaseUser=" + databaseUser
                + ", schemaName=" + schemaName
                + ", schemaVersion=" + schemaVersion
                + ", artifactSha256="
                + (artifactSha256 == null ? "none" : "[SHA256]")
                + ", authorizationFingerprint=[SHA256]"
                + ", userApproval=" + userApproval
                + ", runtimeBoundary=" + runtimeBoundary
                + ", reuseProtectionScope=" + reuseProtectionScope
                + ", durableConsumptionRecorded="
                + durableConsumptionRecorded()
                + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt
                + ", consumed=" + consumed.get() + ']';
    }

    private static String requireAcceptanceId(String value) {
        if (value == null || !value.matches("[A-Z0-9_-]{8,64}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ID_INVALID");
        }
        return value;
    }

    private static String requireCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BASELINE_INVALID");
        }
        return value;
    }

    private static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ARTIFACT_INVALID");
        }
        return value;
    }

    private String fingerprint() {
        String canonicalEndpoints = endpoints.stream()
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String material = acceptanceId + '|' + codeBaselineCommit + '|'
                + providerCode + '|' + security.providerInstrumentId() + '|'
                + tradeDate + '|' + canonicalEndpoints + '|' + maximumProviderRequests
                + '|' + retryPermission + '|' + databaseIdentity + '|'
                + databaseUser + '|' + schemaName + '|' + schemaVersion + '|'
                + userApproval + '|' + runtimeBoundary + '|'
                + reuseProtectionScope + '|' + issuedAt + '|' + expiresAt + '|'
                + (artifactSha256 == null ? "NONE" : artifactSha256);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid(String safeCode) {
        return new IllegalArgumentException(safeCode);
    }

    public enum ControlledEndpoint {
        DAILY("daily"),
        ADJ_FACTOR("adj_factor"),
        TRADE_CAL("trade_cal");

        private final String apiName;

        ControlledEndpoint(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }
    }

    public enum RetryPermission {
        FORBIDDEN
    }

    public enum UserApproval {
        CONFIRMED
    }

    /**
     * F1F-A only prevents concurrent reuse of the same in-memory object.
     * A durable acceptance-id reservation remains an F1F-B prerequisite.
     */
    public enum ReuseProtectionScope {
        OBJECT_INSTANCE_CAS_ONLY,
        DURABLE_ACCEPTANCE_ID_RESERVATION
    }

    public record RuntimeBoundary(
            boolean normalBusinessDatabaseAllowed,
            boolean publicSchemaAllowed,
            boolean schedulerAllowed,
            boolean agentAllowed,
            boolean backtestAllowed,
            boolean shadowAllowed,
            boolean tradingAllowed,
            boolean downstreamStageAllowed
    ) {
        public RuntimeBoundary {
            if (!allForbidden()) {
                throw invalid(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_RUNTIME_SCOPE_INVALID");
            }
        }

        static RuntimeBoundary forbidAll() {
            return new RuntimeBoundary(
                    false, false, false, false,
                    false, false, false, false);
        }

        boolean allForbidden() {
            return !normalBusinessDatabaseAllowed
                    && !publicSchemaAllowed
                    && !schedulerAllowed
                    && !agentAllowed
                    && !backtestAllowed
                    && !shadowAllowed
                    && !tradingAllowed
                    && !downstreamStageAllowed;
        }
    }
}
