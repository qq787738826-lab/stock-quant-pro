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

class MainboardDailyIncrementSanitizedResultTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesDatesAndInstantsAsIsoTextForTheStrictBrokerContract()
            throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 19);
        Instant instant = Instant.parse("2026-08-20T10:00:00Z");
        var result = new MainboardDailyIncrementSanitizedResult.Result(
                MainboardDailyIncrementSanitizedResult.VERSION,
                "SUCCEEDED", "MBINC_20260820T100000Z_ABCDEF123456",
                "a".repeat(40), date, instant, instant.plusSeconds(1),
                "RESEARCH_UNIVERSE_MAINBOARD_V1", "SNAPSHOT", "b".repeat(64),
                3193, 1699, 1494, 147, 2, 1, 1, 0, List.of(141L),
                3189, 3189, 6378, 0, 3189, 3189, 0, true, true, true,
                true, date, 0, 0, 0, 0, 0, true, false, true, false,
                null);
        Path path = temporaryDirectory.resolve("result.json");

        MainboardDailyIncrementSanitizedResult.ResultFile.reserve(path,
                result);

        JsonNode json = new ObjectMapper().readTree(Files.readString(path));
        assertTrue(json.path("tradeDate").isTextual());
        assertEquals("2026-08-19", json.path("tradeDate").textValue());
        assertTrue(json.path("latestCompleteTradeDate").isTextual());
        assertEquals("2026-08-19",
                json.path("latestCompleteTradeDate").textValue());
        assertTrue(json.path("startedAt").isTextual());
        assertEquals("2026-08-20T10:00:00Z",
                json.path("startedAt").textValue());
    }
}
