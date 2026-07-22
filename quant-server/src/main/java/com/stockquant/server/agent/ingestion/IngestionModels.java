package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Source-neutral domain contracts for stage 2D-2B-1A ingestion governance. */
public final class IngestionModels {

    private IngestionModels() {}

    public enum DatasetType {
        SECURITY_STATUS,
        TRADING_CALENDAR
    }

    public enum RunNamespace {
        FORMAL,
        TEST,
        DEMO
    }

    public enum OperationType {
        INGEST,
        BACKFILL,
        REBUILD,
        RETRY
    }

    public enum RunStatus {
        RUNNING,
        COMPLETED,
        PARTIAL,
        FAILED;

        public boolean terminal() {
            return this != RUNNING;
        }
    }

    public enum AttemptStatus {
        COMPLETED,
        REJECTED,
        CONFLICT,
        UNSUPPORTED_CONTRACT,
        IDENTITY_UNRESOLVED,
        PROJECTION_FAILED
    }

    public enum PublicationTimeVerification {
        VERIFIED,
        UNVERIFIED,
        NOT_PROVIDED
    }

    public enum AssuranceLevel {
        PIT_VERIFIED(2),
        RECONSTRUCTED_VERIFIED(1),
        INFERRED_RESEARCH(0);

        private final int strength;

        AssuranceLevel(int strength) {
            this.strength = strength;
        }

        public AssuranceLevel conservativeWith(AssuranceLevel other) {
            return strength <= IngestionValidation.required(other, "assuranceLevel").strength
                    ? this : other;
        }

        public static AssuranceLevel mostConservative(List<AssuranceLevel> values) {
            if (values == null || values.isEmpty()) {
                return INFERRED_RESEARCH;
            }
            AssuranceLevel result = PIT_VERIFIED;
            for (AssuranceLevel value : values) {
                result = result.conservativeWith(value);
            }
            return result;
        }
    }

    public record StartRunCommand(
            long datasetVersionId,
            DatasetType datasetType,
            RunNamespace runNamespace,
            OperationType operationType,
            String requestKey,
            LocalDate requestedRangeStart,
            LocalDate requestedRangeEnd,
            String retryOfRunLogicalKey
    ) {
        public StartRunCommand {
            datasetVersionId = IngestionValidation.positiveId(datasetVersionId, "datasetVersionId");
            datasetType = IngestionValidation.required(datasetType, "datasetType");
            runNamespace = IngestionValidation.required(runNamespace, "runNamespace");
            operationType = IngestionValidation.required(operationType, "operationType");
            requestKey = IngestionValidation.text(requestKey, "requestKey", 200);
            IngestionValidation.closedDateRange(
                    requestedRangeStart, requestedRangeEnd, "requestedRange");
            retryOfRunLogicalKey = IngestionValidation.optionalText(
                    retryOfRunLogicalKey, "retryOfRunLogicalKey", 256);
            if (operationType == OperationType.RETRY && retryOfRunLogicalKey == null) {
                throw new IllegalArgumentException("RETRY run requires retryOfRunLogicalKey");
            }
            if (operationType != OperationType.RETRY && retryOfRunLogicalKey != null) {
                throw new IllegalArgumentException(
                        "non-RETRY run must not have retryOfRunLogicalKey");
            }
        }
    }

