package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Reconciles Broker failures and terminalizes abandoned queued runs. */
@Component
@ConditionalOnProperty(prefix = "stockquant.production",
        name = "enabled", havingValue = "true")
public final class ResearchSelectionBrokerResultReconciler {
    static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(2);
    private final ResearchSelectionRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path resultsDirectory;

    @Autowired
    public ResearchSelectionBrokerResultReconciler(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this(new ResearchSelectionRepository(jdbc, mapper), mapper, clock,
                repositoryRoot().resolve(
                        "quant-server/target/stock-quant-host-broker/results"));
    }

    ResearchSelectionBrokerResultReconciler(
            ResearchSelectionRepository repository,
            ObjectMapper mapper,
            Clock clock,
            Path resultsDirectory
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.resultsDirectory = resultsDirectory.toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString = "${stockquant.research-selection.reconcile-delay:PT2S}")
    public void reconcile() {
        Instant now = clock.instant();
        for (var run : repository.queuedRunsForReconciliation(20)) {
            try {
                reconciliationFailure(mapper, resultsDirectory, run, now)
                        .ifPresent(reason -> {
                    try {
                        repository.fail(run.runId(),
                                ResearchSelectionModels.Status.QUEUED,
                                ResearchSelectionFailureCategory.from(
                                        reason), reason, now);
                    } catch (IllegalStateException ignored) {
                        // The Runner may have claimed or terminalized it concurrently.
                    }
                });
            } catch (RuntimeException ignored) {
                // A partial or foreign result is never trusted; the next pass retries.
            }
        }
    }

    static Optional<String> reconciliationFailure(
            ObjectMapper mapper,
            Path resultsDirectory,
            ResearchSelectionRepository.QueuedRun run,
            Instant now
    ) {
        String requestId = run.brokerRequestId();
        if (requestId != null) {
            Path base = resultsDirectory.toAbsolutePath().normalize();
            Path result = base.resolve(requestId + ".result.json")
                    .normalize();
            if (result.startsWith(base)) {
                Optional<BrokerFailure> failure = failure(mapper, result,
                        requestId);
                if (failure.isPresent()) {
                    return Optional.of(failure.orElseThrow().reason());
                }
            }
        }
        if (run.updatedAt().plus(STALE_RUN_TIMEOUT).isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(requestId == null
                ? "RESEARCH_SELECTION_BROKER_BINDING_TIMEOUT"
                : "RESEARCH_SELECTION_BROKER_RESULT_TIMEOUT");
    }

    static Optional<BrokerFailure> failure(
            ObjectMapper mapper,
            Path file,
            String expectedRequestId
    ) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode value = mapper.readTree(file.toFile());
            String status = value.path("status").asText();
            String reason = value.path("reason").asText();
            boolean valid = expectedRequestId.equals(
                    value.path("requestId").asText())
                    && "RUN_RESEARCH_SELECTION".equals(
                    value.path("operation").asText())
                    && ("FAILED".equals(status) || "REJECTED".equals(status))
                    && value.path("noRetry").asBoolean(false)
                    && value.path("retryCount").asInt(-1) == 0
                    && value.path("providerCallCount").asInt(-1) == 0
                    && reason.matches("[A-Z][A-Z0-9_]{3,127}");
            return valid ? Optional.of(new BrokerFailure(reason))
                    : Optional.empty();
        } catch (IOException error) {
            return Optional.empty();
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
        throw new IllegalStateException(
                "RESEARCH_SELECTION_REPOSITORY_ROOT_INVALID");
    }

    record BrokerFailure(String reason) {
    }
}
