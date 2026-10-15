package com.stockquant.server.production;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Submits one secret-free, deterministic maintenance request. A timeout is
 * resolved from Broker state and never causes a second POST/request file.
 */
@Component
@ConditionalOnProperty(
        prefix = "stockquant.market-data-maintenance.scheduler",
        name = "enabled", havingValue = "true")
public final class PowerShellAutomaticMarketDataMaintenanceDispatchGateway
        implements AutomaticMarketDataMaintenanceDispatchGateway {
    private final AutomaticMarketDataMaintenanceProperties properties;
    private final Path repositoryRoot;
    private final Object dispatchLock = new Object();

    public PowerShellAutomaticMarketDataMaintenanceDispatchGateway(
            AutomaticMarketDataMaintenanceProperties properties
    ) {
        this(properties, repositoryRoot());
    }

    PowerShellAutomaticMarketDataMaintenanceDispatchGateway(
            AutomaticMarketDataMaintenanceProperties properties,
            Path repositoryRoot
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot,
                "repositoryRoot").toAbsolutePath().normalize();
        properties.validate();
    }

    @Override
    public DispatchResult dispatch(LocalDate tradeDate, Instant submittedAt) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(submittedAt, "submittedAt");
        String requestId = AutomaticMarketDataMaintenanceRequestIds.forDate(
                tradeDate, ProductionRuntimeState.require().gitCommit());
        synchronized (dispatchLock) {
            Optional<String> existing = existingReason(requestId);
            if (existing.isPresent()) {
                return new DispatchResult(requestId, false,
                        existing.orElseThrow());
            }
            return submit(requestId, tradeDate);
        }
    }

    private DispatchResult submit(String requestId, LocalDate tradeDate) {
        Path script = fixedFile("quant-server/scripts/host-broker/"
                + "invoke-stock-quant-host-broker.ps1");
        fixedFile("quant-server/target/"
                + "quant-server-1.3.1-research-production.jar");
        fixedFile("quant-server/target/"
                + "quant-server-1.3.1-research-production.jar"
                + ".f1f-b2-proof.properties");
        List<String> command = brokerCommand(powershell(), script,
                requestId, tradeDate,
                properties.getSubmitTimeoutSeconds());
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repositoryRoot.toFile())
                    .redirectErrorStream(true).start();
            boolean completed = process.waitFor(
                    properties.getSubmitTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                if (stateExists(requestId)) {
                    return new DispatchResult(requestId, true,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_STATE_CONFIRMED");
                }
                throw invalid(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMIT_TIMEOUT");
            }
            List<String> output = readBounded(process);
            boolean accepted = process.exitValue() == 0
                    && output.contains(
                    "STOCK_QUANT_HOST_BROKER_REQUEST_ID=" + requestId)
                    && output.contains(
                    "STOCK_QUANT_HOST_BROKER_STATUS=SUBMITTED");
            if (accepted) {
                return new DispatchResult(requestId, true,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
            }
            if (stateExists(requestId)) {
                return new DispatchResult(requestId, false,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_ALREADY_SUBMITTED");
            }
            throw invalid(rejectionReason(output).orElse(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_BROKER_REJECTED"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (stateExists(requestId)) {
                return new DispatchResult(requestId, true,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_STATE_CONFIRMED");
            }
            throw invalid(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMIT_INTERRUPTED");
        } catch (IOException error) {
            throw invalid(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMIT_FAILED");
        }
    }

    static List<String> brokerCommand(
            Path powershell,
            Path script,
            String requestId,
            LocalDate tradeDate,
            int timeoutSeconds
    ) {
        return List.of(powershell.toString(), "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                script.toString(), "-Operation",
                "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE", "-RequestId",
                requestId, "-TradeDate", tradeDate.toString(),
                "-MaximumProviderRequests", "0", "-SubmitOnly",
                "-TimeoutSeconds", Integer.toString(timeoutSeconds));
    }

    private Optional<String> existingReason(String requestId) {
        return existingReason(brokerBase(), requestId);
    }

    static Optional<String> existingReason(
            Path base,
            String requestId
    ) {
        if (Files.isRegularFile(base.resolve("results")
                .resolve(requestId + ".result.json"))) {
            return Optional.of(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_ALREADY_TERMINAL");
        }
        if (stateExists(base, requestId)) {
            return Optional.of(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_ALREADY_SUBMITTED");
        }
        return Optional.empty();
    }

    private boolean stateExists(String requestId) {
        return stateExists(brokerBase(), requestId);
    }

    private static boolean stateExists(Path base, String requestId) {
        Path requests = base.resolve("requests");
        Path results = base.resolve("results");
        for (String state : List.of("request", "processing", "processed",
                "rejected")) {
            if (Files.isRegularFile(requests.resolve(
                    requestId + '.' + state + ".properties"))) {
                return true;
            }
        }
        return Files.isRegularFile(results.resolve(
                requestId + ".result.json"));
    }

    private Path brokerBase() {
        return repositoryRoot.resolve(
                "quant-server/target/stock-quant-host-broker");
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
                    throw invalid(
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_OUTPUT_INVALID");
                }
                lines.add(line);
            }
        }
        return List.copyOf(lines);
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
                .distinct().toList();
        return reasons.size() == 1 ? Optional.of(reasons.get(0))
                : Optional.empty();
    }

    private Path fixedFile(String relative) {
        Path value = repositoryRoot.resolve(relative).toAbsolutePath()
                .normalize();
        if (!value.startsWith(repositoryRoot)
                || !Files.isRegularFile(value)
                || value.toString().contains(".ai")) {
            throw invalid(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_FIXED_PATH_INVALID");
        }
        return value;
    }

    private static Path powershell() {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            throw invalid(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_WINDOWS_REQUIRED");
        }
        Path root = Path.of(windir).toAbsolutePath().normalize();
        Path value = root.resolve(
                "System32/WindowsPowerShell/v1.0/powershell.exe")
                .normalize();
        if (!value.startsWith(root) || !Files.isRegularFile(value)) {
            throw invalid(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_POWERSHELL_INVALID");
        }
        return value;
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
        throw invalid(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_REPOSITORY_INVALID");
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
