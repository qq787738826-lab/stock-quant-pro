package com.stockquant.server.agent.temporal;

import com.stockquant.server.agent.temporal.TemporalModels.AppendSecurityStatusEventCommand;
import com.stockquant.server.agent.temporal.TemporalModels.AppendTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;

import java.time.Instant;
import java.time.LocalDate;

/** Exact immutable tuples mirrored by the V6 unique constraints. */
final class TemporalIdempotencyKeys {

    private TemporalIdempotencyKeys() {}

    static DatasetKey dataset(RegisterDatasetVersionCommand value) {
        TemporalValidation.required(value, "value");
        return new DatasetKey(
                value.datasetType(), value.source(), value.sourceVersion(),
                value.connectorVersion(), value.rangeStart(), value.rangeEnd(), value.payloadHash()
        );
    }

    static SecurityEventKey securityEvent(AppendSecurityStatusEventCommand value) {
        TemporalValidation.required(value, "value");
        return new SecurityEventKey(
                value.source(), value.sourceVersion(), value.sourceRecordId(), value.sourceRevision()
        );
    }

    static SecurityStatusKey securityStatus(PublishSecurityStatusVersionCommand value) {
        TemporalValidation.required(value, "value");
        return new SecurityStatusKey(value.sourceEventId(), value.validFrom(), value.knownFrom());
    }

    static TradingCalendarKey tradingCalendar(AppendTradingCalendarRevisionCommand value) {
        TemporalValidation.required(value, "value");
        return new TradingCalendarKey(
                value.source(), value.sourceVersion(), value.sourceRecordId(),
                value.sourceRevision(), value.exchange(), value.tradeDate()
        );
    }

    record DatasetKey(
            String datasetType,
            String source,
            String sourceVersion,
            String connectorVersion,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            String payloadHash
    ) {}

    record SecurityEventKey(
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision
    ) {}

    record SecurityStatusKey(long sourceEventId, LocalDate validFrom, Instant knownFrom) {}

    record TradingCalendarKey(
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            MarketExchange exchange,
            LocalDate tradeDate
    ) {}
}
