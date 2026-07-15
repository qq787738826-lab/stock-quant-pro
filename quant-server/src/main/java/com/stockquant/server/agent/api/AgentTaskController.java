package com.stockquant.server.agent.api;

import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentModels.PageResult;
import com.stockquant.server.agent.service.AgentTaskService;
import com.stockquant.server.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-tasks")
public class AgentTaskController {

    private final AgentTaskService taskService;

    public AgentTaskController(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<CreatedTask> create(@Valid @RequestBody CreateAgentTaskRequest request) {
        return ApiResponse.ok(taskService.create(request, "API"));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AgentTask> task(@PathVariable long taskId) {
        return ApiResponse.ok(taskService.task(taskId));
    }

    @GetMapping("/{taskId}/runs")
    public ApiResponse<?> runs(@PathVariable long taskId) {
        return ApiResponse.ok(taskService.runs(taskId));
    }

    @GetMapping("/{taskId}/evidence")
    public ApiResponse<?> evidence(@PathVariable long taskId) {
        return ApiResponse.ok(taskService.evidence(taskId));
    }

    @GetMapping("/{taskId}/decision")
    public ApiResponse<?> decision(@PathVariable long taskId) {
        return ApiResponse.ok(taskService.decision(taskId));
    }

    @GetMapping("/{taskId}/vetoes")
    public ApiResponse<List<FormalVeto>> vetoes(@PathVariable long taskId) {
        return ApiResponse.ok(taskService.vetoes(taskId));
    }

    @GetMapping("/history")
    public ApiResponse<PageResult<AgentTask>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(taskService.history(page, size));
    }
}
