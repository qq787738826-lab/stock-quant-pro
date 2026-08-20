package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Secret-free terminal evidence for the data-only main-board increment. */
public final class MainboardDailyIncrementSanitizedResult {
    public static final String VERSION =
            "MAINBOARD_DAILY_INCREMENT_RESULT_V1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private MainboardDailyIncrementSanitizedResult() {
    }

    public record Result(
            String schemaVersion,
            String status,
            String executionId,
            String gitCommit,
            LocalDate tradeDate,
            Instant startedAt,
            Instant completedAt,
            String universeVersion,
            String universeSnapshotId,
            String universeMemberFingerprint,
            int universeMemberCount,
            int sseCount,
            int szseCount,
            int stCount,
            int tushareProviderCallCount,
            int dailyProviderCallCount,
            int adjustmentFactorProviderCallCount,
            int retryCount,
            List<Long> captureBatchIds,
            int dailyAddedCount,
            int adjustmentFactorAddedCount,
            int appendedObservationCount,
            int idempotentChainTailHits,
            int dailyVisibleCount,
            int adjustmentFactorVisibleCount,
            int duplicateCount,
            boolean coverageComplete,
            boolean knownAtValid,
            boolean pitAdmissionPassed,
            boolean universeUnchanged,
            LocalDate latestCompleteTradeDate,
            long researchSelectionRunsCreated,
            long shadowRunsCreated,
            long paperOrdersCreated,
            long evaluationRowsCreated,
            int modelCallCount,
            boolean outputAuditClean,
            boolean deterministicFake,
            boolean dataOnly,
            boolean realTradingStarted,
            String failureReason
    ) {
        public Result {
            captureBatchIds = List.copyOf(captureBatchIds);
        }
    }

    public static final class ResultFile {
        private final Path path;

        private ResultFile(Path path) {
            this.path = path;
        }

        public static ResultFile reserve(Path path, Result initial) {
            try {
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.getParent() == null
                        || normalized.toString().contains(".ai")) {
                    throw invalid("MAINBOARD_DAILY_INCREMENT_RESULT_PATH_INVALID");
                }
                Files.createDirectories(normalized.getParent());
                Files.writeString(normalized,
                        MAPPER.writeValueAsString(initial) + "\n",
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ResultFile(normalized);
            } catch (IOException error) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_RESULT_RESERVE_FAILED");
            }
        }

        public void write(Result result) {
            try {
                Files.writeString(path,
                        MAPPER.writeValueAsString(result) + "\n",
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException error) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_RESULT_WRITE_FAILED");
            }
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
