package com.stockquant.server.researchselection;

import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.researchselection.ResearchSelectionProviderBudgetPlanner.MainboardPlan;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.MemberPage;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchSelectionModels.RunSummary;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionResult;
import com.stockquant.server.researchselection.ResearchSelectionModels.Status;
import com.stockquant.server.production.SystemHealthService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "stockquant.production",
        name = "enabled", havingValue = "true")
public final class ResearchSelectionService {
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ResearchSelectionRepository repository;
    private final ResearchSelectionDispatchGateway dispatcher;
    private final ResearchUniverseMainboardRepository universes;
    private final ResearchUniverseMainboardDatasetLoader loader;
    private final SystemHealthService health;
    private final Clock clock;
    private final String gitCommit;

    public ResearchSelectionService(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            ResearchSelectionDispatchGateway dispatcher,
            SystemHealthService health,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.repository = new ResearchSelectionRepository(jdbc, mapper);
        this.dispatcher = dispatcher;
        this.health = health;
        this.universes = new ResearchUniverseMainboardRepository(jdbc);
        this.loader = new ResearchUniverseMainboardDatasetLoader(
                new PitMarketFactRepository(jdbc, mapper));
        this.clock = clock;
        this.gitCommit = com.stockquant.server.production
                .ProductionRuntimeState.require().gitCommit();
    }

    public StartResponse start(SelectionRequest request) {
        Instant asOf = clock.instant();
        RunSummary run = repository.create(publicRunId(asOf), request, asOf,
                gitCommit);
        try {
            int maximumProviderRequests = maximumProviderRequests(request);
            String brokerRequest = dispatcher.dispatch(run, request,
                    maximumProviderRequests);
            repository.bindBrokerRequest(run.runId(), brokerRequest);
            return new StartResponse(run, "PREPARING_DATA", true);
        } catch (RuntimeException error) {
            String reason = safeCode(error,
                    "RESEARCH_SELECTION_DISPATCH_FAILED");
            repository.fail(run.runId(), Status.QUEUED,
                    ResearchSelectionFailureCategory.from(reason), reason,
                    clock.instant());
            throw error;
        }
    }

    public Optional<SelectionResult> result(long id) {
        return repository.result(id);
    }

    public Optional<RunSummary> summary(long id) {
        return repository.summary(id);
    }

    public List<RunSummary> history(int limit) {
        return repository.history(limit);
    }

    public Optional<SelectionResult> latest() {
        return repository.latestResult();
    }

    public UniverseView universe() {
        Instant now = clock.instant();
        SnapshotBundle snapshot = universes.latest().orElse(null);
        MainboardPlan plan = plan(SelectionRequest.immediate(), now,
                snapshot);
        return new UniverseView(ResearchUniverseMainboard.VERSION,
                snapshot == null ? null : snapshot.snapshot(),
                plan.backfill());
    }

    public MemberPage members(
            long runId,
            int page,
            int size,
            String eligibility
    ) {
        return universes.memberPage(runId, page, size, eligibility);
    }

    private int maximumProviderRequests(SelectionRequest request) {
        MainboardPlan plan = plan(request, clock.instant(),
                universes.latest().orElse(null));
        if (plan.audit().calendarIncomplete()) {
            throw new IllegalStateException(
                    "MAINBOARD_TRADE_CALENDAR_INCOMPLETE");
        }
        if (!plan.backfill().executableWithinBudget()) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_MONTHLY_BUDGET_EXHAUSTED");
        }
        return plan.backfill().totalRequests();
    }

    private MainboardPlan plan(
            SelectionRequest request,
            Instant at,
            SnapshotBundle snapshot
    ) {
        return ResearchSelectionProviderBudgetPlanner.mainboardPlan(loader,
                snapshot, request, at,
                universes.existingMarketFactSecurityCount(),
                health.monthlyBudget(at).tushareRequests(),
                health.monthlyBudget(at).tushareLimit());
    }

    private static String safeCode(Throwable error, String fallback) {
        String message = error.getMessage();
        return message != null && message.matches(
                "[A-Z][A-Z0-9_]{3,127}") ? message : fallback;
    }

    private static String publicRunId(Instant at) {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "SELECT_" + ID_TIME.format(at) + "_"
                + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    public record StartResponse(
            RunSummary run,
            String userVisibleStage,
            boolean accepted
    ) {
    }

    public record UniverseView(
            String version,
            ResearchUniverseMainboard.Snapshot snapshot,
            ResearchUniverseMainboard.BackfillPlan backfillPlan
    ) {
    }
}
