package com.stockquant.server.agent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Atomic, sanitized report file store shared by the runner and read-only API. */
public final class AgentResearchReportFiles {
    private static final long MAX_REPORT_BYTES = 4L * 1024L * 1024L;
    private static final String TASK_PATTERN = "M3TASK_[A-Z0-9_]{6,80}";

    private final Path root;
    private final ObjectMapper mapper;

    public AgentResearchReportFiles(Path root, ObjectMapper mapper) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath()
                .normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (containsAi(this.root)) {
            throw AgentResearchModels.invalid("M3_REPORT_ROOT_INVALID");
        }
    }

    public Path write(ResearchReport report) {
        Objects.requireNonNull(report, "report");
        String taskId = validateTaskId(report.task().taskId());
        try {
            Files.createDirectories(root);
            Path target = resolve(taskId);
            if (Files.exists(target)) {
                throw AgentResearchModels.invalid(
                        "M3_REPORT_ALREADY_EXISTS");
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(report);
            if (bytes.length > MAX_REPORT_BYTES) {
                throw AgentResearchModels.invalid("M3_REPORT_TOO_LARGE");
            }
            Path temporary = Files.createTempFile(root, taskId + "-",
                    ".pending");
            try {
                Files.write(temporary, bytes);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("M3_REPORT_WRITE_FAILED",
                    exception);
        }
    }

    public ResearchReport read(String taskId) {
        Path path = resolve(validateTaskId(taskId));
        try {
            if (!Files.isRegularFile(path)
                    || Files.size(path) > MAX_REPORT_BYTES) {
                throw new IllegalArgumentException("M3_REPORT_NOT_FOUND");
            }
            return mapper.readValue(Files.readAllBytes(path),
                    ResearchReport.class);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("M3_REPORT_READ_FAILED",
                    exception);
        }
    }

    public List<ReportSummary> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(value -> value.getFileName().toString()
                            .matches(TASK_PATTERN + "\\.json"))
                    .map(value -> read(value.getFileName().toString()
                            .replaceFirst("\\.json$", "")))
                    .map(ReportSummary::from)
                    .sorted(Comparator.comparing(ReportSummary::completedAt)
                            .reversed().thenComparing(ReportSummary::taskId))
                    .limit(100).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("M3_REPORT_LIST_FAILED",
                    exception);
        }
    }

    public Path root() {
        return root;
    }

    private Path resolve(String taskId) {
        Path result = root.resolve(taskId + ".json").normalize();
        if (!result.getParent().equals(root)) {
            throw AgentResearchModels.invalid("M3_REPORT_PATH_INVALID");
        }
        return result;
    }

    private static String validateTaskId(String taskId) {
        if (taskId == null || !taskId.matches(TASK_PATTERN)) {
            throw AgentResearchModels.invalid("M3_REPORT_TASK_ID_INVALID");
        }
        return taskId;
    }

    private static boolean containsAi(Path path) {
        for (Path part : path) {
            if (".ai".equalsIgnoreCase(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void moveAtomically(Path source, Path target)
            throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    public record ReportSummary(
            String taskId,
            AgentResearchModels.ResearchStatus status,
            java.time.Instant completedAt,
            int securityCount,
            int strategyCount,
            String preferredStrategy,
            AgentResearchModels.RiskLevel riskLevel,
            java.math.BigDecimal confidence,
            int agentRuns,
            int toolCalls,
            String researchFingerprint
    ) {
        private static ReportSummary from(ResearchReport report) {
            return new ReportSummary(report.task().taskId(), report.status(),
                    report.completedAt(), report.task().securities().size(),
                    report.strategyExperiments().experiments().size(),
                    report.finalDecision().preferredStrategy(),
                    report.finalDecision().riskLevel(),
                    report.finalDecision().confidence(),
                    report.agentRuns().size(), report.toolCalls().size(),
                    report.researchFingerprint());
        }
    }
}
