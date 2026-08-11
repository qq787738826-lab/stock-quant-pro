package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter
        .CallTelemetry;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter
        .FailureDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM3AgentResearchSmokeResultTest {
    @TempDir
    Path temporary;

    @Test
    void serializesOnlySanitizedFailedModelDiagnostics() throws Exception {
        FailureDiagnostics diagnostics = new FailureDiagnostics(
                "HTTP_STATUS", 1, 1, 0, 0, 0, 0, 0,
                new BigDecimal("0.071000000000"),
                new BigDecimal("4.50"), "CNY", java.util.List.of(
                new CallTelemetry(1, "USAGE_UNAVAILABLE", 0, 0, 0, 0,
                        new BigDecimal("0.071000000000"),
                        new BigDecimal("0.071000000000"), null,
                        "NOT_PROVIDED_BY_API")), 401,
                "APPLICATION_JSON", "VALID_JSON", "INVALID_API_KEY",
                "AUTHENTICATION", "REGION_OR_ENDPOINT");
        Instant now = Instant.parse("2026-08-11T10:33:01Z");
        var result = new TushareM3AgentResearchSmokeResult.Result(
                TushareM3AgentResearchSmokeResult.VERSION, "FAILED",
                "M3SMOKE_20260811T103301Z_584F9EB70E49",
                "c".repeat(40), "d".repeat(64),
                TushareControlledAcceptanceBuildProof.M3_RUNNER_START_CLASS,
                now, now.plusSeconds(1), null, null, false, false,
                null, null,
                TushareM3AgentResearchSmokeResult.Audit.notRun(),
                0, 0, diagnostics, "M3_BAILIAN_AUTHENTICATION_FAILED");
        Path path = temporary.resolve("sanitized.json");

        TushareM3AgentResearchSmokeResult.ResultFile.reserve(path, temporary,
                result);

        String json = Files.readString(path);
        assertTrue(json.contains("\"failureSource\":\"HTTP_STATUS\""));
        assertTrue(json.contains("\"httpStatus\":401"));
        assertTrue(json.contains("\"providerCode\":\"INVALID_API_KEY\""));
        assertTrue(json.contains("\"networkCallCount\":1"));
        assertTrue(json.contains("\"actualCostStatus\":"
                + "\"NOT_PROVIDED_BY_API\""));
        assertFalse(json.toLowerCase().contains("bearer"));
        assertFalse(json.toLowerCase().contains("provider-sensitive"));
    }
}
