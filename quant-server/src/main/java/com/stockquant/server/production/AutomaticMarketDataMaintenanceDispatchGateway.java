package com.stockquant.server.production;

import java.time.Instant;
import java.time.LocalDate;

/** Fixed submission boundary from the production scheduler to the Broker. */
public interface AutomaticMarketDataMaintenanceDispatchGateway {
    DispatchResult dispatch(LocalDate tradeDate, Instant submittedAt);

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
