package com.stockquant.server.researchselection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionDispatchFailureTest {

    @Test
    void acceptsOneStrictSanitizedInvokerReason() {
        var reason = PowerShellResearchSelectionDispatchGateway
                .rejectionReason(List.of(
                        "STOCK_QUANT_HOST_BROKER_STATUS=REJECTED",
                        "STOCK_QUANT_HOST_BROKER_REASON="
                                + "STOCK_QUANT_HOST_BROKER_BUILD_PROOF_BINDING_INVALID"));

        assertEquals("STOCK_QUANT_HOST_BROKER_BUILD_PROOF_BINDING_INVALID",
                reason.orElseThrow());
    }

    @Test
    void rejectsMissingAmbiguousOrUnsafeInvokerReasons() {
        assertTrue(PowerShellResearchSelectionDispatchGateway
                .rejectionReason(List.of(
                        "STOCK_QUANT_HOST_BROKER_STATUS=FAILED",
                        "STOCK_QUANT_HOST_BROKER_REASON=SAFE_CODE"))
                .isEmpty());
        assertTrue(PowerShellResearchSelectionDispatchGateway
                .rejectionReason(List.of(
                        "STOCK_QUANT_HOST_BROKER_STATUS=REJECTED",
                        "STOCK_QUANT_HOST_BROKER_REASON=SAFE_CODE",
                        "STOCK_QUANT_HOST_BROKER_REASON=OTHER_CODE"))
                .isEmpty());
        assertTrue(PowerShellResearchSelectionDispatchGateway
                .rejectionReason(List.of(
                        "STOCK_QUANT_HOST_BROKER_STATUS=REJECTED",
                        "STOCK_QUANT_HOST_BROKER_REASON=unsafe text"))
                .isEmpty());
    }

    @Test
    void classifiesUserVisibleFailureDomainsWithoutExposingDetails() {
        assertEquals("BUILD", ResearchSelectionFailureCategory.from(
                "STOCK_QUANT_HOST_BROKER_BUILD_PROOF_BINDING_INVALID"));
        assertEquals("BROKER", ResearchSelectionFailureCategory.from(
                "HOST_BROKER_NOT_RUNNING"));
        assertEquals("PROVIDER", ResearchSelectionFailureCategory.from(
                "TUSHARE_API_ERROR_40101"));
        assertEquals("MODEL", ResearchSelectionFailureCategory.from(
                "BAILIAN_RESPONSE_INVALID"));
        assertEquals("DATA", ResearchSelectionFailureCategory.from(
                "RESEARCH_SELECTION_MINIMUM_WINDOW_INCOMPLETE"));
        assertEquals("BUDGET", ResearchSelectionFailureCategory.from(
                "RESEARCH_SELECTION_MONTHLY_BUDGET_EXHAUSTED"));
        assertEquals("DATABASE", ResearchSelectionFailureCategory.from(
                "RESEARCH_SELECTION_DATABASE_UNAVAILABLE"));
        assertEquals("UNKNOWN", ResearchSelectionFailureCategory.from(
                "unsafe text"));
    }
}
