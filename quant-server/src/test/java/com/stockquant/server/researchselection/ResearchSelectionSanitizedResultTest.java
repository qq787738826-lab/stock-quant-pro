package com.stockquant.server.researchselection;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResearchSelectionSanitizedResultTest {

    @Test
    void resourceExhaustionKeepsOnlySafeClassCategoryAndProjectFrame() {
        OutOfMemoryError failure = new OutOfMemoryError("secret-like-detail");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.HashMap", "resize",
                        "HashMap.java", 702),
                new StackTraceElement(
                        "com.stockquant.server.researchselection."
                                + "ResearchUniverseMainboardDatasetLoader",
                        "load", "ResearchUniverseMainboardDatasetLoader.java",
                        91)
        });

        var diagnostic = ResearchSelectionSanitizedResult.diagnose(failure);

        assertEquals("java.lang.OutOfMemoryError",
                diagnostic.exceptionClass());
        assertEquals("RESOURCE_EXHAUSTED", diagnostic.category());
        assertEquals("com.stockquant.server.researchselection."
                        + "ResearchUniverseMainboardDatasetLoader.load("
                        + "ResearchUniverseMainboardDatasetLoader.java:91)",
                diagnostic.firstProjectStackFrame());
        assertEquals("RESEARCH_SELECTION_RESOURCE_EXHAUSTED",
                diagnostic.sanitizedReason());
        assertFalse(diagnostic.toString().contains("secret-like-detail"));

        var result = ResearchSelectionSanitizedResult.failure(
                "SELECTEXEC_20260902T111149Z_475D06C0E014",
                "a".repeat(40), Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                37, "SELECT_20260902T111019Z_FBC194B43FD1", 0, 0, 0,
                null, diagnostic.sanitizedReason(), true, null, diagnostic);
        assertEquals(diagnostic, result.failureDiagnostic());
        assertEquals("RESEARCH_SELECTION_RESOURCE_EXHAUSTED",
                result.failureReason());
    }

    @Test
    void stableBusinessReasonSurvivesWrapperWithoutPersistingMessages() {
        var root = new IllegalStateException("MAINBOARD_DATA_INCOMPLETE");
        var diagnostic = ResearchSelectionSanitizedResult.diagnose(
                new RuntimeException("unsafe free text", root));

        assertEquals("java.lang.IllegalStateException",
                diagnostic.exceptionClass());
        assertEquals("EXECUTION", diagnostic.category());
        assertEquals("MAINBOARD_DATA_INCOMPLETE",
                diagnostic.sanitizedReason());
    }
}
