package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

/** Domain records for the stage 2D-2A temporal foundation. */
public final class TemporalModels {

    private TemporalModels() {}

    public record DatasetVersion(
            long id,
            String datasetType,
            String source,
            String sourceVersion,
            String connectorVersion,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Instant fetchedAt,
            Instant recordedAt,
            String payloadHash,
            TemporalTrustLevel trustLevel,
            JsonNode metadata
    ) {
        public DatasetVersion {
            id = TemporalValidation.positiveId(id, "id");
            datasetType = TemporalValidation.requiredText(datasetType, "datasetType");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            connectorVersion = TemporalValidation.requiredText(connectorVersion, "connectorVersion");
            TemporalValidation.closedDateRange(rangeStart, rangeEnd, "dataset range");
            fetchedAt = TemporalValidation.instant(fetchedAt, "fetchedAt");
            recordedAt = TemporalValidation.instant(recordedAt, "recordedAt");
            TemporalValidation.notBefore(recordedAt, fetchedAt, "recordedAt", "fetchedAt");
            payloadHash = TemporalValidation.sha256(payloadHash, "payloadHash");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            metadata = TemporalValidation.object(metadata, "metadata");
        }
    }

    public record RegisterDatasetVersionCommand(
            String datasetType,
            String source,
            String sourceVersion,
            String connectorVersion,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Instant fetchedAt,
            String payloadHash,
            TemporalTrustLevel trustLevel,
            JsonNode metadata
    ) {
        public RegisterDatasetVersionCommand {
            datasetType = TemporalValidation.requiredText(datasetType, "datasetType");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            connectorVersion = TemporalValidation.requiredText(connectorVersion, "connectorVersion");
            TemporalValidation.closedDateRange(rangeStart, rangeEnd, "dataset range");
            fetchedAt = TemporalValidation.instant(fetchedAt, "fetchedAt");
            payloadHash = TemporalValidation.sha256(payloadHash, "payloadHash");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            metadata = TemporalValidation.object(metadata, "metadata");
        }
    }

    public record SecurityStatusEvent(
            long id,
            long datasetVersionId,
            String symbol,
            SecurityStatusEventType eventType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant publishedAt,
            Instant knownAt,
            Instant recordedAt,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            TemporalTrustLevel trustLevel,
            JsonNode payload,
            String payloadHash,
            Long supersedesEventId
    ) {
        public SecurityStatusEvent {
            id = TemporalValidation.positiveId(id, "id");
            datasetVersionId = TemporalValidation.positiveId(datasetVersionId, "datasetVersionId");
            symbol = TemporalValidation.symbol(symbol);
            eventType = TemporalValidation.required(eventType, "eventType");
            TemporalValidation.halfOpenDateRange(effectiveFrom, effectiveTo, "effective interval");
            publishedAt = TemporalValidation.optionalInstant(publishedAt);
            knownAt = TemporalValidation.instant(knownAt, "knownAt");
            recordedAt = TemporalValidation.instant(recordedAt, "recordedAt");
            TemporalValidation.notBefore(knownAt, publishedAt, "knownAt", "publishedAt");
            TemporalValidation.notBefore(recordedAt, knownAt, "recordedAt", "knownAt");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            sourceRecordId = TemporalValidation.requiredText(sourceRecordId, "sourceRecordId");
            sourceRevision = TemporalValidation.requiredText(sourceRevision, "sourceRevision");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            payload = TemporalValidation.object(payload, "payload");
            payloadHash = TemporalValidation.sha256(payloadHash, "payloadHash");
            supersedesEventId = TemporalValidation.optionalPositiveId(
                    supersedesEventId, "supersedesEventId");
        }
    }

    public record AppendSecurityStatusEventCommand(
            long datasetVersionId,
            String symbol,
            SecurityStatusEventType eventType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant publishedAt,
            Instant knownAt,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            TemporalTrustLevel trustLevel,
            JsonNode payload,
            String payloadHash,
            Long supersedesEventId
    ) {
        public AppendSecurityStatusEventCommand {
            datasetVersionId = TemporalValidation.positiveId(datasetVersionId, "datasetVersionId");
            symbol = TemporalValidation.symbol(symbol);
            eventType = TemporalValidation.required(eventType, "eventType");
            TemporalValidation.halfOpenDateRange(effectiveFrom, effectiveTo, "effective interval");
            publishedAt = TemporalValidation.optionalInstant(publishedAt);
            knownAt = TemporalValidation.instant(knownAt, "knownAt");
            TemporalValidation.notBefore(knownAt, publishedAt, "knownAt", "publishedAt");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            sourceRecordId = TemporalValidation.requiredText(sourceRecordId, "sourceRecordId");
            sourceRevision = TemporalValidation.requiredText(sourceRevision, "sourceRevision");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            payload = TemporalValidation.object(payload, "payload");
            payloadHash = TemporalValidation.sha256(payloadHash, "payloadHash");
            supersedesEventId = TemporalValidation.optionalPositiveId(
                    supersedesEventId, "supersedesEventId");
        }
    }

