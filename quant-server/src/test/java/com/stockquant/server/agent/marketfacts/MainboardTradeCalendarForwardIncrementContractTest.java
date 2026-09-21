package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardTradeCalendarForwardIncrementContractTest {

    @Test
    void historicalBackfillAnchorRestrictionRemainsExact() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "MainboardTradeCalendarBackfillService.java"));
        assertTrue(source.contains("!before.latestCommonOpenDate()"
                + ".equals(anchorTradeDate)"));
        assertTrue(source.contains(
                "MAINBOARD_TRADE_CAL_BACKFILL_ANCHOR_INVALID"));
    }

    @Test
    void forwardCaptureWaitsForBothProviderResponsesBeforeAtomicWrite()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareMainboardUniverseCaptureService.java"));
        int secondExchange = source.indexOf(
                "for (String exchange : List.of(\"SSE\", \"SZSE\"))",
                source.indexOf("captureForwardCalendars"));
        int atomicWrite = source.indexOf(
                "captureAuthorizedMainboardCalendars(responses",
                source.indexOf("captureForwardCalendars"));
        assertTrue(secondExchange >= 0 && atomicWrite > secondExchange);
    }
}
