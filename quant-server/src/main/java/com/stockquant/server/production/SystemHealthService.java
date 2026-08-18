package com.stockquant.server.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.evaluation.AgentEvaluationService;
import com.stockquant.server.agent.evaluation.ExternalApiMonthlyBudget;
import com.stockquant.server.agent.shadowresearch.ShadowResearchQueryService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchScheduleProperties;
import com.stockquant.server.production.SystemHealthModels.BudgetHealth;
import com.stockquant.server.production.SystemHealthModels.ComponentHealth;
import com.stockquant.server.production.SystemHealthModels.HealthStatus;
import com.stockquant.server.production.SystemHealthModels.SchedulerHealth;
import com.stockquant.server.production.SystemHealthModels.SystemHealth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only aggregate for personal production operations. */
@Service
@ConditionalOnProperty(prefix = "stockquant.production", name = "enabled",
        havingValue = "true")
public final class SystemHealthService {
    private static final Pattern NON_SHADOW_BASELINE = Pattern.compile(
            "ProjectNonShadowCostCny\\s*=\\s*'([0-9]+(?:\\.[0-9]+)?)'");

    private final JdbcTemplate jdbc;
    private final ShadowResearchQueryService shadows;
    private final AgentEvaluationService evaluations;
    private final ShadowResearchScheduleProperties schedule;
    private final ShadowSchedulerRuntimeState schedulerState;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path repositoryRoot;

    @Autowired
    public SystemHealthService(
            JdbcTemplate jdbc,
            ShadowResearchQueryService shadows,
            AgentEvaluationService evaluations,
            ShadowResearchScheduleProperties schedule,
            ShadowSchedulerRuntimeState schedulerState,
            ObjectMapper mapper
    ) {
        this(jdbc, shadows, evaluations, schedule, schedulerState, mapper,
                Clock.systemUTC(), repositoryRoot());
    }

