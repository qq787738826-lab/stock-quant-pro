package com.stockquant.server.production;

import java.time.LocalDate;

/** Read-only proof gate used immediately before scheduled Shadow dispatch. */
public interface MarketDataReadyGate {
    ReadyResult evaluate(LocalDate tradeDate);

    record ReadyResult(boolean ready, String reasonCode, String requestId) {
        public ReadyResult {
            if (reasonCode == null || !reasonCode.matches(
                    "[A-Z][A-Z0-9_]{3,127}")) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_READY_REASON_INVALID");
            }
            if (requestId == null || !("NONE".equals(requestId)
                    || requestId.matches(
                    "SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}"))) {
                throw new IllegalArgumentException(
                        "AUTOMATIC_MARKET_DATA_READY_REQUEST_ID_INVALID");
            }
        }

        public static ReadyResult ready(String requestId) {
            return new ReadyResult(true, "AUTOMATIC_MARKET_DATA_READY",
                    requestId);
        }

        public static ReadyResult skip(String reasonCode, String requestId) {
            return new ReadyResult(false, reasonCode, requestId);
        }
    }
}
