package com.stockquant.server.agent.shadowresearch;

import java.time.Instant;
import java.time.LocalDate;

/** Narrow boundary between the business scheduler and the fixed host broker. */
public interface ShadowResearchDispatchGateway {
    DispatchResult dispatch(
            LocalDate tradeDate,
            Instant researchAsOf,
            ShadowResearchRepository.CalendarState calendarState
    );

    record DispatchResult(String requestId, boolean accepted, String reason) {
        public DispatchResult(String requestId, boolean accepted) {
            this(requestId, accepted,
                    accepted ? "DISPATCHED" : "DISPATCH_IDEMPOTENT");
        }

        public DispatchResult {
            if (requestId == null
                    || !requestId.matches("SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")) {
                throw new IllegalArgumentException(
                        "M4_SCHEDULER_REQUEST_ID_INVALID");
            }
            if (reason == null
                    || !reason.matches("[A-Z][A-Z0-9_]{3,127}")) {
                throw new IllegalArgumentException(
                        "M4_SCHEDULER_DISPATCH_REASON_INVALID");
            }
        }
    }
}
