package com.stockquant.server.agent.shadowresearch;

import com.stockquant.server.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M4 read-only history/report/paper projection. */
@RestController
@RequestMapping("/api/agent-team/shadow-research")
public final class ShadowResearchController {
    private final ShadowResearchQueryService queries;

    public ShadowResearchController(ShadowResearchQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ApiResponse<?> overview(
            @RequestParam(defaultValue = "30") int limit
    ) {
        return ApiResponse.ok(queries.overview(limit));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<?> run(@PathVariable long runId) {
        return ApiResponse.ok(queries.run(runId));
    }
}
