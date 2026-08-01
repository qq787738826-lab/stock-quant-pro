package com.stockquant.server.agent.marketfacts;

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
    private final UserApproval userApproval;
    private final RuntimeBoundary runtimeBoundary;
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
        this.schemaVersion = 13;
        this.userApproval = UserApproval.CONFIRMED;
        this.runtimeBoundary = RuntimeBoundary.forbidAll();
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        validateFrozen();
    }

    public static TushareControlledAcceptanceAuthorization
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
                || schemaVersion != 13
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
                + ", userApproval=" + userApproval
                + ", runtimeBoundary=" + runtimeBoundary
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
