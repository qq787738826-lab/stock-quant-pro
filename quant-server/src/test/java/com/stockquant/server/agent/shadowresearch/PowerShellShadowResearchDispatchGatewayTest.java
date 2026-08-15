package com.stockquant.server.agent.shadowresearch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerShellShadowResearchDispatchGatewayTest {

    @Test
    void scheduledCommandUsesTheFixedSelectionContract() {
        List<String> command = PowerShellShadowResearchDispatchGateway
                .brokerCommand(Path.of("powershell.exe"),
                        Path.of("invoke-stock-quant-host-broker.ps1"),
                        Path.of("research-selection-runner.jar"),
                        "SQHB_20260817T092000Z_A1B2C3D4E5F6", 11,
                        "SELECT_20260817T092000Z_A1B2C3D4E5F6", 2);

        assertPair(command, "-Operation", "RUN_RESEARCH_SELECTION");
        assertPair(command, "-SelectionTrigger", "SCHEDULED_SHADOW");
        assertPair(command, "-PrimaryWindow", "20");
        assertPair(command, "-AuxiliaryWindow", "60");
        assertPair(command, "-MaximumProviderRequests", "2");
        assertTrue(command.contains("-SubmitOnly"));
        assertFalse(command.stream().anyMatch(value -> value.contains(
                "schtasks") || value.contains("Token")
                || value.contains("Password") || value.equals("-Command")));
    }

    @Test
    void preservesOneStrictSanitizedBrokerRejectionReason() {
        var reason = PowerShellShadowResearchDispatchGateway.rejectionReason(
                List.of("STOCK_QUANT_HOST_BROKER_STATUS=REJECTED",
                        "STOCK_QUANT_HOST_BROKER_REASON="
                                + "M4_MONTHLY_BUDGET_LEDGER_INVALID"));

        assertEquals("M4_MONTHLY_BUDGET_LEDGER_INVALID",
                reason.orElseThrow());
        assertTrue(PowerShellShadowResearchDispatchGateway.rejectionReason(
                List.of("STOCK_QUANT_HOST_BROKER_STATUS=REJECTED",
                        "STOCK_QUANT_HOST_BROKER_REASON=unsafe detail"))
                .isEmpty());
        assertTrue(PowerShellShadowResearchDispatchGateway.rejectionReason(
                List.of("STOCK_QUANT_HOST_BROKER_STATUS=REJECTED",
                        "STOCK_QUANT_HOST_BROKER_REASON=SAFE_REASON",
                        "STOCK_QUANT_HOST_BROKER_REASON=OTHER_REASON"))
                .isEmpty());
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
