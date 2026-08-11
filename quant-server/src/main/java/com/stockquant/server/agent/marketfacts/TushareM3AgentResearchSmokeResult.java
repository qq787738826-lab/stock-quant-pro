package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/** Sanitized file-only evidence for the packaged M3 M1/M2 smoke. */
final class TushareM3AgentResearchSmokeResult {
    static final String VERSION = "M3_AGENT_RESEARCH_SMOKE_RESULT_V1";

    private TushareM3AgentResearchSmokeResult() {
    }

    record Audit(boolean captureComplete, boolean clean, int hitCount) {
        Audit {
            if (hitCount < 0 || clean != (captureComplete && hitCount == 0)) {
                throw new IllegalArgumentException(
                        "M3_OUTPUT_AUDIT_RESULT_INVALID");
            }
        }

        static Audit notRun() {
            return new Audit(false, false, 0);
        }
    }

    record DatabaseSnapshot(
            long batchCount,
            long observationCount,
            long rawDailyCount,
            long adjustmentFactorCount,
            long calendarCount
    ) {
        DatabaseSnapshot {
            if (batchCount < 0 || observationCount < 0 || rawDailyCount < 0
                    || adjustmentFactorCount < 0 || calendarCount < 0) {
                throw new IllegalArgumentException(
                        "M3_DATABASE_SNAPSHOT_INVALID");
            }
        }
    }

    record Result(
            String schemaVersion,
            String status,
            String executionId,
            String gitCommit,
            String artifactSha256,
            String runnerStartClass,
            Instant startedAt,
            Instant completedAt,
            ResearchReport research,
            String reportFile,
            boolean databaseReadOnly,
            boolean databaseSnapshotUnchanged,
            DatabaseSnapshot databaseBefore,
            DatabaseSnapshot databaseAfter,
            Audit outputAudit,
            int providerCallCount,
            int databaseWriteCount,
            String reason
    ) {
        Result {
            Objects.requireNonNull(schemaVersion, "schemaVersion");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(gitCommit, "gitCommit");
            Objects.requireNonNull(artifactSha256, "artifactSha256");
            Objects.requireNonNull(runnerStartClass, "runnerStartClass");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(outputAudit, "outputAudit");
            Objects.requireNonNull(reason, "reason");
            if (!VERSION.equals(schemaVersion) || providerCallCount != 0
                    || databaseWriteCount != 0
                    || "SUCCEEDED".equals(status)
                    && (research == null || reportFile == null
                    || !databaseReadOnly || !databaseSnapshotUnchanged
                    || !outputAudit.clean())) {
                throw new IllegalArgumentException("M3_RESULT_INVALID");
            }
        }
    }

    static final class ResultFile {
        private static final ObjectMapper MAPPER =
                new ObjectMapper().findAndRegisterModules();
        private final Path path;

        private ResultFile(Path path) {
            this.path = path;
        }

        static ResultFile reserve(Path requested, Path allowedRoot,
                                  Result value) {
            Objects.requireNonNull(requested, "requested");
            Objects.requireNonNull(allowedRoot, "allowedRoot");
            Path path = requested.toAbsolutePath().normalize();
            Path root = allowedRoot.toAbsolutePath().normalize();
            if (!path.startsWith(root) || path.equals(root)
                    || !path.getFileName().toString().endsWith(".json")
                    || containsAi(path)) {
                throw new IllegalArgumentException("M3_RESULT_PATH_INVALID");
            }
            try {
                Files.createDirectories(Objects.requireNonNull(
                        path.getParent()));
                Files.writeString(path, json(value), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ResultFile(path);
            } catch (IOException error) {
                throw new IllegalStateException("M3_RESULT_RESERVE_FAILED",
                        error);
            }
        }

        void write(Result value) {
            Path temporary = path.resolveSibling('.' + path.getFileName()
                    .toString() + "." + java.util.UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temporary, json(value),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, path,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                throw new IllegalStateException("M3_RESULT_WRITE_FAILED",
                        error);
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary result remains authoritative.
                }
            }
        }

        private static String json(Result value) {
            try {
                return MAPPER.writeValueAsString(value)
                        + System.lineSeparator();
            } catch (IOException error) {
                throw new IllegalStateException("M3_RESULT_JSON_FAILED",
                        error);
            }
        }

        private static boolean containsAi(Path path) {
            for (Path part : path) {
                if (".ai".equalsIgnoreCase(part.toString())) {
                    return true;
                }
            }
            return false;
        }
    }
}
