package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Secret-free terminal evidence for the forward calendar increment. */
public final class MainboardTradeCalendarForwardIncrementSanitizedResult {
    public static final String VERSION =
            "MAINBOARD_TRADE_CAL_FORWARD_INCREMENT_RESULT_V1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MainboardTradeCalendarForwardIncrementSanitizedResult() {
    }

    public record Result(
            String schemaVersion,
            String status,
            String action,
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            LocalDate requestedEndDate,
            LocalDate currentSseMaxCalDate,
            LocalDate currentSzseMaxCalDate,
            LocalDate startDate,
            LocalDate finalSseMaxCalDate,
            LocalDate finalSzseMaxCalDate,
            LocalDate latestCommonCompletedOpenTradeDate,
            int rangeCalendarDateCount,
            String universeVersion,
            String universeSnapshotId,
            String universeMemberFingerprint,
            int universeMemberCount,
            int duplicateCount,
            int tushareProviderCallCount,
            int sseTradeCalendarProviderCallCount,
            int szseTradeCalendarProviderCallCount,
            int dailyProviderCallCount,
            int adjustmentFactorProviderCallCount,
            int stockBasicProviderCallCount,
            int retryCount,
            int networkRecoveryBudget,
            int maximumProviderRequests,
            List<Long> captureBatchIds,
            int appendedObservationCount,
            int idempotentChainTailHits,
            boolean continuousCoverage,
            boolean knownAtValid,
            boolean firstObservedAtValid,
            boolean sourceLineageValid,
            boolean providerFieldsPreserved,
            boolean existingFactsUnchanged,
            boolean appendOnly,
            boolean universeUnchanged,
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
                    throw invalid(
                            "MAINBOARD_TRADE_CAL_FORWARD_RESULT_PATH_INVALID");
                }
                Files.createDirectories(normalized.getParent());
                Files.writeString(normalized,
                        MAPPER.writeValueAsString(initial) + "\n",
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ResultFile(normalized);
            } catch (IOException error) {
                throw invalid(
                        "MAINBOARD_TRADE_CAL_FORWARD_RESULT_RESERVE_FAILED");
            }
        }

        public void write(Result result) {
            try {
                Files.writeString(path,
                        MAPPER.writeValueAsString(result) + "\n",
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException error) {
                throw invalid(
                        "MAINBOARD_TRADE_CAL_FORWARD_RESULT_WRITE_FAILED");
            }
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