    SystemHealthService(
            JdbcTemplate jdbc,
            ShadowResearchQueryService shadows,
            AgentEvaluationService evaluations,
            ShadowResearchScheduleProperties schedule,
            ShadowSchedulerRuntimeState schedulerState,
            ObjectMapper mapper,
            Clock clock,
            Path repositoryRoot
    ) {
        this.jdbc = jdbc;
        this.shadows = shadows;
        this.evaluations = evaluations;
        this.schedule = schedule;
        this.schedulerState = schedulerState;
        this.mapper = mapper;
        this.clock = clock;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    public SystemHealth health() {
        Instant now = clock.instant();
        ProductionRuntimeState.Snapshot runtime = ProductionRuntimeState
                .require();
        List<ComponentHealth> components = new ArrayList<>();
        database(components);
        BrokerSnapshot broker = broker(components, now);
        dataset(components);
        components.add(healthy("Backend", "API_RESPONDING",
                Map.of("startedAt", runtime.startedAt())));
        boolean frontendAvailable = frontendAvailable();
        components.add(new ComponentHealth("Frontend/API",
                frontendAvailable ? HealthStatus.HEALTHY
                        : HealthStatus.DEGRADED,
                frontendAvailable ? "PRODUCTION_UI_AVAILABLE"
                        : "API_ONLY_UI_DEGRADED",
                Map.of("staticIndex", frontendAvailable)));
        components.add(healthy("M2 Backtest", "STRATEGY_RESEARCH_API_V1",
                Map.of("realTrading", false)));
        components.add(healthy("M3 Agent Runtime", "AGENT_RESEARCH_TEAM_V1",
                Map.of("roles", 7)));
        ShadowResearchQueryService.Overview shadow = shadows.overview(1);
        AgentEvaluationService.Overview evaluation = evaluations.overview();
        components.add(healthy("M4 Shadow", "SHADOW_RESEARCH_RUNTIME_V1",
                Map.of("frozenRuns", shadow.runs().stream()
                        .filter(value -> "FROZEN".equals(value.status().name()))
                        .count(), "realTrading", false)));
        components.add(new ComponentHealth("M5 Evaluation",
                evaluation.latestReport() == null
                        ? HealthStatus.DEGRADED : HealthStatus.HEALTHY,
                evaluation.latestReport() == null
                        ? "INSUFFICIENT_EVALUATION_SAMPLE"
                        : "AGENT_EVALUATION_SYSTEM_V1",
                Map.of("frozenSamples", evaluation.frozenShadowSamples())));
        components.add(credentialPresence("Tushare Credential",
                "StockQuant/TushareToken"));
        components.add(credentialPresence("Bailian Credential",
                "StockQuant/BailianApiKey"));

        BudgetHealth budget = budget(now);
        SchedulerHealth scheduler = scheduler(now);
        HealthStatus overall = components.stream().map(ComponentHealth::status)
                .max(Comparator.comparingInt(SystemHealthService::severity))
                .orElse(HealthStatus.BLOCKED);
        String reason = overall == HealthStatus.HEALTHY
                ? "ALL_REQUIRED_COMPONENTS_HEALTHY"
                : overall == HealthStatus.DEGRADED
                ? "NON_BLOCKING_COMPONENT_DEGRADED"
                : "REQUIRED_COMPONENT_BLOCKED";
        return new SystemHealth("SYSTEM_HEALTH_V1", overall, reason,
                runtime.gitCommit(), now, components, budget, scheduler,
                latestShadow(shadow), latestEvaluation(evaluation),
                broker.pending(), broker.claimed(), false);
    }

    /**
     * The host-result-aware projection used by SYSTEM_HEALTH_V1. Selection
     * planning consumes this view so an empty database telemetry table cannot
     * hide calls already accounted by the resident Broker.
     */
    public BudgetHealth monthlyBudget(Instant at) {
        return budget(at);
    }

    private void database(List<ComponentHealth> components) {
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        int version = StockQuantResearchProductionRunner.schemaVersion(jdbc);
        if (!Integer.valueOf(1).equals(one) || version != 18) {
            components.add(new ComponentHealth("Database",
                    HealthStatus.BLOCKED, "DATABASE_V18_REQUIRED",
                    Map.of("schemaVersion", version)));
            return;
        }
        components.add(healthy("Database", "POSTGRESQL_V18_READY",
                Map.of("port", 38_432, "schemaVersion", version,
                        "database", "stock_quant_research",
                        "schema", "tushare_research")));
    }

    private void dataset(List<ComponentHealth> components) {
        long batches = count("pit_market_fact_batches");
        long observations = count("pit_market_fact_observations");
        components.add(new ComponentHealth("M1 Dataset",
                batches > 0 && observations > 0
                        ? HealthStatus.HEALTHY : HealthStatus.DEGRADED,
                batches > 0 && observations > 0
                        ? "M1_RESEARCH_DATASET_V1" : "DATASET_EMPTY",
                Map.of("batches", batches, "observations", observations)));
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table,
                Long.class);
        return value == null ? 0 : value;
    }

    private BrokerSnapshot broker(
            List<ComponentHealth> components,
            Instant now
    ) {
        Path base = repositoryRoot.resolve(
                "quant-server/target/stock-quant-host-broker");
        Path heartbeat = base.resolve("heartbeat.json");
        int pending = fileCount(base.resolve("requests"),
                "*.request.properties");
        int claimed = fileCount(base.resolve("requests"),
                "*.processing.properties");
        try {
            JsonNode value = mapper.readTree(heartbeat.toFile());
            Instant last = Instant.parse(value.path("lastHeartbeat").asText());
            boolean fresh = Duration.between(last, now).abs()
                    .compareTo(Duration.ofSeconds(10)) <= 0;
            String state = value.path("state").asText();
            boolean valid = fresh && ("IDLE".equals(state)
                    || "BUSY".equals(state));
            components.add(new ComponentHealth("Broker",
                    valid ? HealthStatus.HEALTHY : HealthStatus.BLOCKED,
                    valid ? "RESIDENT_BROKER_" + state
                            : "HOST_BROKER_HEARTBEAT_STALE",
                    Map.of("state", state, "lastHeartbeat", last,
                            "pending", pending, "claimed", claimed)));
        } catch (Exception error) {
            components.add(new ComponentHealth("Broker",
                    HealthStatus.BLOCKED, "HOST_BROKER_NOT_RUNNING",
                    Map.of("pending", pending, "claimed", claimed)));
        }
        return new BrokerSnapshot(pending, claimed);
    }

