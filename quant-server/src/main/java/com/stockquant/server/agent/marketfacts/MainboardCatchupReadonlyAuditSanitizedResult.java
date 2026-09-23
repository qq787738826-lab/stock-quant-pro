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

/** Secret-free terminal evidence for the fixed read-only catch-up audit. */
public final class MainboardCatchupReadonlyAuditSanitizedResult {
    public static final String VERSION =
            "MAINBOARD_CATCHUP_READONLY_AUDIT_RESULT_V1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MainboardCatchupReadonlyAuditSanitizedResult() {
    }

    public record DateResult(
            LocalDate tradeDate,
            String status,
            List<String> reasonCodes,
            int dailyRowCount,
            int adjustmentFactorRowCount,
            int dailySecurityCount,
            int adjustmentFactorSecurityCount,
            long activeSecurityCount,
            int duplicateCount,
            boolean securitySetsEqual,
            boolean dailyComplete,
            boolean adjustmentFactorComplete,
            boolean knownAtValid
    ) {
        public DateResult {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    public record Result(
            String schemaVersion,
            String status,
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            LocalDate calendarMaxSse,
            LocalDate calendarMaxSzse,
            LocalDate latestCommonCompletedOpenTradeDate,
            LocalDate latestCompleteTradeDate,
            List<LocalDate> commonOpenTradeDates,
            List<DateResult> dateAudits,
            List<LocalDate> missingTradeDates,
            List<LocalDate> partialTradeDates,
            List<LocalDate> completeTradeDates,
            int missingTradeDateCount,
            int partialTradeDateCount,
            int completeTradeDateCount,
            String universeVersion,
            int universeMemberCount,
            int sseCount,
            int szseCount,
            int stCount,
            int factSecurityCount,
            int currentLedger,
            int ledgerLimit,
            int plannedBaseCalls,
            int networkRecoveryBudget,
            int projectedWorstCaseLedger,
            boolean projectedWithinLimit,
            int tushareProviderCallCount,
            int dailyProviderCallCount,
            int adjustmentFactorProviderCallCount,
            int tradeCalendarProviderCallCount,
            int stockBasicProviderCallCount,
            int retryCount,
            int modelCallCount,
            long databaseRowsWritten,
            long researchSelectionRunsCreated,
            long shadowRunsCreated,
            long paperOrdersCreated,
            long evaluationRowsCreated,
            boolean readOnlyTransaction,
            boolean outputAuditClean,
            boolean deterministicFake,
            boolean dataOnly,
            boolean realTradingStarted,
            String failureReason
    ) {
        public Result {
            commonOpenTradeDates = List.copyOf(commonOpenTradeDates);
            dateAudits = List.copyOf(dateAudits);
            missingTradeDates = List.copyOf(missingTradeDates);
            partialTradeDates = List.copyOf(partialTradeDates);
            completeTradeDates = List.copyOf(completeTradeDates);
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
                            "MAINBOARD_CATCHUP_AUDIT_RESULT_PATH_INVALID");
                }
                Files.createDirectories(normalized.getParent());
                Files.writeString(normalized,
                        MAPPER.writeValueAsString(initial) + "\n",
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ResultFile(normalized);
            } catch (IOException error) {
                throw invalid(
                        "MAINBOARD_CATCHUP_AUDIT_RESULT_RESERVE_FAILED");
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
                        "MAINBOARD_CATCHUP_AUDIT_RESULT_WRITE_FAILED");
            }
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
