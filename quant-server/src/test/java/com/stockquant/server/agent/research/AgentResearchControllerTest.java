package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResearchControllerTest {
    @TempDir
    Path temporary;

    @Test
    void APIIsReadOnlyAndReturnsEmptyHistoryWithoutCreatingState() {
        AgentResearchReportService service = new AgentResearchReportService(
                temporary.resolve("reports"), new ObjectMapper()
                .findAndRegisterModules());
        AgentResearchController controller = new AgentResearchController(
                service);

        assertTrue(controller.reports().success());
        assertTrue(controller.reports().data().isEmpty());
        assertTrue(java.util.Arrays.stream(
                        AgentResearchController.class.getDeclaredMethods())
                .noneMatch(value -> value.getName().matches(
                        "(?i).*(run|start|trade|order|shadow).*")));
    }
}
