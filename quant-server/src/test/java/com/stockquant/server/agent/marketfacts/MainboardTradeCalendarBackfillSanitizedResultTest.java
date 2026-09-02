package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardTradeCalendarBackfillSanitizedResultTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOnlyCalendarAttemptAndLineageEvidence() throws Exception {
        LocalDate anchor = LocalDate.of(2026, 8, 27);
        Instant started = Instant.parse("2026-09-01T11:00:00Z");
        List<LocalDate> target = java.util.stream.IntStream.range(0, 250)
                .mapToObj(index -> anchor.minusDays(249L - index))
                .toList();
        var result = new MainboardTradeCalendarBackfillSanitizedResult.Result(
                MainboardTradeCalendarBackfillSanitizedResult.VERSION,
                "SUCCEEDED", "MBTC250_20260901T110000Z_ABCDEF123456",
                "a".repeat(40), started, started.plusSeconds(3), anchor,
                anchor.minusDays(500), anchor, 260, 250, 60, 357,
                target, anchor, "RESEARCH_UNIVERSE_MAINBOARD_V1",
                "SNAPSHOT", "b".repeat(64), 3193, 501, 501, 357,
                357, 0, 3, 2, 1, 0, 0, 0, 1, 2, 4,
                List.of(11L, 12L), 882, 120, true, true, true, true,
                0, 0, 0, 0, 0, true, true, true, false, null);
        Path path = temporaryDirectory.resolve("calendar.json");

        MainboardTradeCalendarBackfillSanitizedResult.ResultFile.reserve(
                path, result);

        JsonNode json = new ObjectMapper().readTree(Files.readString(path));
        assertEquals("2026-08-27", json.path("anchorTradeDate").asText());
        assertEquals(250, json.path("target250TradeDates").size());
        assertEquals(3, json.path("tushareProviderCallCount").asInt());
        assertEquals(2,
                json.path("sseTradeCalendarProviderCallCount").asInt());
        assertEquals(1,
                json.path("szseTradeCalendarProviderCallCount").asInt());
        assertEquals(0, json.path("dailyProviderCallCount").asInt());
        assertEquals(0,
                json.path("adjustmentFactorProviderCallCount").asInt());
        assertEquals(0, json.path("modelCallCount").asInt());
        assertTrue(json.path("knownAtValid").asBoolean());
        assertTrue(json.path("sourceLineageValid").asBoolean());
        assertTrue(json.path("dataOnly").asBoolean());
    }
}
