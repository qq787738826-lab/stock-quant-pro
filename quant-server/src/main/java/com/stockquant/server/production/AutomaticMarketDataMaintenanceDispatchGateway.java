package com.stockquant.server.production;

import java.time.Instant;
import java.time.LocalDate;

/** Fixed automatic/manual submission boundary to the shared Broker operation. */
public interface AutomaticMarketDataMaintenanceDispatchGateway {
    DispatchResult dispatch(LocalDate tradeDate, Instant submittedAt);

    /**
     * Explicit manual submission boundary. Implementations must opt in so a
     * scheduler/test double cannot accidentally turn an automatic dispatch
     * into a manual one.
     */
    default DispatchResult dispatchManual(
            LocalDate effectiveTargetTradeDate,
            Instant submittedAt
    ) {
        throw new IllegalStateException(
                "MANUAL_MARKET_DATA_MAINTENANCE_DISPATCH_UNSUPPORTED");
    }

    enum SubmissionMode {
        AUTOMATIC,
        MANUAL
    }

    record DispatchResult(String requestId, boolean accepted, String reason) {
        public DispatchResult {
            if (requestId == null || !requestId.matches(
                    "SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_REQUEST_ID_INVALID");
            }
            if (reason == null || !reason.matches(
                    "[A-Z][A-Z0-9_]{3,127}")) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_REASON_INVALID");
            }
        }
    }
}
