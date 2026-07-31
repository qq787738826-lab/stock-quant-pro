package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareDedicatedResearchBatchContractTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 30);

    @Test
    void acceptsOneToThreeSymbolsAndComputesExactBudget() {
        assertEquals(3, command(List.of(
                security("600000", "SSE")))
                .expectedProviderRequests());
        assertEquals(6, command(List.of(
                security("600000", "SSE"),
                security("000001", "SZSE")))
                .expectedProviderRequests());
        assertEquals(9, command(List.of(
                security("600000", "SSE"),
                security("000001", "SZSE"),
                security("600001", "SSE")))
                .expectedProviderRequests());
    }

    @Test
    void rejectsDuplicateFourthAndMalformedSymbolsBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> command(
                List.of(
                        security("600000", "SSE"),
                        security("600000", "SSE"))));
        assertThrows(IllegalArgumentException.class, () -> command(
                List.of(
                        security("600000", "SSE"),
                        security("000001", "SZSE"),
                        security("600001", "SSE"),
                        security("000002", "SZSE"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> security("DEMO01", "SSE"));
        assertThrows(
                IllegalArgumentException.class,
                () -> security("600000", "NYSE"));
    }

    @Test
    void authorizationIsExactAndCannotEnableProductionCapabilities() {
        var authorization =
                TushareDedicatedResearchBatchAuthorization
                        .manualPersonalResearch();
        authorization.validateFrozen();
        assertEquals(3, authorization.maximumSymbols());
        assertEquals(1, authorization.maximumNaturalDays());
        assertEquals(9, authorization.maximumProviderRequests());
        assertFalse(authorization.automaticRetryAllowed());
        assertFalse(authorization.formalEligible());

        assertThrows(
                IllegalArgumentException.class,
                () -> new TushareDedicatedResearchBatchAuthorization(
                        authorization.providerCode(),
                        authorization.adapterVersion(),
                        authorization.accountScope(),
                        authorization.usageQualification(),
                        authorization.writtenPermissionCompleteness(),
                        authorization.runtimeMode(),
                        authorization.runNamespace(),
                        authorization.formalEligibility(),
                        authorization.maximumSymbols(),
                        authorization.maximumNaturalDays(),
                        10,
                        authorization.allowedFactTypes(),
                        authorization.automaticRetryPolicy(),
                        TushareDedicatedResearchBatchAuthorization
                                .RuntimePermission.ALLOWED,
                        authorization.scheduler(),
                        authorization.shadow(),
                        authorization.agentDecision(),
                        authorization.backtestExecution(),
                        authorization.investmentAdvice(),
                        authorization.trading()).validateFrozen());
    }

    private static TushareDedicatedResearchBatchCommand command(
            List<SecuritySelection> securities
    ) {
        return new TushareDedicatedResearchBatchCommand(
                DATE, securities, Duration.ofSeconds(5));
    }

    private static SecuritySelection security(
            String symbol,
            String exchange
    ) {
        return new SecuritySelection(symbol, exchange);
    }
}