    public record IngestionRun(
            long id,
            String logicalKey,
            long datasetVersionId,
            String datasetLogicalKey,
            DatasetType datasetType,
            RunNamespace runNamespace,
            OperationType operationType,
            String requestKey,
            String retryOfRunLogicalKey,
            String rootRequestLogicalKey,
            int runAttemptNumber,
            LocalDate requestedRangeStart,
            LocalDate requestedRangeEnd,
            RunStatus status,
            Instant startedAt,
            Instant sealedAt,
            Instant finishedAt,
            String manifestHash,
            Integer finalExpectedCount,
            Integer finalReceivedCount,
            Integer finalAcceptedCount,
            Integer finalRejectedCount,
            AssuranceLevel assuranceLevel,
            Instant createdAt
    ) {
        public IngestionRun {
            id = IngestionValidation.positiveId(id, "id");
            logicalKey = IngestionValidation.text(logicalKey, "logicalKey", 256);
            datasetVersionId = IngestionValidation.positiveId(datasetVersionId, "datasetVersionId");
            datasetLogicalKey = IngestionValidation.text(
                    datasetLogicalKey, "datasetLogicalKey", 256);
            datasetType = IngestionValidation.required(datasetType, "datasetType");
            runNamespace = IngestionValidation.required(runNamespace, "runNamespace");
            operationType = IngestionValidation.required(operationType, "operationType");
            requestKey = IngestionValidation.text(requestKey, "requestKey", 200);
            retryOfRunLogicalKey = IngestionValidation.optionalText(
                    retryOfRunLogicalKey, "retryOfRunLogicalKey", 256);
            rootRequestLogicalKey = IngestionValidation.text(
                    rootRequestLogicalKey, "rootRequestLogicalKey", 256);
            if (runAttemptNumber <= 0) {
                throw new IllegalArgumentException("runAttemptNumber must be positive");
            }
            IngestionValidation.closedDateRange(
                    requestedRangeStart, requestedRangeEnd, "requestedRange");
            if (operationType == OperationType.RETRY
                    && (retryOfRunLogicalKey == null || runAttemptNumber <= 1)) {
                throw new IllegalArgumentException(
                        "RETRY run requires a parent and attempt number greater than one");
            }
            if (operationType != OperationType.RETRY
                    && (retryOfRunLogicalKey != null || runAttemptNumber != 1)) {
                throw new IllegalArgumentException(
                        "non-RETRY run must be the first root request attempt");
            }
            status = IngestionValidation.required(status, "status");
            startedAt = IngestionValidation.instant(startedAt, "startedAt");
            createdAt = IngestionValidation.instant(createdAt, "createdAt");
            sealedAt = IngestionValidation.optionalInstant(sealedAt);
            finishedAt = IngestionValidation.optionalInstant(finishedAt);
            manifestHash = IngestionValidation.optionalSha256(manifestHash, "manifestHash");
        }

        public boolean sealed() {
            return sealedAt != null;
        }
    }

    public sealed interface AppendRawRecordCommand
            permits AppendSecurityStatusRawCommand, AppendTradingCalendarRawCommand {
        long ingestionRunId();
        String source();
        String sourceVersion();
        String sourceRecordId();
        String sourceRevision();
        Instant sourcePublishedAt();
        LocalDate sourceEffectiveDate();
        Instant sourceEffectiveAt();
        TemporalTrustLevel sourceTrustLevel();
        JsonNode rawPayload();
        String payloadHash();

        default String sourceInstrumentId() { return null; }
        default String exchange() { return null; }
        default LocalDate tradeDate() { return null; }
    }

    public record AppendSecurityStatusRawCommand(
            long ingestionRunId,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            String sourceInstrumentId,
            Instant sourcePublishedAt,
            LocalDate sourceEffectiveDate,
            Instant sourceEffectiveAt,
            TemporalTrustLevel sourceTrustLevel,
            JsonNode rawPayload,
            String payloadHash
    ) implements AppendRawRecordCommand {
        public AppendSecurityStatusRawCommand {
            ingestionRunId = IngestionValidation.positiveId(ingestionRunId, "ingestionRunId");
            source = IngestionValidation.text(source, "source", 128);
            sourceVersion = IngestionValidation.text(sourceVersion, "sourceVersion", 128);
            sourceRecordId = IngestionValidation.text(sourceRecordId, "sourceRecordId", 256);
            sourceRevision = IngestionValidation.text(sourceRevision, "sourceRevision", 128);
            sourceInstrumentId = IngestionValidation.optionalText(
                    sourceInstrumentId, "sourceInstrumentId", 256);
            sourcePublishedAt = IngestionValidation.optionalInstant(sourcePublishedAt);
            sourceEffectiveAt = IngestionValidation.optionalInstant(sourceEffectiveAt);
            sourceTrustLevel = IngestionValidation.required(sourceTrustLevel, "sourceTrustLevel");
            rawPayload = IngestionValidation.object(rawPayload, "rawPayload");
            payloadHash = IngestionValidation.sha256(payloadHash, "payloadHash");
            IngestionValidation.effectiveTimes(sourceEffectiveDate, sourceEffectiveAt);
        }
    }

