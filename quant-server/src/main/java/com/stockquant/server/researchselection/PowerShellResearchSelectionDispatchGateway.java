package com.stockquant.server.researchselection;

import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Writes one fixed, secret-free selection request through the resident Broker. */
@Component
@ConditionalOnProperty(prefix = "stockquant.production",
        name = "enabled", havingValue = "true")
public final class PowerShellResearchSelectionDispatchGateway
        implements ResearchSelectionDispatchGateway {
    private static final DateTimeFormatter REQUEST_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;

    public PowerShellResearchSelectionDispatchGateway(
            @org.springframework.beans.factory.annotation.Qualifier(
                    "agentTemporalClock") Clock clock
    ) {
        this.clock = clock;
    }

    @Override
    public String dispatch(
            ResearchSelectionModels.RunSummary run,
            SelectionRequest request,
            int maximumProviderRequests
    ) {
        String requestId = requestId();
        try {
            Path root = repositoryRoot();
            Path script = fixedFile(root,
                    "quant-server/scripts/host-broker/"
                            + "invoke-stock-quant-host-broker.ps1");
            Path artifact = fixedFile(root,
                    "quant-server/target/quant-server-1.3.1-"
                            + "research-selection-runner.jar");
            fixedFile(root, artifact + ".f1f-b2-proof.properties");
            List<String> command = List.of(powershell().toString(),
                    "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                    "Bypass", "-File", script.toString(), "-Operation",
                    "RUN_RESEARCH_SELECTION", "-ArtifactPath",
                    artifact.toString(), "-RequestId", requestId,
                    "-SelectionRunId", Long.toString(run.runId()),
                    "-SelectionPublicRunId", run.publicRunId(),
                    "-SelectionTrigger", request.triggerMode().name(),
                    "-PrimaryWindow", Integer.toString(
                            request.primaryWindow()), "-AuxiliaryWindow",
                    Integer.toString(request.auxiliaryWindow()),
                    "-MaximumProviderRequests",
                    Integer.toString(maximumProviderRequests), "-SubmitOnly",
                    "-TimeoutSeconds", "30");
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile()).redirectErrorStream(true)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw invalid("RESEARCH_SELECTION_DISPATCH_TIMEOUT");
            }
            List<String> output = readBounded(process);
            if (process.exitValue() != 0 || !output.contains(
                    "STOCK_QUANT_HOST_BROKER_REQUEST_ID=" + requestId)
                    || !output.contains(
                    "STOCK_QUANT_HOST_BROKER_STATUS=SUBMITTED")) {
                throw invalid(rejectionReason(output).orElse(
                        "RESEARCH_SELECTION_DISPATCH_REJECTED"));
            }
            return requestId;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw invalid("RESEARCH_SELECTION_DISPATCH_INTERRUPTED");
        } catch (IOException error) {
            throw invalid("RESEARCH_SELECTION_DISPATCH_FAILED");
        }
    }

    private static List<String> readBounded(Process process)
            throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= 32 || line.length() > 512) {
                    throw invalid("RESEARCH_SELECTION_OUTPUT_INVALID");
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
                .distinct()
                .toList();
        return reasons.size() == 1 ? Optional.of(reasons.get(0))
                : Optional.empty();
    }

    private static Path repositoryRoot() {
        Path value = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; value != null && i < 5;
             i++, value = value.getParent()) {
            if (Files.exists(value.resolve(".git"))
                    && Files.isDirectory(value.resolve("quant-server"))) {
                return value;
            }
        }
        throw invalid("RESEARCH_SELECTION_REPOSITORY_ROOT_INVALID");
    }

    private static Path powershell() {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            throw invalid("RESEARCH_SELECTION_WINDOWS_REQUIRED");
        }
        Path root = Path.of(windir).toAbsolutePath().normalize();
        Path value = root.resolve(
                "System32/WindowsPowerShell/v1.0/powershell.exe")
                .normalize();
        if (!value.startsWith(root) || !Files.isRegularFile(value)) {
            throw invalid("RESEARCH_SELECTION_POWERSHELL_INVALID");
        }
        return value;
    }

    private static Path fixedFile(Path root, String relative) {
        Path value = root.resolve(relative).toAbsolutePath().normalize();
        if (!value.startsWith(root.toAbsolutePath().normalize())
                || !Files.isRegularFile(value)
                || value.toString().contains(".ai")) {
            throw invalid("RESEARCH_SELECTION_FIXED_PATH_INVALID");
        }
        return value;
    }

    private String requestId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "SQHB_" + REQUEST_TIME.format(clock.instant()) + "_"
                + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