    private ComponentHealth credentialPresence(String component, String target) {
        try {
            boolean present = WindowsCredentialPresence.present(target);
            return new ComponentHealth(component,
                    present ? HealthStatus.HEALTHY : HealthStatus.BLOCKED,
                    present ? "CREDENTIAL_PRESENT" : "CREDENTIAL_MISSING",
                    Map.of("present", present));
        } catch (RuntimeException error) {
            return new ComponentHealth(component, HealthStatus.BLOCKED,
                    "CREDENTIAL_STATUS_UNAVAILABLE", Map.of());
        }
    }

    private BudgetHealth budget(Instant now) {
        YearMonth month = YearMonth.from(now.atZone(ZoneId.of("Asia/Shanghai")));
        BigDecimal shadowCost = ledgerCost(month, "M4_SHADOW");
        int tushare = ledgerRequests(month, "M4_SHADOW", "TUSHARE");
        Path results = repositoryRoot.resolve(
                "quant-server/target/stock-quant-host-broker/results");
        if (Files.isDirectory(results)) {
            try (var files = Files.list(results)) {
                for (Path file : files.filter(path -> path.getFileName()
                        .toString().endsWith(".result.json")).toList()) {
                    try {
                        JsonNode node = mapper.readTree(file.toFile());
                        if (!List.of("RUN_M4_SHADOW_RESEARCH",
                                "RUN_RESEARCH_SELECTION").contains(
                                node.path("operation").asText())) {
                            continue;
                        }
                        Instant completed = Instant.parse(
                                node.path("completedAt").asText());
                        if (!YearMonth.from(completed.atZone(
                                ZoneId.of("Asia/Shanghai"))).equals(month)) {
                            continue;
                        }
                        String requestId = node.path("requestId").asText();
                        if (ledgerContains(requestId)) {
                            continue;
                        }
                        tushare += node.path("providerCallCount").asInt(0);
                        String cost = node.path("summary")
                                .path("accountedCostCny").asText("0");
                        shadowCost = shadowCost.add(new BigDecimal(cost));
                    } catch (RuntimeException | IOException ignored) {
                        // Invalid historical result is excluded, never guessed.
                    }
                }
            } catch (IOException ignored) {
                // A zero projection remains fail-safe and visible in health.
            }
        }
        BigDecimal nonShadow = nonShadowBaseline();
        return new BudgetHealth(month.toString(), tushare,
                ExternalApiMonthlyBudget.SHADOW_MONTHLY_TUSHARE_REQUESTS,
                shadowCost, ExternalApiMonthlyBudget.SHADOW_MONTHLY_COST_CNY,
                nonShadow.add(shadowCost),
                ExternalApiMonthlyBudget.PROJECT_MONTHLY_COST_CNY);
    }

