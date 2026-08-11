package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only M3 research report API; it cannot launch agents or trading. */
@RestController
@RequestMapping("/api/agent-research")
public final class AgentResearchController {
    private final AgentResearchReportService reports;

    public AgentResearchController(AgentResearchReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports")
    public ApiResponse<List<AgentResearchReportFiles.ReportSummary>> reports() {
        return ApiResponse.ok(reports.reports());
    }

    @GetMapping("/reports/{taskId}")
    public ApiResponse<ResearchReport> report(@PathVariable String taskId) {
        return ApiResponse.ok(reports.report(taskId));
    }
}