    public record SecurityStatusVersion(
            long id,
            String symbol,
            MarketExchange exchange,
            String board,
            boolean listed,
            boolean active,
            boolean st,
            LocalDate validFrom,
            LocalDate validTo,
            Instant knownFrom,
            Instant knownTo,
            long sourceEventId,
            long datasetVersionId,
            String source,
            String sourceVersion,
            TemporalTrustLevel trustLevel,
            String statusHash,
            Instant recordedAt
    ) {
        public SecurityStatusVersion {
            id = TemporalValidation.positiveId(id, "id");
            symbol = TemporalValidation.symbol(symbol);
            exchange = TemporalValidation.required(exchange, "exchange");
            board = TemporalValidation.requiredText(board, "board");
            TemporalValidation.halfOpenDateRange(validFrom, validTo, "valid interval");
            knownFrom = TemporalValidation.instant(knownFrom, "knownFrom");
            knownTo = TemporalValidation.optionalInstant(knownTo);
            TemporalValidation.halfOpenInstantRange(knownFrom, knownTo, "knowledge interval");
            sourceEventId = TemporalValidation.positiveId(sourceEventId, "sourceEventId");
            datasetVersionId = TemporalValidation.positiveId(datasetVersionId, "datasetVersionId");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            statusHash = TemporalValidation.sha256(statusHash, "statusHash");
            recordedAt = TemporalValidation.instant(recordedAt, "recordedAt");
            TemporalValidation.notBefore(recordedAt, knownFrom, "recordedAt", "knownFrom");
        }
    }

    public record PublishSecurityStatusVersionCommand(
            String symbol,
            MarketExchange exchange,
            String board,
            boolean listed,
            boolean active,
            boolean st,
            LocalDate validFrom,
            LocalDate validTo,
            Instant knownFrom,
            Instant knownTo,
            long sourceEventId,
            long datasetVersionId,
            String source,
            String sourceVersion,
            TemporalTrustLevel trustLevel
    ) {
        public PublishSecurityStatusVersionCommand {
            symbol = TemporalValidation.symbol(symbol);
            exchange = TemporalValidation.required(exchange, "exchange");
            board = TemporalValidation.requiredText(board, "board");
            TemporalValidation.halfOpenDateRange(validFrom, validTo, "valid interval");
            knownFrom = TemporalValidation.instant(knownFrom, "knownFrom");
            knownTo = TemporalValidation.optionalInstant(knownTo);
            TemporalValidation.halfOpenInstantRange(knownFrom, knownTo, "knowledge interval");
            sourceEventId = TemporalValidation.positiveId(sourceEventId, "sourceEventId");
            datasetVersionId = TemporalValidation.positiveId(datasetVersionId, "datasetVersionId");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
        }
    }

    public record CorrectSecurityStatusVersionCommand(
            long supersededVersionId,
            PublishSecurityStatusVersionCommand replacement
    ) {
        public CorrectSecurityStatusVersionCommand {
            supersededVersionId = TemporalValidation.positiveId(
                    supersededVersionId, "supersededVersionId");
            replacement = TemporalValidation.required(replacement, "replacement");
            if (replacement.knownTo() != null) {
                throw new IllegalArgumentException("a correction replacement must be knowledge-open");
            }
        }
    }

