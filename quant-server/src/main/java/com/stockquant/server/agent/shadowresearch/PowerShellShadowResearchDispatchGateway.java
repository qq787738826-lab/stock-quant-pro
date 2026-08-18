package com.stockquant.server.agent.shadowresearch;

import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.researchselection.ResearchSelectionModels;
import com.stockquant.server.researchselection.ResearchSelectionRepository;
import com.stockquant.server.researchselection.ResearchSelectionProviderBudgetPlanner;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import com.stockquant.server.production.SystemHealthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Submits one fixed, secret-free M4 request to the resident host broker.
 * It never invokes Task Scheduler, a provider, or a configurable command.
 */
@Component
@ConditionalOnProperty(prefix = "stockquant.shadow-research.scheduler",
        name = "enabled", havingValue = "true")
public final class PowerShellShadowResearchDispatchGateway
        implements ShadowResearchDispatchGateway {
    private static final DateTimeFormatter REQUEST_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShadowResearchRepository repository;
    private final ShadowResearchScheduleProperties properties;
    private final java.time.Clock clock;
    private final ResearchSelectionRepository selections;
    private final ResearchUniverseMainboardRepository universes;
    private final ResearchUniverseMainboardDatasetLoader universeLoader;
    private final SystemHealthService health;

    public PowerShellShadowResearchDispatchGateway(
            ShadowResearchRepository repository,
            ShadowResearchScheduleProperties properties,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            SystemHealthService health,
            @org.springframework.beans.factory.annotation.Qualifier(
                    "agentTemporalClock") java.time.Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.health = Objects.requireNonNull(health, "health");
        this.selections = new ResearchSelectionRepository(jdbc, mapper);
        this.universes = new ResearchUniverseMainboardRepository(jdbc);
        this.universeLoader = new ResearchUniverseMainboardDatasetLoader(
                new PitMarketFactRepository(jdbc, mapper));
    }

    @Override
    public DispatchResult dispatch(
            LocalDate tradeDate,
            Instant researchAsOf,
            ShadowResearchRepository.CalendarState calendarState
    ) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(researchAsOf, "researchAsOf");
        Objects.requireNonNull(calendarState, "calendarState");
        if (calendarState == ShadowResearchRepository.CalendarState.CLOSED) {
            throw invalid("M4_SCHEDULER_CLOSED_DATE_DISPATCH_FORBIDDEN");
        }
        String requestId = requestId(researchAsOf);
        if (!repository.claimScheduledDispatch(tradeDate, requestId,
                researchAsOf)) {
            return new DispatchResult(requestId, false);
        }
        ResearchSelectionModels.RunSummary selection = null;
        try {
            ResearchSelectionModels.SelectionRequest selectionRequest =
                    new ResearchSelectionModels.SelectionRequest(
                            ResearchSelectionModels.TriggerMode
                                    .SCHEDULED_SHADOW,
                            20, 60, 10, 5, true);
            selection = selections.create(selectionPublicId(researchAsOf),
                    selectionRequest, researchAsOf,
                    com.stockquant.server.production.ProductionRuntimeState
                            .require().gitCommit());
            Path root = repositoryRoot();
            Path script = fixedFile(root,
                    "quant-server/scripts/host-broker/"
                            + "invoke-stock-quant-host-broker.ps1");
            Path artifact = fixedFile(root,
                    "quant-server/target/"
                            + "quant-server-1.3.1-research-selection-runner.jar");
            fixedFile(root, artifact.toString()
                    + ".f1f-b2-proof.properties");
            Path powershell = powershell();
            List<String> command = brokerCommand(powershell, script,
                    artifact, requestId, selection.runId(),
                    selection.publicRunId(), maximumProviderRequests(
                            selectionRequest, researchAsOf));
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile()).redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(
                    properties.getSubmitTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw invalid("M4_SCHEDULER_SUBMIT_TIMEOUT");
            }
            List<String> output = readBounded(process);
            boolean accepted = process.exitValue() == 0
                    && output.contains("STOCK_QUANT_HOST_BROKER_REQUEST_ID="
                    + requestId)
                    && output.contains(
                    "STOCK_QUANT_HOST_BROKER_STATUS=SUBMITTED");
            if (!accepted) {
                throw invalid(rejectionReason(output).orElse(
                        "M4_SCHEDULER_BROKER_SUBMIT_REJECTED"));
            }
            selections.bindBrokerRequest(selection.runId(), requestId);
            repository.completeScheduledDispatch(requestId, true, null,
                    clock.instant());
            return new DispatchResult(requestId, true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminalizeSelection(selection,
                    "M4_SCHEDULER_SUBMIT_INTERRUPTED");
            repository.completeScheduledDispatch(requestId, false,
                    "M4_SCHEDULER_SUBMIT_INTERRUPTED", clock.instant());
            throw invalid("M4_SCHEDULER_SUBMIT_INTERRUPTED");
        } catch (RuntimeException | IOException error) {
            String code = safeCode(error);
            terminalizeSelection(selection, code);
            repository.completeScheduledDispatch(requestId, false, code,
                    clock.instant());
            throw invalid(code);
        }
    }

    private static List<String> readBounded(Process process)
            throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= 32 || line.length() > 512) {
                    throw invalid("M4_SCHEDULER_OUTPUT_INVALID");
                }
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    static List<String> brokerCommand(
            Path powershell,
            Path script,
            Path artifact,
            String requestId,
            long selectionRunId,
            String selectionPublicRunId,
            int maximumProviderRequests
    ) {
        return List.of(
                powershell.toString(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                "-Operation", "RUN_RESEARCH_SELECTION", "-ArtifactPath",
                artifact.toString(), "-RequestId", requestId,
                "-SelectionRunId", Long.toString(selectionRunId),
                "-SelectionPublicRunId", selectionPublicRunId,
                "-SelectionTrigger", "SCHEDULED_SHADOW",
                "-PrimaryWindow", "20", "-AuxiliaryWindow", "60",
                "-MaximumProviderRequests",
                Integer.toString(maximumProviderRequests), "-SubmitOnly",
                "-TimeoutSeconds", "30");
    }

    static Optional<String> rejectionReason(List<String> output) {
        if (output == null || !output.contains(
                "STOCK_QUANT_HOST_BROKER_STATUS=REJECTED")) {
            return Optional.empty();
        }
        List<String> reasons = output.stream()
                .filter(line -> line.startsWith(
                        "STOCK_QUANT_HOST_BROKER_REASON="))
                .map(line -> line.substring(
                        "STOCK_QUANT_HOST_BROKER_REASON=".length()))
                .filter(reason -> reason.matches(
                        "[A-Z][A-Z0-9_]{3,127}"))
                .distinct()
                .toList();
        return reasons.size() == 1 ? Optional.of(reasons.get(0))
                : Optional.empty();
    }

    private int maximumProviderRequests(
            ResearchSelectionModels.SelectionRequest request,
            Instant asOf
    ) {
        var plan = ResearchSelectionProviderBudgetPlanner.mainboardPlan(
                universeLoader, universes.latest().orElse(null), request,
                asOf, universes.existingMarketFactSecurityCount(),
                health.monthlyBudget(asOf).tushareRequests(),
                ResearchSelectionProviderBudgetPlanner
                        .CURRENT_MONTHLY_TUSHARE_LIMIT);
        if (plan.audit().calendarIncomplete()) {
            throw invalid("MAINBOARD_TRADE_CALENDAR_INCOMPLETE");
        }
        if (!plan.backfill().executableWithinBudget()) {
            throw invalid("RESEARCH_SELECTION_MONTHLY_BUDGET_EXHAUSTED");
        }
        return plan.backfill().totalRequests();
    }

    private void terminalizeSelection(
            ResearchSelectionModels.RunSummary selection,
            String reason
    ) {
        if (selection == null) return;
        try {
            selections.fail(selection.runId(),
                    ResearchSelectionModels.Status.QUEUED, "SCHEDULER",
                    reason, clock.instant());
        } catch (RuntimeException ignored) {
            // Keep the dispatch reason; a concurrent terminal transition or
            // stale recovery remains authoritative.
        }
    }

    private static Path repositoryRoot() {
        Path value = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; value != null && depth < 5;
                depth++, value = value.getParent()) {
            if (Files.exists(value.resolve(".git"))
                    && Files.isDirectory(value.resolve("quant-server"))) {
                return value;
            }
        }
        throw invalid("M4_SCHEDULER_REPOSITORY_ROOT_INVALID");
    }

    private static Path powershell() {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            throw invalid("M4_SCHEDULER_WINDOWS_REQUIRED");
        }
        Path root = Path.of(windir).toAbsolutePath().normalize();
        Path value = root.resolve(
                "System32/WindowsPowerShell/v1.0/powershell.exe")
                .normalize();
        if (!value.startsWith(root) || !Files.isRegularFile(value)) {
            throw invalid("M4_SCHEDULER_POWERSHELL_INVALID");
        }
        return value;
    }

    private static Path fixedFile(Path root, String relative) {
        return fixedFile(root, relative, root);
    }

    private static Path fixedFile(Path root, String relative, Path parent) {
        Path value = parent.resolve(relative).toAbsolutePath().normalize();
        if (!value.startsWith(root.toAbsolutePath().normalize())
                || !Files.isRegularFile(value)
                || value.toString().contains(".ai")) {
            throw invalid("M4_SCHEDULER_FIXED_PATH_INVALID");
        }
        return value;
    }

    private static String requestId(Instant at) {
        byte[] random = new byte[6];
        RANDOM.nextBytes(random);
        return "SQHB_" + REQUEST_TIME.format(at) + "_"
                + HexFormat.of().withUpperCase().formatHex(random);
    }

    private static String selectionPublicId(Instant at) {
        byte[] random = new byte[6];
        RANDOM.nextBytes(random);
        return "SELECT_" + REQUEST_TIME.format(at) + "_"
                + HexFormat.of().withUpperCase().formatHex(random);
    }

    private static String safeCode(Throwable error) {
        String message = error.getMessage();
        return message != null
                && message.matches("[A-Z][A-Z0-9_]{3,127}")
                ? message : "M4_SCHEDULER_DISPATCH_FAILED";
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