    public record AppendTradingCalendarRawCommand(
            long ingestionRunId,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            String exchange,
            LocalDate tradeDate,
            Instant sourcePublishedAt,
            LocalDate sourceEffectiveDate,
            Instant sourceEffectiveAt,
            TemporalTrustLevel sourceTrustLevel,
            JsonNode rawPayload,
            String payloadHash
    ) implements AppendRawRecordCommand {
        public AppendTradingCalendarRawCommand {
            ingestionRunId = IngestionValidation.positiveId(ingestionRunId, "ingestionRunId");
            source = IngestionValidation.text(source, "source", 128);
            sourceVersion = IngestionValidation.text(sourceVersion, "sourceVersion", 128);
            sourceRecordId = IngestionValidation.text(sourceRecordId, "sourceRecordId", 256);
            sourceRevision = IngestionValidation.text(sourceRevision, "sourceRevision", 128);
            exchange = IngestionValidation.exchange(exchange);
            tradeDate = IngestionValidation.required(tradeDate, "tradeDate");
            sourcePublishedAt = IngestionValidation.optionalInstant(sourcePublishedAt);
            sourceEffectiveAt = IngestionValidation.optionalInstant(sourceEffectiveAt);
            sourceTrustLevel = IngestionValidation.required(sourceTrustLevel, "sourceTrustLevel");
            rawPayload = IngestionValidation.object(rawPayload, "rawPayload");
            payloadHash = IngestionValidation.sha256(payloadHash, "payloadHash");
            IngestionValidation.effectiveTimes(sourceEffectiveDate, sourceEffectiveAt);
        }
    }

    public record RawRecord(
            long id,
            DatasetType datasetType,
            long firstIngestionRunId,
            long datasetVersionId,
            String logicalKey,
            RunNamespace recordNamespace,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            String sourceInstrumentId,
            String exchange,
            LocalDate tradeDate,
            Instant sourcePublishedAt,
            LocalDate sourceEffectiveDate,
            Instant sourceEffectiveAt,
            Instant systemFirstObservedAt,
            Instant systemRecordedAt,
            TemporalTrustLevel sourceTrustLevel,
            JsonNode rawPayload,
            String payloadHash
    ) {
        public RawRecord {
            id = IngestionValidation.positiveId(id, "id");
            datasetType = IngestionValidation.required(datasetType, "datasetType");
            firstIngestionRunId = IngestionValidation.positiveId(
                    firstIngestionRunId, "firstIngestionRunId");
            datasetVersionId = IngestionValidation.positiveId(datasetVersionId, "datasetVersionId");
            logicalKey = IngestionValidation.text(logicalKey, "logicalKey", 256);
            recordNamespace = IngestionValidation.required(recordNamespace, "recordNamespace");
            source = IngestionValidation.text(source, "source", 128);
            sourceVersion = IngestionValidation.text(sourceVersion, "sourceVersion", 128);
            sourceRecordId = IngestionValidation.text(sourceRecordId, "sourceRecordId", 256);
            sourceRevision = IngestionValidation.text(sourceRevision, "sourceRevision", 128);
            sourceInstrumentId = IngestionValidation.optionalText(
                    sourceInstrumentId, "sourceInstrumentId", 256);
            if (datasetType == DatasetType.SECURITY_STATUS) {
                if (exchange != null || tradeDate != null) {
                    throw new IllegalArgumentException(
                            "security raw must not contain calendar identity fields");
                }
            } else {
                if (sourceInstrumentId != null) {
                    throw new IllegalArgumentException(
                            "calendar raw must not contain sourceInstrumentId");
                }
                exchange = IngestionValidation.exchange(exchange);
                tradeDate = IngestionValidation.required(tradeDate, "tradeDate");
            }
            sourcePublishedAt = IngestionValidation.optionalInstant(sourcePublishedAt);
            sourceEffectiveAt = IngestionValidation.optionalInstant(sourceEffectiveAt);
            systemFirstObservedAt = IngestionValidation.instant(
                    systemFirstObservedAt, "systemFirstObservedAt");
            systemRecordedAt = IngestionValidation.instant(systemRecordedAt, "systemRecordedAt");
            sourceTrustLevel = IngestionValidation.required(sourceTrustLevel, "sourceTrustLevel");
            rawPayload = IngestionValidation.object(rawPayload, "rawPayload");
            payloadHash = IngestionValidation.sha256(payloadHash, "payloadHash");
            IngestionValidation.effectiveTimes(sourceEffectiveDate, sourceEffectiveAt);
            IngestionValidation.notBefore(
                    systemFirstObservedAt, sourcePublishedAt,
                    "systemFirstObservedAt", "sourcePublishedAt");
            IngestionValidation.notBefore(
                    systemRecordedAt, systemFirstObservedAt,
                    "systemRecordedAt", "systemFirstObservedAt");
        }
    }

