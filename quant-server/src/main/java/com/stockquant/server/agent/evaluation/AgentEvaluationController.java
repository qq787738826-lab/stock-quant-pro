package com.stockquant.server.agent.evaluation;

import com.stockquant.server.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M5 projection and fixed offline refresh; never mutates M4 or trading. */
@RestController
@RequestMapping("/api/agent-team/evaluation")
public final class AgentEvaluationController {
    private final AgentEvaluationService service;
    private final AgentEvaluationOrchestrator orchestrator;

    public AgentEvaluationController(
            AgentEvaluationService service,
            AgentEvaluationOrchestrator orchestrator
    ) {
        this.service = service;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public ApiResponse<?> overview() {
        return ApiResponse.ok(service.overview());
    }

    /** Fixed deterministic refresh; it cannot call Provider or trading. */
    @PostMapping("/refresh")
    public ApiResponse<?> refresh() {
        return ApiResponse.ok(orchestrator.refresh());
    }

}
