package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareM1ResearchDataAuthorizationTest {
    @Test
    void parsesExactUserApprovedBudgetAndScope() {
        var authorization = TushareM1ResearchDataAuthorization.from(
                properties("CAPTURE", "0"));
        authorization.validateAt(Clock.fixed(
                Instant.parse("2026-08-11T02:01:00Z"), ZoneOffset.UTC));

        assertEquals(2, authorization.securities().size());
        assertEquals(6, authorization.maximumProviderRequests());
        assertEquals(34, authorization.cumulativeProviderCallsBefore());
        assertEquals("600000.SH",
                authorization.securities().get(0).providerInstrumentId());
        assertEquals("000001.SZ",
                authorization.securities().get(1).providerInstrumentId());
    }

    @Test
    void rejectsUnknownSecretAndStageBudgetOverflow() {
        Properties unknown = properties("CAPTURE", "0");
        unknown.setProperty("provider.token", "forbidden");
        assertThrows(IllegalArgumentException.class, () ->
                TushareM1ResearchDataAuthorization.from(unknown));
        assertThrows(IllegalArgumentException.class, () ->
                TushareM1ResearchDataAuthorization.from(
                        properties("CAPTURE", "25")));
        Properties mismatch = properties("CAPTURE", "0");
        mismatch.setProperty("maximum.provider.requests", "9");
        assertThrows(IllegalArgumentException.class, () ->
                TushareM1ResearchDataAuthorization.from(mismatch));
    }

    static Properties properties(String mode, String callsBefore) {
        Properties values = new Properties();
        values.setProperty("authorization.status", "USER_APPROVED");
        values.setProperty("authorization.version",
                "M1_RESEARCH_DATA_AUTHORIZATION_V1");
        values.setProperty("run.id", "M1_RUN_20260811_0001");
        values.setProperty("git.commit", "a".repeat(40));
        values.setProperty("artifact.sha256", "b".repeat(64));
        values.setProperty("build.proof.path",
                "D:/repo/quant-server/target/m1.jar.f1f-b2-proof.properties");
        values.setProperty("provider", "TUSHARE");
        values.setProperty("securities", "600000:SSE,000001:SZSE");
        values.setProperty("range.start", "2025-01-02");
        values.setProperty("range.end", "2025-01-06");
        values.setProperty("anchor.trade.date", "2025-01-06");
        values.setProperty("mode", mode);
        values.setProperty("endpoints", "daily,adj_factor,trade_cal");
        values.setProperty("endpoint.daily.requests", "2");
        values.setProperty("endpoint.adj_factor.requests", "2");
        values.setProperty("endpoint.trade_cal.requests", "2");
        values.setProperty("maximum.provider.requests", "6");
        values.setProperty("retry.budget", "0");
        values.setProperty("redirects", "NEVER");
        values.setProperty("provider.historical.baseline", "34");
        values.setProperty("provider.stage.limit", "30");
        values.setProperty("provider.cumulative.limit", "64");
        values.setProperty("provider.stage.calls.before", callsBefore);
        values.setProperty("database.host", "127.0.0.1");
        values.setProperty("database.port", "38432");
        values.setProperty("database.name", "stock_quant_research");
        values.setProperty("database.user", "stock_quant_research");
        values.setProperty("database.ssl.mode", "DISABLE_LOCAL_ONLY");
        values.setProperty("schema.name", "tushare_research");
        values.setProperty("issued.at", "2026-08-11T02:00:00Z");
        values.setProperty("expires.at", "2026-08-11T02:30:00Z");
        values.setProperty("purpose", "M1_RESEARCH_DATA_READY");
        values.setProperty("execution.source", "M1_RESEARCH_DATA_MANUAL");
        values.setProperty("user.approval.reference",
                "M1_STAGE_USER_APPROVED_20260811");
        return values;
    }
}
