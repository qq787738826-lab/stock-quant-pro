package com.stockquant.server.production;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Read-only projection of the deterministic formal maintenance request. */
public interface AutomaticMarketDataMaintenanceStatusQuery {
    Snapshot query(LocalDate tradeDate);

    enum Status {
        NOT_RUN,
        SUBMITTED,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }

    record Snapshot(
            Status status,
            String requestId,
            LocalDate tradeDate,
            String reason,
            boolean readyForShadow,
            LocalDate latestCompleteTradeDate,
            LocalDate latestCommonCompletedOpenTradeDate,
            List<LocalDate> missingTradeDates,
            List<LocalDate> partialTradeDates,
            Integer providerCallCount
    ) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(tradeDate, "tradeDate");
            if (requestId == null || !("NONE".equals(requestId)
                    || requestId.matches(
                    "SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}"))) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_REQUEST_ID_INVALID");
            }
            if (reason == null
                    || !reason.matches("[A-Z][A-Z0-9_]{3,127}")) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_REASON_INVALID");
            }
            missingTradeDates = missingTradeDates == null ? null
                    : List.copyOf(missingTradeDates);
            partialTradeDates = partialTradeDates == null ? null
                    : List.copyOf(partialTradeDates);
            if (providerCallCount != null && providerCallCount < 0) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_CALL_COUNT_INVALID");
            }
        }

        public static Snapshot notRun(
                LocalDate tradeDate,
                String requestId,
                String reason
        ) {
            return new Snapshot(Status.NOT_RUN, requestId, tradeDate,
                    reason, false, null, null, null, null, null);
        }
    }
}
