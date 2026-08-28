package com.stockquant.server.researchselection;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionProviderBudgetTest {

    @Test
    void stockBasicAloneAndPairedMarketFactsAreLegal() {
        assertDoesNotThrow(() -> new ResearchSelectionProviderBudget(
                1, 0, 0, 0, 4, 5));
        assertDoesNotThrow(() -> new ResearchSelectionProviderBudget(
                0, 1, 1, 0, 4, 6));
        assertDoesNotThrow(() -> new ResearchSelectionProviderBudget(
                1, 60, 60, 0, 4, 125));
        assertDoesNotThrow(() -> new ResearchSelectionProviderBudget(
                0, 0, 0, 0, 0, 0));
    }

    @Test
    void incompletePairsAndInconsistentTotalsAreRejected() {
        assertInvalid(0, 1, 0, 0, 4, 5);
        assertInvalid(0, 0, 1, 0, 4, 5);
        assertInvalid(0, 2, 1, 0, 4, 7);
        assertInvalid(0, 1, 2, 0, 4, 7);
        assertInvalid(-1, 0, 0, 0, 4, 3);
        assertInvalid(1, -1, -1, 0, 4, 3);
        assertInvalid(1, 0, 0, 1, 4, 6);
        assertInvalid(1, 0, 0, 0, 0, 1);
        assertInvalid(0, 0, 0, 0, 4, 4);
        assertInvalid(1, 0, 0, 0, 4, 6);
        assertInvalid(0, 250, 250, 0, 4, 504);
    }

    @Test
    void manualDispatchBindsEveryEndpointWithoutTotalInference() {
        var budget = new ResearchSelectionProviderBudget(
                1, 0, 0, 0, 4, 5);
        var run = new ResearchSelectionModels.RunSummary(31,
                "SELECT_20260827T121035Z_39BBE59C2A4F",
                ResearchSelectionModels.Status.QUEUED,
                ResearchSelectionModels.TriggerMode.ON_DEMAND,
                Instant.parse("2026-08-27T12:10:35Z"), null,
                0, 0, 0, null, null, null,
                Instant.parse("2026-08-27T12:10:35Z"), null);

        List<String> command = PowerShellResearchSelectionDispatchGateway
                .brokerCommand(Path.of("powershell.exe"),
                        Path.of("invoke-stock-quant-host-broker.ps1"),
                        Path.of("research-selection-runner.jar"),
                        "SQHB_20260827T121035Z_A1B2C3D4E5F6", run,
                        ResearchSelectionModels.SelectionRequest.immediate(),
                        budget);

        assertPair(command, "-StockBasicRequests", "1");
        assertPair(command, "-DailyRequests", "0");
        assertPair(command, "-AdjustmentFactorRequests", "0");
        assertPair(command, "-TradeCalendarRequests", "0");
        assertPair(command, "-NetworkRecoveryRequests", "4");
        assertPair(command, "-MaximumProviderRequests", "5");
        assertTrue(command.contains("-SubmitOnly"));
    }

    private static void assertInvalid(
            int stockBasic,
            int daily,
            int factor,
            int calendar,
            int recovery,
            int total
    ) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () ->
                        new ResearchSelectionProviderBudget(stockBasic,
                                daily, factor, calendar, recovery, total));
        assertEquals("RESEARCH_SELECTION_FIXED_SCOPE_INVALID",
                failure.getMessage());
    }

    private static void assertPair(
            List<String> command,
            String key,
            String expected
    ) {
        int index = command.indexOf(key);
        assertTrue(index >= 0 && index + 1 < command.size());
        assertEquals(expected, command.get(index + 1));
    }
}
