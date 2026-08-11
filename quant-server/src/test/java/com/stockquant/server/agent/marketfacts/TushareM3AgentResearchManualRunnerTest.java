package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.research.AgentResearchModels;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM3AgentResearchManualRunnerTest {
    @Test
    void parsesOnlyFixedFiveArgumentContract() {
        var parsed = TushareM3AgentResearchManualRunner.Arguments.parse(
                arguments("E2E_DRY_RUN", "15432"));

        assertEquals("M3SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                parsed.executionId());
        assertEquals(15432, parsed.databasePort());
        assertEquals(TushareM3AgentResearchManualRunner.ExecutionMode
                .E2E_DRY_RUN, parsed.executionMode());
        assertThrows(IllegalStateException.class, () ->
                TushareM3AgentResearchManualRunner.Arguments.parse(
                        new String[]{arguments("E2E_DRY_RUN", "15432")[0]}));
        assertThrows(IllegalStateException.class, () ->
                TushareM3AgentResearchManualRunner.Arguments.parse(new String[]{
                        "--result-file=.ai/result.json",
                        "--report-directory=target/reports",
                        "--execution-id=M3SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                        "--database-port=15432",
                        "--execution-mode=E2E_DRY_RUN"}));
    }

    @Test
    void fixedTaskUsesM1M2ScopeAndResearchOnlyBudgets() {
        var task = TushareM3AgentResearchManualRunner.task(
                "M3SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                Instant.parse("2026-08-11T01:02:03Z"));

        assertEquals("M3TASK_M3SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                task.taskId());
        assertEquals(2, task.securities().size());
        assertEquals(4, task.strategies().size());
        assertEquals(2, task.limits().maxRounds());
        assertEquals(8, task.limits().maxToolCalls());
        assertEquals(12, task.limits().maxModelCalls());
        assertTrue(task.objective().contains("M1"));
        assertTrue(task.objective().contains("M2"));
        assertEquals(AgentResearchModels.RuntimeLimits.standard(),
                task.limits());
    }

    private static String[] arguments(String mode, String port) {
        return new String[]{
                "--result-file=target/m3-result.json",
                "--report-directory=target/agent-research/reports",
                "--execution-id=M3SMOKE_20260811T010203Z_A1B2C3D4E5F6",
                "--database-port=" + port,
                "--execution-mode=" + mode};
    }
}
