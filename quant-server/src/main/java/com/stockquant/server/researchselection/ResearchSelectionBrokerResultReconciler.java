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
import java.util.Optional;

/** Recovers a queued UI run when the resident Broker rejects it before Java starts. */
@Component
@ConditionalOnProperty(prefix = "stockquant.production",
        name = "enabled", havingValue = "true")
public final class ResearchSelectionBrokerResultReconciler {
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
        for (var run : repository.queuedBrokerRuns(20)) {
            Path result = resultsDirectory.resolve(
                    run.brokerRequestId() + ".result.json").normalize();
            if (!result.startsWith(resultsDirectory)) continue;
            try {
                failure(mapper, result, run.brokerRequestId()).ifPresent(value -> {
                    try {
                        repository.fail(run.runId(),
                                ResearchSelectionModels.Status.QUEUED,
                                ResearchSelectionFailureCategory.from(
                                        value.reason()), value.reason(),
                                clock.instant());
                    } catch (IllegalStateException ignored) {
                        // The Runner may have claimed or terminalized it concurrently.
                    }
                });
            } catch (RuntimeException ignored) {
                // A partial or foreign result is never trusted; the next pass retries.
            }
        }
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