    private BigDecimal ledgerCost(YearMonth month, String scopePrefix) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(sum(accounted_cost_cny), 0)
                  FROM external_api_monthly_usage_ledger
                 WHERE calendar_month=? AND budget_scope LIKE ?
                """, BigDecimal.class, month.toString(), scopePrefix + "%");
        return value == null ? BigDecimal.ZERO : value;
    }

    private int ledgerRequests(
            YearMonth month,
            String scopePrefix,
            String provider
    ) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(sum(request_count), 0)
                  FROM external_api_monthly_usage_ledger
                 WHERE calendar_month=? AND budget_scope LIKE ?
                   AND provider=?
                """, Integer.class, month.toString(), scopePrefix + "%",
                provider);
        return value == null ? 0 : value;
    }

    private boolean ledgerContains(String requestId) {
        if (requestId == null || !requestId.matches(
                "SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")) {
            return false;
        }
        Integer value = jdbc.queryForObject("""
                SELECT count(*) FROM external_api_monthly_usage_ledger
                 WHERE usage_key=?
                """, Integer.class, requestId);
        return value != null && value > 0;
    }

    private BigDecimal nonShadowBaseline() {
        Path file = repositoryRoot.resolve("quant-server/scripts/host-broker/"
                + "StockQuantExternalApiBudgetBaseline.psd1");
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher match = NON_SHADOW_BASELINE.matcher(content);
            return match.find() ? new BigDecimal(match.group(1))
                    : BigDecimal.ZERO;
        } catch (IOException error) {
            return BigDecimal.ZERO;
        }
    }

    private SchedulerHealth scheduler(Instant now) {
        ShadowSchedulerRuntimeState.Snapshot state = schedulerState.snapshot();
        return new SchedulerHealth(schedule.isEnabled(), schedule.getZone(),
                schedule.getCron(), nextSlot(now),
                schedule.isEnabled() ? "ACTIVE" : "DISABLED",
                state.lastReason());
    }

    private Instant nextSlot(Instant now) {
        ZoneId zone = ZoneId.of(schedule.getZone());
        ZonedDateTime value = now.atZone(zone);
        LocalDate date = value.toLocalDate();
        LocalTime time = LocalTime.of(17, 20);
        if (!value.toLocalTime().isBefore(time)) {
            date = date.plusDays(1);
        }
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date.atTime(time).atZone(zone).toInstant();
    }

    private Map<String, Object> latestShadow(
            ShadowResearchQueryService.Overview overview
    ) {
        if (overview.runs().isEmpty()) {
            return Map.of("status", "NO_SHADOW_RUN");
        }
        var run = overview.runs().get(0);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runId", run.id());
        value.put("tradeDate", run.tradeDate());
        value.put("status", run.status());
        value.put("completedAt", run.completedAt() == null
                ? "PENDING" : run.completedAt().toString());
        value.put("errorCode", run.errorCode() == null
                ? "NONE" : run.errorCode());
        value.put("realTrading", false);
        return value;
    }

    private Map<String, Object> latestEvaluation(
            AgentEvaluationService.Overview overview
    ) {
        if (overview.latestReport() == null) {
            return Map.of("status", "INSUFFICIENT_SAMPLE");
        }
        return Map.of("reportFingerprint", overview.latestReport().fingerprint(),
                "champion", overview.latestReport()
                        .currentChampionVersionKey(),
                "shadowSamples", overview.frozenShadowSamples(),
                "status", overview.realShadowStatus());
    }

    private boolean frontendAvailable() {
        return getClass().getResource("/static/index.html") != null;
    }

    private static ComponentHealth healthy(
            String component,
            String reason,
            Map<String, Object> details
    ) {
        return new ComponentHealth(component, HealthStatus.HEALTHY, reason,
                details);
    }

    private static int severity(HealthStatus value) {
        return switch (value) {
            case HEALTHY -> 0;
            case DEGRADED -> 1;
            case BLOCKED -> 2;
        };
    }

    private static int fileCount(Path directory, String glob) {
        if (!Files.isDirectory(directory)) return 0;
        try (var stream = Files.newDirectoryStream(directory, glob)) {
            int count = 0;
            for (Path ignored : stream) count++;
            return count;
        } catch (IOException error) {
            return 0;
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 6;
             current = current.getParent(), depth++) {
            if (Files.isDirectory(current.resolve(".git"))
                    && Files.isDirectory(current.resolve("quant-server"))) {
                return current;
            }
        }
        throw new IllegalStateException("M6_REPOSITORY_ROOT_INVALID");
    }

    private record BrokerSnapshot(int pending, int claimed) {
    }
}
