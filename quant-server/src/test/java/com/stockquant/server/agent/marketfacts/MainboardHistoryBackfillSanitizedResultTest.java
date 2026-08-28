package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardHistoryBackfillSanitizedResultTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesStrictIsoDatesAndAttemptAccounting() throws Exception {
        LocalDate start = LocalDate.of(2025, 8, 29);
        LocalDate end = LocalDate.of(2026, 8, 27);
        Instant instant = Instant.parse("2026-08-28T02:00:00Z");
        var result = new MainboardHistoryBackfillSanitizedResult.Result(
                MainboardHistoryBackfillSanitizedResult.VERSION,
                "SUCCEEDED", "MBH250_20260828T020000Z_ABCDEF123456",
                "a".repeat(40), instant, instant.plusSeconds(10), end,
                start, end, 250, 60, 190, List.of(start, end),
                List.of(start), List.of(start), 250, true, 0, true, 0,
                0, 0, "RESEARCH_UNIVERSE_MAINBOARD_V1", "SNAPSHOT",
                "b".repeat(64), 3193, 1699, 1494, 145, 382, 191, 191,
                0, 0, 2, 4, 384, List.of(1L), 606_670, 606_670,
                1_213_340, 0, new BigDecimal("3193.00"), true, true,
                "POST_HOC_RESEARCH", "PIT_PARTIAL", true, 0, 0, 0, 0,
                0, true, false, true, false, null);
        Path path = temporaryDirectory.resolve("backfill.json");

        MainboardHistoryBackfillSanitizedResult.ResultFile.reserve(path,
                result);

        JsonNode json = new ObjectMapper().readTree(Files.readString(path));
        assertTrue(json.path("anchorTradeDate").isTextual());
        assertEquals("2026-08-27", json.path("anchorTradeDate").asText());
        assertTrue(json.path("targetRangeStart").isTextual());
        assertEquals("2025-08-29", json.path("targetRangeStart").asText());
        assertTrue(json.path("startedAt").isTextual());
        assertEquals(382, json.path("tushareProviderCallCount").asInt());
        assertEquals(2, json.path("retryCount").asInt());
        assertEquals("PIT_PARTIAL", json.path("pitClassification").asText());
    }
}