    public record TradingCalendarRevision(
            long id,
            long datasetVersionId,
            MarketExchange exchange,
            LocalDate tradeDate,
            boolean open,
            TradingSessionType sessionType,
            Instant sessionOpenAt,
            Instant sessionCloseAt,
            Instant knownFrom,
            Instant knownTo,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            TemporalTrustLevel trustLevel,
            String payloadHash,
            Instant recordedAt
    ) {
        public TradingCalendarRevision {
            id = TemporalValidation.positiveId(id, "id");
            datasetVersionId = TemporalValidation.positiveId(datasetVersionId, "datasetVersionId");
            exchange = TemporalValidation.required(exchange, "exchange");
            tradeDate = TemporalValidation.required(tradeDate, "tradeDate");
            sessionOpenAt = TemporalValidation.optionalInstant(sessionOpenAt);
            sessionCloseAt = TemporalValidation.optionalInstant(sessionCloseAt);
            TemporalValidation.session(open, sessionType, sessionOpenAt, sessionCloseAt);
            knownFrom = TemporalValidation.instant(knownFrom, "knownFrom");
            knownTo = TemporalValidation.optionalInstant(knownTo);
            TemporalValidation.halfOpenInstantRange(knownFrom, knownTo, "knowledge interval");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            sourceRecordId = TemporalValidation.requiredText(sourceRecordId, "sourceRecordId");
            sourceRevision = TemporalValidation.requiredText(sourceRevision, "sourceRevision");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            payloadHash = TemporalValidation.sha256(payloadHash, "payloadHash");
            recordedAt = TemporalValidation.instant(recordedAt, "recordedAt");
            TemporalValidation.notBefore(recordedAt, knownFrom, "recordedAt", "knownFrom");
        }
    }

    public record AppendTradingCalendarRevisionCommand(
            long datasetVersionId,
            MarketExchange exchange,
            LocalDate tradeDate,
            boolean open,
            TradingSessionType sessionType,
            Instant sessionOpenAt,
            Instant sessionCloseAt,
            Instant knownFrom,
            Instant knownTo,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            TemporalTrustLevel trustLevel,
            String payloadHash
    ) {
        public AppendTradingCalendarRevisionCommand {
            datasetVersionId = TemporalValidation.positiveId(datasetVersionId, "datasetVersionId");
            exchange = TemporalValidation.required(exchange, "exchange");
            tradeDate = TemporalValidation.required(tradeDate, "tradeDate");
            sessionOpenAt = TemporalValidation.optionalInstant(sessionOpenAt);
            sessionCloseAt = TemporalValidation.optionalInstant(sessionCloseAt);
            TemporalValidation.session(open, sessionType, sessionOpenAt, sessionCloseAt);
            knownFrom = TemporalValidation.instant(knownFrom, "knownFrom");
            knownTo = TemporalValidation.optionalInstant(knownTo);
            TemporalValidation.halfOpenInstantRange(knownFrom, knownTo, "knowledge interval");
            source = TemporalValidation.requiredText(source, "source");
            sourceVersion = TemporalValidation.requiredText(sourceVersion, "sourceVersion");
            sourceRecordId = TemporalValidation.requiredText(sourceRecordId, "sourceRecordId");
            sourceRevision = TemporalValidation.requiredText(sourceRevision, "sourceRevision");
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            payloadHash = TemporalValidation.sha256(payloadHash, "payloadHash");
        }
    }

    public record CorrectTradingCalendarRevisionCommand(
            long supersededRevisionId,
            AppendTradingCalendarRevisionCommand replacement
    ) {
        public CorrectTradingCalendarRevisionCommand {
            supersededRevisionId = TemporalValidation.positiveId(
                    supersededRevisionId, "supersededRevisionId");
            replacement = TemporalValidation.required(replacement, "replacement");
            if (replacement.knownTo() != null) {
                throw new IllegalArgumentException("a correction replacement must be knowledge-open");
            }
        }
    }

    public record TemporalTrustAssessment(
            TemporalTrustLevel trustLevel,
            boolean pointInTimeCandidate,
            String reasonCode
    ) {
        public TemporalTrustAssessment {
            trustLevel = TemporalValidation.required(trustLevel, "trustLevel");
            reasonCode = TemporalValidation.requiredText(reasonCode, "reasonCode");
        }
    }

    public record SecurityStatusAsOf(
            SecurityStatusVersion version,
            LocalDate validDate,
            Instant knowledgeCutoff,
            TemporalTrustAssessment trustAssessment
    ) {
        public SecurityStatusAsOf {
            version = TemporalValidation.required(version, "version");
            validDate = TemporalValidation.required(validDate, "validDate");
            knowledgeCutoff = TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff");
            trustAssessment = TemporalValidation.required(trustAssessment, "trustAssessment");
        }
    }

    public record TradingCalendarAsOf(
            TradingCalendarRevision revision,
            Instant knowledgeCutoff,
            TemporalTrustAssessment trustAssessment
    ) {
        public TradingCalendarAsOf {
            revision = TemporalValidation.required(revision, "revision");
            knowledgeCutoff = TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff");
            trustAssessment = TemporalValidation.required(trustAssessment, "trustAssessment");
        }
    }
}
