package com.stockquant.server.production;

import com.stockquant.server.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed local operations surface; no provider or trading operation exists. */
@RestController
@RequestMapping("/api/system")
@ConditionalOnProperty(prefix = "stockquant.production", name = "enabled",
        havingValue = "true")
public final class SystemHealthController {
    private final SystemHealthService health;
    private final LocalResearchBackupService backups;

    public SystemHealthController(
            SystemHealthService health,
            LocalResearchBackupService backups
    ) {
        this.health = health;
        this.backups = backups;
    }

    @GetMapping("/health")
    public ApiResponse<?> health() {
        return ApiResponse.ok(health.health());
    }

    @PostMapping("/backups")
    public ApiResponse<?> backup() {
        return ApiResponse.ok(backups.create());
    }
}
