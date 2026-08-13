package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionBrokerResultReconcilerTest {
    private static final String ID =
            "SQHB_20260813T100000Z_A1B2C3D4E5F6";

    @TempDir
    Path temporary;

    @Test
    void acceptsOnlySanitizedZeroCallPreRunnerFailure() throws Exception {
        Path file = temporary.resolve(ID + ".result.json");
        Files.writeString(file, """
                {"requestId":"%s","operation":"RUN_RESEARCH_SELECTION",
                 "status":"REJECTED","reason":"STOCK_QUANT_HOST_BROKER_BUILD_INVALID",
                 "providerCallCount":0,"retryCount":0,"noRetry":true}
                """.formatted(ID));

        var failure = ResearchSelectionBrokerResultReconciler.failure(
                new ObjectMapper(), file, ID).orElseThrow();

        assertEquals("STOCK_QUANT_HOST_BROKER_BUILD_INVALID",
                failure.reason());
    }

    @Test
    void rejectsForeignOrProviderConsumingResult() throws Exception {
        Path file = temporary.resolve(ID + ".result.json");
        Files.writeString(file, """
                {"requestId":"%s","operation":"RUN_RESEARCH_SELECTION",
                 "status":"FAILED","reason":"RESEARCH_SELECTION_FAILED",
                 "providerCallCount":1,"retryCount":0,"noRetry":true}
                """.formatted(ID));

        assertTrue(ResearchSelectionBrokerResultReconciler.failure(
                new ObjectMapper(), file, ID).isEmpty());
    }
}
