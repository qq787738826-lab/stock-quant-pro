package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentResearchReportFilesTest {
    @TempDir
    Path temporary;

    @Test
    void reportIsWrittenAtomicallyAndReadThroughSanitizedApiModel() {
        var report = report();
        AgentResearchReportFiles files = new AgentResearchReportFiles(
                temporary.resolve("reports"), new ObjectMapper()
                .findAndRegisterModules());

        Path path = files.write(report);

        assertEquals(report, files.read(report.task().taskId()));
        assertEquals(1, files.list().size());
        assertEquals(report.researchFingerprint(),
                files.list().get(0).researchFingerprint());
        assertEquals(report.task().taskId() + ".json",
                path.getFileName().toString());
        assertThrows(IllegalArgumentException.class, () ->
                files.write(report));
    }

    @Test
    void taskPathTraversalAndMissingReportFailClosed() {
        AgentResearchReportFiles files = new AgentResearchReportFiles(
                temporary.resolve("reports"), new ObjectMapper()
                .findAndRegisterModules());

        assertThrows(IllegalArgumentException.class, () ->
                files.read("../../outside"));
        assertThrows(IllegalArgumentException.class, () ->
                files.read("M3TASK_MISSING_REPORT"));
    }

    @Test
    void externalAiDirectoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new AgentResearchReportFiles(temporary.resolve(".ai"),
                        new ObjectMapper().findAndRegisterModules()));
    }

    private static AgentResearchModels.ResearchReport report() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T01:02:03Z"),
                ZoneOffset.UTC);
        AgentResearchDatasetSource source = ignored ->
                AgentResearchTestFixtures.loadedDataset();
        return new AgentResearchRuntime(new AgentResearchToolGateway(source,
                new DefaultStrategyResearchApi(), BacktestConfig.standard(),
                clock), new DeterministicFakeModelAdapter(),
                new AgentPromptCatalog(), clock)
                .run(AgentResearchTestFixtures.task());
    }
}
