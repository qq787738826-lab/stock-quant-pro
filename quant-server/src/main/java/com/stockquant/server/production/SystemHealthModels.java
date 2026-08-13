package com.stockquant.server.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SystemHealthModels {
    private SystemHealthModels() {
    }

    public enum HealthStatus { HEALTHY, DEGRADED, BLOCKED }

    public record ComponentHealth(
            String component,
            HealthStatus status,
            String reason,
            Map<String, Object> details
    ) {
        public ComponentHealth {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record BudgetHealth(
            String calendarMonth,
            int tushareRequests,
            int tushareLimit,
            BigDecimal shadowCostCny,
            BigDecimal shadowLimitCny,
            BigDecimal projectCostCny,
            BigDecimal projectLimitCny
    ) {
    }

    public record SchedulerHealth(
            boolean enabled,
            String zone,
            String cron,
            Instant nextPlannedAt,
            String state,
            String lastReason
    ) {
    }

    public record SystemHealth(
            String contract,
            HealthStatus status,
            String reason,
            String gitCommit,
            Instant checkedAt,
            List<ComponentHealth> components,
            BudgetHealth budget,
            SchedulerHealth scheduler,
            Map<String, Object> latestShadow,
            Map<String, Object> latestEvaluation,
            int pendingRequests,
            int claimedRequests,
            boolean realTradingEnabled
    ) {
        public SystemHealth {
            components = List.copyOf(components);
            latestShadow = latestShadow == null
                    ? Map.of() : Map.copyOf(latestShadow);
            latestEvaluation = latestEvaluation == null
                    ? Map.of() : Map.copyOf(latestEvaluation);
        }
    }

    public record BackupManifest(
            String contract,
            String backupId,
            Instant createdAt,
            String gitCommit,
            int schemaVersion,
            Map<String, Long> tableRows,
            String archiveSha256,
            String archivePath,
            boolean secretsIncluded,
            boolean immutableShadowChanged
    ) {
    }
}