    public record RecordAttemptCommand(
            long ingestionRunId,
            long rawRecordId,
            int attemptNo,
            AttemptStatus status,
            String processorVersion,
            String contractVersion,
            PublicationTimeVerification publicationTimeVerification,
            AssuranceLevel requestedAssuranceLevel,
            String errorCode,
            JsonNode resultMetadata
    ) {
        public RecordAttemptCommand {
            ingestionRunId = IngestionValidation.positiveId(ingestionRunId, "ingestionRunId");
            rawRecordId = IngestionValidation.positiveId(rawRecordId, "rawRecordId");
            if (attemptNo <= 0) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            status = IngestionValidation.required(status, "status");
            processorVersion = IngestionValidation.text(
                    processorVersion, "processorVersion", 120);
            contractVersion = IngestionValidation.text(contractVersion, "contractVersion", 120);
            publicationTimeVerification = IngestionValidation.required(
                    publicationTimeVerification, "publicationTimeVerification");
            requestedAssuranceLevel = IngestionValidation.required(
                    requestedAssuranceLevel, "requestedAssuranceLevel");
            errorCode = IngestionValidation.optionalText(errorCode, "errorCode", 120);
            if (status == AttemptStatus.COMPLETED && errorCode != null) {
                throw new IllegalArgumentException("completed attempt must not have errorCode");
            }
            if (status != AttemptStatus.COMPLETED && errorCode == null) {
                throw new IllegalArgumentException("non-completed attempt requires errorCode");
            }
            resultMetadata = IngestionValidation.emptyObject(resultMetadata, "resultMetadata");
        }
    }

    public record ProcessingAttempt(
            long id,
            DatasetType datasetType,
            long ingestionRunId,
            long rawRecordId,
            int attemptNo,
            String logicalKey,
            AttemptStatus status,
            String processorVersion,
            String contractVersion,
            PublicationTimeVerification publicationTimeVerification,
            Instant derivedKnownFrom,
            String knowledgeTimePolicyVersion,
            AssuranceLevel assuranceLevel,
            String errorCode,
            JsonNode resultMetadata,
            String resultHash,
            Instant completedAt,
            Instant systemRecordedAt
    ) {
        public ProcessingAttempt {
            id = IngestionValidation.positiveId(id, "id");
            datasetType = IngestionValidation.required(datasetType, "datasetType");
            ingestionRunId = IngestionValidation.positiveId(ingestionRunId, "ingestionRunId");
            rawRecordId = IngestionValidation.positiveId(rawRecordId, "rawRecordId");
            if (attemptNo <= 0) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            logicalKey = IngestionValidation.text(logicalKey, "logicalKey", 256);
            status = IngestionValidation.required(status, "status");
            processorVersion = IngestionValidation.text(processorVersion, "processorVersion", 120);
            contractVersion = IngestionValidation.text(contractVersion, "contractVersion", 120);
            publicationTimeVerification = IngestionValidation.required(
                    publicationTimeVerification, "publicationTimeVerification");
            derivedKnownFrom = IngestionValidation.instant(derivedKnownFrom, "derivedKnownFrom");
            knowledgeTimePolicyVersion = IngestionValidation.text(
                    knowledgeTimePolicyVersion, "knowledgeTimePolicyVersion", 80);
            assuranceLevel = IngestionValidation.required(assuranceLevel, "assuranceLevel");
            errorCode = IngestionValidation.optionalText(errorCode, "errorCode", 120);
            resultMetadata = IngestionValidation.emptyObject(resultMetadata, "resultMetadata");
            resultHash = IngestionValidation.sha256(resultHash, "resultHash");
            completedAt = IngestionValidation.instant(completedAt, "completedAt");
            systemRecordedAt = IngestionValidation.instant(systemRecordedAt, "systemRecordedAt");
            IngestionValidation.notBefore(
                    systemRecordedAt, completedAt, "systemRecordedAt", "completedAt");
        }
    }

