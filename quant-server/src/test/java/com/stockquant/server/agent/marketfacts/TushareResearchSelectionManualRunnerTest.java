package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.researchselection.ResearchSelectionModels;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareResearchSelectionManualRunnerTest {

    @Test
    void bindsScheduledTriggerAsARequiredRunnerArgument() {
        var arguments = TushareResearchSelectionManualRunner.Arguments.parse(
                validArguments("SCHEDULED_SHADOW"));

        assertEquals(ResearchSelectionModels.TriggerMode.SCHEDULED_SHADOW,
                arguments.triggerMode());
        assertEquals(52, arguments.maximumProviderRequests());
    }

    @Test
    void rejectsUnknownOrMissingTrigger() {
        assertThrows(IllegalStateException.class, () ->
                TushareResearchSelectionManualRunner.Arguments.parse(
                        validArguments("MANUAL")));
        String[] missing = java.util.Arrays.stream(
                        validArguments("ON_DEMAND"))
                .filter(value -> !value.startsWith("--selection-trigger="))
                .toArray(String[]::new);
        assertThrows(IllegalStateException.class, () ->
                TushareResearchSelectionManualRunner.Arguments.parse(missing));
    }

    private static String[] validArguments(String trigger) {
        return new String[]{
                "--result-file=" + Path.of("target", "result.json"),
                "--execution-id=SELECTEXEC_20260817T092000Z_A1B2C3D4E5F6",
                "--selection-run-id=11",
                "--public-run-id=SELECT_20260817T092000Z_A1B2C3D4E5F6",
                "--git-commit=" + "a".repeat(40),
                "--selection-trigger=" + trigger,
                "--database-port=54321",
                "--maximum-provider-requests=52",
                "--execution-mode=FAKE",
                "--maximum-cost-cny=5.00"
        };
    }
}
