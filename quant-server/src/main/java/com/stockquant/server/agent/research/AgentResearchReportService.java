package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Read-only application service for viewing sanitized M3 reports. */
@Service
public final class AgentResearchReportService {
    private final AgentResearchReportFiles files;

    public AgentResearchReportService(ObjectMapper mapper) {
        this(defaultRoot(), mapper);
    }

    AgentResearchReportService(Path root, ObjectMapper mapper) {
        this.files = new AgentResearchReportFiles(root, mapper);
    }

    public List<AgentResearchReportFiles.ReportSummary> reports() {
        return files.list();
    }

    public ResearchReport report(String taskId) {
        return files.read(taskId);
    }

    private static Path defaultRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path module = current.resolve("quant-server");
        return Files.isDirectory(module)
                ? module.resolve("target/agent-research/reports")
                : current.resolve("target/agent-research/reports");
    }
}
