package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Authorization.AuthorizationMode;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Authorization.Day001Mode;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.CheckResult;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.FinalStatus;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.OutputAuditSummary;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Result.QfqSummary;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TushareReducedResearchDay001AuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void realAuthorizationFreezesTheExactDay001Scope() {
        var authorization = authorization(
                AuthorizationMode.USER_APPROVED,
                Day001Mode.IDEMPOTENCY_VERIFICATION,
                TushareReducedResearchDay001Authorization.PRODUCTION_DATABASE_PORT,
                NOW.minusSeconds(5), NOW.plusSeconds(600));

        assertDoesNotThrow(() -> authorization.validateAt(CLOCK));
        assertEquals("600000.SH",
                authorization.security().providerInstrumentId());
        assertEquals(3, authorization.command().expectedProviderRequests());
        assertFalse(authorization.e2eDryRun());
        assertEquals(64, authorization.fingerprint().length());
    }

    @Test
    void e2eAuthorizationUsesOnlyAnEphemeralPortAndNoApproval() {
        var authorization = authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));

        assertTrue(authorization.e2eDryRun());
        assertEquals("NOT_APPLICABLE_E2E_DRY_RUN",
                authorization.userApprovalReference());
    }

    @Test
    void expiredFutureAndNonEndedDatesAreRejected() {
        var expired = authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(900), NOW.minusSeconds(1));
        var future = authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.plusSeconds(1), NOW.plusSeconds(300));
        Properties today = validProperties(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        today.setProperty("trade.date", "2026-08-04");

        assertEquals("TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_EXPIRED",
                assertThrows(IllegalArgumentException.class,
                        () -> expired.validateAt(CLOCK)).getMessage());
        assertEquals("TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_EXPIRED",
                assertThrows(IllegalArgumentException.class,
                        () -> future.validateAt(CLOCK)).getMessage());
        var sameDay = TushareReducedResearchDay001Authorization.from(today);
        assertEquals("TUSHARE_REDUCED_RESEARCH_TRADE_DATE_NOT_ENDED",
                assertThrows(IllegalArgumentException.class,
                        () -> sameDay.validateAt(CLOCK)).getMessage());
    }

    @Test
    void validityCannotExceedThirtyMinutes() {
        assertThrows(IllegalArgumentException.class, () -> authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW, NOW.plusSeconds(1_801)));
    }

    @Test
    void productionAndE2ePortsCannotCrossTheirBoundary() {
        assertThrows(IllegalArgumentException.class, () -> authorization(
                AuthorizationMode.USER_APPROVED, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600)));
        assertThrows(IllegalArgumentException.class, () -> authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                TushareReducedResearchDay001Authorization.PRODUCTION_DATABASE_PORT,
                NOW.minusSeconds(5), NOW.plusSeconds(600)));
    }

    @Test
    void wrongProviderEndpointBudgetRetryRedirectDatabaseAndSourceAreRejected() {
        assertInvalid("provider", "TUSHARE_PRO");
        assertInvalid("endpoints", "daily,trade_cal,adj_factor");
        assertInvalid("endpoint.adj_factor.requests", "2");
        assertInvalid("maximum.provider.requests", "4");
        assertInvalid("retry.budget", "1");
        assertInvalid("redirects", "NORMAL");
        assertInvalid("database.host", "localhost");
        assertInvalid("database.name", "stock_quant");
        assertInvalid("database.user", "postgres");
        assertInvalid("schema.name", "public");
        assertInvalid("execution.source", "TEST");
    }

    @Test
    void secretLikeFieldsAndUnknownFieldsAreRejected() {
        Properties token = validProperties(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        token.setProperty("provider.token", "forbidden");
        Properties password = validProperties(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        password.setProperty("database.password", "forbidden");

        assertThrows(IllegalArgumentException.class,
                () -> TushareReducedResearchDay001Authorization.from(token));
        assertThrows(IllegalArgumentException.class,
                () -> TushareReducedResearchDay001Authorization.from(password));
    }

    @Test
    void buildBindingRejectsAHashOrEligibilityMismatch() {
        var authorization = authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        var testOnlyProof = TushareControlledAcceptanceBuildProof
                .verifiedTestProof("1".repeat(40), "a".repeat(64));

        assertEquals("TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_INVALID",
                assertThrows(IllegalArgumentException.class,
                        () -> authorization.validateBuildProof(testOnlyProof))
                        .getMessage());
    }

    @Test
    void successResultDistinguishesNewCaptureAndIdempotentTail() {
        var newCapture = authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        var idempotent = authorization(
                AuthorizationMode.E2E_DRY_RUN,
                Day001Mode.IDEMPOTENCY_VERIFICATION,
                55_433, NOW.minusSeconds(5), NOW.plusSeconds(600));
        OutputAuditSummary clean = new OutputAuditSummary(
                true, true, 0, java.util.List.of());

        var appended = TushareReducedResearchDay001Result.success(
                newCapture, NOW, NOW.plusSeconds(1), 1, 3, 0,
                QfqSummary.passed(), clean);
        var tail = TushareReducedResearchDay001Result.success(
                idempotent, NOW, NOW.plusSeconds(1), 2, 0, 3,
                QfqSummary.passed(), clean);

        assertEquals(FinalStatus.SUCCEEDED, appended.status());
        assertEquals(3, appended.newObservationCount());
        assertEquals(3, tail.existingChainTailCount());
        assertEquals(CheckResult.PASSED, tail.typedFactReadback());
        assertFalse(tail.passedAcceptanceStatusProduced());
        assertFalse(tail.operationalReadinessModified());
        assertTrue(tail.prohibitedStages().allNotStarted());
    }

    @Test
    void successResultRejectsModeMismatchOrNonCleanAudit() {
        var authorization = authorization(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        OutputAuditSummary clean = new OutputAuditSummary(
                true, true, 0, java.util.List.of());
        OutputAuditSummary dirty = new OutputAuditSummary(
                true, false, 1, java.util.List.of("SECRET_EXACT"));

        assertThrows(IllegalArgumentException.class, () ->
                TushareReducedResearchDay001Result.success(
                        authorization, NOW, NOW.plusSeconds(1),
                        1, 0, 3, QfqSummary.passed(), clean));
        assertThrows(IllegalArgumentException.class, () ->
                TushareReducedResearchDay001Result.success(
                        authorization, NOW, NOW.plusSeconds(1),
                        1, 3, 0, QfqSummary.passed(), dirty));
    }

    private static void assertInvalid(String key, String value) {
        Properties properties = validProperties(
                AuthorizationMode.E2E_DRY_RUN, Day001Mode.NEW_CAPTURE,
                55_432, NOW.minusSeconds(5), NOW.plusSeconds(600));
        properties.setProperty(key, value);
        assertThrows(IllegalArgumentException.class,
                () -> TushareReducedResearchDay001Authorization.from(properties));
    }

    static TushareReducedResearchDay001Authorization authorization(
            AuthorizationMode authorizationMode,
            Day001Mode day001Mode,
            int port,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return TushareReducedResearchDay001Authorization.from(
                validProperties(authorizationMode, day001Mode, port,
                        issuedAt, expiresAt));
    }

    static Properties validProperties(
            AuthorizationMode authorizationMode,
            Day001Mode day001Mode,
            int port,
            Instant issuedAt,
            Instant expiresAt
    ) {
        Properties properties = new Properties();
        properties.setProperty("authorization.status",
                authorizationMode == AuthorizationMode.E2E_DRY_RUN
                        ? TushareReducedResearchDay001Authorization.STATUS_E2E_DRY_RUN
                        : TushareReducedResearchDay001Authorization
                        .STATUS_USER_APPROVED);
        properties.setProperty("authorization.version",
                TushareReducedResearchDay001Authorization.VERSION);
        properties.setProperty("run.id", "RRDAY001_TEST_0001");
        properties.setProperty("git.commit", "1".repeat(40));
        properties.setProperty("artifact.sha256", "a".repeat(64));
        properties.setProperty("build.proof.path",
                "C:\\artifact\\runner.jar.f1f-b2-proof.properties");
        properties.setProperty("provider", "TUSHARE");
        properties.setProperty("security.symbol", "600000");
        properties.setProperty("security.exchange", "SSE");
        properties.setProperty("trade.date", "2025-01-03");
        properties.setProperty("day001.mode", day001Mode.name());
        properties.setProperty("endpoints", "daily,adj_factor,trade_cal");
        properties.setProperty("endpoint.daily.requests", "1");
        properties.setProperty("endpoint.adj_factor.requests", "1");
        properties.setProperty("endpoint.trade_cal.requests", "1");
        properties.setProperty("maximum.provider.requests", "3");
        properties.setProperty("retry.budget", "0");
        properties.setProperty("redirects", "NEVER");
        properties.setProperty("database.host", "127.0.0.1");
        properties.setProperty("database.port", Integer.toString(port));
        properties.setProperty("database.name", "stock_quant_research");
        properties.setProperty("database.user", "stock_quant_research");
        properties.setProperty("database.ssl.mode", "DISABLE_LOCAL_ONLY");
        properties.setProperty("schema.name", "tushare_research");
        properties.setProperty("issued.at", issuedAt.toString());
        properties.setProperty("expires.at", expiresAt.toString());
        properties.setProperty("purpose", "3A_R3B_RR_DAY001");
        properties.setProperty("execution.source",
                "REDUCED_RESEARCH_MANUAL_DAY001");
        properties.setProperty("user.approval.reference",
                authorizationMode == AuthorizationMode.E2E_DRY_RUN
                        ? "NOT_APPLICABLE_E2E_DRY_RUN"
                        : "USER_APPROVED_DAY001_0001");
        return properties;
    }
}
