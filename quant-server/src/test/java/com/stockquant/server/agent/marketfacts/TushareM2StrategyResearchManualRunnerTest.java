package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM2StrategyResearchManualRunnerTest {
    @Test
    void fixedArgumentsParseWithoutAuthorizationOrProviderScope() {
        Path result = Path.of("quant-server", "target", "m2-smoke.json")
                .toAbsolutePath();
        var parsed = TushareM2StrategyResearchManualRunner.Arguments.parse(
                new String[]{
                        "--result-file=" + result,
                        "--execution-id=M2SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                        "--database-port=45432",
                        "--execution-mode=E2E_DRY_RUN"
                });

        assertEquals(result, parsed.resultFile());
        assertEquals(45_432, parsed.databasePort());
        assertEquals(TushareM2StrategyResearchManualRunner.ExecutionMode
                .E2E_DRY_RUN, parsed.executionMode());
        assertThrows(IllegalStateException.class, () ->
                TushareM2StrategyResearchManualRunner.Arguments.parse(
                        new String[]{
                                "--result-file=" + result,
                                "--execution-id=M2SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                                "--database-port=45432",
                                "--execution-mode=E2E_DRY_RUN",
                                "--token=forbidden"
                        }));
    }

    @Test
    void databaseOnlyAuditRequiresExactlyOneDatabaseSecretAndStaysClean()
            throws Exception {
        char[] secret = "M2_SYNTHETIC_DATABASE_PASSWORD".toCharArray();
        try {
            var captured = TushareControlledAcceptanceOutputAudit
                    .captureDatabaseOnlyProcess(registry -> {
                        registry.register(TushareControlledAcceptanceOutputAudit
                                .SensitiveKind.DATABASE_PASSWORD, secret);
                        return 7;
                    });
            assertEquals(7, captured.value());
            assertTrue(captured.auditResult().clean());
        } finally {
            Arrays.fill(secret, '\0');
        }

        assertThrows(TushareControlledAcceptanceOutputAudit
                .CapturedExecutionException.class, () ->
                TushareControlledAcceptanceOutputAudit
                        .captureDatabaseOnlyProcess(registry -> 1));
    }
}