    public record SealRunCommand(long ingestionRunId, RunStatus status, int expectedCount) {
        public SealRunCommand {
            ingestionRunId = IngestionValidation.positiveId(ingestionRunId, "ingestionRunId");
            status = IngestionValidation.required(status, "status");
            if (!status.terminal()) {
                throw new IllegalArgumentException("seal status must be terminal");
            }
            if (expectedCount < 0) {
                throw new IllegalArgumentException("expectedCount must not be negative");
            }
        }
    }

    record RunMaterial(
            IngestionRun run,
            DatasetVersion dataset,
            List<ManifestEntry> entries
    ) {}

    record ManifestEntry(
            String rawRecordLogicalKey,
            String rawPayloadHash,
            TemporalTrustLevel rawSourceTrustLevel,
            String sourceInstrumentId,
            String exchange,
            LocalDate tradeDate,
            int attemptNo,
            String attemptLogicalKey,
            AttemptStatus attemptStatus,
            String processorVersion,
            String contractVersion,
            PublicationTimeVerification publicationTimeVerification,
            String knowledgeTimePolicyVersion,
            AssuranceLevel assuranceLevel,
            Instant derivedKnownFrom,
            String errorCode,
            String resultHash
    ) {
        ManifestEntry {
            rawRecordLogicalKey = IngestionValidation.text(
                    rawRecordLogicalKey, "rawRecordLogicalKey", 256);
            rawPayloadHash = IngestionValidation.sha256(rawPayloadHash, "rawPayloadHash");
            rawSourceTrustLevel = IngestionValidation.required(
                    rawSourceTrustLevel, "rawSourceTrustLevel");
            sourceInstrumentId = IngestionValidation.optionalText(
                    sourceInstrumentId, "sourceInstrumentId", 256);
            if (exchange != null) exchange = IngestionValidation.exchange(exchange);
            if (attemptNo <= 0) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            attemptLogicalKey = IngestionValidation.text(
                    attemptLogicalKey, "attemptLogicalKey", 256);
            attemptStatus = IngestionValidation.required(attemptStatus, "attemptStatus");
            processorVersion = IngestionValidation.text(
                    processorVersion, "processorVersion", 120);
            contractVersion = IngestionValidation.text(
                    contractVersion, "contractVersion", 120);
            publicationTimeVerification = IngestionValidation.required(
                    publicationTimeVerification, "publicationTimeVerification");
            knowledgeTimePolicyVersion = IngestionValidation.text(
                    knowledgeTimePolicyVersion, "knowledgeTimePolicyVersion", 80);
            assuranceLevel = IngestionValidation.required(assuranceLevel, "assuranceLevel");
            derivedKnownFrom = IngestionValidation.instant(derivedKnownFrom, "derivedKnownFrom");
            errorCode = IngestionValidation.optionalText(errorCode, "errorCode", 120);
            resultHash = IngestionValidation.sha256(resultHash, "resultHash");
        }

        static ManifestEntry from(RawRecord raw, ProcessingAttempt attempt) {
            IngestionValidation.required(raw, "raw");
            IngestionValidation.required(attempt, "attempt");
            return new ManifestEntry(
                    raw.logicalKey(), raw.payloadHash(), raw.sourceTrustLevel(),
                    raw.sourceInstrumentId(), raw.exchange(), raw.tradeDate(),
                    attempt.attemptNo(), attempt.logicalKey(), attempt.status(),
                    attempt.processorVersion(), attempt.contractVersion(),
                    attempt.publicationTimeVerification(), attempt.knowledgeTimePolicyVersion(),
                    attempt.assuranceLevel(), attempt.derivedKnownFrom(), attempt.errorCode(),
                    attempt.resultHash());
        }
    }

    record KnowledgeAssessment(Instant derivedKnownFrom, AssuranceLevel assuranceLevel) {}
}
